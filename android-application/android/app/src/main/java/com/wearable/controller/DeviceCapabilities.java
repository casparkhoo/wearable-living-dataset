package com.wearable.controller;

import java.util.ArrayList;
import java.util.List;

public class DeviceCapabilities {
    private final String deviceName;
    private final List<String> sensors;
    private final List<String> supportedCommands;
    private final boolean realTimeData;
    private final boolean audioControl;
    private final boolean fileOperations;
    private final int maxDataRate;

    private DeviceCapabilities(Builder builder) {
        this.deviceName = builder.deviceName;
        this.sensors = builder.sensors;
        this.supportedCommands = builder.supportedCommands;
        this.realTimeData = builder.realTimeData;
        this.audioControl = builder.audioControl;
        this.fileOperations = builder.fileOperations;
        this.maxDataRate = builder.maxDataRate;
    }

    public String getDeviceName() { return deviceName; }
    public List<String> getSensors() { return sensors; }
    public List<String> getSupportedCommands() { return supportedCommands; }
    public boolean supportsRealTimeData() { return realTimeData; }
    public boolean supportsAudioControl() { return audioControl; }
    public boolean supportsFileOperations() { return fileOperations; }
    public int getMaxDataRate() { return maxDataRate; }

    public static class Builder {
        private String deviceName;
        private List<String> sensors = new ArrayList<>();
        private List<String> supportedCommands = new ArrayList<>();
        private boolean realTimeData = false;
        private boolean audioControl = false;
        private boolean fileOperations = false;
        private int maxDataRate = 0;

        public Builder(String deviceName) { this.deviceName = deviceName; }
        public Builder addSensor(String sensor) { sensors.add(sensor); return this; }
        public Builder addCommand(String command) { supportedCommands.add(command); return this; }
        public Builder supportsRealTimeData(boolean supports) { this.realTimeData = supports; return this; }
        public Builder supportsAudioControl(boolean supports) { this.audioControl = supports; return this; }
        public Builder supportsFileOperations(boolean supports) { this.fileOperations = supports; return this; }
        public Builder maxDataRate(int rate) { this.maxDataRate = rate; return this; }
        public DeviceCapabilities build() { return new DeviceCapabilities(this); }
    }
}
