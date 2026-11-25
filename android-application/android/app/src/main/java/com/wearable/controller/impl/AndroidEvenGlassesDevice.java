package com.wearable.controller.impl;

import com.wearable.controller.*;
import com.wearable.controller.bluetooth.AndroidBLEService;
import com.wearable.controller.bluetooth.BleAdvertisement;
import com.wearable.controller.bluetooth.BleDeviceAdapter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class AndroidEvenGlassesDevice implements WearableDevice, BleDeviceAdapter {
    public static final String TYPE = "Even Glasses";

    /** How the app names and treats this wearable; see DeviceProfile. */
    public static final DeviceProfile PROFILE = new DeviceProfile.Builder(TYPE)
            .displayName("Even G1")
            .pickerLabel("Even G1  ·  glasses for live alerts")
            .wearPosition("face")
            .iconKey("glasses")
            .display(EvenGlassesDisplay::new)
            // The arms keep a connection only with a phone they are paired with.
            .bondBeforeConnect(
                    "Pairing the glasses: keep them in their case, lid open, and accept each request.",
                    "Glasses did not pair. Put them in their case, lid open, and tap Connect.",
                    " did not finish. Put the glasses in their case with the lid open, close Even's app,"
                            + " and tap Connect again.",
                    "Glasses paired; connecting both arms")
            // Each arm advertises on its own; both are connected as one wearable.
            .parts(AndroidEvenGlassesDevice::pairOf,
                    name -> sideOf(name).isEmpty() ? null
                            : "Even G1 · " + ("L".equals(sideOf(name)) ? "left arm" : "right arm"),
                    pair -> "Even G1 glasses (pair " + pair + ")",
                    "Connects both arms")
            .build();

    /** "L" or "R" from an arm's name such as "Even G1_45_L_8C3A1B"; empty otherwise. */
    public static String sideOf(String name) {
        String[] parts = name == null ? new String[0] : name.split("_");
        return parts.length >= 3 && (parts[2].equals("L") || parts[2].equals("R")) ? parts[2] : "";
    }

    /** The pair number both arms of one pair of glasses share; empty otherwise. */
    public static String pairOf(String name) {
        return sideOf(name).isEmpty() ? "" : name.split("_")[1];
    }
    public static final String SERVICE_UUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E";
    public static final String WRITE_UUID = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E";
    public static final String READ_UUID = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E";
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
        this.capabilities = new DeviceCapabilities.Builder(TYPE)
            .addSensor("Microphone").addSensor("Touchpad")
            .addCommand("DISPLAY_TEXT").addCommand("HEARTBEAT").addCommand("GET_BATTERY")
            .supportsAudioControl(true)
            .build();
    }

    @Override
    public String getDeviceName() { return TYPE; }

    @Override
    public String getDeviceType() { return TYPE; }

    public static boolean matchesAdvertisement(BleAdvertisement advertisement) {
        String name = advertisement.getNormalizedName();
        return name.matches("even g\\d+_.*_.*_.*")
                || name.contains("even g")
                || name.contains("even_glasses")
                || name.matches("g\\d+_.*_.*_.*")
                || advertisement.hasService(SERVICE_UUID);
    }

    @Override
    public boolean requiresRecentAdvertisement() {
        return true;
    }

    @Override
    public void onBleServicesDiscovered() {
        if (!bleService.requestMtu(address, 247)) {
            subscribeToDeviceData();
        }
    }

    @Override
    public void onBleMtuChanged(int mtu, int status) {
        subscribeToDeviceData();
    }

    private void subscribeToDeviceData() {
        bleService.enableNotifications(address, SERVICE_UUID, READ_UUID);
    }

    @Override
    public void onBleNotification(String serviceUuid, String characteristicUuid, byte[] data) {
        if (SERVICE_UUID.equalsIgnoreCase(serviceUuid) && READ_UUID.equalsIgnoreCase(characteristicUuid)) {
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
        return new DeviceInfo(TYPE, address);
    }

    @Override
    public boolean startMeasurement(MeasurementConfig config) {
        if ("Microphone".equals(config.getSensorType())) {
            return bleService.write(address, 
                SERVICE_UUID,
                WRITE_UUID,
                new byte[]{0x0E, 0x01});
        }
        return false;
    }

    @Override
    public boolean stopMeasurement() {
        return bleService.write(address, 
            SERVICE_UUID,
            WRITE_UUID,
            new byte[]{0x18}); // Exit functions
    }

    @Override
    public List<SensorData> getLatestData() { return new ArrayList<>(); }

    @Override
    public boolean sendCommand(String command, Map<String, Object> parameters) {
        if ("RAW_HEX".equals(command)) {
            // Debug aid: send bytes as given, to try protocol variants without a rebuild.
            String[] parts = String.valueOf(parameters.getOrDefault("hex", "")).trim().split("\\s+");
            byte[] bytes = new byte[parts.length];
            for (int i = 0; i < parts.length; i++) bytes[i] = (byte) Integer.parseInt(parts[i], 16);
            return bleService.write(address, SERVICE_UUID, WRITE_UUID, bytes);
        }
        if ("DISPLAY_IMAGE".equals(command)) {
            Object bmp = parameters.get("bmp");
            return bmp instanceof byte[] && sendImage((byte[]) bmp);
        }
        if ("DISPLAY_TEXT".equals(command)) {
            Object screen = parameters.get("screen");
            return sendText((String) parameters.getOrDefault("text", ""),
                    screen instanceof Number ? ((Number) screen).intValue() : 0x71,
                    Boolean.TRUE.equals(parameters.get("preformatted")),
                    Boolean.TRUE.equals(parameters.get("unicode")));
        } else if ("HEARTBEAT".equals(command)) {
            return sendHeartbeat();
        } else if ("GET_BATTERY".equals(command)) {
            // Battery query (0x2C 0x01) as used by community G1 clients; the
            // reply is 0x2C 0x66 followed by the arm's percentage. Not yet
            // verified on hardware.
            return bleService.write(address, SERVICE_UUID, WRITE_UUID, new byte[]{0x2C, 0x01});
        }
        return false;
    }

    @Override
    public List<String> getAvailableCommands() { return capabilities.getSupportedCommands(); }

    @Override
    public void setDataListener(DataListener listener) {
        this.dataListener = listener;
    }

    @Override
    public void enableDataStreaming(boolean enabled) {
        if (enabled) {
            bleService.enableNotifications(address, SERVICE_UUID, READ_UUID);
        }
    }

    /**
     * Show text on the display with the text command (0x4E) from Even
     * Realities' published G1 protocol: a 9-byte header of sequence, packet
     * count, packet number, screen status (0x71: new content, text page),
     * character position (2 bytes), and page number and count, followed by up
     * to 191 bytes of UTF-8 text. The text is pre-wrapped because the glasses
     * do not wrap it themselves.
     *
     * The G1 has a separate BLE connection per arm and the protocol sends text
     * to the left arm, then the right. This adapter holds one connection, so
     * text appears on whichever arm it is connected to.
     */
    private boolean sendText(String text, int screenStatus, boolean preformatted, boolean unicode) {
        byte[] body = (preformatted ? preformat(text, unicode) : wrapForDisplay(text)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int chunk = 191;
        int total = Math.max(1, (body.length + chunk - 1) / chunk);
        int seq = sequenceNumber.getAndIncrement() & 0xFF;
        boolean ok = true;
        for (int part = 0; part < total; part++) {
            int start = part * chunk;
            int length = Math.min(chunk, body.length - start);
            byte[] packet = new byte[9 + Math.max(0, length)];
            packet[0] = 0x4E;
            packet[1] = (byte) seq;
            packet[2] = (byte) total;
            packet[3] = (byte) part;
            packet[4] = (byte) screenStatus;
            packet[5] = 0x00;
            packet[6] = 0x00;
            packet[7] = 0x01;
            packet[8] = 0x01;
            if (length > 0) System.arraycopy(body, start, packet, 9, length);
            ok &= bleService.write(address, SERVICE_UUID, WRITE_UUID, packet);
        }
        return ok;
    }

    /**
     * Text laid out by the caller: spaces and line breaks are kept, so columns can be
     * lined up with padding. ASCII only, at most five lines.
     */
    private static String preformat(String text, boolean unicode) {
        if (!unicode) text = text.replaceAll("[^\\x20-\\x7E\\n]", "-");
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(lines.length, 5); i++) {
            if (i > 0) out.append('\n');
            out.append(lines[i]);
        }
        return out.toString();
    }

    /**
     * Send a 1-bit 576 x 136 BMP to the lenses, as Even's demo app documents: 194-byte
     * packets (0x15, index; the first also carries the storage address 00 1C 00 00),
     * then 20 0D 0E to end, then 16 and the CRC-32 (XZ) of address plus picture, big
     * endian, once the glasses have the end command.
     */
    private boolean sendImage(byte[] bmp) {
        final int pack = 194;
        final byte[] address4 = {0x00, 0x1C, 0x00, 0x00};
        int total = (bmp.length + pack - 1) / pack;
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(address4);
        crc.update(bmp);
        final long crcValue = crc.getValue();
        boolean ok = true;
        for (int i = 0; i < total; i++) {
            int start = i * pack;
            int length = Math.min(pack, bmp.length - start);
            int head = i == 0 ? 6 : 2;
            byte[] packet = new byte[head + length];
            packet[0] = 0x15;
            packet[1] = (byte) (i & 0xFF);
            if (i == 0) System.arraycopy(address4, 0, packet, 2, 4);
            System.arraycopy(bmp, start, packet, head, length);
            ok &= bleService.write(address, SERVICE_UUID, WRITE_UUID, packet);
        }
        ok &= bleService.write(address, SERVICE_UUID, WRITE_UUID, new byte[]{0x20, 0x0D, 0x0E});
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> bleService.write(
                address, SERVICE_UUID, WRITE_UUID, new byte[]{
                        0x16,
                        (byte) (crcValue >> 24), (byte) (crcValue >> 16), (byte) (crcValue >> 8), (byte) crcValue}), 400);
        return ok;
    }

    /** Greedy word wrap to the display's width, at most five lines. */
    private static String wrapForDisplay(String text) {
        // Plain ASCII only. Both arms dropped their links together a few seconds
        // after the first text containing a middle dot (two bytes in UTF-8), every
        // time; the lens font and firmware are not known to handle other characters.
        text = text.replaceAll("[^\\x20-\\x7E\\n]", "-");
        final int width = 40;
        final int maxLines = 5;
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        // Line breaks in the text are kept; only lines too long for the lens are wrapped.
        for (String paragraph : text.trim().split("\n")) {
            for (String word : paragraph.trim().split("\\s+")) {
                if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                if (line.length() > 0) line.append(' ');
                line.append(word);
            }
            if (line.length() > 0) lines.add(line.toString());
            line.setLength(0);
        }
        return String.join("\n", lines.subList(0, Math.min(maxLines, lines.size())));
    }

    private boolean sendHeartbeat() {
        int seq = sequenceNumber.getAndIncrement() & 0xFF;
        byte[] heartbeat = new byte[]{0x25, 0x06, 0x00, (byte) seq, 0x04, (byte) seq};
        return bleService.write(address, SERVICE_UUID, WRITE_UUID, heartbeat);
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

    private long lastMicOffMs;
    /** Debug flag "nomicoff" turns this off to see what the glasses do on their own. */
    public static volatile boolean micOffEnabled = true;

    public void handleRawData(byte[] data) {
        if (data.length < 1) return;
        if ((data[0] & 0xFF) == 0xF1) {
            // Audio from the glasses' microphone. Looking up starts it, and nothing
            // here listens to it; every disconnect in the logs followed about ten
            // seconds of it. Tell the arm to close the microphone (0x0E 0x00).
            long now = System.currentTimeMillis();
            if (now - lastMicOffMs > 2000) {
                lastMicOffMs = now;
                android.util.Log.i("EvenGlasses", address + " is streaming microphone audio"
                        + (micOffEnabled ? "; closing the microphone" : " (not closing it: debug flag)"));
                if (micOffEnabled) bleService.write(address, SERVICE_UUID, WRITE_UUID, new byte[]{0x0E, 0x00});
            }
            return;
        }
        // Replies were discarded, so a text the glasses refused looked the same
        // as one they showed. 0xC9 is success and 0xCA failure for the text command.
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < Math.min(data.length, 16); i++) hex.append(String.format("%02X ", data[i]));
        android.util.Log.i("EvenGlasses", address + " replied " + hex.toString().trim());
        if (data.length < 2) return;
        if ((data[0] & 0xFF) == 0x4E && (data[1] & 0xFF) == 0xC9 && dataListener != null) {
            dataListener.onCommandResponse("TEXT_ACK", true, address);
            return;
        }
        if ((data[0] & 0xFF) == 0x2C && (data[1] & 0xFF) == 0x66 && data.length >= 3 && dataListener != null) {
            // Byte 2 is the arm's battery. The right arm reads 00 or 01 there, and the
            // official Even app shows the same 1%: the arm is nearly flat, which would
            // also explain its dropping out. The byte after it (0x51-0x57 and rising on
            // the right) is something else, probably the case; an earlier version of
            // this code took it for the arm's percentage and was wrong.
            int percent = data[2] & 0xFF;
            if (percent <= 100) dataListener.onCommandResponse("BATTERY", true, String.valueOf(percent));
            return;
        }
        if ((data[0] & 0xFF) == 0x0E && (data[1] & 0xFF) == 0xC9 && dataListener != null) {
            // The arm has closed the microphone; its voice screen goes with it, and
            // whatever we sent while it was up was drawn over.
            dataListener.onCommandResponse("GLASSES_EVENT", true, "MIC_CLOSED|" + address);
            return;
        }
        // Check for specific Even events (e.g. F5 23 for Start AI Recording)
        if ((data[0] & 0xFF) == 0xF5 && dataListener != null) {
            // 0x02 head up, 0x03 head down, 0x00 double tap, among others.
            dataListener.onCommandResponse("GLASSES_EVENT", true, String.format("%02X|%s", data[1] & 0xFF, address));
            dataListener.onDataReceived(new SensorData("Even Glasses", address, "Event", System.currentTimeMillis()));
        }
    }

    @Override
    public void setConnected(boolean connected) {
        this.connected = connected;
        if (connected) {
            // No 0x25 heartbeat: the official Even app never sends one. A Bluetooth log
            // of it shows a battery query every 10 s and two status queries a minute,
            // which MainActivity now repeats (see glassesKeepAlive).
            if (connectionListener != null) connectionListener.onConnected(address);
        } else {
            stopHeartbeat();
            if (connectionListener != null) connectionListener.onDisconnected(address);
        }
    }
}
