package com.wearable.controller;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * One message waiting in the MQTT outbox, and the pure rules for merging and encoding
 * messages. MQTTManager owns the queue and the threads; the decisions live here so
 * they can be tested without Android or a broker.
 */
public final class OutboundBatch {
    /** Uncompressed, about 1,000 samples stays under the backend's 1 MB message limit. */
    public static final int MAX_SAMPLES_PLAIN = 1000;
    public static final int MAX_SAMPLES_GZIP = 3000;

    public final String topic;
    public final String payload;
    public final int qos;
    public final int samples;

    public OutboundBatch(String topic, String payload, int qos, int samples) {
        this.topic = topic;
        this.payload = payload;
        this.qos = qos;
        this.samples = samples;
    }

    /** A JSON array of samples: the only kind of payload that is merged or compressed. */
    public boolean isArray() {
        return payload.startsWith("[");
    }

    /** The most samples one merged message may carry. */
    public static int maxSamples(boolean gzipIngest) {
        return gzipIngest ? MAX_SAMPLES_GZIP : MAX_SAMPLES_PLAIN;
    }

    /**
     * Whether {@code next}, waiting behind {@code first}, may be merged into it: same
     * topic and QoS, both JSON arrays. Order is never changed because only the next
     * message in line is considered.
     */
    public static boolean canMerge(OutboundBatch first, OutboundBatch next) {
        return next != null && first.isArray() && next.isArray()
                && next.topic.equals(first.topic) && next.qos == first.qos;
    }

    /** {@code first} followed by {@code followers}, as one JSON array; {@code first} itself when there are none. */
    public static OutboundBatch merge(OutboundBatch first, List<OutboundBatch> followers) {
        if (followers.isEmpty()) return first;
        StringBuilder merged = new StringBuilder(first.payload.length() * 4);
        merged.append(first.payload, 0, first.payload.length() - 1);
        int samples = first.samples;
        for (OutboundBatch next : followers) {
            merged.append(',').append(next.payload, 1, next.payload.length());
            merged.setLength(merged.length() - 1);
            samples += next.samples;
        }
        merged.append(']');
        return new OutboundBatch(first.topic, merged.toString(), first.qos, samples);
    }

    /** The bytes to publish: compressed when the backend takes gzip and this is a batch. */
    public byte[] encode(boolean gzipIngest) {
        byte[] body = null;
        if (gzipIngest && isArray()) body = gzip(payload);
        if (body == null) body = payload.getBytes(StandardCharsets.UTF_8);
        return body;
    }

    public static byte[] gzip(String text) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(text.length() / 4);
            try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }
}
