package com.wearable.controller;

import com.wearable.controller.bluetooth.BleAdvertisement;
import com.wearable.controller.impl.AndroidHeartRateDevice;
import com.wearable.controller.impl.AndroidHeartRateDevice.Measurement;

import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Recorded-byte tests for the Heart Rate Measurement decoder: the pattern for any new adapter. */
public class HeartRateMeasurementTest {
    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) out[i] = (byte) values[i];
        return out;
    }

    @Test
    public void eightBitRateWithoutExtras() {
        Measurement m = AndroidHeartRateDevice.parseMeasurement(bytes(0x00, 72));
        assertEquals(72, m.heartRateBpm);
        assertNull(m.sensorContact);
        assertTrue(m.rrIntervalsMs.isEmpty());
    }

    @Test
    public void sixteenBitRateIsLittleEndian() {
        Measurement m = AndroidHeartRateDevice.parseMeasurement(bytes(0x01, 0x2C, 0x01));
        assertEquals(300, m.heartRateBpm);
    }

    @Test
    public void contactAndTwoRrIntervals() {
        // Flags 0x16: contact supported and detected, RR intervals present.
        // 0x0400 = 1024/1024 s = 1000 ms; 0x0333 = 819/1024 s = 799.8 ms.
        Measurement m = AndroidHeartRateDevice.parseMeasurement(bytes(0x16, 60, 0x00, 0x04, 0x33, 0x03));
        assertEquals(60, m.heartRateBpm);
        assertEquals(Boolean.TRUE, m.sensorContact);
        assertEquals(2, m.rrIntervalsMs.size());
        assertEquals(1000.0, m.rrIntervalsMs.get(0), 1e-9);
        assertEquals(799.8, m.rrIntervalsMs.get(1), 0.05);
    }

    @Test
    public void contactSupportedButLost() {
        Measurement m = AndroidHeartRateDevice.parseMeasurement(bytes(0x04, 0));
        assertEquals(Boolean.FALSE, m.sensorContact);
    }

    @Test
    public void energyExpendedIsSkippedBeforeRr() {
        Measurement m = AndroidHeartRateDevice.parseMeasurement(bytes(0x18, 80, 0x10, 0x00, 0x00, 0x04));
        assertEquals(80, m.heartRateBpm);
        assertEquals(1, m.rrIntervalsMs.size());
        assertEquals(1000.0, m.rrIntervalsMs.get(0), 1e-9);
    }

    @Test
    public void truncatedPacketsAreRejectedNotGuessed() {
        assertNull(AndroidHeartRateDevice.parseMeasurement(null));
        assertNull(AndroidHeartRateDevice.parseMeasurement(bytes(0x00)));
        assertNull(AndroidHeartRateDevice.parseMeasurement(bytes(0x01, 0x2C)));
        assertNull(AndroidHeartRateDevice.parseMeasurement(bytes(0x10, 60, 0x00)));
        assertNull(AndroidHeartRateDevice.parseMeasurement(bytes(0x08, 60, 0x10)));
    }

    @Test
    public void sixteenBitRateWithEnergyAndRrAllPresent() {
        // Flags 0x1F: 16-bit rate, contact supported and detected, energy expended, RR present.
        Measurement m = AndroidHeartRateDevice.parseMeasurement(
                bytes(0x1F, 0x2C, 0x01, 0x05, 0x00, 0x00, 0x08));
        assertEquals(300, m.heartRateBpm);
        assertEquals(Boolean.TRUE, m.sensorContact);
        assertEquals(1, m.rrIntervalsMs.size());
        assertEquals(2000.0, m.rrIntervalsMs.get(0), 1e-9);
    }

    @Test
    public void highBitsAreUnsigned() {
        Measurement m = AndroidHeartRateDevice.parseMeasurement(bytes(0x10, 0xFF, 0xFF, 0xFF));
        assertEquals(255, m.heartRateBpm);
        assertEquals(65535 * 1000.0 / 1024.0, m.rrIntervalsMs.get(0), 1e-9);
    }

    @Test
    public void contactBitWithoutSupportBitMeansUnknown() {
        // Bit 1 (contact detected) is meaningless unless bit 2 (supported) is set.
        Measurement m = AndroidHeartRateDevice.parseMeasurement(bytes(0x02, 61));
        assertNull(m.sensorContact);
    }

    @Test
    public void rrIntervalsKeepTheirOrder() {
        Measurement m = AndroidHeartRateDevice.parseMeasurement(
                bytes(0x10, 70, 0x00, 0x04, 0x00, 0x02, 0x00, 0x08));
        assertEquals(3, m.rrIntervalsMs.size());
        assertEquals(1000.0, m.rrIntervalsMs.get(0), 1e-9);
        assertEquals(500.0, m.rrIntervalsMs.get(1), 1e-9);
        assertEquals(2000.0, m.rrIntervalsMs.get(2), 1e-9);
    }

    @Test
    public void oddNumberOfRrBytesIsRejected() {
        assertNull(AndroidHeartRateDevice.parseMeasurement(bytes(0x10, 60, 0x00, 0x04, 0x33)));
    }

    @Test
    public void emptyAndSingleBytePacketsAreRejected() {
        assertNull(AndroidHeartRateDevice.parseMeasurement(new byte[0]));
        assertNull(AndroidHeartRateDevice.parseMeasurement(bytes(0xFF)));
    }

    @Test
    public void packetCutAnywhereInsideWhatItsFlagsPromiseIsRejected() {
        // Flags promise a 16-bit rate, energy expended and RR intervals.
        byte[] full = bytes(0x19, 0x2C, 0x01, 0x05, 0x00, 0x00, 0x04);
        for (int length = 2; length < full.length; length++) {
            byte[] cut = java.util.Arrays.copyOf(full, length);
            assertNull("length " + length, AndroidHeartRateDevice.parseMeasurement(cut));
        }
        assertEquals(1000.0, AndroidHeartRateDevice.parseMeasurement(full).rrIntervalsMs.get(0), 1e-9);
    }

    @Test
    public void rrIntervalListCannotBeChangedByTheCaller() {
        Measurement m = AndroidHeartRateDevice.parseMeasurement(bytes(0x10, 60, 0x00, 0x04));
        try {
            m.rrIntervalsMs.add(1.0);
            org.junit.Assert.fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }

    @Test
    public void recognisedByItsAdvertisedService() {
        HashSet<String> services = new HashSet<>();
        services.add("0000180D-0000-1000-8000-00805F9B34FB");
        WearableDeviceRegistry registry = WearableDeviceRegistry.createDefault();
        assertEquals(AndroidHeartRateDevice.TYPE,
                registry.identify(new BleAdvertisement("Polar H10 1234", services, Collections.emptySet())));
    }
}
