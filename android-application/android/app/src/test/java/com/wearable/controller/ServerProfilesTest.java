package com.wearable.controller;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class ServerProfilesTest {

    private static List<ServerProfile> two() {
        List<ServerProfile> l = new ArrayList<>();
        l.add(new ServerProfile("Local", "https://pc.example.ts.net", ""));
        l.add(new ServerProfile("Lab", "https://lab.example.org", "mqtts://lab.example.org:8883"));
        return l;
    }

    @Test
    public void nameValidation() {
        List<ServerProfile> l = two();
        assertNull(ServerProfiles.validateName("Home", l));
        assertNotNull(ServerProfiles.validateName("", l));
        assertNotNull(ServerProfiles.validateName("   ", l));
        assertNotNull(ServerProfiles.validateName(null, l));
        assertNotNull(ServerProfiles.validateName("local", l));
        assertNotNull(ServerProfiles.validateName(" Lab ", l));
        assertNotNull(ServerProfiles.validateName("cloud", l));
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 41; i++) longName.append('a');
        assertNotNull(ServerProfiles.validateName(longName.toString(), l));
    }

    @Test
    public void limitOnProfileCount() {
        List<ServerProfile> l = new ArrayList<>();
        for (int i = 0; i < ServerProfiles.MAX_PROFILES; i++) {
            l.add(new ServerProfile("p" + i, "https://h" + i + ".example.org", ""));
        }
        assertNotNull(ServerProfiles.validateName("one more", l));
    }

    @Test
    public void defaultNameIsHostThenNumbered() {
        List<ServerProfile> l = two();
        assertEquals("api.example.org", ServerProfiles.defaultName("api.example.org", l));
        l.add(new ServerProfile("api.example.org", "https://api.example.org", ""));
        assertEquals("api.example.org 2", ServerProfiles.defaultName("api.example.org", l));
        l.add(new ServerProfile("api.example.org 2", "https://api2.example.org", ""));
        assertEquals("api.example.org 3", ServerProfiles.defaultName("api.example.org", l));
        assertEquals("Server", ServerProfiles.defaultName("", l));
        assertEquals("Server", ServerProfiles.defaultName(null, l));
    }

    @Test
    public void defaultNameNeverExceedsLimitOrReserved() {
        StringBuilder host = new StringBuilder();
        for (int i = 0; i < 60; i++) host.append('h');
        List<ServerProfile> none = new ArrayList<>();
        String name = ServerProfiles.defaultName(host.toString(), none);
        assertTrue(name.length() <= ServerProfiles.MAX_NAME_LENGTH);
        assertNull(ServerProfiles.validateName(name, none));
        assertEquals("Cloud 2", ServerProfiles.defaultName("Cloud", none));
    }

    @Test
    public void withAddsOnlyValidProfilesAndDoesNotMutate() {
        List<ServerProfile> l = two();
        List<ServerProfile> added = ServerProfiles.with(l, new ServerProfile("Home", "https://home.example.org", ""));
        assertEquals(2, l.size());
        assertEquals(3, added.size());
        assertEquals(2, ServerProfiles.with(l, new ServerProfile("Local", "https://x.example.org", "")).size());
        assertEquals(2, ServerProfiles.with(l, new ServerProfile("Bad", "http://insecure.example.org", "")).size());
        assertEquals(2, ServerProfiles.with(l, new ServerProfile("Bad2", "https://ok.example.org", "ws://nope")).size());
    }

    @Test
    public void withoutRemovesByNameIgnoringCase() {
        List<ServerProfile> out = ServerProfiles.without(two(), "LOCAL");
        assertEquals(1, out.size());
        assertEquals("Lab", out.get(0).name);
        assertEquals(2, ServerProfiles.without(two(), "missing").size());
    }

    @Test
    public void findByServerMatchesBackendAndBroker() {
        List<ServerProfile> l = two();
        assertEquals("Lab", ServerProfiles.findByServer(l, "https://lab.example.org", "mqtts://lab.example.org:8883").name);
        assertNull(ServerProfiles.findByServer(l, "https://lab.example.org", ""));
        assertEquals("Local", ServerProfiles.findByServer(l, "https://pc.example.ts.net", "").name);
    }

    @Test
    public void jsonRoundTrip() {
        List<ServerProfile> back = ServerProfiles.fromJson(ServerProfiles.toJson(two()));
        assertEquals(2, back.size());
        assertEquals("Lab", back.get(1).name);
        assertEquals("mqtts://lab.example.org:8883", back.get(1).broker);
    }

    @Test
    public void corruptStorageIsTolerated() {
        assertTrue(ServerProfiles.fromJson(null).isEmpty());
        assertTrue(ServerProfiles.fromJson("").isEmpty());
        assertTrue(ServerProfiles.fromJson("not json").isEmpty());
        assertTrue(ServerProfiles.fromJson("{\"a\":1}").isEmpty());
        String json = "[{\"name\":\"ok\",\"backend\":\"https://a.example.org\",\"broker\":\"\"},"
                + "{\"name\":\"ok\",\"backend\":\"https://b.example.org\"},"
                + "{\"name\":\"insecure\",\"backend\":\"http://c.example.org\"},"
                + "{\"name\":\"\",\"backend\":\"https://d.example.org\"},5]";
        List<ServerProfile> out = ServerProfiles.fromJson(json);
        assertEquals(1, out.size());
        assertEquals("ok", out.get(0).name);
    }

    @Test
    public void activeLabel() {
        List<ServerProfile> l = two();
        assertEquals("Cloud", ServerProfiles.activeLabel("Local", l, true, "api.example.org"));
        assertEquals("Local", ServerProfiles.activeLabel("local", l, false, "pc.example.ts.net"));
        assertEquals("other.example.org", ServerProfiles.activeLabel("", l, false, "other.example.org"));
        assertEquals("other.example.org", ServerProfiles.activeLabel("deleted", l, false, "other.example.org"));
        assertEquals("Custom server", ServerProfiles.activeLabel("", l, false, ""));
    }

    @Test
    public void profileLinkIsValidated() {
        assertTrue(new ServerProfile("a", "https://x.example.org", "mqtts://x.example.org:8883").toLink().isValid());
        assertFalse(new ServerProfile("a", "ftp://x", "").toLink().isValid());
        assertEquals("x.example.org", new ServerProfile("a", "https://x.example.org", "").backendHost());
    }
}
