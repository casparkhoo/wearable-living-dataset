package com.wearable.controller;

import org.eclipse.paho.client.mqttv3.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MQTTManager implements MqttCallback {

    // A phone cannot infer the PC's LAN address safely. The user selects and saves it in the MQTT tab.
    public static final String DEFAULT_BROKER = "";
    private static final String DEFAULT_TOPIC_PREFIX = "sensors";
    // Wearable evidence and backend status must use acknowledged MQTT delivery.
    // QoS 0 is intentionally not accepted by this client.
    private static final int DEFAULT_QOS = 2;
    private static final int KEEP_ALIVE_INTERVAL = 60;
    // Fail fast when a saved broker is unreachable; reconnect remains enabled
    // after a successful connection is later lost.
    private static final int CONNECTION_TIMEOUT = 8;

    private String brokerUrl;
    private String username;
    private String password;
    private String topicPrefix;
    private final String clientId;
    private final Gson gson;
    private final Object connectionLock = new Object();

    private volatile MqttClient mqttClient;
    private volatile boolean connected = false;
    private volatile boolean connecting = false;

    private final AtomicInteger publishCounter = new AtomicInteger(0);
    private final AtomicInteger errorCounter = new AtomicInteger(0);

    // A QoS 2 publish waits for a four-step handshake with the broker, about
    // 45 ms to the cloud. Done on the Bluetooth callback thread that is less
    // than the 25 samples a second the ring sends, so the ring fell behind
    // (measured: 21 samples/s, then 9/s when the network slowed). Sensor
    // samples are queued and published by a few workers in parallel instead.
    // The queue holds about two minutes; beyond that a sample is counted as an
    // error rather than blocking the radio. Two round trips per sample means
    // N workers carry N / (2 x round-trip time) samples a second: four workers
    // fell below 25/s once the Wi-Fi round trip passed about 80 ms.
    private static final int PUBLISH_WORKERS = 16;
    private static final int MAX_INFLIGHT = 32;
    private static final int PUBLISH_QUEUE_LIMIT = 3000;
    private final ThreadPoolExecutor publishExecutor = newPublishExecutor();

    /**
     * Store and forward. Samples used to be published only while connected and
     * were dropped the moment the broker connection blinked, which over a relayed
     * mobile link happened often: in one recording the phone received the ring's
     * samples with 36 s of gaps, but they reached the server with 181 s of gaps.
     * Every message now goes into this outbox first; senders take it out while
     * connected, and put it back at the front if a publish fails, so a dropout
     * delays samples instead of losing them. Bounded by size: beyond it the
     * oldest messages are dropped, and counted.
     */
    private static final long OUTBOX_MAX_BYTES = 64L * 1024 * 1024;
    private final java.util.concurrent.LinkedBlockingDeque<OutboundBatch> outbox = new java.util.concurrent.LinkedBlockingDeque<>();
    private final java.util.concurrent.atomic.AtomicLong outboxBytes = new java.util.concurrent.atomic.AtomicLong();
    private final AtomicInteger sending = new AtomicInteger();
    private final AtomicInteger droppedCounter = new AtomicInteger();
    private volatile boolean sendersStarted;
    /** Holds payloads back while a recording is starting (see SessionGate). */
    private final SessionGate gate = new SessionGate();

    public SessionGate getSessionGate() {
        return gate;
    }

    /** Samples kept back because the recording's session does not exist yet; 0 when publishing. */
    public int getHeldCount() {
        return gate.holding() ? getQueuedCount() : 0;
    }

    private void enqueue(String topic, String payload, int qos, int samples) {
        outbox.offerLast(new OutboundBatch(topic, payload, qos, samples));
        outboxBytes.addAndGet(payload.length());
        while (outboxBytes.get() > OUTBOX_MAX_BYTES) {
            OutboundBatch oldest = outbox.pollFirst();
            if (oldest == null) break;
            outboxBytes.addAndGet(-oldest.payload.length());
            droppedCounter.addAndGet(oldest.samples);
            errorCounter.addAndGet(oldest.samples);
        }
        startSenders();
    }

    private synchronized void startSenders() {
        if (sendersStarted) return;
        sendersStarted = true;
        for (int i = 0; i < PUBLISH_WORKERS; i++) {
            Thread sender = new Thread(this::sendLoop, "mqtt-send-" + i);
            sender.setDaemon(true);
            sender.start();
        }
    }

    private void sendLoop() {
        while (true) {
            OutboundBatch item;
            if (!gate.canPublish()) {
                // A recording is starting: the backend has no session for this data yet.
                sleepQuietly(100);
                continue;
            }
            try {
                item = outbox.pollFirst(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (item == null) continue;
            if (!gate.canPublish()) {
                // A start began while this item was being taken out: put it back.
                outbox.offerFirst(item);
                continue;
            }
            MqttClient client = mqttClient;
            if (client == null || !isConnected()) {
                // Not connected: keep it, and wait for Paho's automatic reconnect.
                outbox.offerFirst(item);
                sleepQuietly(500);
                continue;
            }
            item = coalesce(item);
            outboxBytes.addAndGet(-item.payload.length());
            sending.incrementAndGet();
            try {
                // Batches go compressed when the backend takes them: the bandwidth of a
                // relayed mobile link, not the number of messages, limited the upload.
                byte[] body = item.encode(gzipIngest);
                MqttMessage message = new MqttMessage(body);
                message.setQos(item.qos);
                client.publish(item.topic, message);
                publishCounter.addAndGet(item.samples);
            } catch (MqttException e) {
                // Put it back first in line and try again shortly.
                outbox.offerFirst(item);
                outboxBytes.addAndGet(item.payload.length());
                sleepQuietly(500);
            } finally {
                sending.decrementAndGet();
            }
        }
    }

    /**
     * After a dropout the outbox holds many batches; each would cost its own round
     * trips over a slow link. Batches waiting behind this one for the same topic are
     * merged into it (up to about 1,000 samples), so a backlog drains in a few
     * messages. Only JSON-array batches are merged, and order is kept.
     */
    private OutboundBatch coalesce(OutboundBatch first) {
        if (!first.isArray()) return first;
        List<OutboundBatch> followers = new ArrayList<>();
        int samples = first.samples;
        while (samples < OutboundBatch.maxSamples(gzipIngest)) {
            OutboundBatch next = outbox.peekFirst();
            if (!OutboundBatch.canMerge(first, next)) break;
            if (!outbox.remove(next)) break;
            outboxBytes.addAndGet(-next.payload.length());
            followers.add(next);
            samples += next.samples;
        }
        OutboundBatch merged = OutboundBatch.merge(first, followers);
        // The caller subtracts the merged payload's size instead of the first's.
        outboxBytes.addAndGet(merged.payload.length() - first.payload.length());
        return merged;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Samples dropped because the outbox was full (a very long outage). */
    public int getDroppedCount() {
        return droppedCounter.get();
    }

    // A high-rate wearable (the earable's IMU and PPG) cannot be published one message per
    // sample: each QoS 2 publish costs a round trip, so the queue would only grow. Its
    // samples are gathered and sent as one JSON array, which a backend accepts only if its
    // /health said so (batch_ingest); otherwise they go one at a time as before.
    // Up to 250 samples (10 s of the ring) per message. Over Tailscale's relay a
    // round trip took 0.15-1.2 s and each QoS 2 message needs two, so the number
    // of messages, not their size, limits throughput: a backlog of 1,200 samples
    // drains in about five messages instead of 24.
    private static final int BATCH_MAX = 250;
    // Half a second, not 0.2 s: over a slow or relayed link (a phone on mobile data
    // reaching a laptop through Tailscale's relay) each message costs two round
    // trips, so fewer, larger messages keep up where many small ones fell behind.
    private static final long BATCH_DELAY_MS = 1000;
    private volatile boolean batchIngest;
    private final Map<String, List<String>> batches = new java.util.HashMap<>();
    private final Object batchLock = new Object();
    private final ScheduledExecutorService batchTimer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mqtt-batch");
        thread.setDaemon(true);
        return thread;
    });

    /** Set from the backend's health reply. */
    private volatile boolean gzipIngest;

    /** Set from the backend's health reply: it decompresses gzip batches. */
    public void setGzipIngest(boolean supported) {
        gzipIngest = supported;
    }

    public void setBatchIngest(boolean supported) {
        batchIngest = supported;
        if (!supported) flushBatches();
    }

    /** Like {@link #publishSensorData(SensorData, int)}, but gathers samples into batches when the backend takes them. */
    public boolean publishSensorDataBatched(SensorData data, int QoS) {
        if (!batchIngest) return publishSensorData(data, QoS);
        final String topic = topicPrefix + "/" + data.getDeviceType();
        final String payload = serializeSensorData(data);
        final int qos = acknowledgedQos(QoS);
        synchronized (batchLock) {
            List<String> batch = batches.computeIfAbsent(topic, key -> new ArrayList<>());
            batch.add(payload);
            if (batch.size() >= BATCH_MAX) {
                sendBatchLocked(topic, qos);
            } else if (batch.size() == 1) {
                batchTimer.schedule(() -> {
                    synchronized (batchLock) {
                        sendBatchLocked(topic, qos);
                    }
                }, BATCH_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        }
        return true;
    }

    private void sendBatchLocked(String topic, int qos) {
        List<String> batch = batches.remove(topic);
        if (batch == null || batch.isEmpty()) return;
        final String json = "[" + String.join(",", batch) + "]";
        enqueue(topic, json, qos, batch.size());
    }

    /** Send whatever is waiting, now. */
    public void flushBatches() {
        synchronized (batchLock) {
            for (String topic : new ArrayList<>(batches.keySet())) sendBatchLocked(topic, 2);
        }
    }

    private static ThreadPoolExecutor newPublishExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(PUBLISH_WORKERS, PUBLISH_WORKERS,
                30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(PUBLISH_QUEUE_LIMIT), runnable -> {
                    Thread thread = new Thread(runnable, "mqtt-publish");
                    thread.setDaemon(true);
                    return thread;
                });
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private ConnectionListener connectionListener;
    private MessageListener messageListener;
    private final Map<String, Integer> topicQoSMap = new ConcurrentHashMap<>();

    public interface ConnectionListener {
        void onConnected();
        void onDisconnected();
        void onConnectionFailed(Throwable cause);
    }

    public interface MessageListener {
        void onMessage(String topic, String payload);
    }

    public MQTTManager() {
        this(DEFAULT_BROKER, DEFAULT_TOPIC_PREFIX);
    }

    public MQTTManager(String brokerUrl, String topicPrefix) {
        this(brokerUrl, topicPrefix, "", "");
    }

    public MQTTManager(String brokerUrl, String topicPrefix, String username, String password) {
        this.brokerUrl = brokerUrl;
        this.topicPrefix = topicPrefix;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.clientId = "AndroidWearable_" + System.currentTimeMillis();
        this.gson = new GsonBuilder().create();
    }

    public boolean connect() {
        return connect(username, password);
    }

    public boolean connect(String username, String password) {
        synchronized (connectionLock) {
            if (mqttClient != null && mqttClient.isConnected()) {
                connected = true;
                connecting = false;
                return true;
            }
            if (connecting) {
                return false;
            }

            connecting = true;
            try {
                closeClientLocked();

                mqttClient = new MqttClient(brokerUrl, clientId, null);
                mqttClient.setCallback(this);

                MqttConnectOptions options = new MqttConnectOptions();
                // Keep status/# subscriptions across Paho automatic reconnects.
                options.setCleanSession(false);
                options.setKeepAliveInterval(KEEP_ALIVE_INTERVAL);
                options.setConnectionTimeout(CONNECTION_TIMEOUT);
                options.setAutomaticReconnect(true);
                options.setMaxInflight(MAX_INFLIGHT);

                if (!username.isEmpty()) {
                    options.setUserName(username);
                    options.setPassword(password.toCharArray());
                }

                mqttClient.connect(options);
                connected = true;
                connecting = false;

                for (Map.Entry<String, Integer> subscription : topicQoSMap.entrySet()) {
                    mqttClient.subscribe(subscription.getKey(), subscription.getValue());
                }

                if (connectionListener != null) {
                    connectionListener.onConnected();
                }

                return true;
            } catch (MqttException e) {
                closeClientLocked();
                connected = false;
                connecting = false;
                if (connectionListener != null) {
                    connectionListener.onConnectionFailed(e);
                }
                return false;
            }
        }
    }

    public boolean reconnectTo(String newBrokerUrl) {
        return reconnectTo(newBrokerUrl, username, password);
    }

    public boolean reconnectTo(String newBrokerUrl, String username, String password) {
        synchronized (connectionLock) {
            closeClientLocked();
            connected = false;
            connecting = false;
            this.brokerUrl = newBrokerUrl;
            this.username = username == null ? "" : username;
            this.password = password == null ? "" : password;
        }
        return connect();
    }

    public void disconnect() {
        synchronized (connectionLock) {
            closeClientLocked();
            connected = false;
            connecting = false;
        }
        if (connectionListener != null) {
            connectionListener.onDisconnected();
        }
    }

    public boolean isConnected() {
        // This is called from the UI status ticker. Do not acquire connectionLock:
        // connect() holds that lock while the blocking Paho handshake is in progress.
        MqttClient client = mqttClient;
        boolean active = client != null && client.isConnected();
        connected = active;
        return active;
    }

    public boolean isConnecting() {
        return connecting;
    }

    /**
     * Publish under a different prefix, such as a participant's own
     * {@code sensors/u<id>} namespace. Takes effect from the next sample.
     */
    public void setTopicPrefix(String topicPrefix) {
        this.topicPrefix = topicPrefix == null || topicPrefix.trim().isEmpty()
                ? DEFAULT_TOPIC_PREFIX
                : topicPrefix.trim();
    }

    /** True when the current connection uses exactly this login. */
    public boolean usesLogin(String user, String secret) {
        return user != null && user.equals(username) && secret != null && secret.equals(password);
    }

    public String getUsername() {
        return username;
    }

    /**
     * Forget remembered subscriptions so a reconnect under a different account
     * does not try to resubscribe to topics that account may not read.
     */
    public void clearSubscriptions() {
        topicQoSMap.clear();
    }

    public int getQoS() {
        return DEFAULT_QOS;
    }

    public static String getDefaultBroker() {
        return DEFAULT_BROKER;
    }

    public void setConnectionListener(ConnectionListener listener) {
        this.connectionListener = listener;
    }

    public void setMessageListener(MessageListener listener) {
        this.messageListener = listener;
    }

    public boolean publishSensorData(SensorData data) {
        return publishSensorData(data, DEFAULT_QOS);
    }

    /** Queue one sensor sample; returns false if it could not be accepted. */
    public boolean publishSensorData(SensorData data, int QoS) {
        // Serialise here: the sample is not touched again by the caller, but
        // its time stamps should be read before the queue delays anything.
        // Accepted whether or not the broker is connected right now (outbox).
        final String topic = topicPrefix + "/" + data.getDeviceType();
        final String payload = serializeSensorData(data);
        final int qos = acknowledgedQos(QoS);
        enqueue(topic, payload, qos, 1);
        return true;
    }

    /** Samples accepted but not yet acknowledged by the broker. */
    public int getQueuedCount() {
        int waiting = 0;
        for (OutboundBatch item : outbox) waiting += item.samples;
        return waiting + sending.get();
    }

    /**
     * Wait until everything queued has been published, or the timeout passes.
     * A recording must not be closed while its samples are still on the phone:
     * the backend refuses samples for a session that has ended.
     *
     * @return true if the queue emptied
     */
    public boolean awaitPublishDrain(long timeoutMs) {
        flushBatches();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (getQueuedCount() > 0) {
            // A brief disconnect is waited out: the outbox sends on reconnect.
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    private void publishNow(String topic, String payload, int qos) {
        MqttClient client = mqttClient;
        if (client == null || !isConnected()) {
            errorCounter.incrementAndGet();
            return;
        }
        try {
            MqttMessage message = new MqttMessage(payload.getBytes());
            message.setQos(qos);
            client.publish(topic, message);
            publishCounter.incrementAndGet();
        } catch (MqttException e) {
            errorCounter.incrementAndGet();
        }
    }

    public boolean publishToTopic(String topicSuffix, String message, int QoS) {
        MqttClient client = mqttClient;
        if (client == null || !isConnected()) {
            return false;
        }

        try {
            String topic = topicPrefix + "/" + topicSuffix;
            MqttMessage mqttMessage = new MqttMessage(message.getBytes());
            mqttMessage.setQos(acknowledgedQos(QoS));

            client.publish(topic, mqttMessage);
            publishCounter.incrementAndGet();

            return true;
        } catch (MqttException e) {
            errorCounter.incrementAndGet();
            return false;
        }
    }

    public boolean subscribe(String topicFilter) {
        return subscribe(topicFilter, DEFAULT_QOS);
    }

    public boolean subscribe(String topicFilter, int QoS) {
        if (!isConnected()) {
            return false;
        }

        try {
            int acknowledgedQos = acknowledgedQos(QoS);
            mqttClient.subscribe(topicFilter, acknowledgedQos);
            topicQoSMap.put(topicFilter, acknowledgedQos);
            return true;
        } catch (MqttException e) {
            return false;
        }
    }

    private String serializeSensorData(SensorData data) {
        SensorDataPacket packet = new SensorDataPacket();
        Map<String, Object> acquisition = data.getAcquisitionMetadata();
        packet.device = data.getDeviceType();
        packet.address = data.getDeviceAddress();
        packet.type = data.getSensorType();
        packet.timestamp = data.getTimestamp();
        packet.serverTimestamp = System.currentTimeMillis();
        packet.data = data.getData();
        packet.schemaVersion = 2;
        packet.sampleRateHz = numberValue(acquisition.get("configuredSampleRateHz"), getSampleRateForSensorType(data.getSensorType()));
        packet.timing = acquisition;

        return gson.toJson(packet);
    }

    private int getSampleRateForSensorType(String sensorType) {
        switch (sensorType) {
            case "PPG":
            case "IMU":
                return 25;
            case "TEMP_BARO":
                return 1;
            case "MICRO":
                return 48000;
            default:
                return 0;
        }
    }

    public int getPublishCount() {
        return publishCounter.get();
    }

    public int getErrorCount() {
        return errorCounter.get();
    }

    public String getBrokerUrl() {
        return brokerUrl;
    }

    public String getTopicPrefix() {
        return topicPrefix;
    }

    @Override
    public void connectionLost(Throwable cause) {
        connected = false;
        connecting = false;
        if (connectionListener != null) {
            connectionListener.onConnectionFailed(cause);
        }
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) throws Exception {
        if (messageListener != null) {
            messageListener.onMessage(topic, new String(message.getPayload()));
        }
    }

    private static int acknowledgedQos(int requestedQos) {
        return requestedQos >= 2 ? 2 : 1;
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
    }

    private int numberValue(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private void closeClientLocked() {
        if (mqttClient == null) {
            return;
        }

        try {
            mqttClient.setCallback(null);
            if (mqttClient.isConnected()) {
                mqttClient.disconnect();
            }
        } catch (MqttException ignored) {
        }

        try {
            mqttClient.close(true);
        } catch (MqttException ignored) {
        }

        mqttClient = null;
    }

    public static class SensorDataPacket {
        public int schemaVersion;
        public String device;
        public String address;
        public String type;
        public long timestamp;
        public long serverTimestamp;
        public int sampleRateHz;
        public Map<String, Object> data;
        public Map<String, Object> timing;
    }
}
