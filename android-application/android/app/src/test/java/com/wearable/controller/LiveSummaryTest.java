package com.wearable.controller;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/** The live-summary readers shared by the Record tab and the head-up display. */
public class LiveSummaryTest {
    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    public void sessionWithAHeartRateBeatsOneWithout() {
        JsonObject data = json("{\"sessions\":["
                + "{\"device_type\":\"OpenEarable\",\"ppg\":{\"quality\":\"good\"}},"
                + "{\"device_type\":\"HeartRateMonitor\",\"ppg\":{\"hr_bpm\":61,\"quality\":\"fair\"}}]}");
        assertEquals("HeartRateMonitor", LiveSummary.jsonString(LiveSummary.pickSession(data), "device_type"));
    }

    @Test
    public void betterSignalWinsWhenBothHaveAHeartRate() {
        JsonObject data = json("{\"sessions\":["
                + "{\"device_type\":\"OpenEarable\",\"ppg\":{\"hr_bpm\":70,\"quality\":\"fair\"}},"
                + "{\"device_type\":\"HeartRateMonitor\",\"ppg\":{\"hr_bpm\":71,\"quality\":\"good\"}}]}");
        assertEquals("HeartRateMonitor", LiveSummary.jsonString(LiveSummary.pickSession(data), "device_type"));
    }

    @Test
    public void theProfilePriorityBreaksATie() {
        // The ring's profile adds 0.5 so that equal readings describe the ring.
        JsonObject data = json("{\"sessions\":["
                + "{\"device_type\":\"OpenEarable\",\"ppg\":{\"hr_bpm\":70,\"quality\":\"good\"}},"
                + "{\"device_type\":\"OpenRing\",\"ppg\":{\"hr_bpm\":70,\"quality\":\"good\"}}]}");
        assertEquals("OpenRing", LiveSummary.jsonString(LiveSummary.pickSession(data), "device_type"));
    }

    @Test
    public void firstSessionWinsATieBetweenUnknownTypes() {
        JsonObject data = json("{\"sessions\":["
                + "{\"device_type\":\"A\",\"ppg\":{\"hr_bpm\":70,\"quality\":\"good\"}},"
                + "{\"device_type\":\"B\",\"ppg\":{\"hr_bpm\":70,\"quality\":\"good\"}}]}");
        assertSame(data.getAsJsonArray("sessions").get(0), LiveSummary.pickSession(data));
    }

    @Test
    public void nothingToPickFromGivesNull() {
        assertNull(LiveSummary.pickSession(null));
        assertNull(LiveSummary.pickSession(json("{}")));
        assertNull(LiveSummary.pickSession(json("{\"sessions\":[{\"device_type\":\"A\"}]}")));
    }

    @Test
    public void displayedHeartRatePrefersTheBackendsChoice() {
        assertEquals(64.0, LiveSummary.shownHeartRate(json("{\"hr_bpm\":70,\"display_hr_bpm\":64}")), 1e-9);
        assertEquals(70.0, LiveSummary.shownHeartRate(json("{\"hr_bpm\":70}")), 1e-9);
        assertNull(LiveSummary.shownHeartRate(json("{\"hr_bpm\":null}")));
        assertNull(LiveSummary.shownHeartRate(json("{\"display_hr_bpm\":null,\"hr_bpm\":70}")));
        assertNull(LiveSummary.shownHeartRate(null));
    }

    @Test
    public void wordsForSignalAndMissingHeartRate() {
        assertEquals("signal good", LiveSummary.signalLabel("good"));
        assertEquals("signal ok", LiveSummary.signalLabel("fair"));
        assertEquals("signal weak", LiveSummary.signalLabel("poor"));
        assertEquals("signal weak", LiveSummary.signalLabel(""));
        assertEquals("no pulse found", LiveSummary.shortHrReason("no_pulse"));
        assertEquals("reading paused", LiveSummary.shortHrReason("moving"));
        assertEquals("signal weak", LiveSummary.shortHrReason("other"));
    }

    @Test
    public void sourceLabelOnlyForPapagei() {
        assertEquals(" (PaPaGei)", LiveSummary.shownHeartRateSource(json("{\"hr_source\":\"papagei\"}")));
        assertEquals("", LiveSummary.shownHeartRateSource(json("{\"hr_source\":\"rules\"}")));
        assertEquals("", LiveSummary.shownHeartRateSource(null));
    }

    @Test
    public void missingStringsReadAsEmpty() {
        assertEquals("", LiveSummary.jsonString(json("{\"a\":null}"), "a"));
        assertEquals("", LiveSummary.jsonString(json("{}"), "a"));
        assertEquals("x", LiveSummary.jsonString(json("{\"a\":\"x\"}"), "a"));
    }
}
