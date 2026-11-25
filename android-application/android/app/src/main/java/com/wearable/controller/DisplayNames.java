package com.wearable.controller;

/**
 * Names as people say them. Where a firmware calls a wearable by a name nobody
 * uses, its profile (DeviceProfile) says so, and the text shown to people uses the
 * profile's display name instead.
 */
public final class DisplayNames {
    private DisplayNames() {
    }

    /** Replace a firmware's name with the display name wherever a sentence mentions it. */
    public static String friendly(String text) {
        if (text == null) return "";
        for (DeviceProfile profile : WearableDeviceRegistry.shared().getProfiles()) {
            String firmware = profile.getFirmwareName();
            if (firmware != null && !firmware.isEmpty()) {
                text = text.replace(firmware, profile.getDisplayName());
            }
        }
        return text;
    }

    /** A short name for a wearable's kind, for the dashboard. */
    public static String shortName(String type) {
        DeviceProfile profile = WearableDeviceRegistry.shared().getProfile(type);
        if (profile != null) return profile.getShortName();
        return type == null ? "" : type;
    }

    /** The name a wearable is listed under: its product name when the profile fixes it, else its own. */
    public static String title(String type, String name) {
        DeviceProfile profile = WearableDeviceRegistry.shared().getProfile(type);
        return profile != null && profile.hasFixedTitle() ? profile.getDisplayName() : name;
    }
}
