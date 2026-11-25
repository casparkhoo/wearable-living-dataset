package com.wearable.controller;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Setup link parsing and validation (pure Java). */
public class SetupLinkTest {

    private static final String BACKEND = "https%3A%2F%2Fapi.example.org";
    private static final String BROKER = "mqtts%3A%2F%2Fmq.example.org%3A8883";

    @Test
    public void parsesHttpsForm() {
        SetupLink l = SetupLink.parse("https://console.example.org/setup?backend=" + BACKEND + "&broker=" + BROKER);
        assertTrue(l.error, l.isValid());
        assertEquals("https://api.example.org", l.backend);
        assertEquals("mqtts://mq.example.org:8883", l.broker);
        assertEquals("api.example.org", l.backendHost());
        assertEquals("mq.example.org", l.brokerHost());
    }

    @Test
    public void parsesSchemeForm() {
        SetupLink l = SetupLink.parse("wearablesetup://configure?backend=" + BACKEND);
        assertTrue(l.error, l.isValid());
        assertEquals("https://api.example.org", l.backend);
        assertEquals("", l.broker);
    }

    @Test
    public void brokerMayUseWss() {
        SetupLink l = SetupLink.parse("wearablesetup://configure?backend=" + BACKEND + "&broker=wss%3A%2F%2Fh.example.org%2Fmqtt");
        assertTrue(l.isValid());
        assertEquals("wss://h.example.org/mqtt", l.broker);
        assertFalse(SetupLink.parse("wearablesetup://configure?backend=" + BACKEND + "&broker=ws%3A%2F%2Fh%2Fmqtt").isValid());
    }

    @Test
    public void brokerMayUseSsl() {
        SetupLink l = SetupLink.parse("wearablesetup://configure?backend=" + BACKEND + "&broker=ssl%3A%2F%2Fh%3A8883");
        assertTrue(l.error, l.isValid());
        assertEquals("ssl://h:8883", l.broker);
    }

    @Test
    public void stripsTrailingSlashAndIgnoresUnknownParams() {
        SetupLink l = SetupLink.parse("wearablesetup://configure?v=2&x=1&backend=https%3A%2F%2Fapi.example.org%2F&future");
        assertTrue(l.error, l.isValid());
        assertEquals("https://api.example.org", l.backend);
    }

    @Test
    public void rejectsNonHttpsBackend() {
        assertFalse(SetupLink.parse("wearablesetup://configure?backend=http%3A%2F%2Fapi.example.org").isValid());
        assertFalse(SetupLink.parse("wearablesetup://configure?backend=ftp%3A%2F%2Fapi.example.org").isValid());
        assertFalse(SetupLink.parse("wearablesetup://configure?backend=api.example.org").isValid());
    }

    @Test
    public void rejectsBadBroker() {
        assertFalse(SetupLink.parse("wearablesetup://configure?backend=" + BACKEND + "&broker=tcp%3A%2F%2Fh%3A1883").isValid());
        assertFalse(SetupLink.parse("wearablesetup://configure?backend=" + BACKEND + "&broker=http%3A%2F%2Fh").isValid());
    }

    @Test
    public void rejectsMissingBackendAndWrongTarget() {
        assertNotNull(SetupLink.parse("wearablesetup://configure?broker=" + BROKER).error);
        assertNotNull(SetupLink.parse("wearablesetup://other?backend=" + BACKEND).error);
        assertNotNull(SetupLink.parse("https://console.example.org/login?backend=" + BACKEND).error);
        assertNotNull(SetupLink.parse("http://console.example.org/setup?backend=" + BACKEND).error);
        assertNotNull(SetupLink.parse("mailto:a@b.c").error);
        assertNotNull(SetupLink.parse("").error);
        assertNotNull(SetupLink.parse(null).error);
    }

    @Test
    public void rejectsCredentialsInUrl() {
        assertFalse(SetupLink.parse("wearablesetup://configure?backend=https%3A%2F%2Fu%3Ap%40api.example.org").isValid());
    }

    @Test
    public void fromInputAcceptsBareAddressAndLinks() {
        SetupLink bare = SetupLink.fromInput("api.example.org/");
        assertTrue(bare.error, bare.isValid());
        assertEquals("https://api.example.org", bare.backend);
        assertEquals("", bare.broker);
        SetupLink link = SetupLink.fromInput("  wearablesetup://configure?backend=" + BACKEND + "&broker=" + BROKER + " ");
        assertTrue(link.error, link.isValid());
        assertEquals("mqtts://mq.example.org:8883", link.broker);
        SetupLink web = SetupLink.fromInput("https://console.example.org/setup?backend=" + BACKEND);
        assertTrue(web.error, web.isValid());
        assertEquals("https://api.example.org", web.backend);
    }

    @Test
    public void fromInputRejectsHttpAndEmpty() {
        assertFalse(SetupLink.fromInput("http://api.example.org").isValid());
        assertFalse(SetupLink.fromInput("   ").isValid());
        assertFalse(SetupLink.fromInput(null).isValid());
    }
}
