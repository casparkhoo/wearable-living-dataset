package com.wearable.controller.impl;

import com.wearable.controller.DeviceCapabilities;
import com.wearable.controller.DeviceInfo;
import com.wearable.controller.DeviceProfile;
import com.wearable.controller.MQTTManager;
import com.wearable.controller.MeasurementConfig;
import com.wearable.controller.SensorData;
import com.wearable.controller.WearableDevice;
import com.wearable.controller.bluetooth.AndroidBLEService;
import com.wearable.controller.bluetooth.BleAdvertisement;
import com.wearable.controller.bluetooth.BleDeviceAdapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Any wearable that implements the Bluetooth SIG Heart Rate Service (0x180D):
 * chest straps, arm bands and many watches in broadcast mode.
 *
 * This is also the reference for adding a wearable. It is the smallest complete
 * adapter: one service, one notifying characteristic, no start command and a
 * documented packet layout. docs/ADDING_A_DEVICE.md walks through it.
 *
 * The protocol decoding ({@link #parseMeasurement}) is a pure function with no
 * Android dependency, so it is unit-tested with recorded bytes
 * (HeartRateMeasurementTest).
 */
public class AndroidHeartRateDevice implements WearableDevice, BleDeviceAdapter {
    public static final String TYPE = "HeartRateMonitor";

    /** How the app names and treats this wearable; see DeviceProfile. */
    public static final DeviceProfile PROFILE = new DeviceProfile.Builder(TYPE)
            .displayName("Heart-rate strap")
            .shortName("HR strap")
            .pickerLabel("Heart-rate strap  ·  standard Bluetooth heart rate")
            .wearPosition("chest")
            .stream("HEART_RATE", DeviceProfile.StreamRole.PULSE)
            .build();
    // 16-bit SIG identifiers written out on the Bluetooth base UUID.
    public static final String SERVICE_UUID = "0000180d-0000-1000-8000-00805f9b34fb";
    public static final String MEASUREMENT_UUID = "00002a37-0000-1000-8000-00805f9b34fb";

    /** One decoded Heart Rate Measurement notification. */
    public static final class Measurement {
        /** Beats per minute as computed by the device. */
        public final int heartRateBpm;
        /** null when the device does not report skin contact. */
        public final Boolean sensorContact;
        /** Beat-to-beat intervals in milliseconds, oldest first; may be empty. */
        public final List<Double> rrIntervalsMs;

        Measurement(int heartRateBpm, Boolean sensorContact, List<Double> rrIntervalsMs) {
            this.heartRateBpm = heartRateBpm;
            this.sensorContact = sensorContact;
            this.rrIntervalsMs = Collections.unmodifiableList(rrIntervalsMs);
        }
    }

    /**
     * Decode a Heart Rate Measurement (GATT characteristic 0x2A37).
     *
     * Byte 0 is a flags field: bit 0 selects an 8- or 16-bit heart rate, bits 1-2
     * report sensor contact, bit 3 says an Energy Expended field follows, and
     * bit 4 says RR intervals follow, each a little-endian uint16 in units of
     * 1/1024 s. Returns null for a packet too short for what its flags promise,
     * so a malformed notification is dropped here instead of decoded into a
     * plausible wrong value.
     */
    public static Measurement parseMeasurement(byte[] data) {
        if (data == null || data.length < 2) return null;
        int flags = data[0] & 0xFF;
        int offset = 1;
        int heartRate;
        if ((flags & 0x01) != 0) {
            if (data.length < offset + 2) return null;
            heartRate = (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
            offset += 2;
        } else {
            heartRate = data[offset] & 0xFF;
            offset += 1;
        }
        Boolean contact = (flags & 0x04) != 0 ? Boolean.valueOf((flags & 0x02) != 0) : null;
        if ((flags & 0x08) != 0) {
            if (data.length < offset + 2) return null;
            offset += 2; // Energy Expended: not used
        }
        List<Double> rr = new ArrayList<>();
        if ((flags & 0x10) != 0) {
            int remaining = data.length - offset;
            if (remaining < 2 || remaining % 2 != 0) return null;
            for (; offset + 1 < data.length; offset += 2) {
                int raw = (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
                rr.add(raw * 1000.0 / 1024.0);
            }
        }
        return new Measurement(heartRate, contact, rr);
    }

    public static boolean matchesAdvertisement(BleAdvertisement advertisement) {
        return advertisement.hasService(SERVICE_UUID);
    }

    private final String address;
    private final AndroidBLEService bleService;
    private final DeviceCapabilities capabilities;
    private final AtomicLong sampleIndex = new AtomicLong(0);
    private ConnectionListener connectionListener;
    private DataListener dataListener;
    private MQTTManager mqttManager;
    private boolean mqttEnabled = true;
    private volatile boolean connected = false;
    // The service notifies as soon as it is subscribed; samples are only
    // forwarded while a recording has asked for them.
    private volatile boolean streaming = false;

    public AndroidHeartRateDevice(String address, AndroidBLEService bleService) {
        this.address = address;
        this.bleService = bleService;
        this.capabilities = new DeviceCapabilities.Builder(TYPE)
                .addSensor("HEART_RATE")
                .addCommand("START_MEASUREMENT")
                .addCommand("STOP_MEASUREMENT")
                .supportsRealTimeData(true)
                .build();
    }

    // -- What the app sends when a recording starts and stops ---------------

    @Override
    public List<String> getRecordingStartCommands() {
        return Collections.singletonList("START_MEASUREMENT");
    }

    @Override
    public List<String> getRecordingStopCommands() {
        return Collections.singletonList("STOP_MEASUREMENT");
    }

    // -- Bluetooth lifecycle: subscribe once services are known -------------

    @Override
    public void onBleServicesDiscovered() {
        // One GATT operation at a time: this adapter needs no MTU change, so the
        // subscription is its only request.
        bleService.enableNotifications(address, SERVICE_UUID, MEASUREMENT_UUID);
    }

    @Override
    public void onBleNotification(String serviceUuid, String characteristicUuid, byte[] data) {
        // Stamp the phone's clock first, before any decoding.
        long receivedAtMs = System.currentTimeMillis();
        if (!MEASUREMENT_UUID.equalsIgnoreCase(characteristicUuid) || !streaming) return;
        Measurement measurement = parseMeasurement(data);
        if (measurement == null) return;

        SensorData sample = new SensorData(TYPE, address, "HEART_RATE", receivedAtMs);
        sample.setData("heart_rate", measurement.heartRateBpm);
        if (!measurement.rrIntervalsMs.isEmpty()) {
            // The newest interval as a channel; all of them, as sent, beside it.
            sample.setData("rr_interval_ms",
                    measurement.rrIntervalsMs.get(measurement.rrIntervalsMs.size() - 1));
            sample.setAcquisitionMetadata("rrIntervalsMs", measurement.rrIntervalsMs);
        }
        if (measurement.sensorContact != null) {
            sample.setAcquisitionMetadata("sensorContact", measurement.sensorContact);
        }
        sample.setAcquisitionMetadata("androidReceivedTimestampMs", receivedAtMs);
        sample.setAcquisitionMetadata("sampleIndex", sampleIndex.getAndIncrement());
        // The service carries no timestamp: arrival time is the only clock there is.
        sample.setAcquisitionMetadata("clockSource", "arrival_only");
        sample.setAcquisitionMetadata("clockQuality", "unvalidated");

        if (dataListener != null) dataListener.onDataReceived(sample);
        if (mqttEnabled && mqttManager != null && mqttManager.isConnected()) {
            mqttManager.publishSensorData(sample, 2);
        }
    }

    // -- Commands -----------------------------------------------------------

    @Override
    public boolean startMeasurement(MeasurementConfig config) {
        streaming = true;
        return true;
    }

    @Override
    public boolean stopMeasurement() {
        streaming = false;
        return true;
    }

    @Override
    public boolean sendCommand(String command, Map<String, Object> parameters) {
        switch (command) {
            case "START_MEASUREMENT":
                return startMeasurement(null);
            case "STOP_MEASUREMENT":
                return stopMeasurement();
            default:
                return false;
        }
    }

    @Override
    public List<String> getAvailableCommands() { return capabilities.getSupportedCommands(); }

    // -- Plumbing every adapter repeats -------------------------------------

    @Override public String getDeviceName() { return TYPE; }
    @Override public String getDeviceType() { return TYPE; }
    @Override public String getDeviceAddress() { return address; }
    @Override public DeviceCapabilities getCapabilities() { return capabilities; }
    @Override public DeviceInfo getDeviceInfo() { return new DeviceInfo(TYPE, address); }
    @Override public boolean isConnected() { return connected; }
    @Override public List<SensorData> getLatestData() { return new ArrayList<>(); }
    @Override public void setConnectionListener(ConnectionListener listener) { this.connectionListener = listener; }
    @Override public void setDataListener(DataListener listener) { this.dataListener = listener; }
    @Override public void setMQTTManager(MQTTManager mqttManager) { this.mqttManager = mqttManager; }
    @Override public void setMQTTEnabled(boolean enabled) { this.mqttEnabled = enabled; }

    @Override
    public boolean connect() {
        bleService.registerDeviceAdapter(this);
        return bleService.connect(address);
    }

    @Override
    public boolean disconnect() {
        bleService.disconnect(address);
        return true;
    }

    @Override
    public void setConnected(boolean connected) {
        this.connected = connected;
        if (!connected) streaming = false;
        if (connectionListener == null) return;
        if (connected) connectionListener.onConnected(address);
        else connectionListener.onDisconnected(address);
    }

    @Override
    public void enableDataStreaming(boolean enabled) {
        if (enabled) bleService.enableNotifications(address, SERVICE_UUID, MEASUREMENT_UUID);
    }
}
