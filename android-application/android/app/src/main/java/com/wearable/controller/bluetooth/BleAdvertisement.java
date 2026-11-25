package com.wearable.controller.bluetooth;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Device-independent view of a BLE advertisement used by adapter matchers.
 * Keeping Android ScanResult details out of adapters makes matching easy to test.
 */
public final class BleAdvertisement {
    private final String name;
    private final Set<String> serviceUuids;
    private final Set<Integer> manufacturerIds;

    public BleAdvertisement(String name, Set<String> serviceUuids, Set<Integer> manufacturerIds) {
        this.name = name == null ? "" : name;
        this.serviceUuids = normalizeUuids(serviceUuids);
        this.manufacturerIds = manufacturerIds == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new HashSet<>(manufacturerIds));
    }

    public String getName() {
        return name;
    }

    public String getNormalizedName() {
        return name.toLowerCase(Locale.ROOT);
    }

    public boolean hasService(String uuid) {
        return uuid != null && serviceUuids.contains(uuid.toLowerCase(Locale.ROOT));
    }

    public boolean hasManufacturer(int manufacturerId) {
        return manufacturerIds.contains(manufacturerId);
    }

    private static Set<String> normalizeUuids(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> normalized = new HashSet<>();
        for (String value : values) {
            if (value != null) {
                normalized.add(value.toLowerCase(Locale.ROOT));
            }
        }
        return Collections.unmodifiableSet(normalized);
    }
}
