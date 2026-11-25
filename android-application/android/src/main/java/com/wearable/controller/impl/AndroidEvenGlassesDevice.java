package com.wearable.controller.impl;

import com.wearable.controller.*;
import com.wearable.controller.bluetooth.AndroidBLEService;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class AndroidEvenGlassesDevice implements WearableDevice {
    private final String address;
    private final AndroidBLEService bleService;
    private final DeviceCapabilities capabilities;
    private ConnectionListener connectionListener;
    private DataListener dataListener;
    private boolean connected = false;
    private final AtomicInteger sequenceNumber = new AtomicInteger(0);
    private Timer heartbeatTimer;

    public AndroidEvenGlassesDevice(String address, AndroidBLEService bleService) {
        this.address = address;
        this.bleService = bleService;
        this.capabilities = new DeviceCapabilities.Builder("Even Glasses")
            .addSensor("Microphone").addSensor("Touchpad")
            .addCommand("DISPLAY_TEXT").addCommand("HEARTBEAT")
            .supportsAudioControl(true)
            .build();
    }

    @Override
    public String getDeviceName() { return "Even Glasses"; }

    @Override
    public String getDeviceType() { return "Even Glasses"; }

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
        stopHeartbeat();
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
        return new DeviceInfo("Even Glasses", address);
    }

    @Override
    public boolean startMeasurement(MeasurementConfig config) {
        if ("Microphone".equals(config.getSensorType())) {
            return bleService.write(address, 
                AndroidBLEService.UUIDS.EVEN_SERVICE, 
                AndroidBLEService.UUIDS.EVEN_WRITE, 
                new byte[]{0x0E, 0x01});
        }
        return false;
    }

    @Override
    public boolean stopMeasurement() {
        return bleService.write(address, 
            AndroidBLEService.UUIDS.EVEN_SERVICE, 
            AndroidBLEService.UUIDS.EVEN_WRITE, 
            new byte[]{0x18}); // Exit functions
    }

    @Override
    public List<SensorData> getLatestData() { return new ArrayList<>(); }

    @Override
    public boolean sendCommand(String command, Map<String, Object> parameters) {
        if ("DISPLAY_TEXT".equals(command)) {
            String text = (String) parameters.getOrDefault("text", "");
            byte[] textBytes = text.getBytes();
            byte[] cmd = new byte[textBytes.length + 1];
            cmd[0] = 0x30; // Initial screen
            System.arraycopy(textBytes, 0, cmd, 1, textBytes.length);
            return bleService.write(address, AndroidBLEService.UUIDS.EVEN_SERVICE, AndroidBLEService.UUIDS.EVEN_WRITE, cmd);
        } else if ("HEARTBEAT".equals(command)) {
            return sendHeartbeat();
        }
        return false;
    }

    @Override
    public List<String> getAvailableCommands() { return capabilities.getSupportedCommands(); }

    @Override
    public void setDataListener(DataListener listener) {
        this.dataListener = listener;
        bleService.enableNotifications(address, AndroidBLEService.UUIDS.EVEN_SERVICE, AndroidBLEService.UUIDS.EVEN_READ);
    }

    @Override
    public void enableDataStreaming(boolean enabled) {
        if (enabled) {
            bleService.enableNotifications(address, AndroidBLEService.UUIDS.EVEN_SERVICE, AndroidBLEService.UUIDS.EVEN_READ);
        }
    }

    private boolean sendHeartbeat() {
        int seq = sequenceNumber.getAndIncrement() & 0xFF;
        byte[] heartbeat = new byte[]{0x25, 0x06, 0x00, (byte) seq, 0x04, (byte) seq};
        return bleService.write(address, AndroidBLEService.UUIDS.EVEN_SERVICE, AndroidBLEService.UUIDS.EVEN_WRITE, heartbeat);
    }

    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatTimer = new Timer();
        heartbeatTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (connected) sendHeartbeat();
            }
        }, 0, 8000);
    }

    private void stopHeartbeat() {
        if (heartbeatTimer != null) {
            heartbeatTimer.cancel();
            heartbeatTimer = null;
        }
    }

    public void handleRawData(byte[] data) {
        if (data.length < 2) return;
        // Check for specific Even events (e.g. F5 23 for Start AI Recording)
        if ((data[0] & 0xFF) == 0xF5 && dataListener != null) {
            dataListener.onDataReceived(new SensorData("Even Glasses", address, "Event", System.currentTimeMillis()));
        }
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
        if (connected) {
            startHeartbeat();
            if (connectionListener != null) connectionListener.onConnected(address);
        } else {
            stopHeartbeat();
            if (connectionListener != null) connectionListener.onDisconnected(address);
        }
    }
}
