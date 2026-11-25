package com.wearable.controller;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.zip.GZIPInputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** The merge and compression rules of the MQTT outbox, without a broker. */
public class OutboundBatchTest {
    private static OutboundBatch batch(String topic, String payload, int qos, int samples) {
        return new OutboundBatch(topic, payload, qos, samples);
    }

    private static String gunzip(byte[] bytes) throws Exception {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void mergesBatchesForTheSameTopicAndQosInOrder() {
        OutboundBatch a = batch("t/s", "[{\"i\":1},{\"i\":2}]", 2, 2);
        OutboundBatch b = batch("t/s", "[{\"i\":3}]", 2, 1);
        OutboundBatch c = batch("t/s", "[{\"i\":4}]", 2, 1);
        OutboundBatch merged = OutboundBatch.merge(a, Arrays.asList(b, c));
        assertEquals("[{\"i\":1},{\"i\":2},{\"i\":3},{\"i\":4}]", merged.payload);
        assertEquals(4, merged.samples);
        assertEquals("t/s", merged.topic);
        assertEquals(2, merged.qos);
    }

    @Test
    public void mergeWithNoFollowersReturnsTheSameMessage() {
        OutboundBatch a = batch("t", "[1]", 1, 1);
        assertSame(a, OutboundBatch.merge(a, Collections.<OutboundBatch>emptyList()));
    }

    @Test
    public void onlyArraysOnTheSameTopicAndQosMerge() {
        OutboundBatch first = batch("t/a", "[1]", 2, 1);
        assertTrue(OutboundBatch.canMerge(first, batch("t/a", "[2]", 2, 1)));
        assertFalse(OutboundBatch.canMerge(first, batch("t/b", "[2]", 2, 1)));
        assertFalse(OutboundBatch.canMerge(first, batch("t/a", "[2]", 1, 1)));
        assertFalse(OutboundBatch.canMerge(first, batch("t/a", "{\"single\":1}", 2, 1)));
        assertFalse(OutboundBatch.canMerge(batch("t/a", "{\"single\":1}", 2, 1), batch("t/a", "[2]", 2, 1)));
        assertFalse(OutboundBatch.canMerge(first, null));
    }

    @Test
    public void mergedMessagesAreLargerWhenTheBackendTakesGzip() {
        assertEquals(1000, OutboundBatch.maxSamples(false));
        assertEquals(3000, OutboundBatch.maxSamples(true));
    }

    @Test
    public void batchesAreCompressedOnlyWhenTheBackendAsksForIt() throws Exception {
        String json = "[{\"a\":1},{\"a\":2},{\"a\":3},{\"a\":4},{\"a\":5},{\"a\":6},{\"a\":7},{\"a\":8}]";
        OutboundBatch batch = batch("t", json, 2, 8);
        byte[] plain = batch.encode(false);
        byte[] zipped = batch.encode(true);
        assertArrayEquals(json.getBytes(StandardCharsets.UTF_8), plain);
        assertNotEquals(json.length(), zipped.length);
        assertEquals((byte) 0x1f, zipped[0]);
        assertEquals((byte) 0x8b, zipped[1]);
        assertEquals(json, gunzip(zipped));
    }

    @Test
    public void singleSamplePayloadsAreNeverCompressed() {
        String json = "{\"sensor\":\"HEART_RATE\"}";
        assertArrayEquals(json.getBytes(StandardCharsets.UTF_8), batch("t", json, 1, 1).encode(true));
    }

    @Test
    public void nonAsciiSurvivesTheRoundTrip() throws Exception {
        String json = "[{\"name\":\"τ-Ring\"}]";
        assertEquals(json, gunzip(batch("t", json, 1, 1).encode(true)));
        assertArrayEquals(json.getBytes(StandardCharsets.UTF_8), batch("t", json, 1, 1).encode(false));
    }
}
