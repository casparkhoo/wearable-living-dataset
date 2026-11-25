package com.wearable.controller.bluetooth;

import android.Manifest;
import android.bluetooth.*;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;
import android.util.SparseArray;

import androidx.core.app.ActivityCompat;

import com.wearable.controller.WearableDeviceRegistry;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Device-independent Android BLE transport. Wearable-specific behavior belongs to
 * registered BleDeviceAdapter implementations.
 */
public class AndroidBLEService {
    private static final String TAG = "AndroidBLEService";
    private static final String CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb";
    public static final String SCAN_TYPE_ALL = "All Devices";

    public interface BLEDataListener {
        void onDataReceived(String deviceAddress, String serviceUuid, String characteristicUuid, byte[] data);
        void onConnected(String deviceAddress, String deviceType);
        void onDisconnected(String deviceAddress, int gattStatus);
        void onError(String error);
        void onDeviceDiscovered(String name, String address, String deviceType, int rssi);

        /** A battery level read from the standard Battery Service, 0 to 100. */
        void onBatteryLevel(String deviceAddress, int percent);
    }

    /** Standard GATT Battery Service and its Battery Level characteristic. */
    public static final String BATTERY_SERVICE_UUID = "0000180f-0000-1000-8000-00805f9b34fb";
    public static final String BATTERY_LEVEL_UUID = "00002a19-0000-1000-8000-00805f9b34fb";

    private final Context context;
    private final BLEDataListener listener;
    private final BluetoothAdapter bluetoothAdapter;
    private final Handler mainHandler;
    private final WearableDeviceRegistry deviceRegistry;
    private final ExecutorService notificationExecutor = Executors.newSingleThreadExecutor();

    // Map of active GATT connections by device address
    private final Map<String, BluetoothGatt> activeGatts = new ConcurrentHashMap<>();
    // Map of device type by address for quick lookup
    private final Map<String, String> deviceTypes = new ConcurrentHashMap<>();
    // Map of retry counters by device address
    private final Map<String, Integer> retryCounters = new ConcurrentHashMap<>();
    // Addresses whose link was fully established. A link that drops after that
    // (a ring put back in its case, a wearable walking away) is left to the
    // app's slower, backed-off reconnect rather than retried here at once.
    private final java.util.Set<String> establishedLinks = ConcurrentHashMap.newKeySet();
    // Map of discovered devices from scan results
    private final Map<String, ScanResult> discoveredDevices = new ConcurrentHashMap<>();
    // Device-specific protocol behavior, registered before connection.
    private final Map<String, BleDeviceAdapter> deviceAdapters = new ConcurrentHashMap<>();

    /**
     * Android allows one GATT operation in flight per connection and refuses
     * a write while another is pending, so commands sent back to back (such
     * as UPDATE_TIME then START_MEASUREMENT) would silently lose the second.
     * Writes therefore queue per device and go out one at a time: the next
     * starts when Android reports the previous one written, or after a
     * fallback delay, and a write refused as busy is retried.
     */
    private static final class PendingWrite {
        final String serviceUuid;
        final String characteristicUuid;
        final byte[] data;
        /** A read rather than a write; reads share the queue because they too must wait their turn. */
        final boolean read;
        int attempts;

        PendingWrite(String serviceUuid, String characteristicUuid, byte[] data) {
            this(serviceUuid, characteristicUuid, data, false);
        }

        PendingWrite(String serviceUuid, String characteristicUuid, byte[] data, boolean read) {
            this.serviceUuid = serviceUuid;
            this.characteristicUuid = characteristicUuid;
            this.data = data;
            this.read = read;
        }
    }

    private static final long WRITE_RETRY_MS = 50;
    private static final long WRITE_FALLBACK_MS = 500;
    private static final int MAX_WRITE_ATTEMPTS = 40;
    private final Map<String, ArrayDeque<PendingWrite>> writeQueues = new ConcurrentHashMap<>();
    /** Increments with each write issued, so a stale fallback timer is ignored. */
    private final Map<String, Integer> writeInFlight = new ConcurrentHashMap<>();
    private int writeSerial;
    private String activeScanType = SCAN_TYPE_ALL;

    private boolean isScanning = false;

    public AndroidBLEService(Context context, BLEDataListener listener) {
        this(context, listener, WearableDeviceRegistry.createDefault());
    }

    public AndroidBLEService(Context context, BLEDataListener listener, WearableDeviceRegistry deviceRegistry) {
        this.context = context;
        this.listener = listener;
        this.deviceRegistry = deviceRegistry;
        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.bluetoothAdapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void setDeviceType(String address, String type) {
        deviceTypes.put(address, type);
    }

    public void registerDeviceAdapter(BleDeviceAdapter adapter) {
        if (adapter == null) {
            return;
        }
        deviceAdapters.put(adapter.getDeviceAddress(), adapter);
        deviceTypes.put(adapter.getDeviceAddress(), adapter.getDeviceType());
    }

    public void unregisterDeviceAdapter(String address) {
        deviceAdapters.remove(address);
    }

    public BleDeviceAdapter getRegisteredDeviceAdapter(String address) {
        return deviceAdapters.get(address);
    }

    public boolean isBluetoothSupported() {
        return bluetoothAdapter != null && context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
    }

    public boolean isBluetoothEnabled() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    public void startDeviceDiscovery() {
        startDeviceDiscovery(SCAN_TYPE_ALL);
    }

    public void startDeviceDiscovery(String scanType) {
        startDeviceDiscovery(scanType, false);
    }

    /**
     * @param background look for devices that are already known, without
     *                   taking radio time from connections that are streaming.
     *                   A continuous scan competes with them: a ring link was
     *                   lost twice in one recording, each time as one began.
     */
    public void startDeviceDiscovery(String scanType, boolean background) {
        Log.d(TAG, "startDeviceDiscovery called");
        if (!isBluetoothSupported()) {
            Log.e(TAG, "BLE not supported on this device");
            notifyError("BLE not supported on this device");
            return;
        }

        if (!isBluetoothEnabled()) {
            Log.e(TAG, "Bluetooth is not enabled");
            notifyError("Bluetooth is not enabled, please enable it");
            return;
        }

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "BLUETOOTH_SCAN permission not granted");
            notifyError("Bluetooth Scan Permission Denied");
            return;
        }

        activeScanType = normalizeScanType(scanType);
        Log.d(TAG, "Using scan type filter: " + activeScanType);

        try {
            List<ScanFilter> filters = new ArrayList<>();
            ScanSettings.Builder settingsBuilder = new ScanSettings.Builder()
                    .setScanMode(background ? ScanSettings.SCAN_MODE_BALANCED : ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(0);

            // Include BLE 5 extended advertisements used by modern wearable devices.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settingsBuilder.setLegacy(false);
                settingsBuilder.setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED);
                settingsBuilder.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES);
                settingsBuilder.setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE);
            }

            ScanSettings settings = settingsBuilder.build();

            if (bluetoothAdapter.getBluetoothLeScanner() == null) {
                Log.e(TAG, "BluetoothLeScanner is null");
                notifyError("Bluetooth scanner unavailable");
                return;
            }

            bluetoothAdapter.getBluetoothLeScanner().startScan(filters, settings, scanCallback);
            isScanning = true;
            Log.d(TAG, "Scan started successfully, " + (background ? "balanced" : "low-latency") + " mode");
            notifyError("Scanning for BLE devices...");
        } catch (Exception e) {
            Log.e(TAG, "Exception starting scan: " + e.getMessage(), e);
            notifyError("Scan error: " + e.getMessage());
            isScanning = false;
        }
    }

    public void stopDeviceDiscovery() {
        if (isScanning && ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            bluetoothAdapter.getBluetoothLeScanner().stopScan(scanCallback);
            isScanning = false;
            // Clear discovered devices after scan stops (they expire quickly)
            mainHandler.postDelayed(() -> discoveredDevices.clear(), 5000);
            Log.d(TAG, "Scan stopped");
        }
    }

    public boolean connect(String address) {
        return connect(address, false);
    }

    /**
     * Connect, or with {@code background} ask the phone's Bluetooth controller to
     * connect whenever the device next advertises. A background request has no
     * timeout and costs the app nothing while it waits, so a remembered wearable
     * that is out of range or in its case is reconnected the moment it is back,
     * instead of at the next scan.
     */
    public boolean connect(String address, boolean background) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            notifyError("Bluetooth Connect Permission Denied");
            return false;
        }

        if (activeGatts.containsKey(address)) {
            Log.i(TAG, "Already connected or connecting to " + address);
            return true;
        }

        BleDeviceAdapter adapter = deviceAdapters.get(address);
        // A bonded device that is not advertising right now can still be waited
        // for: an auto-connect request lets the phone's controller watch for it
        // at almost no cost, with no scan from the app.
        boolean waitForBonded = background;
        if (!background && adapter != null && adapter.requiresRecentAdvertisement() && !discoveredDevices.containsKey(address)) {
            // Not seen in the last few seconds (the scan cache empties soon after
            // a scan stops) and not paired: connect directly anyway. Refusing
            // here left glasses listed under Nearby that could not be connected
            // at all once their old pairing had been removed.
            waitForBonded = isBonded(address);
            if (!waitForBonded) {
                Log.i(TAG, adapter.getDeviceType() + " not in the recent scan and not paired; connecting directly to " + address);
            }
        }

        // Most wearable advertisements are intermittent, so a stale scan cache is advisory.
        if (!background && !isDeviceAvailable(address)) {
            Log.w(TAG, "Device " + address + " not found in recent scan cache, attempting connection anyway");
        }

        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
        BluetoothGatt gatt = device.connectGatt(context, waitForBonded, gattCallback, BluetoothDevice.TRANSPORT_LE);
        if (gatt != null) {
            activeGatts.put(address, gatt);
            Log.i(TAG, "Attempting connection to " + address);
            return true;
        }
        return false;
    }

    public boolean connect(String address, String deviceType) {
        if (deviceType != null) {
            deviceTypes.put(address, deviceType);
        }
        return connect(address);
    }

    public void disconnect(String address) {
        clearWrites(address);
        BluetoothGatt gatt = activeGatts.remove(address);
        if (gatt != null) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                gatt.disconnect();
                gatt.close();
            }
            notifyDisconnected(address, BluetoothGatt.GATT_SUCCESS);
        }
        unregisterDeviceAdapter(address);
    }

    public boolean createBond(String address) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            notifyError("Bluetooth Connect Permission Denied");
            return false;
        }

        try {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
            if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                return true;
            }
            return device.createBond();
        } catch (IllegalArgumentException ex) {
            notifyError("Invalid address for bonding: " + address);
            return false;
        }
    }

    /**
     * Delete this phone's pairing with a device. Used when the device rejects the
     * stored keys (it was reset, or paired with another phone or app since): the
     * old keys can never work again, and the next connection pairs afresh.
     * removeBond is not in the public API, so it is called by reflection.
     */
    public boolean removeBond(String address) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        try {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
            if (device.getBondState() == BluetoothDevice.BOND_NONE) return true;
            Method method = device.getClass().getMethod("removeBond");
            boolean removed = (Boolean) method.invoke(device);
            Log.i(TAG, "removeBond(" + address + ") = " + removed);
            return removed;
        } catch (Exception ex) {
            Log.w(TAG, "removeBond failed for " + address + ": " + ex.getMessage());
            return false;
        }
    }

    public boolean isBonded(String address) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }

        try {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
            return device.getBondState() == BluetoothDevice.BOND_BONDED;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public boolean write(String address, String serviceUuid, String charUuid, byte[] data) {
        BluetoothGatt gatt = activeGatts.get(address);
        if (gatt == null) {
            Log.e(TAG, "write() failed: GATT not found for " + address);
            return false;
        }

        BluetoothGattService service = gatt.getService(UUID.fromString(serviceUuid));
        if (service == null) {
            Log.e(TAG, "write() failed: Service not found " + serviceUuid + " on " + address);
            return false;
        }

        BluetoothGattCharacteristic characteristic = service.getCharacteristic(UUID.fromString(charUuid));
        if (characteristic == null) {
            Log.e(TAG, "write() failed: Characteristic not found " + charUuid + " in service " + serviceUuid);
            return false;
        }

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "write() failed: No BLUETOOTH_CONNECT permission");
            return false;
        }
        writeQueues.computeIfAbsent(address, key -> new ArrayDeque<>())
                .add(new PendingWrite(serviceUuid, charUuid, data));
        mainHandler.post(() -> drainWrites(address));
        return true;
    }

    /**
     * Read the battery level if the device exposes the standard Battery
     * Service. Returns false, quietly, for devices that do not.
     */
    public boolean readBatteryLevel(String address) {
        BluetoothGatt gatt = activeGatts.get(address);
        if (gatt == null) return false;
        BluetoothGattService service = gatt.getService(UUID.fromString(BATTERY_SERVICE_UUID));
        if (service == null || service.getCharacteristic(UUID.fromString(BATTERY_LEVEL_UUID)) == null) {
            return false;
        }
        writeQueues.computeIfAbsent(address, key -> new ArrayDeque<>())
                .add(new PendingWrite(BATTERY_SERVICE_UUID, BATTERY_LEVEL_UUID, null, true));
        mainHandler.post(() -> drainWrites(address));
        return true;
    }

    /** Issue the next queued write for {@code address} if none is in flight. Main thread only. */
    private void drainWrites(String address) {
        if (writeInFlight.containsKey(address)) return;
        ArrayDeque<PendingWrite> queue = writeQueues.get(address);
        if (queue == null || queue.isEmpty()) return;
        BluetoothGatt gatt = activeGatts.get(address);
        if (gatt == null) {
            queue.clear();
            return;
        }
        PendingWrite next = queue.peek();
        BluetoothGattService service = gatt.getService(UUID.fromString(next.serviceUuid));
        BluetoothGattCharacteristic characteristic = service == null ? null
                : service.getCharacteristic(UUID.fromString(next.characteristicUuid));
        if (characteristic == null
                || ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            queue.poll();
            drainWrites(address);
            return;
        }
        boolean issued;
        if (next.read) {
            issued = gatt.readCharacteristic(characteristic);
        } else {
            characteristic.setValue(next.data);
            issued = gatt.writeCharacteristic(characteristic);
        }
        if (issued) {
            queue.poll();
            final int serial = ++writeSerial;
            writeInFlight.put(address, serial);
            Log.d(TAG, (next.read ? "read() requested " : "write() sent " + next.data.length + " bytes to ")
                    + next.characteristicUuid);
            mainHandler.postDelayed(() -> {
                // No write callback arrived; do not let one lost callback stall the queue.
                if (writeInFlight.remove(address, serial)) drainWrites(address);
            }, WRITE_FALLBACK_MS);
        } else if (++next.attempts >= MAX_WRITE_ATTEMPTS) {
            queue.poll();
            Log.e(TAG, "write() gave up after " + next.attempts + " attempts on " + address);
            notifyError("A command could not be sent to " + address);
            mainHandler.postDelayed(() -> drainWrites(address), WRITE_RETRY_MS);
        } else {
            mainHandler.postDelayed(() -> drainWrites(address), WRITE_RETRY_MS);
        }
    }

    private void clearWrites(String address) {
        writeQueues.remove(address);
        writeInFlight.remove(address);
    }

    /** Generic helper for adapters supporting firmware-dependent characteristic aliases. */
    public boolean writeFirstAvailable(String address, String serviceUuid, List<String> characteristicUuids, byte[] data) {
        BluetoothGatt gatt = activeGatts.get(address);
        if (gatt == null || characteristicUuids == null || characteristicUuids.isEmpty()) {
            return false;
        }
        BluetoothGattService service = gatt.getService(UUID.fromString(serviceUuid));
        if (service == null) {
            Log.e(TAG, "writeFirstAvailable() service not found " + serviceUuid);
            return false;
        }
        for (String characteristicUuid : characteristicUuids) {
            if (service.getCharacteristic(UUID.fromString(characteristicUuid)) != null) {
                return write(address, serviceUuid, characteristicUuid, data);
            }
        }
        Log.e(TAG, "writeFirstAvailable() none of the adapter's characteristics were found");
        return false;
    }

    public boolean requestMtu(String address, int mtu) {
        BluetoothGatt gatt = activeGatts.get(address);
        if (gatt == null
                || ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        return gatt.requestMtu(mtu);
    }

    public boolean enableNotifications(String address, String serviceUuid, String charUuid) {
        BluetoothGatt gatt = activeGatts.get(address);
        if (gatt == null) {
            Log.e(TAG, "enableNotifications() failed: GATT not found for " + address);
            return false;
        }

        BluetoothGattService service = gatt.getService(UUID.fromString(serviceUuid));
        if (service == null) {
            Log.e(TAG, "enableNotifications() failed: Service not found " + serviceUuid);
            return false;
        }

        BluetoothGattCharacteristic characteristic = service.getCharacteristic(UUID.fromString(charUuid));
        if (characteristic == null) {
            Log.e(TAG, "enableNotifications() failed: Characteristic not found " + charUuid);
            return false;
        }

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "enableNotifications() failed: No BLUETOOTH_CONNECT permission");
            return false;
        }

        Log.d(TAG, "enableNotifications() setting notification for " + charUuid);
        gatt.setCharacteristicNotification(characteristic, true);
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(UUID.fromString(CCCD_UUID));
        if (descriptor == null) {
            Log.e(TAG, "enableNotifications() failed: CCCD descriptor not found for " + charUuid);
            return false;
        }
        Log.d(TAG, "enableNotifications() writing CCCD descriptor");
        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        boolean result = gatt.writeDescriptor(descriptor);
        Log.d(TAG, "enableNotifications() writeDescriptor returned " + result);
        return result;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        private int resultCount = 0;

        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            resultCount++;
            BluetoothDevice device = result.getDevice();
            if (device == null) {
                Log.w(TAG, "[Scan#" + resultCount + "] Device is null, skipping");
                return;
            }

            final String address = device.getAddress();
            if (address == null || address.isEmpty()) {
                Log.w(TAG, "[Scan#" + resultCount + "] Address is null/empty, skipping");
                return;
            }

            String name = resolveDeviceName(result);
            if (name == null || name.trim().isEmpty()) {
                name = "Unnamed BLE";
            }

            int rssi = result.getRssi();
            Log.d(TAG, "[Scan#" + resultCount + "] Found device: " + address + " | Name: " + name + " | RSSI: " + rssi);
            
            // Log advertised service UUIDs and manufacturer data for debugging
            final ScanRecord scanRecord = result.getScanRecord();
            if (scanRecord == null) {
                Log.d(TAG, "[Scan#" + resultCount + "] NO scan record (no advertising data captured)");
            } else {
                if (scanRecord.getServiceUuids() != null && !scanRecord.getServiceUuids().isEmpty()) {
                    StringBuilder uuids = new StringBuilder();
                    for (ParcelUuid uuid : scanRecord.getServiceUuids()) {
                        uuids.append(uuid.toString()).append(", ");
                    }
                    Log.d(TAG, "[Scan#" + resultCount + "] Advertised Service UUIDs: " + uuids.toString());
                }
                
                // Manufacturer identifiers are made available to adapter matchers.
                final SparseArray<byte[]> manufacturerData = scanRecord.getManufacturerSpecificData();
                if (manufacturerData != null && manufacturerData.size() > 0) {
                    StringBuilder mfg = new StringBuilder();
                    for (int i = 0; i < manufacturerData.size(); i++) {
                        mfg.append("MFG:").append(manufacturerData.keyAt(i)).append(", ");
                    }
                    Log.d(TAG, "[Scan#" + resultCount + "] Manufacturer Data: " + mfg.toString());
                }
            }

            // Store discovered device for availability checking
            discoveredDevices.put(address, result);

            String type = deviceRegistry.identify(toAdvertisement(name, result));
            String previousType = deviceTypes.get(address);
            if (!isKnownType(type) && isKnownType(previousType)) {
                // Keep the previous known type if this advertising packet lacks identifying fields.
                type = previousType;
            }

            if (!matchesActiveScanType(type)) {
                return;
            }

            Log.d(TAG, "[Scan#" + resultCount + "] Identified type: " + type);
            
            // Notify UI for all discovered devices
            deviceTypes.put(address, type);
            Log.i(TAG, "[Scan#" + resultCount + "] Found device: " + name + " [" + address + "] Type: " + type);
            notifyDeviceDiscovered(name, address, type, rssi);
        }

        @Override
        public void onScanFailed(int errorCode) {
            String errorMsg = "Scan failed with error code: " + errorCode;
            if (errorCode == 1) errorMsg += " (SCAN_FAILED_ALREADY_STARTED)";
            else if (errorCode == 2) errorMsg += " (SCAN_FAILED_APPLICATION_REGISTRATION_FAILED)";
            else if (errorCode == 3) errorMsg += " (SCAN_FAILED_INTERNAL_ERROR)";
            else if (errorCode == 4) errorMsg += " (SCAN_FAILED_FEATURE_UNSUPPORTED)";
            else if (errorCode == 5) errorMsg += " (SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES)";
            
            Log.e(TAG, errorMsg);
            notifyError(errorMsg);
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            String address = gatt.getDevice().getAddress();
            // Only the connection this service is tracking may change a device's
            // state. A callback from an older attempt that a retry has replaced
            // is closed and otherwise ignored.
            boolean current = activeGatts.get(address) == gatt;
            if (!current) {
                Log.i(TAG, "Closing superseded connection to " + address + " (state " + newState + ")");
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) gatt.disconnect();
                    gatt.close();
                }
                return;
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "Connected with error status: " + status);
                }
                // Reset retry counter on successful connection
                retryCounters.remove(address);
                establishedLinks.add(address);
                Log.i(TAG, "Connected to " + address);
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    gatt.discoverServices();
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnected from " + address + " with status: " + status);
                activeGatts.remove(address);
                // Every connectGatt registers a client with the Bluetooth stack
                // until close() releases it. Removing the connection from the map
                // without closing it leaked one per failed attempt; once the
                // stack's small pool filled, every new connection failed with
                // 133 or 147. A failed attempt also refreshes the cached service
                // table, which a stale cache can make fail in the same way.
                if (status != BluetoothGatt.GATT_SUCCESS) refreshDeviceCache(gatt);
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    gatt.close();
                }
                mainHandler.post(() -> clearWrites(address));
                
                boolean wasEstablished = establishedLinks.remove(address);
                // Handle specific error statuses with retry logic, for attempts
                // that never connected only
                if (!wasEstablished && (status == 8 || status == 147 || status == 133)) {
                    Log.w(TAG, "Connection failed with status " + status + ", attempting retry...");
                    handleConnectionRetry(address, status);
                } else {
                    // Clear retry counter for normal disconnections
                    retryCounters.remove(address);
                    unregisterDeviceAdapter(address);
                }
                
                notifyDisconnected(address, status);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                String address = gatt.getDevice().getAddress();
                String type = deviceTypes.get(address);
                Log.d(TAG, "onServicesDiscovered: Found " + gatt.getServices().size() + " services for " + address + " type=" + type);
                // Recorded so it is known which standard services (such as
                // Battery) a device offers alongside its own protocol.
                StringBuilder uuids = new StringBuilder();
                for (BluetoothGattService service : gatt.getServices()) {
                    uuids.append(service.getUuid()).append(' ');
                }
                Log.i(TAG, "Services on " + address + ": " + uuids.toString().trim());
                // Android's default connection interval is too slow for a 25 Hz
                // wearable: the τ-Ring delivered 17-19 samples/s with it, short
                // of its configured rate, with no backlog on the phone.
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    // Glasses need a text now and then, not a stream. Two arms at the
                    // fastest interval beside a streaming ring took so much of the
                    // phone's radio that both arms timed out together, 20-30 s after
                    // each connection.
                    boolean glasses = type != null && type.toLowerCase(java.util.Locale.ROOT).contains("glasses");
                    gatt.requestConnectionPriority(glasses
                            ? BluetoothGatt.CONNECTION_PRIORITY_BALANCED
                            : BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                }

                BleDeviceAdapter adapter = deviceAdapters.get(address);
                if (adapter != null) {
                    try {
                        adapter.onBleServicesDiscovered();
                    } catch (RuntimeException ex) {
                        Log.e(TAG, "Adapter service setup failed for " + address, ex);
                        notifyError("Device setup failed for " + type + ": " + ex.getMessage());
                    }
                } else {
                    Log.w(TAG, "No device adapter registered before service discovery for " + address);
                }

                notifyConnected(address, type != null ? type : "Unknown");
            } else {
                Log.e(TAG, "onServicesDiscovered failed with status " + status + " for " + gatt.getDevice().getAddress());
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
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            String address = gatt.getDevice().getAddress();
            byte[] value = characteristic.getValue();
            if (status == BluetoothGatt.GATT_SUCCESS
                    && BATTERY_LEVEL_UUID.equalsIgnoreCase(characteristic.getUuid().toString())
                    && value != null && value.length > 0) {
                int percent = value[0] & 0xFF;
                if (percent <= 100) mainHandler.post(() -> listener.onBatteryLevel(address, percent));
            }
            mainHandler.post(() -> {
                writeInFlight.remove(address);
                drainWrites(address);
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            String address = gatt.getDevice().getAddress();
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "write to " + characteristic.getUuid() + " on " + address + " failed with status " + status);
            }
            mainHandler.post(() -> {
                writeInFlight.remove(address);
                drainWrites(address);
            });
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            Log.d(TAG, "MTU changed to " + mtu + " for " + gatt.getDevice().getAddress() + " status=" + status);
            BleDeviceAdapter adapter = deviceAdapters.get(gatt.getDevice().getAddress());
            if (adapter != null) {
                adapter.onBleMtuChanged(mtu, status);
            }
        }
    };

    private String normalizeScanType(String scanType) {
        if (scanType == null) {
            return SCAN_TYPE_ALL;
        }
        if (deviceRegistry.supports(scanType)) {
            return scanType;
        }
        return SCAN_TYPE_ALL;
    }

    private boolean matchesActiveScanType(String identifiedType) {
        if (SCAN_TYPE_ALL.equals(activeScanType)) {
            return true;
        }

        if (activeScanType.equals(identifiedType)) {
            return true;
        }

        return false;
    }

    private String resolveDeviceName(ScanResult result) {
        final ScanRecord scanRecord = result.getScanRecord();
        if (scanRecord != null) {
            final String recordName = scanRecord.getDeviceName();
            if (recordName != null && !recordName.trim().isEmpty()) {
                return recordName;
            }
        }

        try {
            final BluetoothDevice device = result.getDevice();
            if (device != null) {
                final String name = device.getName();
                if (name != null && !name.trim().isEmpty()) {
                    return name;
                }
            }
        } catch (SecurityException ignored) {
            // Scan results can still be processed without Bluetooth connect permission.
        }

        return null;
    }

    private BleAdvertisement toAdvertisement(String name, ScanResult result) {
        final ScanRecord scanRecord = result.getScanRecord();
        Set<String> services = new HashSet<>();
        Set<Integer> manufacturers = new HashSet<>();
        if (scanRecord != null) {
            if (scanRecord.getServiceUuids() != null) {
                for (ParcelUuid parcelUuid : scanRecord.getServiceUuids()) {
                    if (parcelUuid != null) {
                        services.add(parcelUuid.toString());
                    }
                }
            }
            SparseArray<byte[]> manufacturerData = scanRecord.getManufacturerSpecificData();
            if (manufacturerData != null) {
                for (int i = 0; i < manufacturerData.size(); i++) {
                    manufacturers.add(manufacturerData.keyAt(i));
                }
            }
        }
        return new BleAdvertisement(name, services, manufacturers);
    }

    private boolean isKnownType(String type) {
        return deviceRegistry.supports(type);
    }

    private void notifyDeviceDiscovered(final String name, final String address, final String type, final int rssi) {
        mainHandler.post(() -> listener.onDeviceDiscovered(name, address, type, rssi));
    }

    private void notifyConnected(final String address, final String type) {
        mainHandler.post(() -> listener.onConnected(address, type));
    }

    private void notifyDisconnected(final String address, final int status) {
        mainHandler.post(() -> listener.onDisconnected(address, status));
    }

    private void notifyDataReceived(final String address, final String service, final String characteristic, final byte[] data) {
        final byte[] packetCopy = Arrays.copyOf(data, data.length);
        notificationExecutor.execute(() -> {
            BleDeviceAdapter adapter = deviceAdapters.get(address);
            if (adapter != null) {
                adapter.onBleNotification(service, characteristic, packetCopy);
            }
            listener.onDataReceived(address, service, characteristic, packetCopy);
        });
    }

    private void notifyError(final String error) {
        mainHandler.post(() -> listener.onError(error));
    }

    private void handleConnectionRetry(String address, int status) {
        // Check retry count
        int currentRetries = retryCounters.getOrDefault(address, 0);
        if (currentRetries >= 3) {
            Log.w(TAG, "Max retries reached for " + address + ", giving up");
            retryCounters.remove(address);
            notifyError("Connection failed after 3 attempts for " + address);
            return;
        }

        // Increment retry counter
        retryCounters.put(address, currentRetries + 1);

        // The failed connection was already closed by the disconnect callback.
        // Schedule retry after 2 seconds
        mainHandler.postDelayed(() -> {
            Log.i(TAG, "Retrying connection to " + address + " (attempt " + (currentRetries + 1) + "/3)");
            connect(address);
        }, 2000);
    }

    private boolean refreshDeviceCache(BluetoothGatt gatt) {
        if (gatt != null) {
            try {
                Method refresh = gatt.getClass().getMethod("refresh");
                if (refresh != null) {
                    boolean result = (Boolean) refresh.invoke(gatt);
                    Log.d(TAG, "Device cache refresh result: " + result);
                    return result;
                }
            } catch (Exception e) {
                Log.w(TAG, "Error refreshing device cache: " + e.getMessage());
            }
        }
        return false;
    }

    private boolean isDeviceAvailable(String address) {
        ScanResult result = discoveredDevices.get(address);
        if (result == null) {
            // If not found in recent scan, try a quick scan to check availability
            Log.d(TAG, "Device " + address + " not in recent scan, checking availability...");
            return performQuickDeviceCheck(address);
        }
        
        // Check if the device is still in range (RSSI check)
        int rssi = result.getRssi();
        boolean inRange = rssi > -80; // Reasonable signal strength threshold
        Log.d(TAG, "Device " + address + " RSSI: " + rssi + ", in range: " + inRange);
        return inRange;
    }

    private boolean performQuickDeviceCheck(String address) {
        // A full asynchronous availability probe is outside the connection path.
        if (!isScanning) {
            Log.d(TAG, "Performing quick availability check for " + address);
            return true;
        }
        return false;
    }
}
