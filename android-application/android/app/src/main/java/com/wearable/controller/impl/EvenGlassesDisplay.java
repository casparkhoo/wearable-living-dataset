package com.wearable.controller.impl;

import android.os.Handler;

import com.wearable.controller.DisplayNames;
import com.wearable.controller.HeadUpDisplay;
import com.wearable.controller.LiveSummary;
import com.wearable.controller.WearableDevice;
import com.wearable.controller.WearableDeviceRegistry;
import com.wearable.controller.fragments.RecordFragment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The live dashboard on Even G1 glasses: a picture (or a text page) sent to both arms
 * when the wearer looks up, the keep-alive and time sync the glasses expect, the order
 * in which the two arms are written to, and the debug tools for testing the link.
 *
 * Moved out of MainActivity unchanged so that the activity names no wearable. The G1
 * protocol notes in the comments below were taken from Bluetooth logs of the glasses
 * and are the reason for the odd delays; do not tidy them without testing on glasses.
 */
public class EvenGlassesDisplay implements HeadUpDisplay {
    // Not final: the head-down callback below is built before the constructor runs.
    private Host host;
    private Handler uiHandler;

    public EvenGlassesDisplay(Host host) {
        this.host = host;
        this.uiHandler = host.handler();
    }

    // -- HeadUpDisplay ------------------------------------------------------

    @Override
    public String deviceType() {
        return AndroidEvenGlassesDevice.TYPE;
    }

    @Override
    public boolean handlesCommand(String command) {
        return "TEXT_ACK".equals(command) || "GLASSES_EVENT".equals(command);
    }

    @Override
    public void onCommandResponse(String command, String response) {
        if ("TEXT_ACK".equals(command)) onGlassesTextAck(response);
        else if ("GLASSES_EVENT".equals(command)) handleGlassesEvent(response);
    }

    @Override
    public void onDeviceConnected(String address) {
        startGlassesKeepAlive();
        uiHandler.postDelayed(this::syncGlasses, 1500);
    }

    @Override
    public void onLiveSummary(com.google.gson.JsonObject data) {
        showVitalsOnGlasses(data, false);
    }

    @Override
    public boolean showsAutomatically() {
        return glassesAutoDisplay;
    }

    @Override
    public void onAlert(String state, String text) {
        glassesAlertLine = ("raised".equals(state) ? "! " : "OK ") + text;
        if (glassesAlertLine.length() > 42) glassesAlertLine = glassesAlertLine.substring(0, 42);
        glassesAlertAtMs = System.currentTimeMillis();
        // Only while looking up, and then as the banner on the dashboard; with the
        // head down the phone's notification is the alert.
        if (glassesHeadUp && host.latestLiveData() != null) {
            showVitalsOnGlasses(host.latestLiveData(), true);
        }
    }

    @Override
    public void refreshPreview(RecordFragment fragment) {
        fragment.setGlassesPreview(buildGlassesDashboard(host.latestLiveData()));
        fragment.setGlassesArms(glassesArmText("L"), glassesArmText("R"), glassesHeadUp, anyGlassesConnected());
    }

    @Override
    public void startDebugTools() {
        registerDebugReceiver();
        loadDebugFlags();
    }

    @Override
    public void stopDebugTools() {
        if (debugReceiver != null) {
            try {
                host.context().unregisterReceiver(debugReceiver);
            } catch (IllegalArgumentException ignored) {
                // Not registered.
            }
        }
    }

    private String defaultType() {
        com.wearable.controller.DeviceProfile profile = WearableDeviceRegistry.shared().getDashboardDefault();
        return profile == null ? "" : profile.getType();
    }

    private String dashboardNoun() {
        com.wearable.controller.DeviceProfile profile = WearableDeviceRegistry.shared().getDashboardDefault();
        return profile == null ? "" : profile.getDashboardNoun();
    }

    // -- Moved from MainActivity --------------------------------------------

    /**
     * Gap between a command to the left arm and the same one to the right. At 300 ms
     * the arms stayed connected for minutes; at 120 ms both dropped about ten
     * seconds after each text, six times out of six, so the arms appear to relay
     * to each other and need the left to finish first.
     */
    private static final long GLASSES_ARM_GAP_MS = 350;
    /** A picture is about 10 KB per arm, so it is refreshed less often than the text page was. */
    private static final long GLASSES_VITALS_INTERVAL_MS = 8_000;
    /** How long an alert stays on the lenses under the vitals. */
    private static final long GLASSES_ALERT_SHOW_MS = 30_000;
    /** Looking down for less than this is a nod, not a decision to stop reading. */
    private static final long GLASSES_HEAD_DOWN_DELAY_MS = 1_200;
    private long lastGlassesVitalsMs;
    private boolean glassesHeadUp;
    /** Debug builds can switch the head-up display off to test the glasses link alone. */
    private boolean glassesAutoDisplay = true;
    private String glassesAlertLine = "";
    private long glassesAlertAtMs;
    private String lastGlassesText;
    private final Runnable glassesHeadDown = () -> {
        glassesHeadUp = false;
        lastGlassesText = null;
        // The glasses hide the picture themselves when the head goes down (they
        // report 0xF5 0x1F); an exit command on top of that adds traffic and a state
        // change to a link that was dropping, and the text page needs it, not the picture.
        // A picture still in flight when the head goes down lands afterwards and the
        // glasses show it, so close the display now and again once it has landed.
        clearGlasses();
        host.handler().postDelayed(() -> { if (!glassesHeadUp) clearGlasses(); }, 1500);
    };

    // ── What the official Even app does, taken from a Bluetooth log of it ────────
    //
    // When an arm reports 0xF5 0x06 the official app sends both arms a time sync and
    // dashboard setup within a second (left, then right ~50 ms later), and the arms
    // answer within ~50 ms. Ours sent nothing; in our own logs every disconnect came
    // about ten seconds after that event, with an 0xF5 0x00 from both arms between.
    private int glassesSeq = 0x24;
    private long lastGlassesSyncMs;
    private boolean glassesKeepAliveRunning;
    private int glassesKeepAliveTicks;

    private int nextGlassesSeq() {
        glassesSeq = (glassesSeq + 1) & 0xFF;
        return glassesSeq;
    }

    private static String littleEndianHex(long value, int bytes) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < bytes; i++) {
            if (i > 0) out.append(' ');
            out.append(String.format("%02X", (value >> (8 * i)) & 0xFF));
        }
        return out.toString();
    }

    /** Send raw bytes to the left arm and/or the right, the right 50 ms after the left. */
    private void glassesWrite(String hex, boolean left, boolean right, long delayMs) {
        Map<String, Object> params = new HashMap<>();
        params.put("hex", hex);
        for (Map.Entry<String, WearableDevice> entry : host.connectedDevices().entrySet()) {
            if (!AndroidEvenGlassesDevice.TYPE.equals(entry.getValue().getDeviceType())) continue;
            boolean isRight = "R".equals(AndroidEvenGlassesDevice.sideOf(host.deviceName(entry.getKey())));
            if (isRight ? !right : !left) continue;
            final WearableDevice arm = entry.getValue();
            uiHandler.postDelayed(() -> arm.sendCommand("RAW_HEX", params), delayMs + (isRight ? 50 : 0));
        }
    }


    private GlassesImage.Dashboard buildGlassesDashboard(com.google.gson.JsonObject data) {
        long now = System.currentTimeMillis();
        GlassesImage.Dashboard d = new GlassesImage.Dashboard();
        d.clock = new java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(new java.util.Date());
        d.recording = host.recordingActive() ? "REC " + host.recordingElapsed() : "not recording";
        d.recordingActive = host.recordingActive();
        d.cloudOnline = host.cloudOnline();
        d.cloud = d.cloudOnline ? "cloud" : "offline";
        // The recording the dashboard describes, and that wearable's own name and battery.
        com.google.gson.JsonObject picked = LiveSummary.pickSession(data);
        String shownType = picked != null ? LiveSummary.jsonString(picked, "device_type") : defaultType();
        Integer deviceBattery = null;
        for (Map.Entry<String, WearableDevice> entry : host.connectedDevices().entrySet()) {
            if (shownType.equals(entry.getValue().getDeviceType())) deviceBattery = host.battery(entry.getKey());
        }
        d.ring = deviceBattery != null ? DisplayNames.shortName(shownType) + " " + deviceBattery + "%" : "";
        boolean alertShown = !glassesAlertLine.isEmpty() && now - glassesAlertAtMs < GLASSES_ALERT_SHOW_MS;
        d.alert = alertShown ? glassesAlertLine : "";
        d.alerts = "0 alerts";
        if (data != null && data.has("sessions") && data.get("sessions").isJsonArray()) {
            for (com.google.gson.JsonElement element : data.getAsJsonArray("sessions")) {
                if (!element.isJsonObject()) continue;
                com.google.gson.JsonObject session = element.getAsJsonObject();
                if (picked != null && session != picked) continue;
                if (!session.has("ppg") || !session.get("ppg").isJsonObject()) continue;
                com.google.gson.JsonObject ppg = session.getAsJsonObject("ppg");
                Double shown = LiveSummary.shownHeartRate(ppg);
                d.heartRate = shown != null ? String.valueOf(Math.round(shown)) : "--";
                d.pulseQuality = shown == null && ppg.has("hr_reason") && !ppg.get("hr_reason").isJsonNull()
                        ? LiveSummary.shortHrReason(LiveSummary.jsonString(ppg, "hr_reason")) : LiveSummary.signalLabel(LiveSummary.jsonString(ppg, "quality"));
                d.movement = session.has("movement") && session.get("movement").isJsonObject()
                        ? LiveSummary.jsonString(session.getAsJsonObject("movement"), "state") : "";
                if (session.has("streams") && session.get("streams").isJsonObject()) {
                    com.google.gson.JsonObject streams = session.getAsJsonObject("streams");
                    if (streams.has("ppg") && streams.get("ppg").isJsonObject()) {
                        com.google.gson.JsonObject health = streams.getAsJsonObject("ppg");
                        if (health.has("effective_rate_hz") && !health.get("effective_rate_hz").isJsonNull()) {
                            d.rate = Math.round(health.get("effective_rate_hz").getAsDouble()) + " Hz";
                        }
                    }
                }
                int alerts = session.has("active_alerts") && session.get("active_alerts").isJsonArray()
                        ? session.getAsJsonArray("active_alerts").size() : 0;
                d.alerts = alerts + (alerts == 1 ? " alert" : " alerts");
                break;
            }
        }
        d.wave = recentPulseWave();
        // Every recording wearable gets its own row when there are several.
        for (RecordFragment.LiveDevice device : host.liveDevices(data)) {
            String shortName = WearableDeviceRegistry.profileOf(device.type).dashboardTag(device.name);
            String hr = device.heartRate.replace(" bpm", "");
            String signal = device.detail.contains("signal good") ? "good"
                    : device.detail.contains("signal ok") ? "ok"
                    : device.detail.contains("signal weak") ? "weak" : "";
            d.devices.add(new GlassesImage.DeviceRow(shortName, hr, signal));
        }
        return d;
    }

    /** "left 87%", "right 12%" or "left --" for the preview card. */
    private String glassesArmText(String side) {
        String label = "L".equals(side) ? "left" : "right";
        for (Map.Entry<String, WearableDevice> entry : host.connectedDevices().entrySet()) {
            if (!AndroidEvenGlassesDevice.TYPE.equals(entry.getValue().getDeviceType())) continue;
            String arm = AndroidEvenGlassesDevice.sideOf(host.deviceName(entry.getKey()));
            boolean isRight = "R".equals(arm);
            if (isRight != "R".equals(side)) continue;
            Integer level = host.battery(entry.getKey());
            return label + " " + (level == null ? "connected" : level + "%");
        }
        return label + " off";
    }

    /** Show the dashboard as a picture; off falls back to the five-row text page. */
    private boolean glassesImageMode = true;

    /**
     * The pulse waveform for the picture: the same 10 s trace the record screen and
     * the console plot, unchanged. It used to be the last 125 points, about 4 s,
     * smoothed again, so the lenses showed a different-looking graph from the app.
     */
    private float[] recentPulseWave() {
        float[] source = host.livePulseTrace();
        if (source == null || source.length < 20) return null;
        return source.clone();
    }

    /** A made-up dashboard with a synthetic pulse, to look at the picture on the lenses. */
    private GlassesImage.Dashboard sampleDashboard() {
        GlassesImage.Dashboard d = new GlassesImage.Dashboard();
        d.clock = new java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(new java.util.Date());
        d.recording = "REC 03:12";
        d.recordingActive = true;
        d.heartRate = "62";
        d.pulseQuality = "pulse good";
        d.movement = "still";
        d.ring = dashboardNoun() + " 94%";
        d.rate = "25 Hz";
        d.cloud = "cloud";
        d.alerts = "0 alerts";
        d.cloudOnline = true;
        float[] wave = new float[125];
        for (int i = 0; i < wave.length; i++) {
            double phase = (i / 25.0 * 1.2) % 1.0;
            wave[i] = (float) (Math.exp(-Math.pow((phase - 0.17) / 0.065, 2)) + 0.42 * Math.exp(-Math.pow((phase - 0.45) / 0.085, 2)));
        }
        d.wave = wave;
        return d;
    }

    private GlassesImage.Dashboard lastGlassesDashboard;
    private final Map<String, Long> lastArmImageMs = new HashMap<>();

    /** Send the latest picture to one arm only, at most once every 700 ms per arm, except after a microphone close. */
    private void sendGlassesImageToArm(String armAddress, long delayMs) {
        final WearableDevice arm = host.connectedDevices().get(armAddress);
        if (arm == null || lastGlassesDashboard == null) return;
        long now = System.currentTimeMillis();
        Long last = lastArmImageMs.get(armAddress);
        if (last != null && now - last < (delayMs >= 300 ? 0 : 700)) return;
        lastArmImageMs.put(armAddress, now);
        Map<String, Object> params = new HashMap<>();
        params.put("bmp", GlassesImage.render(lastGlassesDashboard));
        uiHandler.postDelayed(() -> { if (glassesHeadUp) arm.sendCommand("DISPLAY_IMAGE", params); }, delayMs);
    }

    /** Send the dashboard picture to the left arm, then the right after the left has had it. */
    private void sendGlassesDashboardImage(GlassesImage.Dashboard dashboard) {
        if (!glassesHeadUp && !debugFlags.contains("showimage")) return;
        lastGlassesDashboard = dashboard;
        long sentAt = System.currentTimeMillis();
        for (String key : host.connectedDevices().keySet()) lastArmImageMs.put(key, sentAt);
        byte[] bmp = GlassesImage.render(dashboard);
        Map<String, Object> params = new HashMap<>();
        params.put("bmp", bmp);
        // Both arms at once (the vendor's demo sends images to the two independently):
        // the glasses show their own voice screen on head-up until our picture lands,
        // so the sooner it lands on both the better.
        for (Map.Entry<String, WearableDevice> entry : host.connectedDevices().entrySet()) {
            if (!AndroidEvenGlassesDevice.TYPE.equals(entry.getValue().getDeviceType())) continue;
            final WearableDevice arm = entry.getValue();
            boolean isRight = "R".equals(AndroidEvenGlassesDevice.sideOf(host.deviceName(entry.getKey())));
            uiHandler.postDelayed(() -> arm.sendCommand("DISPLAY_IMAGE", params), isRight ? 80 : 0);
        }
    }

    /** Time sync, dashboard setup and status, in the order and spacing the official app uses. */
    private void syncGlasses() {
        if (debugFlags.contains("nosetup")) return;
        long now = System.currentTimeMillis();
        if (now - lastGlassesSyncMs < 5_000) return;
        lastGlassesSyncMs = now;
        host.log("Glasses: sending time sync and dashboard setup");
        glassesWrite(String.format("06 07 00 %02X 06 00 00", nextGlassesSeq()), true, true, 0);
        glassesWrite(String.format("06 16 00 %02X 01 %s %s 02 15 00 00 02", nextGlassesSeq(),
                littleEndianHex(now / 1000, 4), littleEndianHex(now, 8)), true, true, 550);
        glassesWrite(String.format("06 0C 00 %02X 03 01 00 01 00 00 00 01", nextGlassesSeq()), true, true, 1100);
        glassesWrite(String.format("22 05 00 %02X 01", nextGlassesSeq()), false, true, 1150);
        for (int page = 1; page <= 4; page++) {
            int seq = nextGlassesSeq();
            nextGlassesSeq();
            glassesWrite(String.format("1E 10 00 %02X 03 01 00 01 00 %02X 00 01 00 01 00 00", seq, page),
                    true, true, 3900L + (page - 1) * 150L);
        }
        glassesWrite(String.format("1E 06 00 %02X 01 01", nextGlassesSeq()), false, true, 4600);
    }

    /**
     * Width of the lens in digit-widths. A line of 48 digits fitted with room to spare
     * (measured with a ruler of digits), so this leaves a margin below that.
     */
    private static final double GLASSES_WIDTH = 44.0;

    /** Rough width of one character in digit-widths; the font is not fixed-width. */
    private static double glyphWidth(char c) {
        if (c == ' ') return 0.45;
        if (c >= '0' && c <= '9') return 1.0;
        if (c >= 'A' && c <= 'Z') return 1.1;
        if (c >= 'a' && c <= 'z') return 0.9;
        return 0.6;
    }

    private static double textWidth(String text) {
        double width = 0;
        for (int i = 0; i < text.length(); i++) width += glyphWidth(text.charAt(i));
        return width;
    }

    /** {@code left} at the left edge and {@code right} at the right, padded with spaces. */
    private static String columns(String left, String right) {
        int gap = (int) Math.max(2, Math.round((GLASSES_WIDTH - textWidth(left) - textWidth(right)) / glyphWidth(' ')));
        StringBuilder out = new StringBuilder(left);
        for (int i = 0; i < gap; i++) out.append(' ');
        return out.append(right).toString();
    }

    private boolean anyGlassesConnected() {
        for (WearableDevice device : host.connectedDevices().values()) {
            if (AndroidEvenGlassesDevice.TYPE.equals(device.getDeviceType())) return true;
        }
        return false;
    }

    /** Battery query every 10 s; two status queries a minute: the official app's keep-alive. */
    private final Runnable glassesKeepAlive = new Runnable() {
        @Override
        public void run() {
            if (!anyGlassesConnected()) {
                glassesKeepAliveRunning = false;
                return;
            }
            glassesWrite("2C 01", true, true, 0);
            if (glassesKeepAliveTicks % 6 == 0 && !debugFlags.contains("nokeepalive")) {
                glassesWrite("2B", true, true, 600);
                glassesWrite("29", false, true, 500);
            }
            glassesKeepAliveTicks++;
            uiHandler.postDelayed(this, 10_000);
        }
    };

    private void startGlassesKeepAlive() {
        if (glassesKeepAliveRunning) return;
        glassesKeepAliveRunning = true;
        glassesKeepAliveTicks = 0;
        uiHandler.postDelayed(glassesKeepAlive, 10_000);
    }

    /** The glasses report head up (02) and head down (03), from one arm or both. */
    private void handleGlassesEvent(String raw) {
        int bar = raw.indexOf('|');
        final String code = bar < 0 ? raw : raw.substring(0, bar);
        final String armAddress = bar < 0 ? "" : raw.substring(bar + 1);
        if ("06".equals(code)) syncGlasses();
        if (!glassesAutoDisplay) return;
        if (glassesImageMode && glassesHeadUp && host.latestLiveData() != null
                && ("MIC_CLOSED".equals(code) || "1E".equals(code))) {
            // An arm's picture is lost when its own display switches or its voice screen
            // covers it, so each arm gets the picture again when it reports its display
            // on, or its microphone closed. Left alone, a second one follows within 700 ms.
            sendGlassesImageToArm(armAddress, "MIC_CLOSED".equals(code) ? 300 : 150);
            return;
        }
        if ("02".equals(code)) {
            uiHandler.removeCallbacks(glassesHeadDown);
            if (glassesHeadUp) return;       // already showing; the second arm's report
            glassesHeadUp = true;
            // Text only while there is something live to show; any text to the
            // glasses is a chance to lose them (see the note on the greeting).
            if (host.latestLiveData() != null && host.recordingActive()) {
                showVitalsOnGlasses(host.latestLiveData(), true);
                // Only the right arm reports looking up. The left arm notices a moment
                // later and can switch to its own screen, or close the display, after
                // our text arrived; sending again puts ours last on both.
                if (!glassesImageMode) uiHandler.postDelayed(() -> {
                    if (glassesHeadUp && host.latestLiveData() != null) {
                        lastGlassesText = null;
                        showVitalsOnGlasses(host.latestLiveData(), true);
                    }
                }, 900);
            }
        } else if ("03".equals(code)) {
            uiHandler.removeCallbacks(glassesHeadDown);
            uiHandler.postDelayed(glassesHeadDown, GLASSES_HEAD_DOWN_DELAY_MS);
        }
    }

    /** Close the text page (command 0x18) on every connected arm. */
    private void clearGlasses() {
        Map<String, Object> params = new HashMap<>();
        params.put("hex", "18");
        sendToGlassesArms("RAW_HEX", params, false);
    }

    /** Take a notice off the lenses after a few seconds unless they are looking up. */
    private void clearGlassesSoon() {
        uiHandler.postDelayed(() -> {
            if (!glassesHeadUp) clearGlasses();
        }, 6000);
    }

    /**
     * The dashboard on the glasses, built from the live summary the Record tab
     * also shows: clock, recording time, heart rate, pulse quality, movement,
     * ring battery, delivered rate, cloud and alerts. Shown while the wearer
     * looks up and refreshed every few seconds only if something changed, so
     * the lenses do not flicker and the radio is left to the ring. The latest
     * alert goes on a last line under it.
     */
    private void showVitalsOnGlasses(com.google.gson.JsonObject data, boolean force) {
        if (data == null || debugFlags.contains("nodisplay")) return;
        // Pull, not push: nothing appears unless the wearer looks up.
        if (!glassesHeadUp && !force) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastGlassesVitalsMs < GLASSES_VITALS_INTERVAL_MS) return;
        if (!data.has("sessions") || !data.get("sessions").isJsonArray()) return;
        // The same wearable as the picture and the record screen, not whichever
        // the backend happened to list first.
        com.google.gson.JsonObject picked = LiveSummary.pickSession(data);
        for (com.google.gson.JsonElement element : data.getAsJsonArray("sessions")) {
            if (!element.isJsonObject()) continue;
            com.google.gson.JsonObject session = element.getAsJsonObject();
            if (picked != null && session != picked) continue;
            if (!session.has("ppg") || !session.get("ppg").isJsonObject()) continue;
            com.google.gson.JsonObject ppg = session.getAsJsonObject("ppg");
            Double shownHr = LiveSummary.shownHeartRate(ppg);
            String heartRate = shownHr != null ? "HR " + Math.round(shownHr) + " bpm" : "HR --";
            String movement = session.has("movement") && session.get("movement").isJsonObject()
                    ? LiveSummary.jsonString(session.getAsJsonObject("movement"), "state") : "";
            String rate = "";
            if (session.has("streams") && session.get("streams").isJsonObject()) {
                com.google.gson.JsonObject streams = session.getAsJsonObject("streams");
                if (streams.has("ppg") && streams.get("ppg").isJsonObject()) {
                    com.google.gson.JsonObject health = streams.getAsJsonObject("ppg");
                    if (health.has("effective_rate_hz") && !health.get("effective_rate_hz").isJsonNull()) {
                        rate = Math.round(health.get("effective_rate_hz").getAsDouble()) + " Hz";
                    }
                }
            }
            int alerts = session.has("active_alerts") && session.get("active_alerts").isJsonArray()
                    ? session.getAsJsonArray("active_alerts").size() : 0;
            Integer ringBattery = null;
            for (Map.Entry<String, WearableDevice> entry : host.connectedDevices().entrySet()) {
                if (WearableDeviceRegistry.profileOf(entry.getValue().getDeviceType()).isDashboardDefault()) {
                    ringBattery = host.battery(entry.getKey());
                }
            }
            String clock = new java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(new java.util.Date());
            List<String> second = new ArrayList<>();
            if (!movement.isEmpty()) second.add(movement);
            if (ringBattery != null) second.add(dashboardNoun() + " " + ringBattery + "%");
            if (!rate.isEmpty()) second.add(rate);
            // Five rows, each with something at the left edge and something at the
            // right, so the page fills the lens instead of sitting in the middle.
            String recording = host.recordingActive() ? "REC " + host.recordingElapsed() : "not recording";
            String link = host.cloudOnline() ? "cloud online" : "cloud offline";
            String ringText = ringBattery != null ? dashboardNoun() + " " + ringBattery + "%" : "";
            String alertText = alerts + (alerts == 1 ? " alert" : " alerts");
            boolean alertShown = !glassesAlertLine.isEmpty() && now - glassesAlertAtMs < GLASSES_ALERT_SHOW_MS;
            if (glassesImageMode) {
                lastGlassesVitalsMs = now;
                sendGlassesDashboardImage(buildGlassesDashboard(data));
                return;
            }
            StringBuilder text = new StringBuilder();
            text.append(columns(clock, recording))
                    .append('\n').append(columns(heartRate, LiveSummary.signalLabel(LiveSummary.jsonString(ppg, "quality"))))
                    .append('\n').append(columns(movement, ringText))
                    .append('\n').append(columns(rate, link))
                    .append('\n').append(!glassesAlertLine.isEmpty() && now - glassesAlertAtMs < GLASSES_ALERT_SHOW_MS
                            ? glassesAlertLine : columns("", alertText));
            String shown = text.toString();
            lastGlassesVitalsMs = now;
            if (shown.equals(lastGlassesText)) return;
            lastGlassesText = shown;
            showOnGlasses(shown, true);
            return;
        }
    }

    // ── Even G1 glasses ──────────────────────────────────────────────────

    /**
     * Show text on connected Even G1 glasses, so a participant learns of a
     * loose sensor without looking at the phone. Each arm drives its own lens,
     * and the G1 protocol sends to the left arm before the right; a short
     * pause stands in for waiting on the left arm's acknowledgement.
     */
    private void showOnGlasses(String text) {
        showOnGlasses(text, false);
    }

    private void showOnGlasses(String text, boolean preformatted) {
        Map<String, Object> params = new HashMap<>();
        params.put("text", text);
        if (preformatted) params.put("preformatted", true);
        sendToGlassesArms("DISPLAY_TEXT", params, true);
    }

    /** What to send to the right arm once the left has acknowledged; the latest request wins. */
    private Runnable pendingRightArmSend;
    private String pendingRightArmAfter;
    /** Give up waiting for the left arm's acknowledgement after this long. */
    private static final long GLASSES_ACK_TIMEOUT_MS = 1_500;

    /**
     * Send to the left arm, then to the right. Sending to both at once, or the
     * right 120 ms after the left, left both arms silent and then disconnected
     * within seconds. The Even G1 protocol has the left arm finish first, so the
     * right arm is sent to only after the left acknowledges (or, for commands
     * that are not acknowledged, after a fixed pause).
     */
    private void sendToGlassesArms(String command, Map<String, Object> params, boolean waitForAck) {
        WearableDevice left = null;
        WearableDevice right = null;
        String leftAddress = null;
        for (Map.Entry<String, WearableDevice> entry : host.connectedDevices().entrySet()) {
            if (!AndroidEvenGlassesDevice.TYPE.equals(entry.getValue().getDeviceType())) {
                continue;
            }
            if ("R".equals(AndroidEvenGlassesDevice.sideOf(host.deviceName(entry.getKey())))) {
                right = entry.getValue();
            } else {
                left = entry.getValue();
                leftAddress = entry.getKey();
            }
        }
        if (pendingRightArmSend != null) {
            uiHandler.removeCallbacks(pendingRightArmSend);
            pendingRightArmSend = null;
        }
        if (left != null) left.sendCommand(command, params);
        if (right == null) return;
        final WearableDevice rightArm = right;
        if (left == null) {
            rightArm.sendCommand(command, params);
            return;
        }
        final Runnable sendRight = () -> {
            pendingRightArmSend = null;
            rightArm.sendCommand(command, params);
        };
        pendingRightArmSend = sendRight;
        pendingRightArmAfter = waitForAck ? leftAddress : null;
        uiHandler.postDelayed(sendRight, waitForAck ? GLASSES_ACK_TIMEOUT_MS : GLASSES_ARM_GAP_MS * 2);
    }

    /** The left arm has taken the text: now the right arm's turn. */
    private void onGlassesTextAck(String address) {
        if (pendingRightArmSend == null || !address.equals(pendingRightArmAfter)) return;
        Runnable send = pendingRightArmSend;
        uiHandler.removeCallbacks(send);
        uiHandler.postDelayed(send, 60);
    }

    /** Debug builds: words in files/flags switch parts of the glasses code off to find what triggers what. */
    private final java.util.Set<String> debugFlags = new java.util.HashSet<>();

    private void loadDebugFlags() {
        if ((host.context().getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        try {
            java.io.File file = new java.io.File(host.context().getFilesDir(), "flags");
            if (!file.exists()) return;
            for (String word : new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8).split("\\s+")) {
                if (!word.isEmpty()) debugFlags.add(word);
            }
            AndroidEvenGlassesDevice.micOffEnabled = !debugFlags.contains("nomicoff");
            glassesImageMode = !debugFlags.contains("textonly");
            host.log("DEBUG flags: " + debugFlags);
        } catch (java.io.IOException | RuntimeException e) {
            // No flags.
        }
    }

    private android.content.BroadcastReceiver debugReceiver;

    /**
     * Debug builds only: show text on the glasses from a computer, to check
     * what the lenses display without waiting for an alert.
     *   adb shell am broadcast -a com.wearable.controller.DEBUG_GLASSES --es text "hello"
     */
    private void registerDebugReceiver() {
        if ((host.context().getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        debugReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, android.content.Intent intent) {
                if (intent.hasExtra("auto")) {
                    glassesAutoDisplay = intent.getBooleanExtra("auto", true);
                    host.log("DEBUG: automatic glasses display " + (glassesAutoDisplay ? "on" : "off"));
                    return;
                }
                if (intent.hasExtra("image")) {
                    glassesImageMode = intent.getBooleanExtra("image", true);
                    host.log("DEBUG: glasses picture " + (glassesImageMode ? "on" : "off"));
                    return;
                }
                if (intent.hasExtra("top")) GlassesImage.topMargin = intent.getIntExtra("top", 22);
                if (intent.hasExtra("bottom")) GlassesImage.bottomMargin = intent.getIntExtra("bottom", 30);
                if (intent.getBooleanExtra("demo", false)) {
                    sendGlassesDashboardImage(sampleDashboard());
                    return;
                }
                String hex = intent.getStringExtra("hex");
                String text = intent.getStringExtra("text");
                String side = intent.getStringExtra("side");
                Map<String, Object> params = new HashMap<>();
                String command;
                if (hex != null && !hex.isEmpty()) {
                    command = "RAW_HEX";
                    params.put("hex", hex);
                } else if (text != null && !text.isEmpty()) {
                    command = "DISPLAY_TEXT";
                    params.put("text", text.replace('|', '\n'));
                    if (intent.getBooleanExtra("raw", false)) params.put("preformatted", true);
                    if (intent.getBooleanExtra("unicode", false)) params.put("unicode", true);
                    if (intent.hasExtra("screen")) params.put("screen", intent.getIntExtra("screen", 0x71));
                } else {
                    return;
                }
                if (side == null || side.isEmpty()) {
                    host.log("DEBUG: " + command + " to both arms, left first");
                    sendToGlassesArms(command, params, "DISPLAY_TEXT".equals(command));
                    return;
                }
                for (Map.Entry<String, WearableDevice> entry : host.connectedDevices().entrySet()) {
                    if (!AndroidEvenGlassesDevice.TYPE.equals(entry.getValue().getDeviceType())) continue;
                    String arm = AndroidEvenGlassesDevice.sideOf(host.deviceName(entry.getKey()));
                    if (!side.equalsIgnoreCase(arm)) continue;
                    host.log("DEBUG: " + command + " to arm " + arm);
                    entry.getValue().sendCommand(command, params);
                }
            }
        };
        android.content.IntentFilter filter = new android.content.IntentFilter("com.wearable.controller.DEBUG_GLASSES");
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            host.context().registerReceiver(debugReceiver, filter, android.content.Context.RECEIVER_EXPORTED);
        } else {
            host.context().registerReceiver(debugReceiver, filter);
        }
    }
}
