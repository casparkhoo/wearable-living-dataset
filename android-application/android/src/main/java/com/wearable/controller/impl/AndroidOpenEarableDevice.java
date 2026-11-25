package com.wearable.controller.impl;

import com.wearable.controller.*;
import com.wearable.controller.bluetooth.AndroidBLEService;
import java.util.*;

public class AndroidOpenEarableDevice implements WearableDevice {
    private final String address;
    private final AndroidBLEService bleService;
    private final DeviceCapabilities capabilities;
    private ConnectionListener connectionListener;
    private DataListener dataListener;
    private boolean connected = false;

    public AndroidOpenEarableDevice(String address, AndroidBLEService bleService) {
        this.address = address;
        this.bleService = bleService;
        this.capabilities = new DeviceCapabilities.Builder("OpenEarable")
            .addSensor("IMU").addSensor("PPG").addSensor("TEMP")
            .addCommand("SET_LED").addCommand("AUDIO_MODE")
            .supportsRealTimeData(true)
            .build();
    }

    @Override
    public String getDeviceName() { return "OpenEarable"; }

    @Override
    public String getDeviceType() { return "OpenEarable"; }

    @Override
    public String getDeviceAddress() { return address; }

    @Override
    public DeviceCapabilities getCapabilities() { return capabilities; }

    @Override
    public boolean connect() {
        return bleService.connect(address);
    }

    @Override
    public boolean disconnect() {
        bleService.disconnect(address);
        return true;
    }

    @Override
    public boolean isConnected() { return connected; }

    @Override
    public void setConnectionListener(ConnectionListener listener) {
        this.connectionListener = listener;
    }

    @Override
    public DeviceInfo getDeviceInfo() {
        return new DeviceInfo("OpenEarable", address);
    }

    @Override
    public boolean startMeasurement(MeasurementConfig config) {
        byte[] data = new byte[]{
            getSensorId(config.getSensorType()),
            (byte) config.getSampleRate(),
            0x01 // Streaming
        };
        return bleService.write(address, 
            AndroidBLEService.UUIDS.OPENEARABLE_SENSOR_SERVICE,
            AndroidBLEService.UUIDS.OPENEARABLE_SENSOR_CONFIG, 
            data);
    }

    @Override
    public boolean stopMeasurement() {
        byte[] data = new byte[]{0, 0, 0};
        return bleService.write(address, 
            AndroidBLEService.UUIDS.OPENEARABLE_SENSOR_SERVICE,
            AndroidBLEService.UUIDS.OPENEARABLE_SENSOR_CONFIG, 
            data);
    }

    @Override
    public List<SensorData> getLatestData() { return new ArrayList<>(); }

    @Override
    public boolean sendCommand(String command, Map<String, Object> parameters) {
        if ("SET_LED".equals(command)) {
            byte r = ((Number) parameters.getOrDefault("r", 0)).byteValue();
            byte g = ((Number) parameters.getOrDefault("g", 0)).byteValue();
            byte b = ((Number) parameters.getOrDefault("b", 0)).byteValue();
            return bleService.write(address, 
                AndroidBLEService.UUIDS.OPENEARABLE_LED_SERVICE,
                AndroidBLEService.UUIDS.OPENEARABLE_LED_RGB, 
                new byte[]{r, g, b});
        }
        return false;
    }

    @Override
    public List<String> getAvailableCommands() {
        return capabilities.getSupportedCommands();
    }

    @Override
    public void setDataListener(DataListener listener) {
        this.dataListener = listener;
        bleService.enableNotifications(address, 
            AndroidBLEService.UUIDS.OPENEARABLE_SENSOR_SERVICE,
            AndroidBLEService.UUIDS.OPENEARABLE_SENSOR_DATA);
    }

    @Override
    public void enableDataStreaming(boolean enabled) {
        if (enabled) {
            bleService.enableNotifications(address, 
                AndroidBLEService.UUIDS.OPENEARABLE_SENSOR_SERVICE,
                AndroidBLEService.UUIDS.OPENEARABLE_SENSOR_DATA);
        }
    }

    private byte getSensorId(String type) {
        switch (type) {
            case "IMU": return 0;
            case "TEMP": return 1;
            case "PPG": return 4;
            default: return 0;
        }
    }
    
    // Internal callback called by AndroidBLEService via MainActivity or similar
    public void handleRawData(String charUuid, byte[] data) {
        if (AndroidBLEService.UUIDS.OPENEARABLE_SENSOR_DATA.equalsIgnoreCase(charUuid)) {
            // Parse and notify dataListener
            if (dataListener != null) {
                dataListener.onDataReceived(new SensorData("OpenEarable", address, "Sensor", System.currentTimeMillis()));
            }
        }
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
        if (connected && connectionListener != null) {
            connectionListener.onConnected(address);
        } else if (!connected && connectionListener != null) {
            connectionListener.onDisconnected(address);
        }
    }
}
