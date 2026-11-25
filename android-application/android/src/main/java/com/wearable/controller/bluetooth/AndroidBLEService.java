package com.wearable.controller.bluetooth;

import android.Manifest;
import android.bluetooth.*;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Android BLE Service based on actual device firmware implementations.
 * Centralized Bluetooth connectivity for OpenRing, OpenEarable, and Even Glasses.
 */
public class AndroidBLEService {
    private static final String TAG = "AndroidBLEService";
    
    public interface BLEDataListener {
        void onDataReceived(String deviceAddress, String serviceUuid, String characteristicUuid, byte[] data);
        void onConnected(String deviceAddress, String deviceType);
        void onDisconnected(String deviceAddress);
        void onError(String error);
        void onDeviceDiscovered(String name, String address, String deviceType, int rssi);
    }
    
    private final Context context;
    private final BLEDataListener listener;
    private final BluetoothAdapter bluetoothAdapter;
    private final Handler mainHandler;
    
    // Map of active GATT connections by device address
    private final Map<String, BluetoothGatt> activeGatts = new HashMap<>();
    // Map of device type by address for quick lookup
    private final Map<String, String> deviceTypes = new HashMap<>();
    
    private boolean isScanning = false;

    // Device UUID Constants (Verified from Firmware Docs)
    public static final class UUIDS {
        // OpenRing
        public static final String OPENRING_SERVICE = "BAE80001-4F05-4503-8E65-3AF1F7329D1F";
        public static final String OPENRING_TX = "BAE80010-4F05-4503-8E65-3AF1F7329D1F";
        public static final String OPENRING_RX = "BAE80011-4F05-4503-8E65-3AF1F7329D1F";

        // OpenEarable 2
        public static final String OPENEARABLE_SENSOR_SERVICE = "34c2e3bb-34aa-11eb-adc1-0242ac120002";
        public static final String OPENEARABLE_SENSOR_CONFIG = "34c2e3bd-34aa-11eb-adc1-0242ac120002";
        public static final String OPENEARABLE_SENSOR_DATA = "34c2e3bc-34aa-11eb-adc1-0242ac120002";
        public static final String OPENEARABLE_LED_SERVICE = "81040a2e-4819-11ee-be56-0242ac120002";
        public static final String OPENEARABLE_LED_RGB = "81040e7a-4819-11ee-be56-0242ac120002";

        // Even Glasses
        public static final String EVEN_SERVICE = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E";
        public static final String EVEN_WRITE = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E";
        public static final String EVEN_READ = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E";
        
        public static final String CCCD = "00002902-0000-1000-8000-00805f9b34fb";
    }

    public AndroidBLEService(Context context, BLEDataListener listener) {
        this.context = context;
        this.listener = listener;
        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.bluetoothAdapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public boolean isBluetoothSupported() {
        return bluetoothAdapter != null && context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
    }

    public boolean isBluetoothEnabled() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    public void startDeviceDiscovery() {
        if (!isBluetoothSupported() || !isBluetoothEnabled()) return;
        
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            notifyError("Bluetooth Scan Permission Denied");
            return;
        }

        List<ScanFilter> filters = new ArrayList<>();
        // OpenEarable Filter
        filters.add(new ScanFilter.Builder().setDeviceName("OpenEarable").build());
        // Even Glasses Filter (G1_xx_L_yyyyy)
        filters.add(new ScanFilter.Builder().setDeviceNamePattern(Pattern.compile("G\\d+_.*_.*_.*")).build());
        // OpenRing (Often contains 'Ring' or 'OpenRing')
        filters.add(new ScanFilter.Builder().setDeviceNamePattern(Pattern.compile(".*Ring.*")).build());

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        bluetoothAdapter.getBluetoothLeScanner().startScan(filters, settings, scanCallback);
        isScanning = true;
        Log.d(TAG, "Scan started");
    }

    public void stopDeviceDiscovery() {
        if (isScanning && ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            bluetoothAdapter.getBluetoothLeScanner().stopScan(scanCallback);
            isScanning = false;
            Log.d(TAG, "Scan stopped");
        }
    }

    public boolean connect(String address) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            notifyError("Bluetooth Connect Permission Denied");
            return false;
        }

        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
        BluetoothGatt gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        if (gatt != null) {
            activeGatts.put(address, gatt);
            return true;
        }
        return false;
    }

    public void disconnect(String address) {
        BluetoothGatt gatt = activeGatts.remove(address);
        if (gatt != null) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                gatt.disconnect();
                gatt.close();
            }
        }
    }

    public boolean write(String address, String serviceUuid, String charUuid, byte[] data) {
        BluetoothGatt gatt = activeGatts.get(address);
        if (gatt == null) return false;

        BluetoothGattService service = gatt.getService(UUID.fromString(serviceUuid));
        if (service == null) return false;

        BluetoothGattCharacteristic characteristic = service.getCharacteristic(UUID.fromString(charUuid));
        if (characteristic == null) return false;

        characteristic.setValue(data);
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            return gatt.writeCharacteristic(characteristic);
        }
        return false;
    }

    public boolean enableNotifications(String address, String serviceUuid, String charUuid) {
        BluetoothGatt gatt = activeGatts.get(address);
        if (gatt == null) return false;

        BluetoothGattService service = gatt.getService(UUID.fromString(serviceUuid));
        if (service == null) return false;

        BluetoothGattCharacteristic characteristic = service.getCharacteristic(UUID.fromString(charUuid));
        if (characteristic == null) return false;

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return false;

        gatt.setCharacteristicNotification(characteristic, true);
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(UUID.fromString(UUIDS.CCCD));
        if (descriptor != null) {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            return gatt.writeDescriptor(descriptor);
        }
        return false;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = device.getName();
            if (name == null) return;

            String type = identifyDeviceType(name);
            deviceTypes.put(device.getAddress(), type);
            notifyDeviceDiscovered(name, device.getAddress(), type, result.getRssi());
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            String address = gatt.getDevice().getAddress();
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to " + address);
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    gatt.discoverServices();
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnected from " + address);
                activeGatts.remove(address);
                notifyDisconnected(address);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                String address = gatt.getDevice().getAddress();
                String type = deviceTypes.get(address);
                
                // Even Glasses firmware requires MTU request right after discovery
                if ("Even Glasses".equals(type)) {
                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        gatt.requestMtu(251);
                    }
                }
                
                notifyConnected(address, type != null ? type : "Unknown");
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            notifyDataReceived(gatt.getDevice().getAddress(), 
                             characteristic.getService().getUuid().toString(),
                             characteristic.getUuid().toString(), 
                             characteristic.getValue());
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            Log.d(TAG, "MTU changed to " + mtu + " for " + gatt.getDevice().getAddress());
        }
    };

    private String identifyDeviceType(String name) {
        if (name.contains("OpenEarable")) return "OpenEarable";
        if (name.matches("G\\d+_.*_.*_.*")) return "Even Glasses";
        if (name.toLowerCase().contains("ring")) return "OpenRing";
        return "Unknown";
    }

    private void notifyDeviceDiscovered(String name, String address, String type, int rssi) {
        mainHandler.post(() -> listener.onDeviceDiscovered(name, address, type, rssi));
    }

    private void notifyConnected(String address, String type) {
        mainHandler.post(() -> listener.onConnected(address, type));
    }

    private void notifyDisconnected(String address) {
        mainHandler.post(() -> listener.onDisconnected(address));
    }

    private void notifyDataReceived(String address, String service, String characteristic, byte[] data) {
        mainHandler.post(() -> listener.onDataReceived(address, service, characteristic, data));
    }

    private void notifyError(String error) {
        mainHandler.post(() -> listener.onError(error));
    }
}
