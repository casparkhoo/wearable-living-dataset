package com.wearable.controller;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** HTTP API control path to the Rust backend. MQTT remains the sensor-data path. */
public class RestApiClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    public interface ResultCallback {
        void onSuccess(JsonObject response);
        void onError(String message);
    }

    /**
     * Supplies the credential for each request. Implementations may block, for
     * example to refresh an expired access token, because this is called on
     * OkHttp's own threads rather than the UI thread.
     */
    public interface CredentialProvider {
        String bearerToken();
    }

    private volatile String baseUrl = "";
    private volatile String bearerToken = "";
    private volatile CredentialProvider credentialProvider;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            // Attaching the credential here, rather than where each request is
            // built, keeps a token refresh off the calling thread.
            .addInterceptor(chain -> {
                Request original = chain.request();
                String token = "";
                CredentialProvider provider = credentialProvider;
                if (provider != null) {
                    try {
                        token = provider.bearerToken();
                    } catch (RuntimeException e) {
                        token = "";
                    }
                }
                if (token == null || token.isEmpty()) {
                    // No signed-in participant: fall back to the deployment-wide
                    // token while it is still accepted.
                    token = bearerToken;
                }
                if (token != null && !token.isEmpty()) {
                    original = original.newBuilder()
                            .header("Authorization", "Bearer " + token)
                            .build();
                }
                return chain.proceed(original);
            })
            .build();

    public static final class DeviceRecordingSpec {
        public final String deviceType;
        public final String deviceAddress;
        public final String label;

        public DeviceRecordingSpec(String deviceType, String deviceAddress, String label) {
            this.deviceType = deviceType;
            this.deviceAddress = deviceAddress;
            this.label = label;
        }
    }

    public void setBaseUrl(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isEmpty() && !normalized.contains("://")) {
            normalized = "http://" + normalized;
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        baseUrl = normalized;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBearerToken(String value) {
        bearerToken = value == null ? "" : value.trim();
    }

    /** A signed-in participant's token takes precedence over the shared one. */
    public void setCredentialProvider(CredentialProvider provider) {
        this.credentialProvider = provider;
    }

    /** One request atomically starts the experiment and every connected device recording. */
    public void startRecording(String label, String protocol, List<DeviceRecordingSpec> devices,
                               long clientTimestampMs, ResultCallback callback) {
        startRecording(label, protocol, devices, clientTimestampMs, null, callback);
    }

    /**
     * Start a recording, optionally attributing it to a study.
     *
     * A null study id records without study membership, which is what an
     * unauthenticated operator gets. The backend refuses a study the signed-in
     * account is not an active member of.
     */
    public void startRecording(String label, String protocol, List<DeviceRecordingSpec> devices,
                               long clientTimestampMs, Long studyId, ResultCallback callback) {
        JsonObject body = new JsonObject();
        body.addProperty("experiment_label", label);
        if (protocol != null && !protocol.trim().isEmpty()) {
            body.addProperty("protocol_name", protocol.trim());
        }
        if (studyId != null) {
            body.addProperty("study_id", studyId);
        }
        body.addProperty("client_timestamp_ms", clientTimestampMs);
        JsonArray deviceRecordings = new JsonArray();
        for (DeviceRecordingSpec device : devices) {
            JsonObject item = new JsonObject();
            item.addProperty("device_type", device.deviceType);
            item.addProperty("device_address", device.deviceAddress);
            if (device.label != null && !device.label.trim().isEmpty()) {
                item.addProperty("session_label", device.label.trim());
            }
            deviceRecordings.add(item);
        }
        body.add("device_recordings", deviceRecordings);
        post("/api/recordings", body, callback);
    }

    /** Studies the signed-in account can see. Requires a real account. */
    public void getStudies(ResultCallback callback) {
        get("/api/studies", callback);
    }

    /** The signed-in account, its platform role and its studies. */
    public void getMe(ResultCallback callback) {
        get("/api/me", callback);
    }

    /**
     * This participant's own broker account: username, password, the sensor
     * topic prefix they may publish under and the status topic they may read.
     * Answers {@code per_user_credentials: false} on a deployment that still
     * shares one broker account.
     */
    public void getMqttCredentials(ResultCallback callback) {
        get("/api/me/mqtt-credentials", callback);
    }

    public void stopRecording(long experimentId, long clientTimestampMs, ResultCallback callback) {
        post("/api/recordings/" + experimentId + "/stop", timestampBody(clientTimestampMs), callback);
    }

    public void getActiveControlState(ResultCallback callback) {
        if (baseUrl.isEmpty()) {
            callback.onError("Set the backend URL first");
            return;
        }
        request(new Request.Builder().url(baseUrl + "/api/experiments/active").get().build(), callback);
    }

    public void checkHealth(ResultCallback callback) {
        get("/health", callback);
    }

    public void getExperiments(ResultCallback callback) {
        get("/api/experiments", callback);
    }

    public void getExperimentDetails(long experimentId, int evidenceLimit, ResultCallback callback) {
        int safeLimit = Math.max(1, Math.min(500, evidenceLimit));
        get("/api/experiments/" + experimentId + "/details?limit=" + safeLimit, callback);
    }

    public void createExperiment(String label, String protocol, long clientTimestampMs, ResultCallback callback) {
        createExperiment(label, protocol, clientTimestampMs, false, callback);
    }

    public void createAndStartExperiment(String label, String protocol, long clientTimestampMs, ResultCallback callback) {
        createExperiment(label, protocol, clientTimestampMs, true, callback);
    }

    private void createExperiment(String label, String protocol, long clientTimestampMs,
                                  boolean startImmediately, ResultCallback callback) {
        post("/api/experiments", buildCreateExperimentBody(label, protocol, clientTimestampMs, startImmediately), callback);
    }

    static JsonObject buildCreateExperimentBody(String label, String protocol, long clientTimestampMs,
                                                boolean startImmediately) {
        JsonObject body = new JsonObject();
        body.addProperty("experiment_label", label);
        if (protocol != null && !protocol.trim().isEmpty()) body.addProperty("protocol_name", protocol.trim());
        body.addProperty("start_immediately", startImmediately);
        body.addProperty("client_timestamp_ms", clientTimestampMs);
        return body;
    }

    public void startExperiment(long experimentId, long clientTimestampMs, ResultCallback callback) {
        post("/api/experiments/" + experimentId + "/start", timestampBody(clientTimestampMs), callback);
    }

    public void stopExperiment(long experimentId, long clientTimestampMs, ResultCallback callback) {
        post("/api/experiments/" + experimentId + "/stop", timestampBody(clientTimestampMs), callback);
    }

    public void startEvidenceSession(long experimentId, String deviceType, String deviceAddress,
                                     String sessionLabel, long clientTimestampMs, ResultCallback callback) {
        JsonObject body = new JsonObject();
        body.addProperty("device_type", deviceType);
        body.addProperty("device_address", deviceAddress);
        if (sessionLabel != null && !sessionLabel.trim().isEmpty()) body.addProperty("session_label", sessionLabel.trim());
        body.addProperty("client_timestamp_ms", clientTimestampMs);
        post("/api/experiments/" + experimentId + "/evidence-sessions", body, callback);
    }

    public void stopEvidenceSession(long sessionId, long clientTimestampMs, ResultCallback callback) {
        post("/api/evidence-sessions/" + sessionId + "/stop", timestampBody(clientTimestampMs), callback);
    }

    public void startAnnotation(long experimentId, String labelName, String labelValue,
                                long clientTimestampMs, long clientMonotonicNs, ResultCallback callback) {
        JsonObject body = new JsonObject();
        body.addProperty("label_name", labelName);
        body.addProperty("label_value", labelValue);
        body.addProperty("started_at_ms", clientTimestampMs);
        body.addProperty("client_timestamp_ms", clientTimestampMs);
        body.addProperty("client_monotonic_ns", clientMonotonicNs);
        body.addProperty("source", "manual_app");
        post("/api/experiments/" + experimentId + "/annotations", body, callback);
    }

    public void stopAnnotation(long annotationId, long clientTimestampMs, ResultCallback callback) {
        post("/api/annotations/" + annotationId + "/stop", timestampBody(clientTimestampMs), callback);
    }

    private JsonObject timestampBody(long clientTimestampMs) {
        JsonObject body = new JsonObject();
        body.addProperty("client_timestamp_ms", clientTimestampMs);
        return body;
    }

    private void post(String path, JsonObject body, ResultCallback callback) {
        if (baseUrl.isEmpty()) {
            callback.onError("Set the backend URL first");
            return;
        }
        Request request = new Request.Builder()
                .url(baseUrl + path)
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        request(request, callback);
    }

    private void get(String path, ResultCallback callback) {
        if (baseUrl.isEmpty()) {
            callback.onError("Set the backend URL first");
            return;
        }
        request(new Request.Builder().url(baseUrl + path).get().build(), callback);
    }

    private void request(Request request, ResultCallback callback) {
        if (baseUrl.isEmpty()) {
            callback.onError("Set the backend URL first");
            return;
        }
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(friendlyConnectionError(e));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (Response closeable = response) {
                    String text = closeable.body() == null ? "{}" : closeable.body().string();
                    JsonObject payload;
                    try {
                        payload = JsonParser.parseString(text).getAsJsonObject();
                    } catch (Exception ignored) {
                        payload = new JsonObject();
                        payload.addProperty("raw", text);
                    }
                    if (!closeable.isSuccessful()) {
                        String message = payload.has("error") ? payload.get("error").getAsString() : "HTTP " + closeable.code();
                        callback.onError(message);
                    } else {
                        callback.onSuccess(payload);
                    }
                }
            }
        });
    }

    private String friendlyConnectionError(IOException error) {
        if (error instanceof UnknownHostException) {
            return "Host not found. Check the PC IP address in " + baseUrl + ".";
        }
        if (error instanceof ConnectException) {
            return "Connection refused by " + baseUrl +
                    ". Start the Rust backend and check that it listens on 0.0.0.0:8080.";
        }
        if (error instanceof SocketTimeoutException) {
            return "Timed out contacting " + baseUrl +
                    ". Confirm the phone and PC are on the same network and Windows Firewall allows port 8080.";
        }
        String message = error.getMessage();
        return "Could not reach " + baseUrl + (message == null ? "." : ": " + message);
    }
}
