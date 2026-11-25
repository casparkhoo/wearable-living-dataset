package com.wearable.controller;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;

/**
 * Parser for the server setup link the console produces (docs/APK_DISTRIBUTION.md).
 *
 * <pre>
 * https://console-host/setup?backend=https%3A%2F%2Fapi.example.org&amp;broker=mqtts%3A%2F%2Fhost%3A8883
 * wearablesetup://configure?backend=...&amp;broker=...
 * </pre>
 *
 * {@code backend} is required and must be https. {@code broker} is optional and must be
 * mqtts, ssl or wss. Unknown parameters are ignored. A link is untrusted input: this class only
 * validates; the caller must show {@link #backendHost()} and ask before saving.
 * Pure Java, no Android types, so it is unit tested on the JVM.
 */
public final class SetupLink {

    public final String backend;
    /** Empty when the link carried no broker. */
    public final String broker;
    /** Null when parsing succeeded. */
    public final String error;

    private SetupLink(String backend, String broker, String error) {
        this.backend = backend;
        this.broker = broker;
        this.error = error;
    }

    public boolean isValid() {
        return error == null;
    }

    public String backendHost() {
        return hostOf(backend);
    }

    public String brokerHost() {
        return hostOf(broker);
    }

    private static String hostOf(String url) {
        if (url == null || url.isEmpty()) return "";
        try {
            String host = new URI(url).getHost();
            return host == null ? "" : host;
        } catch (URISyntaxException e) {
            return "";
        }
    }

    private static SetupLink fail(String message) {
        return new SetupLink("", "", message);
    }

    /** Parse a pasted or opened setup link. */
    public static SetupLink parse(String text) {
        if (text == null || text.trim().isEmpty()) return fail("Paste a setup link.");
        URI uri;
        try {
            uri = new URI(text.trim());
        } catch (URISyntaxException e) {
            return fail("That is not a valid link.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if ("wearablesetup".equals(scheme)) {
            String target = uri.getHost() == null ? uri.getAuthority() : uri.getHost();
            if (!"configure".equalsIgnoreCase(target)) {
                return fail("Not a setup link (expected wearablesetup://configure).");
            }
        } else if ("https".equals(scheme)) {
            if (uri.getHost() == null || uri.getHost().isEmpty()) return fail("The link has no host.");
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (path.endsWith("/") && path.length() > 1) path = path.substring(0, path.length() - 1);
            if (!"/setup".equals(path)) return fail("Not a setup link (the path must be /setup).");
        } else {
            return fail("Not a setup link (use https://.../setup or wearablesetup://configure).");
        }
        String rawQuery = uri.getRawQuery();
        String backend = null;
        String broker = null;
        if (rawQuery != null) {
            for (String pair : rawQuery.split("&")) {
                int eq = pair.indexOf('=');
                String key = decode(eq < 0 ? pair : pair.substring(0, eq));
                String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
                if (key == null || value == null) return fail("The link is not correctly encoded.");
                if ("backend".equals(key) && backend == null) backend = value.trim();
                else if ("broker".equals(key) && broker == null) broker = value.trim();
                // anything else is ignored (forward compatibility)
            }
        }
        if (backend == null || backend.isEmpty()) return fail("The link has no server address (backend).");
        String backendError = checkUrl(backend, "https", "https", "server address");
        if (backendError != null) return fail(backendError);
        if (broker != null && !broker.isEmpty()) {
            String brokerError = checkBrokerUrl(broker);
            if (brokerError != null) return fail(brokerError);
        } else {
            broker = "";
        }
        return new SetupLink(stripTrailingSlash(backend), stripTrailingSlash(broker), null);
    }

    /**
     * Accept whatever a person pastes into the "server address" box: a full setup link, or a
     * bare https address (the scheme is added when missing). Backend only in the latter case.
     */
    public static SetupLink fromInput(String input) {
        if (input == null || input.trim().isEmpty()) return fail("Enter a server address or paste a setup link.");
        String trimmed = input.trim();
        String lower = trimmed.toLowerCase();
        if (lower.startsWith("wearablesetup://") || (lower.startsWith("https://") && lower.contains("/setup?"))) {
            return parse(trimmed);
        }
        if (trimmed.contains("://") && !lower.startsWith("https://")) {
            return fail("The server address must start with https://");
        }
        if (!trimmed.contains("://")) trimmed = "https://" + trimmed;
        String err = checkUrl(trimmed, "https", "https", "server address");
        if (err != null) return fail(err);
        return new SetupLink(stripTrailingSlash(trimmed), "", null);
    }

    /** A broker is reached over TLS: mqtts, ssl, or wss (the websocket form every deployment of this project serves at /mqtt). */
    private static String checkBrokerUrl(String value) {
        String error = checkUrl(value, "mqtts", "ssl", "broker address");
        if (error == null) return null;
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            return error;
        }
        if ("wss".equalsIgnoreCase(uri.getScheme())) {
            if (uri.getHost() == null || uri.getHost().isEmpty()) return "The broker address has no host.";
            if (uri.getUserInfo() != null) return "The broker address must not contain a user name or password.";
            return null;
        }
        return "The broker address must start with mqtts://, ssl:// or wss://";
    }

    private static String checkUrl(String value, String schemeA, String schemeB, String what) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            return "The " + what + " is not a valid URL.";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals(schemeA) && !scheme.equals(schemeB)) {
            return schemeA.equals(schemeB)
                    ? "The " + what + " must start with " + schemeA + "://"
                    : "The " + what + " must start with " + schemeA + ":// or " + schemeB + "://";
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) return "The " + what + " has no host.";
        if (uri.getUserInfo() != null) return "The " + what + " must not contain a user name or password.";
        return null;
    }

    private static String stripTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return null;
        }
    }
}
