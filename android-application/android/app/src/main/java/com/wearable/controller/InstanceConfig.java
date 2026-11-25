package com.wearable.controller;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;

/**
 * Which server the cloud profile talks to: a saved override (from a setup link or the
 * "Server address" dialog) or, when there is none, the addresses compiled into the build.
 * The only place that reads BuildConfig.CLOUD_BACKEND_URL / CLOUD_BROKER_URL.
 */
public final class InstanceConfig {

    private static final String PREFS = "instance";
    private static final String KEY_BACKEND = "backend_override";
    private static final String KEY_BROKER = "broker_override";
    private static final String KEY_ACTIVE_NAME = "active_profile";
    private static final String KEY_PROFILES = "profiles";

    private InstanceConfig() {}

    /** Pure choice: an override wins when it names a backend; otherwise the build value. */
    static String resolveBackend(String overrideBackend, String builtIn) {
        return overrideBackend != null && !overrideBackend.isEmpty() ? overrideBackend : builtIn;
    }

    /**
     * The broker goes with the backend: when a backend override is set the built-in broker
     * belongs to a different server and must not be used. A link that names no broker gets the
     * websocket broker every deployment of this project serves on the backend's own host at
     * {@code /mqtt} (the gateway and the cloud proxy both route it), so a server can be chosen
     * from its address alone.
     */
    static String resolveBroker(String overrideBackend, String overrideBroker, String builtIn) {
        if (overrideBackend != null && !overrideBackend.isEmpty()) {
            if (overrideBroker != null && !overrideBroker.isEmpty()) return overrideBroker;
            return derivedBroker(overrideBackend);
        }
        return builtIn;
    }

    /** wss://host[:port]/mqtt for an https backend address, or "" when the address cannot be read. */
    static String derivedBroker(String backend) {
        try {
            java.net.URI uri = new java.net.URI(backend);
            if (uri.getHost() == null || uri.getHost().isEmpty()) return "";
            String host = uri.getHost();
            // deploy.sh serves the REST API on api.<domain> and MQTT on mqtt.<domain>; the local
            // gateway serves both on one host.
            if (host.startsWith("api.")) host = "mqtt." + host.substring(4);
            return "wss://" + host + (uri.getPort() > 0 ? ":" + uri.getPort() : "") + "/mqtt";
        } catch (java.net.URISyntaxException e) {
            return "";
        }
    }

    public static String backendUrl(Context context) {
        return resolveBackend(prefs(context).getString(KEY_BACKEND, ""), BuildConfig.CLOUD_BACKEND_URL);
    }

    public static String brokerUrl(Context context) {
        SharedPreferences p = prefs(context);
        return resolveBroker(p.getString(KEY_BACKEND, ""), p.getString(KEY_BROKER, ""),
                BuildConfig.CLOUD_BROKER_URL);
    }

    public static boolean hasOverride(Context context) {
        return !prefs(context).getString(KEY_BACKEND, "").isEmpty();
    }

    /** True when saving this link would change the server in use. */
    public static boolean differsFromCurrent(Context context, SetupLink link) {
        return !link.backend.equals(backendUrl(context)) || !link.broker.equals(brokerUrl(context));
    }

    /**
     * Save a validated link and drop everything tied to the previous server: the broker
     * login (u2 on one server is not u2 on another), topic settings and the cloud token.
     * The caller must also sign the account out.
     */
    public static void apply(Context context, SetupLink link) {
        apply(context, link, "");
    }

    /** As {@link #apply(Context, SetupLink)}, remembering which saved profile is now active. */
    public static void apply(Context context, SetupLink link, String profileName) {
        if (!link.isValid()) return;
        prefs(context).edit()
                .putString(KEY_BACKEND, link.backend)
                .putString(KEY_BROKER, link.broker)
                .putString(KEY_ACTIVE_NAME, profileName == null ? "" : profileName)
                .apply();
        clearServerBoundState(context);
    }

    /** Go back to the addresses built into the app. */
    public static void reset(Context context) {
        prefs(context).edit().remove(KEY_BACKEND).remove(KEY_BROKER).remove(KEY_ACTIVE_NAME).apply();
        clearServerBoundState(context);
    }

    // --- named profiles (rules live in ServerProfiles; this is only the storage) ---

    public static List<ServerProfile> profiles(Context context) {
        return ServerProfiles.fromJson(prefs(context).getString(KEY_PROFILES, ""));
    }

    public static void saveProfiles(Context context, List<ServerProfile> list) {
        prefs(context).edit().putString(KEY_PROFILES, ServerProfiles.toJson(list)).apply();
    }

    public static String activeName(Context context) {
        return prefs(context).getString(KEY_ACTIVE_NAME, "");
    }

    /** Switch to a saved profile: same cleanup as any server change. */
    public static void switchTo(Context context, ServerProfile profile) {
        apply(context, profile.toLink(), profile.name);
    }

    /** Name shown to the person: "Cloud" for the built-in address, else the profile or host. */
    public static String activeLabel(Context context) {
        return ServerProfiles.activeLabel(activeName(context), profiles(context), !hasOverride(context),
                SetupLink.fromInput(backendUrl(context)).backendHost());
    }

    /** True when the built-in address is empty (an APK built without a default instance). */
    public static boolean noBuiltIn() {
        return BuildConfig.CLOUD_BACKEND_URL.isEmpty();
    }

    public static String builtInBackend() {
        return BuildConfig.CLOUD_BACKEND_URL;
    }

    public static String builtInBroker() {
        return BuildConfig.CLOUD_BROKER_URL;
    }

    private static void clearServerBoundState(Context context) {
        context.getSharedPreferences("connections", Context.MODE_PRIVATE).edit()
                .remove("cloud_mqtt_username")
                .remove("cloud_mqtt_password")
                .remove("cloud_topic_prefix")
                .remove("cloud_status_topic_filter")
                .remove("cloud_api_token")
                .apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
