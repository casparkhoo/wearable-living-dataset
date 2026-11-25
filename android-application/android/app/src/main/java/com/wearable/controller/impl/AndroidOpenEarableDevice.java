package com.wearable.controller.impl;

import com.wearable.controller.DeviceProfile;

import android.os.Handler;
import android.os.Looper;

import com.wearable.controller.*;
import com.wearable.controller.bluetooth.AndroidBLEService;
import com.wearable.controller.bluetooth.BleAdvertisement;
import com.wearable.controller.bluetooth.BleDeviceAdapter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class AndroidOpenEarableDevice implements WearableDevice, BleDeviceAdapter {
    public static final String TYPE = "OpenEarable";

    /** How the app names and treats this wearable; see DeviceProfile. */
    public static final DeviceProfile PROFILE = new DeviceProfile.Builder(TYPE)
            .displayName("OpenEarable")
            .shortName("Earable")
            .pickerLabel("OpenEarable  ·  earphones")
            .wearPosition("ear")
            .iconKey("earbud")
            .stream("PPG", DeviceProfile.StreamRole.PULSE)
            .stream("IMU", DeviceProfile.StreamRole.MOVEMENT)
            .stream("MICRO", DeviceProfile.StreamRole.AUDIO)
            .stream("TEMP_BARO", DeviceProfile.StreamRole.TEMPERATURE)
            .dashboardTag(name -> name.startsWith("OpenEarable")
                    ? "EAR" + name.substring("OpenEarable".length()).replace(":", "").trim() : null)
            .build();
    public static final String SENSOR_SERVICE_UUID = "34c2e3bb-34aa-11eb-adc1-0242ac120002";
    public static final String SENSOR_CONFIG_UUID = "34c2e3be-34aa-11eb-adc1-0242ac120002";
    public static final String SENSOR_CONFIG_LEGACY_UUID = "34c2e3bd-34aa-11eb-adc1-0242ac120002";
    public static final String SENSOR_DATA_UUID = "34c2e3bc-34aa-11eb-adc1-0242ac120002";
    public static final String BUTTON_SERVICE_UUID = "29c10bdc-4773-11ee-be56-0242ac120002";
    public static final String LED_SERVICE_UUID = "81040a2e-4819-11ee-be56-0242ac120002";
    public static final String LED_RGB_UUID = "81040e7a-4819-11ee-be56-0242ac120002";
    public static final String AUDIO_SERVICE_UUID = "1410df95-5f68-4ebb-a7c7-5e0fb9ae7557";
    public static final String AUDIO_MODE_UUID = "1410df96-5f68-4ebb-a7c7-5e0fb9ae7557";
    public static final String MIC_SELECT_UUID = "1410df97-5f68-4ebb-a7c7-5e0fb9ae7557";
    public static final String DEVICE_INFO_SERVICE_UUID = "45622510-6468-465a-b141-0b9b0f96b468";
    public static final String PARSE_INFO_SERVICE_UUID = "caa25cb7-7e1b-44f2-adc9-e8c06c9ced43";
    public static final String BATTERY_SERVICE_UUID = "0000180f-0000-1000-8000-00805f9b34fb";
    private final String address;
    private final AndroidBLEService bleService;
    private final DeviceCapabilities capabilities;
    private ConnectionListener connectionListener;
    private DataListener dataListener;
    private boolean connected = false;
    private MQTTManager mqttManager;
    private boolean mqttEnabled = true;
    /** Samples counted per kind of sensor, so loss and timing can be measured as for the ring. */
    private final Map<String, AtomicLong> sampleCounters = new ConcurrentHashMap<>();
    /** Rate each sensor was last configured to stream at; START_ALL_SENSORS uses 50 Hz. */
    private final Map<String, Integer> configuredRates = new ConcurrentHashMap<>();

    public AndroidOpenEarableDevice(String address, AndroidBLEService bleService) {
        this.address = address;
        this.bleService = bleService;
        this.capabilities = new DeviceCapabilities.Builder(TYPE)
            .addSensor("IMU").addSensor("TEMP_BARO").addSensor("MICRO").addSensor("PPG").addSensor("PULSOX")
            .addCommand("CONFIGURE_SENSOR")
            .addCommand("START_MIC_STREAM")
            .addCommand("STOP_MIC_STREAM")
            .addCommand("SET_LED_COLOR")
            .addCommand("SET_AUDIO_MODE")
            .addCommand("SET_AUDIO_MODE_NORMAL")
            .addCommand("SET_AUDIO_MODE_ANC")
            .addCommand("SET_AUDIO_MODE_TRANSPARENT")
            .addCommand("SELECT_MIC_LEFT")
            .addCommand("SELECT_MIC_RIGHT")
            .addCommand("STOP_SENSOR")
            .addCommand("START_ALL_SENSORS")
            .addCommand("STOP_ALL_SENSORS")
            .addCommand("PAIR_DEVICE")
            .addCommand("OPEN_BLUETOOTH_SETTINGS")
            .supportsRealTimeData(true)
            .supportsAudioControl(true)
            .build();
    }

    @Override
    public String getDeviceName() { return TYPE; }

    @Override
    public String getDeviceType() { return TYPE; }

    public static boolean matchesAdvertisement(BleAdvertisement advertisement) {
        String name = advertisement.getNormalizedName();
        return name.contains("openearable")
                || name.contains("open earable")
                || name.contains("earable")
                || name.contains("oe2")
                || advertisement.hasService(SENSOR_SERVICE_UUID)
                || advertisement.hasService(BUTTON_SERVICE_UUID)
                || advertisement.hasService(LED_SERVICE_UUID)
                || advertisement.hasService(AUDIO_SERVICE_UUID)
                || advertisement.hasService(DEVICE_INFO_SERVICE_UUID)
                || advertisement.hasService(PARSE_INFO_SERVICE_UUID);
    }

    @Override
    public void onBleServicesDiscovered() {
        if (!bleService.requestMtu(address, 247)) {
            subscribeToSensorData();
        }
    }

    @Override
    public void onBleMtuChanged(int mtu, int status) {
        subscribeToSensorData();
    }

    private void subscribeToSensorData() {
        bleService.enableNotifications(address, SENSOR_SERVICE_UUID, SENSOR_DATA_UUID);
    }

    @Override
    public void onBleNotification(String serviceUuid, String characteristicUuid, byte[] data) {
        handleRawData(characteristicUuid, data);
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

    private static final int[] IMU_SAMPLE_RATES = {25, 50, 100, 200, 400, 800};
    private static final int[] PPG_SAMPLE_RATES = {25, 50, 84, 100, 200, 400, 8, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096};
    private static final int[] BARO_SAMPLE_RATES = {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 3, 6, 13, 25, 50, 100, 200};
    private static final int[] MICRO_SAMPLE_RATES = {48000};

    public boolean startMeasurement(MeasurementConfig config) {
        byte[] data = buildSensorConfigPacket(config.getSensorType(), config.getSampleRate(), true);
        return writeSensorConfiguration(data);
    }

    @Override
    public boolean stopMeasurement() {
        return stopAllSensors();
    }

    private boolean writeSensorConfig(String sensorType, int sampleRate, boolean streaming) {
        byte[] data = buildSensorConfigPacket(sensorType, sampleRate, streaming);
        return writeSensorConfiguration(data);
    }

    private boolean writeSensorConfiguration(byte[] data) {
        return bleService.writeFirstAvailable(address, SENSOR_SERVICE_UUID,
                Arrays.asList(SENSOR_CONFIG_UUID, SENSOR_CONFIG_LEGACY_UUID), data);
    }

    private boolean writeSensorConfigsSequentially(List<String> sensorTypes, int sampleRate, boolean streaming) {
        if (sensorTypes == null || sensorTypes.isEmpty()) {
            return false;
        }

        // BLE writes must be serialized; schedule with spacing so each has time to complete.
        Handler handler = new Handler(Looper.getMainLooper());
        final long spacingMs = 180L;

        boolean firstResult = writeSensorConfig(sensorTypes.get(0), sampleRate, streaming);
        if (!firstResult) {
            return false;
        }

        for (int i = 1; i < sensorTypes.size(); i++) {
            final String sensorType = sensorTypes.get(i);
            handler.postDelayed(() -> {
                boolean result = writeSensorConfig(sensorType, sampleRate, streaming);
                System.out.println("OpenEarable: " + (streaming ? "start" : "stop")
                        + " sensor " + sensorType + " result=" + result);
            }, i * spacingMs);
        }

        return true;
    }

    private boolean startAllSensors() {
        // The sensors the system uses: motion, temperature and pressure, and pulse.
        // Bone conduction (audio-rate vibration, stored raw and never analysed) and
        // optical temperature are left off; with two earbuds and a ring they took
        // upload bandwidth the useful streams needed. Turn them on per study if needed.
        List<String> sensors = Arrays.asList("IMU", "TEMP_BARO", "PPG");
        return writeSensorConfigsSequentially(sensors, 50, true);
    }

    private boolean stopAllSensors() {
        List<String> sensors = Arrays.asList("IMU", "TEMP_BARO", "PPG", "PULSOX", "OPTTEMP", "BONE_CONDUCTION", "MICRO");
        return writeSensorConfigsSequentially(sensors, 0, false);
    }

    private byte[] buildSensorConfigPacket(String sensorType, int sampleRate, boolean streaming) {
        int sensorId = getSensorId(sensorType);
        // Sensor config v2 expects [sensorId, sampleRateIndex, storageMask].
        int sampleRateIndex = sampleRate > 0 ? getNearestSampleRateIndex(sensorType, sampleRate) : 0;
        if (streaming && sampleRate > 0) configuredRates.put(sensorType, rateAt(sensorType, sampleRateIndex));
        int storageMask = streaming ? 0x01 : 0x00; // 0x01 enables BLE streaming
        System.out.println("OpenEarable: buildConfig sensorId=" + sensorId
                + ", sampleRateIndex=" + sampleRateIndex
                + ", storageMask=" + storageMask);
        return new byte[]{(byte) sensorId, (byte) sampleRateIndex, (byte) storageMask};
    }

    private int getNearestSampleRateIndex(String sensorType, int requestedHz) {
        int[] rates;
        switch (sensorType) {
            case "IMU": rates = IMU_SAMPLE_RATES; break;
            case "PPG": rates = PPG_SAMPLE_RATES; break;
            case "TEMP_BARO": rates = BARO_SAMPLE_RATES; break;
            case "MICRO": rates = MICRO_SAMPLE_RATES; break;
            default: rates = IMU_SAMPLE_RATES; break;
        }

        int bestDiff = Integer.MAX_VALUE;
        int bestIndex = 0;
        for (int i = 0; i < rates.length; i++) {
            int diff = Math.abs(rates[i] - requestedHz);
            if (diff < bestDiff) {
                bestDiff = diff;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    @Override
    public List<SensorData> getLatestData() { return new ArrayList<>(); }

    @Override
    public boolean sendCommand(String command, Map<String, Object> parameters) {
        switch (command) {
            case "SET_LED_COLOR":
                byte r = ((Number) parameters.getOrDefault("r", 0)).byteValue();
                byte g = ((Number) parameters.getOrDefault("g", 255)).byteValue();
                byte b = ((Number) parameters.getOrDefault("b", 0)).byteValue();
                return bleService.write(address, 
                    LED_SERVICE_UUID,
                    LED_RGB_UUID,
                    new byte[]{r, g, b});
            case "CONFIGURE_SENSOR": {
                String sensorType = String.valueOf(parameters.getOrDefault("sensorType", "IMU"));
                int sampleRate = ((Number) parameters.getOrDefault("sampleRate", 50)).intValue();
                boolean streaming = (boolean) parameters.getOrDefault("streaming", true);
                if ("ALL_SENSORS".equals(sensorType)) {
                    return startAllSensors();
                }
                byte[] config = buildSensorConfigPacket(sensorType, sampleRate, streaming);
                System.out.println("OpenEarable: CONFIGURE_SENSOR - sensorType=" + sensorType + " rate=" + sampleRate + " packet=[" 
                    + (config[0] & 0xFF) + "," + (config[1] & 0xFF) + "," + (config[2] & 0xFF) + "]");
                return writeSensorConfiguration(config);
            }
            case "START_MIC_STREAM":
                // Use config packet helper so payload format matches firmware expectations.
                return writeSensorConfiguration(buildSensorConfigPacket("MICRO", 48000, true));
            case "STOP_MIC_STREAM":
                return writeSensorConfiguration(buildSensorConfigPacket("MICRO", 0, false));
            case "START_ALL_SENSORS":
                return startAllSensors();
            case "STOP_ALL_SENSORS":
                return stopAllSensors();
            case "SET_AUDIO_MODE":
                byte mode = ((Number) parameters.getOrDefault("mode", 0)).byteValue();
                return bleService.write(address,
                    AUDIO_SERVICE_UUID,
                    AUDIO_MODE_UUID,
                    new byte[]{mode});
            case "SET_AUDIO_MODE_NORMAL":
                return bleService.write(address,
                    AUDIO_SERVICE_UUID,
                    AUDIO_MODE_UUID,
                    new byte[]{0});
            case "SET_AUDIO_MODE_ANC":
                return bleService.write(address,
                    AUDIO_SERVICE_UUID,
                    AUDIO_MODE_UUID,
                    new byte[]{1});
            case "SET_AUDIO_MODE_TRANSPARENT":
                return bleService.write(address,
                    AUDIO_SERVICE_UUID,
                    AUDIO_MODE_UUID,
                    new byte[]{2});
            case "SELECT_MIC_LEFT":
                return bleService.write(address,
                    AUDIO_SERVICE_UUID,
                    MIC_SELECT_UUID,
                    new byte[]{0});
            case "SELECT_MIC_RIGHT":
                return bleService.write(address,
                    AUDIO_SERVICE_UUID,
                    MIC_SELECT_UUID,
                    new byte[]{1});
            case "STOP_SENSOR": {
                String sensorType = String.valueOf(parameters.getOrDefault("sensorType", "ALL"));
                if ("ALL".equals(sensorType) || "ALL_SENSORS".equals(sensorType)) {
                    return stopAllSensors();
                }
                return writeSensorConfig(sensorType, 0, false);
            }
            case "PAIR_DEVICE":
                return bleService.createBond(address);
        }
        return false;
    }

    @Override
    public List<String> getRecordingStartCommands() {
        return Collections.singletonList("START_ALL_SENSORS");
    }

    @Override
    public List<String> getRecordingStopCommands() {
        return Collections.singletonList("STOP_ALL_SENSORS");
    }

    @Override
    public List<String> getAvailableCommands() {
        return capabilities.getSupportedCommands();
    }

    @Override
    public void setDataListener(DataListener listener) {
        this.dataListener = listener;
    }

    @Override
    public void enableDataStreaming(boolean enabled) {
        if (enabled) {
            bleService.enableNotifications(address, SENSOR_SERVICE_UUID, SENSOR_DATA_UUID);
        }
    }

    private byte getSensorId(String type) {
        switch (type) {
            case "IMU": return 0;
            case "TEMP_BARO": return 1;
            case "MICRO": return 2;
            case "PPG": return 4;
            case "PULSOX": return 5;
            case "OPTTEMP": return 6;
            case "BONE_CONDUCTION": return 7;
            default: return 0;
        }
    }
    
    public void handleRawData(String charUuid, byte[] data) {
        if (SENSOR_DATA_UUID.equalsIgnoreCase(charUuid)) {
            if (dataListener != null) {
                List<SensorData> samples = parseSensorData(data);
                if (samples != null) {
                    for (SensorData sensorData : samples) {
                        dataListener.onDataReceived(sensorData);
                        publishToMQTT(sensorData);
                    }
                }
            }
        }
    }

    /** One notification can carry several samples; each becomes its own record. */
    private List<SensorData> parseSensorData(byte[] raw) {
        if (raw == null || raw.length < 6) {
            System.out.println("OpenEarable: Packet too short or null: " + (raw != null ? raw.length : "null"));
            return null;
        }

        final boolean firmwareLayout = raw.length >= 10 && (raw[1] & 0xFF) <= (raw.length - 10);
        int sensorId = raw[0] & 0xFF;
        int dataSize;
        int payloadOffset;
        long deviceTimestamp;

        if (firmwareLayout) {
            // Current firmware packet: [id][size][time(uint64 LE)][payload...]
            dataSize = raw[1] & 0xFF;
            payloadOffset = 10;
            deviceTimestamp = ((long) raw[2] & 0xFF)
                    | (((long) raw[3] & 0xFF) << 8)
                    | (((long) raw[4] & 0xFF) << 16)
                    | (((long) raw[5] & 0xFF) << 24)
                    | (((long) raw[6] & 0xFF) << 32)
                    | (((long) raw[7] & 0xFF) << 40)
                    | (((long) raw[8] & 0xFF) << 48)
                    | (((long) raw[9] & 0xFF) << 56);
        } else {
            // Legacy docs packet: [id][time(uint32 LE)][size][payload...]
            dataSize = raw[5] & 0xFF;
            payloadOffset = 6;
            deviceTimestamp = ((long) raw[1] & 0xFF)
                    | (((long) raw[2] & 0xFF) << 8)
                    | (((long) raw[3] & 0xFF) << 16)
                    | (((long) raw[4] & 0xFF) << 24);
        }

        System.out.println("OpenEarable: sensorId=" + sensorId + ", dataSize=" + dataSize
                + ", totalLen=" + raw.length + ", packetLayout=" + (firmwareLayout ? "firmware" : "legacy"));
        
        if (raw.length < payloadOffset + dataSize) {
            System.out.println("OpenEarable: Incomplete packet - expected " + (payloadOffset + dataSize) + " bytes, got " + raw.length);
            return null;
        }

        ByteBuffer buf = ByteBuffer.wrap(raw, payloadOffset, dataSize).order(ByteOrder.LITTLE_ENDIAN);
        String sensorType = getSensorTypeName(sensorId);
        SensorData sensorData = newPacket(sensorType, deviceTimestamp);
        List<SensorData> samples = new ArrayList<>();

        switch (sensorId) {
            case 0: // IMU
                System.out.println("OpenEarable: IMU packet, dataSize=" + dataSize);
                if (dataSize >= 36) {
                    sensorData.setData("accel_x", buf.getFloat());
                    sensorData.setData("accel_y", buf.getFloat());
                    sensorData.setData("accel_z", buf.getFloat());
                    sensorData.setData("gyro_x", buf.getFloat());
                    sensorData.setData("gyro_y", buf.getFloat());
                    sensorData.setData("gyro_z", buf.getFloat());
                    sensorData.setData("mag_x", buf.getFloat());
                    sensorData.setData("mag_y", buf.getFloat());
                    sensorData.setData("mag_z", buf.getFloat());
                    System.out.println("OpenEarable: IMU parsed successfully");
                } else {
                    System.out.println("OpenEarable: IMU dataSize too small: " + dataSize + " < 36");
                }
                break;
            case 1: // Barometer / Temperature
                System.out.println("OpenEarable: TEMP_BARO packet, dataSize=" + dataSize);
                if (dataSize >= 8) {
                    sensorData.setData("pressure", buf.getFloat());
                    sensorData.setData("temperature", buf.getFloat());
                    System.out.println("OpenEarable: TEMP_BARO parsed successfully");
                } else {
                    System.out.println("OpenEarable: TEMP_BARO dataSize too small: " + dataSize + " < 8");
                }
                break;
            case 4: { // PPG: samples of ambient, red, ir, green, then an optional 2-byte interval footer
                final int sampleSize = 4 * Integer.BYTES;
                if (dataSize < sampleSize) {
                    System.out.println("OpenEarable: PPG dataSize too small: " + dataSize + " < " + sampleSize);
                    byte[] ppgBytes = new byte[dataSize];
                    System.arraycopy(raw, payloadOffset, ppgBytes, 0, dataSize);
                    sensorData.setData("raw", ppgBytes);
                    break;
                }
                // A 2-byte interval footer follows the samples when the size leaves exactly two spare.
                boolean hasFooter = dataSize % sampleSize == 2;
                int count = Math.max(1, (dataSize - (hasFooter ? 2 : 0)) / sampleSize);
                Integer footerInterval = null;
                if (hasFooter) {
                    int footer = payloadOffset + dataSize;
                    if (footer >= 2 && raw.length >= footer) {
                        footerInterval = (raw[footer - 2] & 0xFF) | ((raw[footer - 1] & 0xFF) << 8);
                    }
                }
                for (int k = 0; k < count; k++) {
                    SensorData one = k == 0 ? sensorData : newPacket(sensorType, deviceTimestamp);
                    one.setData("ppg_ambient", (float) buf.getInt());
                    one.setData("ppg_red", (float) buf.getInt());
                    one.setData("ppg_ir", (float) buf.getInt());
                    one.setData("ppg_green", (float) buf.getInt());
                    one.setAcquisitionMetadata("packetSampleCount", count);
                    one.setAcquisitionMetadata("packetSampleIndex", k);
                    if (footerInterval != null) {
                        one.setData("ppg_sample_interval_us", footerInterval);
                        one.setAcquisitionMetadata("reportedSampleIntervalUs", footerInterval);
                    }
                    samples.add(one);
                }
                break;
            }
            default:
                System.out.println("OpenEarable: Unknown sensorId=" + sensorId + ", dataSize=" + dataSize);
                byte[] payload = new byte[dataSize];
                System.arraycopy(raw, payloadOffset, payload, 0, dataSize);
                sensorData.setData("raw", payload);
                break;
        }

        if (samples.isEmpty()) samples.add(sensorData);
        for (SensorData sample : samples) stamp(sample, sensorType);
        return samples;
    }

    private SensorData newPacket(String sensorType, long deviceTimestamp) {
        SensorData sensorData = new SensorData("OpenEarable", address, sensorType, System.currentTimeMillis());
        sensorData.setData("deviceTimestamp", deviceTimestamp);
        sensorData.setAcquisitionMetadata("androidReceivedTimestampMs", sensorData.getTimestamp());
        sensorData.setAcquisitionMetadata("sourceTimestamp", deviceTimestamp);
        sensorData.setAcquisitionMetadata("sourceTimestampUnit", "unknown");
        sensorData.setAcquisitionMetadata("clockSource", "device");
        sensorData.setAcquisitionMetadata("clockQuality", "unvalidated");
        return sensorData;
    }

    /**
     * Number every sample of a sensor and say what rate it was configured for, as the ring
     * does: the live analysis measures loss from the index and judges the rate against the
     * configured one, and without them it would assume 25 Hz.
     */
    private void stamp(SensorData sample, String sensorType) {
        long index = sampleCounters.computeIfAbsent(sensorType, key -> new AtomicLong()).getAndIncrement();
        sample.setAcquisitionMetadata("sampleIndex", index);
        Integer rate = configuredRates.get(sensorType);
        if (rate == null && ("IMU".equals(sensorType) || "PPG".equals(sensorType) || "TEMP_BARO".equals(sensorType))) {
            rate = 50;
        }
        if (rate != null) sample.setAcquisitionMetadata("configuredSampleRateHz", rate);
    }

    private static int rateAt(String sensorType, int index) {
        int[] rates;
        switch (sensorType) {
            case "PPG": rates = PPG_SAMPLE_RATES; break;
            case "TEMP_BARO": rates = BARO_SAMPLE_RATES; break;
            case "MICRO": rates = MICRO_SAMPLE_RATES; break;
            default: rates = IMU_SAMPLE_RATES; break;
        }
        return rates[Math.max(0, Math.min(index, rates.length - 1))];
    }

    private String getSensorTypeName(int sensorId) {
        switch (sensorId) {
            case 0: return "IMU";
            case 1: return "TEMP_BARO";
            case 2: return "MICRO";
            case 4: return "PPG";
            case 5: return "PULSOX";
            case 6: return "OPTTEMP";
            case 7: return "BONE_CONDUCTION";
            default: return "Sensor";
        }
    }

    @Override
    public void setConnected(boolean connected) {
        this.connected = connected;
        if (connected && connectionListener != null) {
            connectionListener.onConnected(address);
        } else if (!connected && connectionListener != null) {
            connectionListener.onDisconnected(address);
        }
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
            mqttManager.publishSensorDataBatched(sensorData, 2);
        }
    }
}
