package com.wearable.controller;

import java.util.List;
import java.util.Map;

public interface WearableDevice {
    String getDeviceName();
    String getDeviceType();
    String getDeviceAddress();
    DeviceCapabilities getCapabilities();
    boolean connect();
    boolean disconnect();
    boolean isConnected();
    void setConnected(boolean connected);
    void setConnectionListener(ConnectionListener listener);
    DeviceInfo getDeviceInfo();
    boolean startMeasurement(MeasurementConfig config);
    boolean stopMeasurement();
    List<SensorData> getLatestData();
    boolean sendCommand(String command, Map<String, Object> parameters);
    List<String> getAvailableCommands();
    void setDataListener(DataListener listener);
    void enableDataStreaming(boolean enabled);

    /**
     * Commands the app sends, in order, when a recording starts. Declared by the
     * adapter so that adding a wearable needs no change to the recording code.
     */
    default List<String> getRecordingStartCommands() { return java.util.Collections.emptyList(); }

    /** Commands the app sends, in order, when a recording stops. */
    default List<String> getRecordingStopCommands() { return java.util.Collections.emptyList(); }

    /** Optional transport publisher used by sensor-producing adapters. */
    default void setMQTTManager(MQTTManager mqttManager) { }

    default void setMQTTEnabled(boolean enabled) { }

    interface ConnectionListener {
        void onConnected(String deviceAddress);
        void onDisconnected(String deviceAddress);
        void onConnectionError(String deviceAddress, String error);
    }
    interface DataListener {
        void onDataReceived(SensorData data);
        void onCommandResponse(String command, boolean success, String response);
    }
}
