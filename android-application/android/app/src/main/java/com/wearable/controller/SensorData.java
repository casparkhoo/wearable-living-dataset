package com.wearable.controller;

import java.util.HashMap;
import java.util.Map;

public class SensorData {
    private final String deviceType;
    private final String deviceAddress;
    private final long timestamp;
    private final Map<String, Object> data;
    private final Map<String, Object> acquisitionMetadata;
    private final String sensorType;

    public SensorData(String deviceType, String deviceAddress, String sensorType, long timestamp) {
        this.deviceType = deviceType;
        this.deviceAddress = deviceAddress;
        this.sensorType = sensorType;
        this.timestamp = timestamp;
        this.data = new HashMap<>();
        this.acquisitionMetadata = new HashMap<>();
    }

    public String getDeviceType() { return deviceType; }
    public String getDeviceAddress() { return deviceAddress; }
    public long getTimestamp() { return timestamp; }
    public String getSensorType() { return sensorType; }
    public Map<String, Object> getData() { return data; }
    public Map<String, Object> getAcquisitionMetadata() { return new HashMap<>(acquisitionMetadata); }
    
    public void setData(String key, Object value) { data.put(key, value); }
    public void setAcquisitionMetadata(String key, Object value) { acquisitionMetadata.put(key, value); }
}
