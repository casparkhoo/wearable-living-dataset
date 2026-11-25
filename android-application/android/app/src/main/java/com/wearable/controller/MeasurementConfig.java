package com.wearable.controller;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration for sensor measurements
 */
public class MeasurementConfig {
    
    private final String sensorType;
    private final int sampleRate; // Hz
    private final int duration; // seconds, 0 for continuous
    private final Map<String, Object> parameters;
    
    public MeasurementConfig(String sensorType, int sampleRate, int duration) {
        this.sensorType = sensorType;
        this.sampleRate = sampleRate;
        this.duration = duration;
        this.parameters = new HashMap<>();
    }
    
    // Getters
    public String getSensorType() { return sensorType; }
    public int getSampleRate() { return sampleRate; }
    public int getDuration() { return duration; }
    public Map<String, Object> getParameters() { return new HashMap<>(parameters); }
    
    // Parameter setters
    public void setParameter(String key, Object value) {
        parameters.put(key, value);
    }
    
    // Convenience methods for common parameters
    public void setLEDCurrents(int green, int ir, int red) {
        parameters.put("led_green", green);
        parameters.put("led_ir", ir);
        parameters.put("led_red", red);
    }
    
    public void setProgressEnabled(boolean enabled) {
        parameters.put("progress_enabled", enabled);
    }
    
    public void setWaveformEnabled(boolean enabled) {
        parameters.put("waveform_enabled", enabled);
    }
    
    public void setStorageOptions(int options) {
        parameters.put("storage_options", options);
    }
    
    // Builder pattern
    public static class Builder {
        private String sensorType;
        private int sampleRate = 25; // Default 25Hz
        private int duration = 0; // Default continuous
        private Map<String, Object> parameters = new HashMap<>();
        
        public Builder(String sensorType) {
            this.sensorType = sensorType;
        }
        
        public Builder sampleRate(int rate) {
            this.sampleRate = rate;
            return this;
        }
        
        public Builder duration(int seconds) {
            this.duration = seconds;
            return this;
        }
        
        public Builder parameter(String key, Object value) {
            parameters.put(key, value);
            return this;
        }
        
        public Builder ledCurrents(int green, int ir, int red) {
            parameters.put("led_green", green);
            parameters.put("led_ir", ir);
            parameters.put("led_red", red);
            return this;
        }
        
        public Builder enableProgress(boolean enabled) {
            parameters.put("progress_enabled", enabled);
            return this;
        }
        
        public Builder enableWaveform(boolean enabled) {
            parameters.put("waveform_enabled", enabled);
            return this;
        }
        
        public MeasurementConfig build() {
            MeasurementConfig config = new MeasurementConfig(sensorType, sampleRate, duration);
            config.parameters.putAll(this.parameters);
            return config;
        }
    }
}
