package com.wearable.controller;

/** A named server: a backend address and an optional broker address. Immutable, pure Java. */
public final class ServerProfile {

    public final String name;
    public final String backend;
    /** Empty when the profile has no broker. */
    public final String broker;

    public ServerProfile(String name, String backend, String broker) {
        this.name = name == null ? "" : name.trim();
        this.backend = backend == null ? "" : backend;
        this.broker = broker == null ? "" : broker;
    }

    /** The validated link this profile stands for. */
    public SetupLink toLink() {
        return SetupLink.fromInput(broker.isEmpty() ? backend
                : "wearablesetup://configure?backend=" + enc(backend) + "&broker=" + enc(broker));
    }

    public String backendHost() {
        return toLink().backendHost();
    }

    public boolean sameServer(String otherBackend, String otherBroker) {
        return backend.equals(otherBackend) && broker.equals(otherBroker == null ? "" : otherBroker);
    }

    private static String enc(String v) {
        try {
            return java.net.URLEncoder.encode(v, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return v;
        }
    }
}
