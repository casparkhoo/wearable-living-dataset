package com.wearable.controller;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.*;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.wearable.controller.bluetooth.AndroidBLEService;
import com.wearable.controller.impl.AndroidEvenGlassesDevice;
import com.wearable.controller.impl.AndroidOpenEarableDevice;
import com.wearable.controller.impl.AndroidOpenRingDevice;

import java.util.*;

/**
 * Main Activity for Wearable Controller Android App.
 * Centralized interface for controlling all supported wearables.
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 1;
    private static final int REQUEST_ENABLE_BLUETOOTH = 2;
    
    private AndroidBLEService bleService;
    private ListView deviceListView;
    private Button scanButton, connectButton, disconnectButton;
    private TextView statusText;
    private ArrayAdapter<String> deviceListAdapter;
    
    private final List<String> discoveredList = new ArrayList<>();
    private final Map<String, String> deviceAddresses = new HashMap<>(); // Display -> Address
    private final Map<String, String> deviceTypes = new HashMap<>();      // Address -> Type
    private final Map<String, WearableDevice> connectedDevices = new HashMap<>(); // Address -> Device
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        initializeViews();
        setupBLEService();
        checkPermissions();
    }
    
    private void initializeViews() {
        deviceListView = findViewById(R.id.device_list);
        scanButton = findViewById(R.id.scan_button);
        connectButton = findViewById(R.id.connect_button);
        disconnectButton = findViewById(R.id.disconnect_button);
        statusText = findViewById(R.id.status_text);
        
        deviceListAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_single_choice, discoveredList);
        deviceListView.setAdapter(deviceListAdapter);
        deviceListView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        
        scanButton.setOnClickListener(v -> toggleScanning());
        connectButton.setOnClickListener(v -> connectToSelectedDevice());
        disconnectButton.setOnClickListener(v -> disconnectFromDevice());
        
        updateButtonStates(false, false);
    }
    
    private void setupBLEService() {
        bleService = new AndroidBLEService(this, new AndroidBLEService.BLEDataListener() {
            @Override
            public void onDataReceived(String address, String serviceUuid, String charUuid, byte[] data) {
                WearableDevice device = connectedDevices.get(address);
                if (device != null) {
                    // Delegate to the specific implementation
                    if (device instanceof AndroidOpenEarableDevice) {
                        ((AndroidOpenEarableDevice) device).handleRawData(charUuid, data);
                    } else if (device instanceof AndroidOpenRingDevice) {
                        ((AndroidOpenRingDevice) device).handleRawData(data);
                    } else if (device instanceof AndroidEvenGlassesDevice) {
                        ((AndroidEvenGlassesDevice) device).handleRawData(data);
                    }
                }
                
                runOnUiThread(() -> {
                    Log.d(TAG, "Data from " + address + " [" + charUuid + "]: " + bytesToHex(data));
                });
            }
            
            @Override
            public void onConnected(String address, String type) {
                WearableDevice device = createDeviceImplementation(address, type);
                connectedDevices.put(address, device);
                
                runOnUiThread(() -> {
                    updateStatus("Connected to " + type + " (" + address + ")");
                    updateButtonStates(false, true);
                    scanButton.setEnabled(false);
                    
                    // Automatically set a basic data listener to log output
                    device.setDataListener(data -> Log.d(TAG, "Parsed Data: " + data.getSensorType()));
                });
            }
            
            @Override
            public void onDisconnected(String address) {
                connectedDevices.remove(address);
                runOnUiThread(() -> {
                    updateStatus("Disconnected: " + address);
                    updateButtonStates(true, false);
                    scanButton.setEnabled(true);
                });
            }
            
            @Override
            public void onError(String error) {
                runOnUiThread(() -> updateStatus("Error: " + error));
            }
            
            @Override
            public void onDeviceDiscovered(String name, String address, String type, int rssi) {
                runOnUiThread(() -> {
                    String displayText = name + " (" + type + ") [" + address + "]";
                    if (!discoveredList.contains(displayText)) {
                        discoveredList.add(displayText);
                        deviceAddresses.put(displayText, address);
                        deviceTypes.put(address, type);
                        deviceListAdapter.notifyDataSetChanged();
                    }
                });
            }
        });
    }

    private WearableDevice createDeviceImplementation(String address, String type) {
        switch (type) {
            case "OpenEarable":
                return new AndroidOpenEarableDevice(address, bleService);
            case "OpenRing":
                return new AndroidOpenRingDevice(address, bleService);
            case "Even Glasses":
                return new AndroidEvenGlassesDevice(address, bleService);
            default:
                // Fallback or Generic
                return new AndroidOpenEarableDevice(address, bleService);
        }
    }
    
    private void checkPermissions() {
        String[] permissions = {
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        };
        
        List<String> toRequest = new ArrayList<>();
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                toRequest.add(p);
            }
        }
        
        if (!toRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toArray(new String[0]), REQUEST_BLUETOOTH_PERMISSIONS);
        } else {
            checkBluetoothEnabled();
        }
    }
    
    private void checkBluetoothEnabled() {
        if (!bleService.isBluetoothSupported()) {
            updateStatus("BLE not supported");
        } else if (!bleService.isBluetoothEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BLUETOOTH);
        } else {
            updateStatus("Ready");
            scanButton.setEnabled(true);
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            checkBluetoothEnabled();
        }
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BLUETOOTH && resultCode == RESULT_OK) {
            scanButton.setEnabled(true);
        }
    }
    
    private void toggleScanning() {
        if (scanButton.getText().toString().equals("Start Scan")) {
            discoveredList.clear();
            deviceAddresses.clear();
            deviceTypes.clear();
            deviceListAdapter.notifyDataSetChanged();
            bleService.startDeviceDiscovery();
            scanButton.setText("Stop Scan");
            updateStatus("Scanning...");
        } else {
            bleService.stopDeviceDiscovery();
            scanButton.setText("Start Scan");
            updateStatus("Scan stopped");
        }
    }
    
    private void connectToSelectedDevice() {
        int pos = deviceListView.getCheckedItemPosition();
        if (pos == ListView.INVALID_POSITION) return;
        
        String display = discoveredList.get(pos);
        String addr = deviceAddresses.get(display);
        updateStatus("Connecting to " + addr + "...");
        bleService.connect(addr);
    }
    
    private void disconnectFromDevice() {
        // Simple implementation: disconnect all for now or the first one
        for (String addr : connectedDevices.keySet()) {
            bleService.disconnect(addr);
        }
        updateStatus("Disconnecting...");
    }

    public void connectToDevice(String address, String deviceType) {
        updateStatus("Manual connect to " + deviceType + " at " + address);
        bleService.connect(address, deviceType);
    }
    
    private void updateButtonStates(boolean scanning, boolean connected) {
        scanButton.setEnabled(!connected);
        connectButton.setEnabled(!connected && discoveredList.size() > 0);
        disconnectButton.setEnabled(connected);
    }
    
    private void updateStatus(String message) {
        statusText.setText(message);
    }
    
    private String bytesToHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X ", b));
        return sb.toString().trim();
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bleService != null) {
            bleService.stopDeviceDiscovery();
            for (String addr : connectedDevices.keySet()) {
                bleService.disconnect(addr);
            }
        }
    }
}
