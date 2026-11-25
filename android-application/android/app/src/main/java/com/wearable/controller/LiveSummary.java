package com.wearable.controller;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Readers for the backend's live summary (the {@code live.summary} message): which
 * recording to describe, the heart rate to show and the words for its quality.
 * Pure Java, shared by the Record tab and the head-up display, and unit-tested.
 */
public final class LiveSummary {
    private LiveSummary() {
    }

    public static String jsonString(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    /**
     * Which live recording the dashboard describes when several are running: the one
     * with a heart rate, then a good signal, then the wearable whose profile asks to be
     * preferred, so the wearer sees the most trustworthy reading rather than whichever
     * was listed first.
     */
    public static JsonObject pickSession(JsonObject data) {
        if (data == null || !data.has("sessions") || !data.get("sessions").isJsonArray()) return null;
        JsonObject best = null;
        double bestScore = -1;
        for (JsonElement element : data.getAsJsonArray("sessions")) {
            if (!element.isJsonObject()) continue;
            JsonObject session = element.getAsJsonObject();
            if (!session.has("ppg") || !session.get("ppg").isJsonObject()) continue;
            JsonObject ppg = session.getAsJsonObject("ppg");
            double score = 0;
            // A readable pulse by the rules engine's standard, not merely a
            // PaPaGei estimate: PaPaGei gives a number for any wearable with a
            // trace of pulse, which made the glasses pick an earbud with a poor,
            // clipped signal over a ring with a clean one.
            if (ppg.has("hr_bpm") && !ppg.get("hr_bpm").isJsonNull()) score += 4;
            String quality = jsonString(ppg, "quality");
            score += "good".equals(quality) ? 2 : "fair".equals(quality) ? 1 : 0;
            DeviceProfile profile = WearableDeviceRegistry.shared().getProfile(jsonString(session, "device_type"));
            if (profile != null) score += profile.getDashboardPriority();
            if (score > bestScore) {
                bestScore = score;
                best = session;
            }
        }
        return best;
    }

    /** Short wording of why there is no heart rate, for the head-up display. */
    public static String shortHrReason(String reason) {
        switch (reason) {
            case "no_pulse": return "no pulse found";
            // Ordinary in everyday wear: say what is happening, not that it is wrong.
            case "moving": return "reading paused";
            default: return "signal weak";
        }
    }

    /** Signal quality in neutral words: "poor" read as a fault during ordinary wear. */
    public static String signalLabel(String quality) {
        switch (quality) {
            case "good": return "signal good";
            case "fair": return "signal ok";
            default: return "signal weak";
        }
    }

    /**
     * The heart rate to show: the backend's display_hr_bpm (PaPaGei when it
     * prefers it, otherwise the rule-based estimate), or hr_bpm from an older
     * backend. Null when there is none.
     */
    public static Double shownHeartRate(JsonObject ppg) {
        if (ppg == null) return null;
        String key = ppg.has("display_hr_bpm") ? "display_hr_bpm" : "hr_bpm";
        if (!ppg.has(key) || ppg.get(key).isJsonNull()) return null;
        return ppg.get(key).getAsDouble();
    }

    /** " (PaPaGei)" or "" for labelling shownHeartRate. */
    public static String shownHeartRateSource(JsonObject ppg) {
        return ppg != null && "papagei".equals(jsonString(ppg, "hr_source")) ? " (PaPaGei)" : "";
    }
}
