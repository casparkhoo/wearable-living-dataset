package com.wearable.controller;

import com.google.gson.JsonObject;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RestApiClientTest {
    @Test
    public void guidedRecordingCreatesAndStartsExperimentInOneRequest() {
        JsonObject body = RestApiClient.buildCreateExperimentBody(
                "desk study", "typing protocol", 1234L, true);

        assertEquals("desk study", body.get("experiment_label").getAsString());
        assertEquals("typing protocol", body.get("protocol_name").getAsString());
        assertEquals(1234L, body.get("client_timestamp_ms").getAsLong());
        assertTrue(body.get("start_immediately").getAsBoolean());
    }

    @Test
    public void optionalProtocolIsOmittedWhenBlank() {
        JsonObject body = RestApiClient.buildCreateExperimentBody("study", "  ", 1L, false);

        assertFalse(body.has("protocol_name"));
        assertFalse(body.get("start_immediately").getAsBoolean());
    }
}
