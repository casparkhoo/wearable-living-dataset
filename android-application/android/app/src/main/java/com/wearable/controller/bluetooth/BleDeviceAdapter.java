package com.wearable.controller.bluetooth;

/**
 * BLE-specific lifecycle owned by each wearable adapter.
 * AndroidBLEService supplies transport primitives; adapters own protocol choices.
 */
public interface BleDeviceAdapter {
    String getDeviceType();
    String getDeviceAddress();

    /** Called after Android has successfully discovered the device's GATT services. */
    void onBleServicesDiscovered();

    /** Allows an adapter to sequence notification setup after MTU negotiation. */
    default void onBleMtuChanged(int mtu, int status) { }

    /** Called for every notification/indication received from this device. */
    void onBleNotification(String serviceUuid, String characteristicUuid, byte[] data);

    /** Some devices should only connect after they have been observed advertising. */
    default boolean requiresRecentAdvertisement() {
        return false;
    }
}
