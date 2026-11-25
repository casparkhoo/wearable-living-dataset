package com.wearable.controller;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Rules for the list of saved server profiles: names, uniqueness, default names and the
 * stored form. Pure Java (no Android types) so it is unit tested on the JVM; the
 * SharedPreferences access lives in {@link InstanceConfig}. Every method returns a new
 * list and never changes its argument.
 */
public final class ServerProfiles {

    /** The profile for the address built into the app. Always present, never stored. */
    public static final String BUILT_IN_NAME = "Cloud";
    public static final int MAX_NAME_LENGTH = 40;
    public static final int MAX_PROFILES = 20;

    private ServerProfiles() {}

    /** Null when the name can be used for a new profile, otherwise what is wrong with it. */
    public static String validateName(String name, List<ServerProfile> existing) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) return "Give the profile a name.";
        if (n.length() > MAX_NAME_LENGTH) return "Use a name of at most " + MAX_NAME_LENGTH + " characters.";
        if (n.equalsIgnoreCase(BUILT_IN_NAME)) return "\"" + BUILT_IN_NAME + "\" is the built-in server; pick another name.";
        for (ServerProfile p : existing) {
            if (p.name.equalsIgnoreCase(n)) return "A profile called \"" + p.name + "\" already exists.";
        }
        if (existing.size() >= MAX_PROFILES) return "Delete a profile first (at most " + MAX_PROFILES + " are kept).";
        return null;
    }

    /** The host, made unique ("host", "host 2", ...) and never the reserved name. */
    public static String defaultName(String host, List<ServerProfile> existing) {
        String base = host == null || host.trim().isEmpty() ? "Server" : host.trim();
        if (base.length() > MAX_NAME_LENGTH) base = base.substring(0, MAX_NAME_LENGTH);
        String candidate = base;
        int n = 2;
        while (!nameFree(candidate, existing)) {
            String suffix = " " + n++;
            int keep = Math.min(base.length(), MAX_NAME_LENGTH - suffix.length());
            candidate = base.substring(0, keep) + suffix;
        }
        return candidate;
    }

    private static boolean nameFree(String candidate, List<ServerProfile> existing) {
        if (candidate.equalsIgnoreCase(BUILT_IN_NAME)) return false;
        for (ServerProfile p : existing) if (p.name.equalsIgnoreCase(candidate)) return false;
        return true;
    }

    public static ServerProfile find(List<ServerProfile> list, String name) {
        if (name == null) return null;
        for (ServerProfile p : list) if (p.name.equalsIgnoreCase(name.trim())) return p;
        return null;
    }

    /** A saved profile that points at exactly this server, or null. */
    public static ServerProfile findByServer(List<ServerProfile> list, String backend, String broker) {
        for (ServerProfile p : list) if (p.sameServer(backend, broker)) return p;
        return null;
    }

    /** The list with a new profile appended; unchanged when the name or address is not valid. */
    public static List<ServerProfile> with(List<ServerProfile> list, ServerProfile profile) {
        List<ServerProfile> out = new ArrayList<>(list);
        if (validateName(profile.name, list) != null || !profile.toLink().isValid()) return out;
        out.add(profile);
        return out;
    }

    public static List<ServerProfile> without(List<ServerProfile> list, String name) {
        List<ServerProfile> out = new ArrayList<>();
        for (ServerProfile p : list) if (!p.name.equalsIgnoreCase(name == null ? "" : name.trim())) out.add(p);
        return out;
    }

    /** How the current server is called: the profile's name, or the host when it has none. */
    public static String activeLabel(String activeName, List<ServerProfile> list, boolean usingBuiltIn, String host) {
        if (usingBuiltIn) return BUILT_IN_NAME;
        ServerProfile p = find(list, activeName);
        if (p != null) return p.name;
        return host == null || host.isEmpty() ? "Custom server" : host;
    }

    public static String toJson(List<ServerProfile> list) {
        JsonArray array = new JsonArray();
        for (ServerProfile p : list) {
            JsonObject o = new JsonObject();
            o.addProperty("name", p.name);
            o.addProperty("backend", p.backend);
            o.addProperty("broker", p.broker);
            array.add(o);
        }
        return array.toString();
    }

    /** Tolerant: bad text, bad entries and duplicate names are dropped, never thrown. */
    public static List<ServerProfile> fromJson(String json) {
        List<ServerProfile> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return out;
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonArray()) return out;
            for (JsonElement e : root.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                String name = str(o, "name");
                ServerProfile p = new ServerProfile(name, str(o, "backend"), str(o, "broker"));
                if (validateName(name, out) != null || !p.toLink().isValid()) continue;
                out.add(p);
            }
        } catch (RuntimeException ignored) {
            // corrupt storage: keep what was read so far
        }
        return out;
    }

    private static String str(JsonObject o, String key) {
        JsonElement v = o.get(key);
        return v == null || !v.isJsonPrimitive() ? "" : v.getAsString();
    }

    /** Names sorted for display, case-insensitively. */
    public static List<ServerProfile> sorted(List<ServerProfile> list) {
        List<ServerProfile> out = new ArrayList<>(list);
        Collections.sort(out, (a, b) -> a.name.toLowerCase(Locale.ROOT).compareTo(b.name.toLowerCase(Locale.ROOT)));
        return out;
    }
}
