package com.wearable.controller;

import java.util.HashMap;
import java.util.Map;

/**
 * Device information structure
 */
public class DeviceInfo {
    
    private final String deviceType;
    private final String deviceAddress;
    private final String firmwareVersion;
    private final String hardwareVersion;
    private final String serialNumber;
    private final Map<String, String> additionalInfo;
    
    public DeviceInfo(String deviceType, String deviceAddress) {
        this.deviceType = deviceType;
        this.deviceAddress = deviceAddress;
        this.firmwareVersion = "Unknown";
        this.hardwareVersion = "Unknown";
        this.serialNumber = "Unknown";
        this.additionalInfo = new HashMap<>();
    }
    
    public DeviceInfo(String deviceType, String deviceAddress, String firmwareVersion, 
                     String hardwareVersion, String serialNumber) {
        this.deviceType = deviceType;
        this.deviceAddress = deviceAddress;
        this.firmwareVersion = firmwareVersion;
        this.hardwareVersion = hardwareVersion;
        this.serialNumber = serialNumber;
        this.additionalInfo = new HashMap<>();
    }
    
    // Getters
    public String getDeviceType() { return deviceType; }
    public String getDeviceAddress() { return deviceAddress; }
    public String getFirmwareVersion() { return firmwareVersion; }
    public String getHardwareVersion() { return hardwareVersion; }
    public String getSerialNumber() { return serialNumber; }
    public Map<String, String> getAdditionalInfo() { return new HashMap<>(additionalInfo); }
    
    // Additional info setters
    public void setAdditionalInfo(String key, String value) {
        additionalInfo.put(key, value);
    }
    
    @Override
    public String toString() {
        return String.format("DeviceInfo{type=%s, address=%s, fw=%s, hw=%s, serial=%s}", 
                deviceType, deviceAddress, firmwareVersion, hardwareVersion, serialNumber);
    }
}
