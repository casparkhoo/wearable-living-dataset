package com.wearable.controller;

import com.wearable.controller.bluetooth.AndroidBLEService;
import com.wearable.controller.bluetooth.BleAdvertisement;
import com.wearable.controller.impl.AndroidOpenEarableDevice;
import com.wearable.controller.impl.AndroidEvenGlassesDevice;
import com.wearable.controller.impl.AndroidHeartRateDevice;
import com.wearable.controller.impl.AndroidOpenRingDevice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The single registration point for supported wearable adapters. Adding a wearable
 * means one adapter class and one {@code register} line in {@link #createDefault()};
 * nothing else in the app names a device type.
 */
public final class WearableDeviceRegistry {
    public interface AdvertisementMatcher {
        boolean matches(BleAdvertisement advertisement);
    }

    public interface DeviceFactory {
        WearableDevice create(String address, AndroidBLEService bleService);
    }

    private static final class Entry {
        final DeviceProfile profile;
        final AdvertisementMatcher matcher;
        final DeviceFactory factory;

        Entry(DeviceProfile profile, AdvertisementMatcher matcher, DeviceFactory factory) {
            this.profile = profile;
            this.matcher = matcher;
            this.factory = factory;
        }
    }

    private static WearableDeviceRegistry shared;

    private final List<Entry> entries = new ArrayList<>();

    /** The registry the running app uses, so screens can look up a profile from a type string. */
    public static synchronized WearableDeviceRegistry shared() {
        if (shared == null) shared = createDefault();
        return shared;
    }

    /** Profile of a registered type, or a generic one named after the type. */
    public static DeviceProfile profileOf(String type) {
        DeviceProfile profile = shared().getProfile(type);
        return profile != null ? profile : DeviceProfile.generic(type);
    }

    public static WearableDeviceRegistry createDefault() {
        WearableDeviceRegistry registry = new WearableDeviceRegistry();
        registry.register(AndroidOpenEarableDevice.PROFILE,
                AndroidOpenEarableDevice::matchesAdvertisement,
                AndroidOpenEarableDevice::new);
        registry.register(AndroidOpenRingDevice.PROFILE,
                AndroidOpenRingDevice::matchesAdvertisement,
                AndroidOpenRingDevice::new);
        registry.register(AndroidEvenGlassesDevice.PROFILE,
                AndroidEvenGlassesDevice::matchesAdvertisement,
                AndroidEvenGlassesDevice::new);
        // Last, so a device with its own adapter is never claimed by the generic one.
        registry.register(AndroidHeartRateDevice.PROFILE,
                AndroidHeartRateDevice::matchesAdvertisement,
                AndroidHeartRateDevice::new);
        return registry;
    }

    public void register(DeviceProfile profile, AdvertisementMatcher matcher, DeviceFactory factory) {
        if (profile == null || matcher == null || factory == null) {
            throw new IllegalArgumentException("Device registration requires profile, matcher, and factory");
        }
        if (find(profile.getType()) != null) {
            throw new IllegalArgumentException("Device type already registered: " + profile.getType());
        }
        entries.add(new Entry(profile, matcher, factory));
    }

    /** Registers a type with a generic profile (shown under its type name). */
    public void register(String type, AdvertisementMatcher matcher, DeviceFactory factory) {
        if (type == null) {
            throw new IllegalArgumentException("Device registration requires profile, matcher, and factory");
        }
        register(DeviceProfile.generic(type), matcher, factory);
    }

    public String identify(BleAdvertisement advertisement) {
        for (Entry entry : entries) {
            if (entry.matcher.matches(advertisement)) {
                return entry.profile.getType();
            }
        }
        return "Unknown";
    }

    public boolean supports(String type) {
        return find(type) != null;
    }

    public WearableDevice create(String type, String address, AndroidBLEService bleService) {
        Entry entry = find(type);
        return entry == null ? null : entry.factory.create(address, bleService);
    }

    public List<String> getSupportedTypes() {
        List<String> types = new ArrayList<>();
        for (Entry entry : entries) {
            types.add(entry.profile.getType());
        }
        return Collections.unmodifiableList(types);
    }

    /** The profile of a registered type (case-insensitive), or null. */
    public DeviceProfile getProfile(String type) {
        Entry entry = find(type);
        return entry == null ? null : entry.profile;
    }

    public List<DeviceProfile> getProfiles() {
        List<DeviceProfile> profiles = new ArrayList<>();
        for (Entry entry : entries) {
            profiles.add(entry.profile);
        }
        return Collections.unmodifiableList(profiles);
    }

    /** The profile the dashboard describes when no live recording says otherwise, or null. */
    public DeviceProfile getDashboardDefault() {
        for (Entry entry : entries) {
            if (entry.profile.isDashboardDefault()) return entry.profile;
        }
        return null;
    }

    private Entry find(String type) {
        if (type == null) {
            return null;
        }
        for (Entry entry : entries) {
            if (entry.profile.getType().equalsIgnoreCase(type)) {
                return entry;
            }
        }
        return null;
    }
}
