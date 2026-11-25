package com.wearable.controller;

import com.wearable.controller.DeviceProfile.StreamRole;
import com.wearable.controller.bluetooth.AndroidBLEService;
import com.wearable.controller.bluetooth.BleAdvertisement;
import com.wearable.controller.impl.AndroidEvenGlassesDevice;
import com.wearable.controller.impl.AndroidHeartRateDevice;
import com.wearable.controller.impl.AndroidOpenEarableDevice;
import com.wearable.controller.impl.AndroidOpenRingDevice;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Profile metadata of every registered adapter, and registration of an adapter the app has never seen. */
public class DeviceProfileTest {
    private final WearableDeviceRegistry registry = WearableDeviceRegistry.createDefault();

    @Test
    public void everyRegisteredAdapterHasAUniqueTypeAndNames() {
        Set<String> types = new HashSet<>();
        Set<String> displayNames = new HashSet<>();
        for (DeviceProfile profile : registry.getProfiles()) {
            assertNotNull(profile.getType());
            assertFalse("empty type", profile.getType().trim().isEmpty());
            assertTrue("duplicate type " + profile.getType(), types.add(profile.getType().toLowerCase()));
            assertFalse(profile.getType() + " display name", profile.getDisplayName().trim().isEmpty());
            assertFalse(profile.getType() + " short name", profile.getShortName().trim().isEmpty());
            assertFalse(profile.getType() + " picker label", profile.getPickerLabel().trim().isEmpty());
            assertFalse(profile.getType() + " wear position", profile.getWearPosition().trim().isEmpty());
            assertFalse(profile.getType() + " icon key", profile.getIconKey().trim().isEmpty());
            assertTrue("duplicate display name " + profile.getDisplayName(),
                    displayNames.add(profile.getDisplayName()));
        }
        assertEquals(registry.getSupportedTypes().size(), types.size());
    }

    @Test
    public void streamRolesAreNonEmptyAndNamed() {
        for (DeviceProfile profile : registry.getProfiles()) {
            for (Map.Entry<String, Set<StreamRole>> entry : profile.getStreamRoles().entrySet()) {
                assertFalse(profile.getType() + " stream name", entry.getKey().trim().isEmpty());
                assertFalse(profile.getType() + "/" + entry.getKey() + " has no role", entry.getValue().isEmpty());
            }
        }
    }

    @Test
    public void everyStreamNamedInAProfileIsASensorTheAdapterDeclares() {
        // The sensor name on SensorData, PROFILE.stream(...) and addSensor(...) must be one string.
        for (DeviceProfile profile : registry.getProfiles()) {
            WearableDevice device = registry.create(profile.getType(), "AA:BB:CC:DD:EE:FF", null);
            assertNotNull(profile.getType(), device);
            List<String> sensors = device.getCapabilities().getSensors();
            for (String stream : profile.getStreamRoles().keySet()) {
                assertTrue(profile.getType() + " declares stream " + stream + " but its sensors are " + sensors,
                        sensors.contains(stream));
            }
        }
    }

    @Test
    public void wearablesThatReportPulseNameTheStreamThatCarriesIt() {
        assertEquals("PPG", AndroidOpenRingDevice.PROFILE.streamFor(StreamRole.PULSE));
        assertEquals("PPG", AndroidOpenRingDevice.PROFILE.streamFor(StreamRole.MOVEMENT));
        assertEquals("PPG", AndroidOpenEarableDevice.PROFILE.streamFor(StreamRole.PULSE));
        assertEquals("IMU", AndroidOpenEarableDevice.PROFILE.streamFor(StreamRole.MOVEMENT));
        assertEquals("HEART_RATE", AndroidHeartRateDevice.PROFILE.streamFor(StreamRole.PULSE));
        assertNull(AndroidHeartRateDevice.PROFILE.streamFor(StreamRole.MOVEMENT));
        assertNull(AndroidEvenGlassesDevice.PROFILE.streamFor(StreamRole.PULSE));
    }

    @Test
    public void onlyTheGlassesShowTheDashboardAndNeedBonding() {
        for (DeviceProfile profile : registry.getProfiles()) {
            boolean glasses = profile.getType().equals(AndroidEvenGlassesDevice.TYPE);
            assertEquals(profile.getType(), glasses, profile.hasDisplay());
            assertEquals(profile.getType(), glasses, profile.requiresBondBeforeConnect());
            if (profile.requiresBondBeforeConnect()) {
                assertFalse(profile.getPairingStartText().isEmpty());
                assertFalse(profile.getPairingFailText().isEmpty());
            }
        }
    }

    @Test
    public void exactlyOneWearableIsTheDashboardDefault() {
        int defaults = 0;
        for (DeviceProfile profile : registry.getProfiles()) if (profile.isDashboardDefault()) defaults++;
        assertEquals(1, defaults);
        assertEquals(AndroidOpenRingDevice.TYPE, registry.getDashboardDefault().getType());
    }

    @Test
    public void everyRegisteredTypeIsFoundByItsProfile() {
        for (String type : registry.getSupportedTypes()) {
            assertSame(registry.getProfile(type), registry.getProfile(type.toUpperCase()));
            assertTrue(registry.supports(type));
        }
    }

    // -- Names people see ---------------------------------------------------

    @Test
    public void firmwareNameIsReplacedByTheProductName() {
        assertEquals("τ-Ring battery low", DisplayNames.friendly("OpenRing battery low"));
        assertEquals("", DisplayNames.friendly(null));
        assertEquals("Polar H10", DisplayNames.friendly("Polar H10"));
    }

    @Test
    public void listTitleIsFixedOnlyWhereTheProfileSaysSo() {
        assertEquals("τ-Ring", DisplayNames.title("OpenRing", "BCL603616C"));
        assertEquals("Polar H10", DisplayNames.title("HeartRateMonitor", "Polar H10"));
        assertEquals("Anything", DisplayNames.title("NotRegistered", "Anything"));
    }

    @Test
    public void shortNamesFallBackToTheType() {
        assertEquals("Earable", DisplayNames.shortName("OpenEarable"));
        assertEquals("HR strap", DisplayNames.shortName("HeartRateMonitor"));
        assertEquals("NotRegistered", DisplayNames.shortName("NotRegistered"));
        assertEquals("", DisplayNames.shortName(null));
    }

    @Test
    public void unknownTypeGetsAGenericProfile() {
        DeviceProfile profile = WearableDeviceRegistry.profileOf("SomethingNew");
        assertEquals("SomethingNew", profile.getDisplayName());
        assertFalse(profile.hasDisplay());
        assertEquals("dot", profile.getIconKey());
    }

    @Test
    public void dashboardTagsKeepTheOldAbbreviations() {
        assertEquals("RING", AndroidOpenRingDevice.PROFILE.dashboardTag("τ-Ring"));
        assertEquals("EARab12", AndroidOpenEarableDevice.PROFILE.dashboardTag("OpenEarable ab:12"));
        assertEquals("HR STR", AndroidHeartRateDevice.PROFILE.dashboardTag("HR strap"));
        assertEquals("SHORT", AndroidHeartRateDevice.PROFILE.dashboardTag("short"));
    }

    @Test
    public void glassesArmsShareAPairAndHaveASide() {
        DeviceProfile glasses = AndroidEvenGlassesDevice.PROFILE;
        assertEquals("45", glasses.groupKeyOf("Even G1_45_L_8C3A1B"));
        assertEquals("45", glasses.groupKeyOf("Even G1_45_R_01B2C3"));
        assertEquals("", glasses.groupKeyOf("Even G1"));
        assertEquals("", glasses.groupKeyOf(null));
        assertEquals("L", AndroidEvenGlassesDevice.sideOf("Even G1_45_L_8C3A1B"));
        assertEquals("R", AndroidEvenGlassesDevice.sideOf("Even G1_45_R_01B2C3"));
        assertEquals("", AndroidEvenGlassesDevice.sideOf("Even G1_45_X_01B2C3"));
        assertEquals("Even G1 · left arm", glasses.partLabelOf("Even G1_45_L_8C3A1B"));
        assertNull(glasses.partLabelOf("Even G1"));
        assertEquals("Even G1 glasses (pair 45)", glasses.groupTitleOf("45"));
        assertEquals("", AndroidOpenRingDevice.PROFILE.groupKeyOf("Even G1_45_L_8C3A1B"));
    }

    // -- A wearable the app has never seen ----------------------------------

    private static final String FAKE_TYPE = "FakeBand";

    private static WearableDevice fakeDevice(String address) {
        return (WearableDevice) Proxy.newProxyInstance(
                WearableDevice.class.getClassLoader(), new Class<?>[] {WearableDevice.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getDeviceType":
                        case "getDeviceName":
                            return FAKE_TYPE;
                        case "getDeviceAddress":
                            return address;
                        case "isConnected":
                            return false;
                        default:
                            return null;
                    }
                });
    }

    @Test
    public void aNewAdapterRegistersAndResolvesWithoutAnyScreenKnowingIt() {
        WearableDeviceRegistry fresh = new WearableDeviceRegistry();
        DeviceProfile profile = new DeviceProfile.Builder(FAKE_TYPE)
                .displayName("Fake Band")
                .shortName("Band")
                .wearPosition("wrist")
                .stream("HEART_RATE", StreamRole.PULSE)
                .stream("ACCEL", StreamRole.MOVEMENT)
                .build();
        fresh.register(profile,
                adv -> adv.getNormalizedName().startsWith("fakeband"),
                (address, ble) -> fakeDevice(address));

        assertEquals(Collections.singletonList(FAKE_TYPE), fresh.getSupportedTypes());
        assertEquals(FAKE_TYPE, fresh.identify(new BleAdvertisement("FakeBand 7", Collections.emptySet(), Collections.emptySet())));
        assertEquals("Unknown", fresh.identify(new BleAdvertisement("Other", Collections.emptySet(), Collections.emptySet())));
        assertSame(profile, fresh.getProfile("fakeband"));
        WearableDevice device = fresh.create(FAKE_TYPE, "AA:BB", (AndroidBLEService) null);
        assertNotNull(device);
        assertEquals("AA:BB", device.getDeviceAddress());
        assertEquals(FAKE_TYPE, device.getDeviceType());
        assertEquals("ACCEL", profile.streamFor(StreamRole.MOVEMENT));
        // The default registry is untouched.
        assertFalse(registry.supports(FAKE_TYPE));
    }

    @Test
    public void registeringTheSameTypeTwiceIsAnError() {
        WearableDeviceRegistry fresh = new WearableDeviceRegistry();
        fresh.register(FAKE_TYPE, adv -> false, (address, ble) -> fakeDevice(address));
        try {
            fresh.register(FAKE_TYPE.toLowerCase(), adv -> false, (address, ble) -> fakeDevice(address));
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Two adapters must not claim one id.
        }
    }

    @Test
    public void registrationNeedsAllThreeParts() {
        WearableDeviceRegistry fresh = new WearableDeviceRegistry();
        try {
            fresh.register((DeviceProfile) null, adv -> false, (address, ble) -> null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        List<DeviceProfile> none = fresh.getProfiles();
        assertTrue(none.isEmpty());
    }

    @Test
    public void heartRateStrapComesAfterAdaptersWithTheirOwnMatching() {
        List<String> types = registry.getSupportedTypes();
        assertEquals(AndroidHeartRateDevice.TYPE, types.get(types.size() - 1));
    }
}
