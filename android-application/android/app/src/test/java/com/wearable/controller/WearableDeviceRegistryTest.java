package com.wearable.controller;

import com.wearable.controller.bluetooth.BleAdvertisement;
import com.wearable.controller.impl.AndroidEvenGlassesDevice;
import com.wearable.controller.impl.AndroidOpenEarableDevice;
import com.wearable.controller.impl.AndroidOpenRingDevice;

import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;

import static org.junit.Assert.assertEquals;

public class WearableDeviceRegistryTest {
    private final WearableDeviceRegistry registry = WearableDeviceRegistry.createDefault();

    @Test
    public void identifiesTauRingFromHardwareName() {
        assertEquals(AndroidOpenRingDevice.TYPE,
                registry.identify(advertisement("BCL603616C")));
    }

    @Test
    public void identifiesOpenEarableFromSensorService() {
        HashSet<String> services = new HashSet<>();
        services.add(AndroidOpenEarableDevice.SENSOR_SERVICE_UUID);
        assertEquals(AndroidOpenEarableDevice.TYPE,
                registry.identify(new BleAdvertisement("Unnamed BLE", services, Collections.emptySet())));
    }

    @Test
    public void identifiesEvenGlassesFromName() {
        assertEquals(AndroidEvenGlassesDevice.TYPE,
                registry.identify(advertisement("Even G1_left_01_ab")));
    }

    @Test
    public void leavesUnregisteredWearableUnknown() {
        assertEquals("Unknown", registry.identify(advertisement("Research Sensor")));
    }

    private BleAdvertisement advertisement(String name) {
        return new BleAdvertisement(name, Collections.emptySet(), Collections.emptySet());
    }
}
