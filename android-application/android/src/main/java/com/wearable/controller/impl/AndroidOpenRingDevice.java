package com.wearable.controller.impl;

import com.wearable.controller.*;
import com.wearable.controller.bluetooth.AndroidBLEService;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class AndroidOpenRingDevice implements WearableDevice {
    private final String address;
    private final AndroidBLEService bleService;
    private final DeviceCapabilities capabilities;
    private ConnectionListener connectionListener;
    private DataListener dataListener;
    private boolean connected = false;
    private final AtomicInteger frameIdCounter = new AtomicInteger(0);

    public AndroidOpenRingDevice(String address, AndroidBLEService bleService) {
        this.address = address;
        this.bleService = bleService;
        this.capabilities = new DeviceCapabilities.Builder("OpenRing")
            .addSensor("PPG").addSensor("ACCEL").addSensor("TEMP")
            .addCommand("GET_VERSION").addCommand("SYNC_TIME")
            .supportsRealTimeData(true)
            .build();
    }

    @Override
    public String getDeviceName() { return "OpenRing"; }

    @Override
    public String getDeviceType() { return "OpenRing"; }

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
        return new DeviceInfo("OpenRing", address);
    }

    @Override
    public boolean startMeasurement(MeasurementConfig config) {
        // [Frame Type:0][Frame ID][Cmd:3C][Subcmd:00][Data...]
        byte fid = (byte) (frameIdCounter.getAndIncrement() & 0xFF);
        byte[] cmd = new byte[]{0x00, fid, 0x3C, 0x00, 0x1E, 0x19, 0x14, 0x14, 0x14, 0x01, 0x01};
        return bleService.write(address, AndroidBLEService.UUIDS.OPENRING_SERVICE, AndroidBLEService.UUIDS.OPENRING_TX, cmd);
    }

    @Override
    public boolean stopMeasurement() {
        byte fid = (byte) (frameIdCounter.getAndIncrement() & 0xFF);
        byte[] cmd = new byte[]{0x00, fid, 0x3C, 0x04};
        return bleService.write(address, AndroidBLEService.UUIDS.OPENRING_SERVICE, AndroidBLEService.UUIDS.OPENRING_TX, cmd);
    }

    @Override
    public List<SensorData> getLatestData() { return new ArrayList<>(); }

    @Override
    public boolean sendCommand(String command, Map<String, Object> parameters) {
        byte fid = (byte) (frameIdCounter.getAndIncrement() & 0xFF);
        if ("GET_VERSION".equals(command)) {
            return bleService.write(address, AndroidBLEService.UUIDS.OPENRING_SERVICE, AndroidBLEService.UUIDS.OPENRING_TX, new byte[]{0x00, fid, 0x12, 0x00});
        }
        return false;
    }

    @Override
    public List<String> getAvailableCommands() { return capabilities.getSupportedCommands(); }

    @Override
    public void setDataListener(DataListener listener) {
        this.dataListener = listener;
        bleService.enableNotifications(address, AndroidBLEService.UUIDS.OPENRING_SERVICE, AndroidBLEService.UUIDS.OPENRING_RX);
    }

    @Override
    public void enableDataStreaming(boolean enabled) {
        if (enabled) {
            bleService.enableNotifications(address, AndroidBLEService.UUIDS.OPENRING_SERVICE, AndroidBLEService.UUIDS.OPENRING_RX);
        }
    }

    public void handleRawData(byte[] data) {
        if (data.length < 4) return;
        int cmd = data[2] & 0xFF;
        if (cmd == 0x3C && dataListener != null) {
            dataListener.onDataReceived(new SensorData("OpenRing", address, "Health", System.currentTimeMillis()));
        }
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
        if (connected && connectionListener != null) connectionListener.onConnected(address);
        else if (!connected && connectionListener != null) connectionListener.onDisconnected(address);
    }
}
