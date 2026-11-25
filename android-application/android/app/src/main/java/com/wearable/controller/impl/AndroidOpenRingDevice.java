package com.wearable.controller.impl;

import com.wearable.controller.DeviceProfile;

import com.wearable.controller.*;
import com.wearable.controller.bluetooth.AndroidBLEService;
import com.wearable.controller.bluetooth.BleAdvertisement;
import com.wearable.controller.bluetooth.BleDeviceAdapter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class AndroidOpenRingDevice implements WearableDevice, BleDeviceAdapter {
    public static final String TYPE = "OpenRing";

    /** How the app names and treats this wearable; see DeviceProfile. */
    public static final DeviceProfile PROFILE = new DeviceProfile.Builder(TYPE)
            .displayName("τ-Ring")
            .firmwareName("OpenRing")
            .fixedTitle(true)
            .shortName("τ-Ring")
            .pickerLabel("τ-Ring  ·  smart ring")
            .wearPosition("finger")
            .iconKey("ring")
            // Accelerometer samples ride on the PPG sample.
            .stream("PPG", DeviceProfile.StreamRole.PULSE, DeviceProfile.StreamRole.MOVEMENT)
            .dashboardPriority(0.5)
            .dashboardDefault(true)
            .dashboardNoun("ring")
            // The ring may have restarted while away; give it the time again.
            .resumeCommand("UPDATE_TIME")
            .dashboardTag(name -> "RING")
            .build();
    public static final String SERVICE_UUID = "BAE80001-4F05-4503-8E65-3AF1F7329D1F";
    public static final String TX_UUID = "BAE80010-4F05-4503-8E65-3AF1F7329D1F";
    public static final String RX_UUID = "BAE80011-4F05-4503-8E65-3AF1F7329D1F";
    private static final int TAU_RING_MANUFACTURER_ID = 65281;
    private static final boolean DEBUG_WAVEFORM = false;
    private final String address;
    private final AndroidBLEService bleService;
    private final DeviceCapabilities capabilities;
    private ConnectionListener connectionListener;
    private DataListener dataListener;
    private boolean connected = false;
    private final AtomicInteger frameIdCounter = new AtomicInteger(0);
    private final AtomicLong waveformSampleIndex = new AtomicLong(0);
    private volatile int measurementSampleRateHz = 25;
    private volatile long frameTimestampMs = -1;
    private final AtomicLong notificationCount = new AtomicLong(0);
    private volatile int frameDataCount = 0;
    private MQTTManager mqttManager;
    private boolean mqttEnabled = true;

    public AndroidOpenRingDevice(String address, AndroidBLEService bleService) {
        this.address = address;
        this.bleService = bleService;
        this.capabilities = new DeviceCapabilities.Builder(TYPE)
            .addSensor("PPG").addSensor("ACCEL").addSensor("GYRO").addSensor("TEMP")
            .addCommand("GET_BATTERY")
            .addCommand("UPDATE_TIME")
            .addCommand("START_MEASUREMENT")
            .addCommand("STOP_MEASUREMENT")
            .addCommand("START_EXERCISE")
            .addCommand("STOP_EXERCISE")
            .addCommand("GET_FILE_LIST")
            .addCommand("FORMAT_FILESYSTEM")
            .supportsRealTimeData(true)
            .build();
    }

    @Override
    public String getDeviceName() { return TYPE; }

    @Override
    public String getDeviceType() { return TYPE; }

    public static boolean matchesAdvertisement(BleAdvertisement advertisement) {
        String name = advertisement.getNormalizedName();
        return name.contains("openring")
                || name.contains("open ring")
                || name.contains("ring")
                || name.matches("bcl\\d{6}[a-f0-9]")
                || advertisement.hasManufacturer(TAU_RING_MANUFACTURER_ID)
                || advertisement.hasService(SERVICE_UUID);
    }

    @Override
    public void onBleServicesDiscovered() {
        if (!bleService.requestMtu(address, 512)) {
            subscribeToSensorData();
        }
    }

    @Override
    public void onBleMtuChanged(int mtu, int status) {
        subscribeToSensorData();
    }

    private void subscribeToSensorData() {
        bleService.enableNotifications(address, SERVICE_UUID, RX_UUID);
    }

    @Override
    public void onBleNotification(String serviceUuid, String characteristicUuid, byte[] data) {
        if (SERVICE_UUID.equalsIgnoreCase(serviceUuid) && RX_UUID.equalsIgnoreCase(characteristicUuid)) {
            handleRawData(data);
        }
    }

    @Override
    public String getDeviceAddress() { return address; }

    @Override
    public DeviceCapabilities getCapabilities() { return capabilities; }

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
    public boolean isConnected() { return connected; }

    @Override
    public void setConnectionListener(ConnectionListener listener) {
        this.connectionListener = listener;
    }

    @Override
    public DeviceInfo getDeviceInfo() {
        return new DeviceInfo(TYPE, address);
    }

    @Override
    public boolean startMeasurement(MeasurementConfig config) {
        int requestedRate = config != null ? config.getSampleRate() : 25;
        measurementSampleRateHz = requestedRate > 0 ? requestedRate : 25;
        byte fid = (byte) (frameIdCounter.getAndIncrement() & 0xFF);
        // Build proper measurement command with waveform response enabled
        // Format: [FrameType:1][FrameID:1][Cmd:1][Subcmd:1][Time:1][Freq:1][Green:1][IR:1][Red:1][Progress:1][Waveform:1]
        byte[] cmd = new byte[]{
            0x00, fid, 0x3C, 0x00,  // Frame header
            0x1E,                   // Collection time: 30 seconds
            (byte) measurementSampleRateHz,
            0x14,                   // Green LED current: 20 (7.84mA)
            0x14,                   // IR LED current: 20 (7.84mA)  
            0x14,                   // Red LED current: 20 (7.84mA)
            0x01,                   // Progress response: enabled
            0x01                    // Waveform response: enabled (THIS IS KEY!)
        };
        return bleService.write(address, SERVICE_UUID, TX_UUID, cmd);
    }

    @Override
    public boolean stopMeasurement() {
        byte fid = (byte) (frameIdCounter.getAndIncrement() & 0xFF);
        byte[] cmd = new byte[]{0x00, fid, 0x3C, 0x04};
        return bleService.write(address, SERVICE_UUID, TX_UUID, cmd);
    }

    @Override
    public List<SensorData> getLatestData() { return new ArrayList<>(); }

    @Override
    public boolean sendCommand(String command, Map<String, Object> parameters) {
        byte fid = (byte) (frameIdCounter.getAndIncrement() & 0xFF);
        switch (command) {
            case "GET_BATTERY":
                // Command 0x12/0x00 is the battery query: the reference τ-Ring
                // app (thuhci/OpenRing) sends it on connect and reads the
                // percentage from byte 4 of the reply. It was previously
                // labelled GET_VERSION here.
                return bleService.write(address, SERVICE_UUID, TX_UUID,
                    new byte[]{0x00, fid, 0x12, 0x00});
            case "UPDATE_TIME":
                // 8-byte timestamp + timezone
                long ts = System.currentTimeMillis();
                byte[] timeData = new byte[13];
                timeData[0] = 0x00; timeData[1] = fid; timeData[2] = 0x10; timeData[3] = 0x00;
                for (int i=0; i<8; i++) timeData[4+i] = (byte)((ts >> (i*8)) & 0xFF);
                timeData[12] = 0x00; // Timezone UTC
                return bleService.write(address, SERVICE_UUID, TX_UUID, timeData);
            case "START_MEASUREMENT":
                return startMeasurement(null);
            case "STOP_MEASUREMENT":
                return stopMeasurement();
            case "START_EXERCISE":
                return bleService.write(address, SERVICE_UUID, TX_UUID,
                    new byte[]{0x00, fid, 0x38, 0x01, 0x01, 0x00, 0x3C, 0x00, 0x00, 0x00, 0x3C, 0x00, 0x00, 0x00});
            case "STOP_EXERCISE":
                return bleService.write(address, SERVICE_UUID, TX_UUID,
                    new byte[]{0x00, fid, 0x38, 0x03});
            case "GET_FILE_LIST":
                return bleService.write(address, SERVICE_UUID, TX_UUID,
                    new byte[]{0x00, fid, 0x36, 0x10});
            case "FORMAT_FILESYSTEM":
                return bleService.write(address, SERVICE_UUID, TX_UUID,
                    new byte[]{0x00, fid, 0x36, 0x14});
        }
        return false;
    }

    @Override
    public List<String> getAvailableCommands() { return capabilities.getSupportedCommands(); }

    @Override
    public List<String> getRecordingStartCommands() {
        // The ring may have restarted since it was last used; give it the time first.
        return Arrays.asList("UPDATE_TIME", "START_MEASUREMENT");
    }

    @Override
    public List<String> getRecordingStopCommands() {
        return Collections.singletonList("STOP_MEASUREMENT");
    }

    @Override
    public void setDataListener(DataListener listener) {
        this.dataListener = listener;
    }

    @Override
    public void enableDataStreaming(boolean enabled) {
        if (enabled) {
            bleService.enableNotifications(address, SERVICE_UUID, RX_UUID);
        }
    }

    public void handleRawData(byte[] data) {
        if (data.length < 4) return;
        // One line for the first notification and then one a minute's worth
        // (1,500 at 25 Hz), so a ring that connects but never streams can be
        // told apart from one that streams and is not stored.
        long seen = notificationCount.incrementAndGet();
        if (seen == 1 || seen % 1500 == 0) {
            android.util.Log.i("OpenRing", "notification #" + seen + " cmd=0x"
                    + String.format("%02X", data[2] & 0xFF) + " sub=0x" + String.format("%02X", data[3] & 0xFF)
                    + " len=" + data.length);
        }
        int cmd = data[2] & 0xFF;
        int subcmd = data[3] & 0xFF;
        
        if (cmd == 0x12 && subcmd == 0x00) {
            // Bytes after the percentage are undocumented; recorded so a
            // charging flag, if the ring sends one, can be found by comparing
            // replies on the finger and in the case.
            StringBuilder hex = new StringBuilder();
            for (byte b : data) hex.append(String.format("%02X ", b));
            android.util.Log.i("OpenRing", "battery reply: " + hex.toString().trim());
        }
        if (cmd == 0x12 && subcmd == 0x00 && data.length >= 5 && dataListener != null) {
            int percent = data[4] & 0xFF;
            if (percent <= 100) dataListener.onCommandResponse("BATTERY", true, String.valueOf(percent));
            return;
        }
        if (cmd == 0x3C && dataListener != null) {
            // Handle different types of real-time data
            if ((subcmd == 0x01 || subcmd == 0x02) && data.length >= 14) {
                // BOTH subcmd 0x01 and 0x02 can have full 30-byte data packets
                debug("OpenRing: Parsing waveform packet length=" + data.length);
                parseAndSendWaveformData(data);
            } else if (subcmd == 0x01 && data.length >= 19) {
                // Fallback to old parser for shorter packets
                System.out.println("OpenRing: Using fallback parser for subcmd 0x01");
                parseAndSendSensorData(data);
            } else {
                // Generic health data for other subcommands
                System.out.println("OpenRing: Generic health data for subcmd 0x" + String.format("%02X", subcmd));
                dataListener.onDataReceived(new SensorData("OpenRing", address, "Health", System.currentTimeMillis()));
            }
        }
    }
    
    private void parseAndSendSensorData(byte[] data) {
        try {
            // Use current timestamp since device timestamp may be unreliable
            long timestamp = System.currentTimeMillis();
            
            // Parse PPG data (bytes 8-13, little-endian 16-bit values)
            int ppgGreen = ((data[9] & 0xFF) << 8) | (data[8] & 0xFF);
            int ppgIR = ((data[11] & 0xFF) << 8) | (data[10] & 0xFF);
            int ppgRed = ((data[13] & 0xFF) << 8) | (data[12] & 0xFF);
            
            // Parse accelerometer data (bytes 14-19, little-endian 16-bit signed values)
            int accelX = (short) (((data[15] & 0xFF) << 8) | (data[14] & 0xFF));
            int accelY = (short) (((data[17] & 0xFF) << 8) | (data[16] & 0xFF));
            int accelZ = (short) (((data[19] & 0xFF) << 8) | (data[18] & 0xFF));
            
            // Parse gyroscope data (bytes 20-25, little-endian 16-bit signed values)
            int gyroX = 0, gyroY = 0, gyroZ = 0;
            if (data.length >= 25) {
                gyroX = (short) (((data[21] & 0xFF) << 8) | (data[20] & 0xFF));
                gyroY = (short) (((data[23] & 0xFF) << 8) | (data[22] & 0xFF));
                gyroZ = (short) (((data[25] & 0xFF) << 8) | (data[24] & 0xFF));
            }
            
            // Create sensor data with all parsed values
            SensorData sensorData = new SensorData("OpenRing", address, "PPG", timestamp);
            sensorData.setData("ppg_green", (float) ppgGreen);
            sensorData.setData("ppg_ir", (float) ppgIR);
            sensorData.setData("ppg_red", (float) ppgRed);
            sensorData.setData("accel_x", (float) accelX);
            sensorData.setData("accel_y", (float) accelY);
            sensorData.setData("accel_z", (float) accelZ);
            sensorData.setData("gyro_x", (float) gyroX);
            sensorData.setData("gyro_y", (float) gyroY);
            sensorData.setData("gyro_z", (float) gyroZ);

            dataListener.onDataReceived(sensorData);
            publishToMQTT(sensorData);

        } catch (Exception e) {
            // Fallback to basic health data if parsing fails
            dataListener.onDataReceived(new SensorData("OpenRing", address, "Health", System.currentTimeMillis()));
        }
    }
    
    private void parseAndSendWaveformData(byte[] data) {
        try {
            if (data.length < 6) {
                System.out.println("OpenRing: Invalid waveform packet length - using fallback");
                parseSimpleWaveform(data);
                return;
            }
            
            // Parse standard waveform response (subcmd 0x02) like the reference implementation
            int seq = data[4] & 0xFF;
            int dataNum = data[5] & 0xFF;
            // Bytes 6-13 of the frame header are the ring's own unix time in
            // milliseconds (the reference app reads them the same way). Keeping
            // it, with the frame sequence, lets a later analysis tell samples
            // the ring never sent from samples lost between the ring and phone.
            frameTimestampMs = data.length >= 14 ? readUInt64LE(data, 6) : -1;
            frameDataCount = dataNum;
            
            debug("OpenRing: Waveform seq=" + seq + ", dataNum=" + dataNum);
            
            // Safety check: if dataNum is unreasonable, use fallback
            if (dataNum <= 0 || dataNum > 10) {
                System.out.println("OpenRing: Invalid dataNum=" + dataNum + " - using fallback");
                parseSimpleWaveform(data);
                return;
            }
            
            int expectedLength = 4 + 10 + dataNum * 30;
            debug("OpenRing: Expected length=" + expectedLength + ", actual=" + data.length);
            
            // Handle incomplete packets - extract whatever data is available
            if (data.length < expectedLength) {
                System.out.println("OpenRing: Incomplete waveform. Expected: " + expectedLength + ", Got: " + data.length);
                
                // Try to extract partial data if packet is large enough for at least one data point
                if (data.length >= 44) { // Minimum for one 30-byte data point (14 header + 30 data)
                    int availableDataPoints = (data.length - 14) / 30;
                    System.out.println("OpenRing: Extracting " + availableDataPoints + " partial data points");
                    
                    int validPoints = 0;
                    for (int i = 0; i < availableDataPoints && i < dataNum; i++) {
                        int offset = 4 + 10 + i * 30;
                        if (offset + 30 <= data.length) {
                            if (parseAndSendDataPoint(data, offset, seq, i)) {
                                validPoints++;
                            }
                        }
                    }
                    System.out.println("OpenRing: Processed " + validPoints + " partial data points");
                    return;
                } else {
                    System.out.println("OpenRing: Packet too short for full data - using fallback");
                    parseSimpleWaveform(data);
                    return;
                }
            }
            
            // Process each 30-byte data point
            int validPoints = 0;
            for (int i = 0; i < dataNum; i++) {
                int offset = 4 + 10 + i * 30; // 4-byte frame header + 10-byte data header + i * 30
                debug("OpenRing: Processing data point " + i + " at offset " + offset);
                if (offset + 30 <= data.length) {
                if (parseAndSendDataPoint(data, offset, seq, i)) {
                        validPoints++;
                    }
                } else {
                    System.out.println("OpenRing: Incomplete data point " + i + " at offset " + offset);
                    break;
                }
            }
            
            debug("OpenRing: Processed " + validPoints + " waveform data points");
            
        } catch (Exception e) {
            System.out.println("OpenRing parsing error: " + e.getMessage() + " - using fallback");
            parseSimpleWaveform(data);
        }
    }
    
    private void parseSimpleWaveform(byte[] data) {
        try {
            // Fallback to simple value parsing for shorter packets or as backup
            System.out.println("OpenRing: Using simple fallback parsing for " + data.length + " byte packet");
            
            long timestamp = System.currentTimeMillis();
            SensorData sensorData = new SensorData("OpenRing", address, "PPG", timestamp);
            
            // Extract ALL available 16-bit values from waveform data (starting from byte 8)
            // OpenRing can send up to 12 values: 3 PPG (4-byte each = 12 bytes) + 3 Accel (2-byte each = 6 bytes) 
            // + 3 Gyro (2-byte each = 6 bytes) + 3 Temp (2-byte each = 6 bytes) = 30 bytes total
            int maxValues = 15; // Enough for all sensor data
            float[] values = new float[maxValues];
            int valueCount = 0;
            
            // First extract 4-byte PPG values (3 values = 12 bytes)
            for (int i = 8; i + 3 < data.length && valueCount < 3; i += 4) {
                long val = ((data[i + 3] & 0xFFL) << 24) | ((data[i + 2] & 0xFFL) << 16) | 
                          ((data[i + 1] & 0xFFL) << 8) | (data[i] & 0xFFL);
                values[valueCount] = (float) val;
                valueCount++;
            }
            
            // Then extract 2-byte values for Accel, Gyro, Temp (remaining values)
            int startOffset = 8 + (3 * 4); // After PPG data
            for (int i = startOffset; i + 1 < data.length && valueCount < maxValues; i += 2) {
                short val = (short) (((data[i + 1] & 0xFF) << 8) | (data[i] & 0xFF));
                values[valueCount] = (float) val;
                valueCount++;
            }
            
            System.out.println("OpenRing: Extracted " + valueCount + " values from fallback parser");
            
            // Map values to sensor data (following OpenRing order: PPG, Accel, Gyro, Temp)
            if (valueCount >= 3) {
                sensorData.setData("ppg_green", values[0]);
                sensorData.setData("ppg_red", values[1]);
                sensorData.setData("ppg_ir", values[2]);
            }
            
            if (valueCount >= 6) {
                sensorData.setData("accel_x", values[3]);
                sensorData.setData("accel_y", values[4]);
                sensorData.setData("accel_z", values[5]);
            }
            
            if (valueCount >= 9) {
                sensorData.setData("gyro_x", values[6]);
                sensorData.setData("gyro_y", values[7]);
                sensorData.setData("gyro_z", values[8]);
            }
            
            if (valueCount >= 12) {
                sensorData.setData("temperature", values[9]); // Use first temp sensor
            }
            
            System.out.println("OpenRing: Fallback data - PPG:" + values[0] + "," + values[1] + "," + values[2] + 
                " Accel:" + values[3] + "," + values[4] + "," + values[5] + 
                " Gyro:" + values[6] + "," + values[7] + "," + values[8] +
                " Temp:" + values[9]);

            dataListener.onDataReceived(sensorData);
            publishToMQTT(sensorData);

        } catch (Exception e) {
            System.out.println("OpenRing fallback parsing error: " + e.getMessage());
            // Last resort - send basic health data
            dataListener.onDataReceived(new SensorData("OpenRing", address, "Health", System.currentTimeMillis()));
        }
    }
    
    private boolean parseAndSendDataPoint(byte[] data, int offset, int frameSequence, int pointIndex) {
        try {
            // PPG data (first 12 bytes, 4 bytes each) - EXACT COPY from reference
            long green = readUInt32LE(data, offset);
            long red = readUInt32LE(data, offset + 4);
            long ir = readUInt32LE(data, offset + 8);

            // Accelerometer data (12-17 bytes, 2 bytes signed each) - EXACT COPY from reference
            short accX = readInt16LE(data, offset + 12);
            short accY = readInt16LE(data, offset + 14);
            short accZ = readInt16LE(data, offset + 16);

            // Gyroscope data (18-23 bytes, 2 bytes signed each) - EXACT COPY from reference
            short gyroX = readInt16LE(data, offset + 18);
            short gyroY = readInt16LE(data, offset + 20);
            short gyroZ = readInt16LE(data, offset + 22);

            // Temperature data (24-29 bytes, 2 bytes signed each) - EXACT COPY from reference
            short temp0 = readInt16LE(data, offset + 24);
            short temp1 = readInt16LE(data, offset + 26);
            short temp2 = readInt16LE(data, offset + 28);
            
            // Create sensor data with all values - EXACT COPY from reference approach
            SensorData sensorData = newOpenRingSample(System.currentTimeMillis(), frameSequence, pointIndex);
            
            sensorData.setData("ppg_green", (float) green);
            sensorData.setData("ppg_red", (float) red);
            sensorData.setData("ppg_ir", (float) ir);
            sensorData.setData("accel_x", (float) accX);
            sensorData.setData("accel_y", (float) accY);
            sensorData.setData("accel_z", (float) accZ);
            sensorData.setData("gyro_x", (float) gyroX);
            sensorData.setData("gyro_y", (float) gyroY);
            sensorData.setData("gyro_z", (float) gyroZ);
            sensorData.setData("temperature", (float) temp0); // Use temp0 as main temperature
            
            dataListener.onDataReceived(sensorData);
            publishToMQTT(sensorData);
            return true;
            
        } catch (Exception e) {
            System.out.println("OpenRing data point parsing error: " + e.getMessage());
            return false;
        }
    }

    private SensorData newOpenRingSample(long androidReceivedTimestampMs, int frameSequence, int pointIndex) {
        SensorData sensorData = new SensorData("OpenRing", address, "PPG", androidReceivedTimestampMs);
        sensorData.setAcquisitionMetadata("androidReceivedTimestampMs", androidReceivedTimestampMs);
        sensorData.setAcquisitionMetadata("configuredSampleRateHz", measurementSampleRateHz);
        // The meaning of data[4] is not validated and captures currently report it as
        // zero throughout. Preserve it as a candidate header field, not as a sequence
        // number that could falsely imply packet-loss detection.
        sensorData.setAcquisitionMetadata("frameSequenceCandidate", frameSequence);
        if (frameTimestampMs > 0) {
            // Candidate only: not yet checked against the ring's clock behaviour.
            sensorData.setAcquisitionMetadata("sourceTimestamp", frameTimestampMs);
            sensorData.setAcquisitionMetadata("sourceTimestampUnit", "unix_ms_unverif");
            sensorData.setAcquisitionMetadata("frameDataCount", frameDataCount);
        }
        sensorData.setAcquisitionMetadata("framePointIndex", pointIndex);
        sensorData.setAcquisitionMetadata("sampleIndex", waveformSampleIndex.getAndIncrement());
        // OpenRing waveform points do not currently expose a verified device clock.
        // Callback time is arrival evidence only; fixed-rate reconstruction remains a
        // query-time policy until the ring's timing semantics have been validated.
        sensorData.setAcquisitionMetadata("clockSource", "arrival_only");
        sensorData.setAcquisitionMetadata("clockQuality", "unvalidated");
        return sensorData;
    }

    private static void debug(String message) {
        if (DEBUG_WAVEFORM) {
            System.out.println(message);
        }
    }
    
    // EXACT COPY from reference implementation
    private static long readUInt64LE(byte[] data, int offset) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (data[offset + i] & 0xFFL);
        return v;
    }

    private static long readUInt32LE(byte[] data, int offset) {
        if (offset + 4 > data.length) {
            throw new IndexOutOfBoundsException("Not enough data to read uint32");
        }
        return ((long)(data[offset] & 0xFF)) |
                ((long)(data[offset + 1] & 0xFF) << 8) |
                ((long)(data[offset + 2] & 0xFF) << 16) |
                ((long)(data[offset + 3] & 0xFF) << 24);
    }
    
    // EXACT COPY from reference implementation
    private static short readInt16LE(byte[] data, int offset) {
        if (offset + 2 > data.length) {
            throw new IndexOutOfBoundsException("Not enough data to read int16");
        }
        return (short)(((data[offset + 1] & 0xFF) << 8) | (data[offset] & 0xFF));
    }

    @Override
    public void setConnected(boolean connected) {
        this.connected = connected;
        if (connected && connectionListener != null) connectionListener.onConnected(address);
        else if (!connected && connectionListener != null) connectionListener.onDisconnected(address);
    }

    public void setMQTTManager(MQTTManager mqttManager) {
        this.mqttManager = mqttManager;
    }

    public void setMQTTEnabled(boolean enabled) {
        this.mqttEnabled = enabled;
    }

    public boolean isMQTTEnabled() {
        return mqttEnabled;
    }

    private void publishToMQTT(SensorData sensorData) {
        if (mqttEnabled && mqttManager != null && mqttManager.isConnected()) {
            // Batched like the earable (MQTTManager): 25 samples a second sent one
            // message each cost a broker round trip and a database transaction apiece.
            mqttManager.publishSensorDataBatched(sensorData, 2);
        }
    }
}
