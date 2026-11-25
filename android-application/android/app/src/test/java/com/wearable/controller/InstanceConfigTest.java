package com.wearable.controller;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** The fallback rule between a saved server override and the built-in addresses. */
public class InstanceConfigTest {

    @Test
    public void noOverrideUsesBuiltIn() {
        assertEquals("https://built", InstanceConfig.resolveBackend("", "https://built"));
        assertEquals("https://built", InstanceConfig.resolveBackend(null, "https://built"));
        assertEquals("mqtts://built:8883", InstanceConfig.resolveBroker("", "", "mqtts://built:8883"));
        assertEquals("mqtts://built:8883", InstanceConfig.resolveBroker(null, "mqtts://x", "mqtts://built:8883"));
    }

    @Test
    public void overrideWins() {
        assertEquals("https://other", InstanceConfig.resolveBackend("https://other", "https://built"));
        assertEquals("mqtts://other:8883",
                InstanceConfig.resolveBroker("https://other", "mqtts://other:8883", "mqtts://built:8883"));
    }

    @Test
    public void overrideWithoutBrokerDerivesTheBrokerFromTheBackendNotTheBuiltInOne() {
        assertEquals("wss://other/mqtt", InstanceConfig.resolveBroker("https://other", "", "mqtts://built:8883"));
        assertEquals("wss://other/mqtt", InstanceConfig.resolveBroker("https://other", null, "mqtts://built:8883"));
        assertEquals("wss://h.example.org:8443/mqtt", InstanceConfig.derivedBroker("https://h.example.org:8443"));
        assertEquals("wss://mqtt.example.org/mqtt", InstanceConfig.derivedBroker("https://api.example.org"));
        assertEquals("", InstanceConfig.derivedBroker("not a url"));
    }
}
