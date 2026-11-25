package com.wearable.controller;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.widget.*;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.viewpager2.widget.ViewPager2;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import com.wearable.controller.bluetooth.AndroidBLEService;
import com.wearable.controller.bluetooth.BleDeviceAdapter;
import com.wearable.controller.fragments.ConnectedDevicesFragment;
import com.wearable.controller.fragments.MqttFragment;
import com.wearable.controller.fragments.RecordFragment;
import com.wearable.controller.adapters.MainPagerAdapter;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Main Activity for Wearable Controller Android App.
 * Centralized interface for controlling all supported wearables with live console logging and Data Bridge.
 */
public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 1;
    private static final int REQUEST_ENABLE_BLUETOOTH = 2;
    private static final int MAX_LOG_LINES = 100;

    private AndroidBLEService bleService;
    private final WearableDeviceRegistry deviceRegistry = WearableDeviceRegistry.shared();
    /** Drivers for wearables that show the live dashboard; made from the registered profiles. */
    private final List<HeadUpDisplay> displays = new ArrayList<>();
    private com.google.gson.JsonObject latestLiveData;
    private MQTTManager mqttManager;
    private final RestApiClient restApiClient = new RestApiClient();
    private SupabaseAuth supabaseAuth;
    private final java.util.concurrent.ExecutorService authExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private MainPagerAdapter mainPagerAdapter;
    private TabLayout tabLayout;
    private ViewPager2 viewPager;

    /** A search the participant started from "Add a wearable". */
    private boolean isScanning = false;
    /** A short background search for remembered wearables that are missing. */
    private boolean autoScanning = false;
    /** Wearables seen during a participant's search, by address, in discovery order. */
    private final Map<String, String> nearbyDevices = new LinkedHashMap<>();
    /** The kind of wearable being added, chosen before searching. */
    private String searchType = "";
    private int searchGeneration;
    private final Map<String, String> deviceNames = new HashMap<>();      // Address -> Display Name
    /** Last battery percentage per wearable, for those that report one. */
    private final Map<String, Integer> batteryLevels = new HashMap<>();
    private static final long BATTERY_POLL_MS = 60000;
    private final Runnable batteryTick = new Runnable() {
        @Override
        public void run() {
            for (String address : new ArrayList<>(connectedDevices.keySet())) requestBattery(address);
            uiHandler.postDelayed(this, BATTERY_POLL_MS);
        }
    };
    private final Map<String, String> deviceTypes = new HashMap<>();      // Address -> Type
    private final Map<String, WearableDevice> connectedDevices = new HashMap<>(); // Address -> Device
    private final Map<String, WearableDevice> pendingDevices = new HashMap<>();   // Address -> Adapter awaiting service discovery
    private final Set<String> pendingConnections = new HashSet<>();       // Addresses currently connecting
    
    private final LinkedList<String> logLines = new LinkedList<>();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
    
    private ConnectedDevicesFragment connectedDevicesFragment;
    private MqttFragment mqttFragment;
    private RecordFragment recordFragment;
    private Long currentExperimentId;
    private String currentExperimentLabel;
    private Long currentExperimentStartedAtMs;
    private boolean experimentActive;
    private TextView serverLabel;

    /** Switching server from the app: refuse while recording, otherwise end the session cleanly. */
    private final ServerSwitcher.Host serverHost = new ServerSwitcher.Host() {
        @Override
        public boolean canSwitch() {
            return !experimentActive;
        }

        @Override
        public void onServerChanged(String message) {
            // InstanceConfig already removed the broker login, topic prefix and API token.
            // Sign out, then restart at the login screen with CLEAR_TASK so this activity
            // (and its broker connection, closed in onDestroy) does not survive.
            clearParticipantMqttCredentials();
            new Thread(() -> {
                if (supabaseAuth != null) supabaseAuth.signOut();
                runOnUiThread(() -> {
                    displayName = "";
                    startActivity(new Intent(MainActivity.this, LoginActivity.class)
                            .putExtra(LoginActivity.EXTRA_MESSAGE, message)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                    finish();
                });
            }).start();
        }
    };

    private void refreshServerLabel() {
        if (serverLabel != null) serverLabel.setText(ServerSwitcher.summary(this) + "  ▾");
    }
    private boolean restoredActiveWarningShown;
    private final Set<Long> activeEvidenceSessionIds = new HashSet<>();
    private Long activeAnnotationId;
    private String activeLabelValue;
    private Long activeLabelStartedAtMs;
    private boolean recordingTransitionInProgress;
    /** Latest live analysis from the backend, shown above the control status. */
    private String latestLiveStatus = "";
    private float[] liveTracePpg = new float[0];
    private float[] liveTraceAccel = new float[0];
    private float[] liveTraceHr = new float[0];
    private String liveTraceHrRange = "";
    /**
     * Status topics this phone listens to. A signed-in participant may only
     * read their own {@code status/u<id>/#}; the broker refuses wider filters.
     */
    private String statusTopicFilter = "status/#";
    private boolean backendHealthCheckInProgress;
    private boolean backendOnline;
    /** The account's name from the backend, shown as who is recording. */
    private String displayName = "";
    private long lastBackendHealthCheckMs;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable mqttStatusTick = new Runnable() {
        @Override
        public void run() {
            refreshMqttTab();
            maybeCheckBackendConnection();
            refreshRecordTab();
            // Keeps the process running with the screen off or the app in the
            // background; changes the notification only when this state changes.
            CaptureService.update(MainActivity.this, connectedDevices.size(), experimentActive,
                    currentExperimentLabel);
            uiHandler.postDelayed(this, 1000);
        }
    };

    /** How often missing remembered wearables are looked for, and for how long. */
    private static final long RECONNECT_INTERVAL_MS = 20000;
    private static final long AUTO_SCAN_MS = 8000;
    private static final long USER_SCAN_MS = 30000;

    /**
     * Looking for wearables is not free: one left in its charging case that
     * is connected to again keeps waking, and a scan every 20 s drains the
     * phone. Each scan that finds nothing doubles the wait up to this limit; a
     * wearable that was switched off by the participant, or that connects and
     * drops straight away, is left alone until it is asked for.
     */
    private static final long RECONNECT_MAX_MS = 60000;
    private static final long STABLE_CONNECTION_MS = 60000;
    private static final int FLAPS_BEFORE_PAUSE = 3;
    private long reconnectDelayMs = RECONNECT_INTERVAL_MS;
    private final Map<String, Long> connectedSince = new HashMap<>();
    private final Map<String, Integer> flapCounts = new HashMap<>();

    private final Runnable reconnectTick = new Runnable() {
        @Override
        public void run() {
            reconnectRememberedWearables();
            uiHandler.postDelayed(this, reconnectDelayMs);
        }
    };

    /** Start looking again at the normal pace, and soon. */
    private void resetReconnectBackoff() {
        reconnectDelayMs = RECONNECT_INTERVAL_MS;
        uiHandler.removeCallbacks(reconnectTick);
        uiHandler.postDelayed(reconnectTick, 1500);
    }

    private boolean inForeground = false;

    @Override
    protected void onPause() {
        super.onPause();
        inForeground = false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        inForeground = true;
        refreshServerLabel();
        // Opening the app is a sign the participant is about to use it.
        if (reconnectDelayMs > RECONNECT_INTERVAL_MS && bleService != null) resetReconnectBackoff();
    }

    /**
     * Wearables the participant disconnected, and until when they are left alone.
     *
     * A disconnect used to be permanent and saved across launches: a wearable
     * disconnected by mistake (or by a double tap on Connect, which turns into
     * Cancel) was never reconnected again until someone noticed. Now it is a
     * short break, kept in memory only: reconnection resumes after
     * PAUSE_MS, when the app is next started, or at once when Connect is tapped.
     */
    private static final long PAUSE_MS = 10 * 60 * 1000L;
    private final Map<String, Long> pausedUntil = new HashMap<>();

    private Set<String> pausedWearables() {
        long now = System.currentTimeMillis();
        pausedUntil.values().removeIf(until -> until <= now);
        return new HashSet<>(pausedUntil.keySet());
    }

    private void setWearablePaused(String address, boolean paused) {
        if (paused) pausedUntil.put(address, System.currentTimeMillis() + PAUSE_MS);
        else pausedUntil.remove(address);
    }

    /** Wearables whose pairing was rejected and re-done this session, so it is tried once. */
    private final Set<String> repairedPairing = new HashSet<>();
    private final Map<String, Long> repairedAtMs = new HashMap<>();
    /** When each device last refused the stored pairing (GATT 5 or 137). */
    private final Map<String, Long> pairingRejectedAtMs = new HashMap<>();
    /** When each background (wait-for-it) connection was asked for. */
    private final Map<String, Long> backgroundSince = new HashMap<>();
    /** Wearables the stack would not wait for; found by scanning instead. */
    private final Set<String> backgroundRefused = new HashSet<>();
    /** A background connection that has not completed in this long is renewed. */
    private static final long BACKGROUND_RENEW_MS = 90000;


    /**
     * Draw the whole app at a smaller scale: more fits on the screen and there is less
     * scrolling. A uniform change of density rather than per-view sizes, so dialogs and
     * every tab follow.
     */
    @Override
    protected void attachBaseContext(android.content.Context base) {
        android.content.res.Configuration config = new android.content.res.Configuration(base.getResources().getConfiguration());
        config.densityDpi = Math.round(config.densityDpi * 0.94f);
        super.attachBaseContext(base.createConfigurationContext(config));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Apply a saved appearance before inflating views so all resource colours
        // use the selected mode from the first frame. Without a preference, the
        // Material DayNight theme follows the device setting.
        if (!getSharedPreferences("display", MODE_PRIVATE).contains("dark_mode")) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        }
        if (getSharedPreferences("display", MODE_PRIVATE).contains("dark_mode")) {
            boolean dark = getSharedPreferences("display", MODE_PRIVATE)
                    .getBoolean("dark_mode", false);
            AppCompatDelegate.setDefaultNightMode(dark
                    ? AppCompatDelegate.MODE_NIGHT_YES
                    : AppCompatDelegate.MODE_NIGHT_NO);
        }
        // The tab fragments are created by MainPagerAdapter and wired to this
        // activity directly. Restoring saved ones (as happens when the appearance
        // switch recreates the activity) would bring back old instances that
        // none of that wiring points at, and Settings would show signed out.
        super.onCreate(null);
        // Pauses used to be saved permanently; drop any left from older versions.
        getSharedPreferences("wearables", MODE_PRIVATE).edit().remove("paused").apply();
        createDisplays();
        for (HeadUpDisplay display : displays) display.startDebugTools();
        // A wearable the wearer disconnected stays disconnected, across launches too,
        // until they tap Connect on it; the Devices tab says so on its row.
        setContentView(R.layout.activity_main);
        serverLabel = findViewById(R.id.record_server_label);
        if (serverLabel != null) {
            serverLabel.setOnClickListener(v -> ServerSwitcher.showPicker(this, serverHost));
            refreshServerLabel();
        }

        initializeViews();
        setupViewPager();
        setupRestControl();
        setupBLEService();
        setupMQTT();
        setupAuth();
        // Only now is a restored session attached to outgoing requests; a
        // check made earlier would go out without it and report a 401.
        if (!restApiClient.getBaseUrl().isEmpty()) checkBackendConnection(true);
        checkPermissions();
        uiHandler.postDelayed(mqttStatusTick, 1000);
        uiHandler.postDelayed(reconnectTick, 1500);
        uiHandler.postDelayed(batteryTick, BATTERY_POLL_MS);
        // Back leaves the app running, like Home: finishing this activity would
        // close every wearable and the broker connection.
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                moveTaskToBack(true);
            }
        });
        appendLog("System initialized. Awaiting device...");
    }

    private void initializeViews() {
        tabLayout = findViewById(R.id.tab_layout);
        viewPager = findViewById(R.id.view_pager);
        // Pages are recreated on purpose; restoring saved page state asks the fragment
        // manager for fragments it no longer has and crashed on the dark-mode switch.
        viewPager.setSaveEnabled(false);
    }

    private void setupViewPager() {
        mainPagerAdapter = new MainPagerAdapter(this);
        viewPager.setAdapter(mainPagerAdapter);
        // Keep every page alive: there are only three, and none should lose
        // its state or miss an update while the participant is elsewhere.
        viewPager.setOffscreenPageLimit(MainPagerAdapter.NUM_TABS - 1);

        recordFragment = mainPagerAdapter.getRecordFragment();
        connectedDevicesFragment = mainPagerAdapter.getConnectedDevicesFragment();
        mqttFragment = mainPagerAdapter.getMqttFragment();

        recordFragment.setActionListener(new RecordFragment.ActionListener() {
            @Override
            public void onToggleRecording(String name, String protocol) {
                if (experimentActive) {
                    stopGuidedRecording();
                } else {
                    startGuidedRecording(name, protocol);
                }
            }

            @Override
            public void onLabelTapped(String label) {
                toggleActivityLabel(label);
            }

            @Override
            public void onStudySelected(Long studyId) {
                getSharedPreferences("record", MODE_PRIVATE).edit()
                        .putLong("study_id", studyId == null ? -1 : studyId).apply();
            }

            @Override
            public void onOpenSettings() {
                if (supabaseAuth != null && supabaseAuth.isSignedIn()) {
                    viewPager.setCurrentItem(MainPagerAdapter.SETTINGS_TAB);
                } else {
                    openLoginScreen();
                }
            }

            @Override
            public void onOpenDevices() {
                viewPager.setCurrentItem(MainPagerAdapter.DEVICES_TAB);
            }
        });

        connectedDevicesFragment.setSupportedTypes(deviceRegistry.getSupportedTypes());
        connectedDevicesFragment.setActionListener(new ConnectedDevicesFragment.ActionListener() {
            @Override
            public void onStartSearch(String deviceType) {
                startSearch(deviceType);
            }

            @Override
            public void onStopSearch() {
                endUserScan();
            }

            @Override
            public void onConnectNearby(String address) {
                connectToAddress(address);
            }

            @Override
            public void onConnectNow(String address) {
                connectToAddress(address);
            }

            @Override
            public void onDisconnect(String address) {
                disconnectByUser(address);
            }

            @Override
            public void onForget(String address) {
                forgetWearable(address);
            }

            @Override
            public void onCommand(String address, String command) {
                handleDeviceCommand(address, command);
            }
        });

        mqttFragment.setMqttActionListener(new MqttFragment.MqttActionListener() {
            @Override
            public void onSelectProfile(String profile) {
                selectConnectionProfile(profile);
            }

            @Override
            public void onConnectBroker(String brokerUrl, String username, String password) {
                connectToBroker(brokerUrl, username, password);
            }

            @Override
            public void onDisconnectBroker() {
                disconnectBroker();
            }
            @Override
            public void onSaveBackend(String backendUrl, String apiToken) {
                saveBackendUrl(backendUrl, apiToken);
            }
            @Override
            public void onSignIn(String email, String password) {
                signIn(email, password);
            }
            @Override
            public void onSignOut() {
                signOut();
            }
            @Override
            public void onSwitchServer() {
                ServerSwitcher.showPicker(MainActivity.this, serverHost);
            }
            @Override
            public void onSignUp(String email, String password) {
                signUp(email, password);
            }
            @Override
            public void onResetPassword(String email) {
                resetPassword(email);
            }
        });

        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            switch (position) {
                case MainPagerAdapter.RECORD_TAB:
                    tab.setText("Record");
                    break;
                case MainPagerAdapter.DEVICES_TAB:
                    tab.setText("Devices");
                    break;
                case MainPagerAdapter.SETTINGS_TAB:
                    tab.setText("Settings");
                    break;
            }
        }).attach();

        refreshDevices();
    }
    
    private void setupBLEService() {
        bleService = new AndroidBLEService(this, new AndroidBLEService.BLEDataListener() {
            @Override
            public void onDataReceived(final String address, final String serviceUuid, final String charUuid, final byte[] data) {
                // AndroidBLEService routes notifications through the registered adapter.
                // This callback remains available for optional raw-packet diagnostics.
            }

            @Override
            public void onConnected(final String address, final String type) {
                final String storedType = deviceTypes.get(address);
                final String confirmedType = (storedType != null) ? storedType : type;
                final WearableDevice existingDevice = connectedDevices.get(address);
                WearableDevice resolvedDevice = existingDevice != null
                        ? existingDevice
                        : pendingDevices.remove(address);
                if (resolvedDevice == null && bleService.getRegisteredDeviceAdapter(address) instanceof WearableDevice) {
                    resolvedDevice = (WearableDevice) bleService.getRegisteredDeviceAdapter(address);
                }
                final WearableDevice device = resolvedDevice;
                pendingDevices.remove(address);

                if (device == null) {
                    pendingConnections.remove(address);
                    runOnUiThread(() -> appendLog("ERROR: No registered adapter for " + confirmedType));
                    bleService.disconnect(address);
                    return;
                }

                pendingConnections.remove(address);
                connectedDevices.put(address, device);
                connectedSince.put(address, System.currentTimeMillis());
                // Connected: no longer waiting, and once connected the stack knows
                // the device's address, so waiting for it in future works.
                backgroundSince.remove(address);
                backgroundRefused.remove(address);
                deviceTypes.put(address, confirmedType);
                bleService.setDeviceType(address, confirmedType);
                device.setConnected(true);
                setupDeviceDataListener(device);

                runOnUiThread(() -> {
                    appendLog("CONNECTED: " + confirmedType + " [" + address + "]");
                    rememberWearable(address, confirmedType, deviceNames.getOrDefault(address, confirmedType));
                    nearbyDevices.remove(address);
                    // A wearable that drops out mid-recording and comes back
                    // resumes streaming into its still-open device recording.
                    // Give the adapter's own setup (MTU, notifications) its turn
                    // on the radio, then pick up where the wearable left off.
                    uiHandler.postDelayed(() -> resumeStreaming(address), 1500);
                    // After the adapter's own setup has had its turn on the radio.
                    uiHandler.postDelayed(() -> requestBattery(address), 3000);
                    for (HeadUpDisplay display : displays) {
                        if (display.deviceType().equalsIgnoreCase(confirmedType)) display.onDeviceConnected(address);
                    }
                    // No greeting on the lenses: in the logs, both arms went silent within
                    // about 20 s of every reconnect that showed one, and stayed up for
                    // minutes when no text was sent at all.
                    refreshDevices();
                });
            }

            @Override
            public void onDisconnected(final String address, final int gattStatus) {
                runOnUiThread(() -> handleDisconnectedState(address, true, gattStatus));
            }

            @Override
            public void onError(final String error) {
                final String fError = error;
                // The service reports a started scan through its error channel;
                // with background reconnection that would fill the log.
                if (fError != null && fError.startsWith("Scanning for")) return;
                runOnUiThread(() -> appendLog("ERROR: " + fError));
            }

            @Override
            public void onBatteryLevel(final String address, final int percent) {
                runOnUiThread(() -> updateBattery(address, percent));
            }

            @Override
            public void onDeviceDiscovered(final String name, final String address, final String type, final int rssi) {
                runOnUiThread(() -> handleDiscoveredDevice(name, address, type, rssi));
            }
        }, deviceRegistry);
    }

    private void setupMQTT() {
        String profile = activeConnectionProfile();
        String savedBroker = profileValue(profile, "broker_url", MQTTManager.DEFAULT_BROKER);
        String savedUsername = profileValue(profile, "mqtt_username", "");
        String savedPassword = profileValue(profile, "mqtt_password", "");
        statusTopicFilter = profileValue(profile, "status_topic_filter", "status/#");
        mqttManager = new MQTTManager(savedBroker, profileValue(profile, "topic_prefix", "sensors"),
                savedUsername, savedPassword);
        mqttManager.setConnectionListener(new MQTTManager.ConnectionListener() {
            @Override
            public void onConnected() {
                mqttManager.subscribe(statusTopicFilter, 1);
                runOnUiThread(() -> {
                    appendLog("MQTT: Connected to broker");
                    refreshMqttTab();
                });
            }

            @Override
            public void onDisconnected() {
                runOnUiThread(() -> {
                    appendLog("MQTT: Disconnected");
                    refreshMqttTab();
                });
            }

            @Override
            public void onConnectionFailed(Throwable cause) {
                runOnUiThread(() -> {
                    appendLog("MQTT ERROR: " + cause.getMessage());
                    refreshMqttTab();
                });
            }
        });
        mqttManager.setMessageListener((topic, payload) -> runOnUiThread(() -> {
            if (topic.contains("live/")) {
                handleLiveMessage(payload);
                return;
            }
            appendLog("MQTT STATUS: " + topic);
        }));

        if (mqttFragment != null) {
            mqttFragment.setDefaults(savedBroker, mqttManager.getTopicPrefix(), mqttManager.getQoS());
            mqttFragment.setProfile(profile);
            mqttFragment.setConnectionValues(
                    savedBroker,
                    savedUsername,
                    savedPassword,
                    restApiClient.getBaseUrl(),
                    profileValue(profile, "api_token", ""));
        }
        if ("cloud".equals(profile) && savedUsername.isEmpty()) {
            // The cloud broker only accepts a participant's own account, which
            // arrives with sign-in; connecting without it would just be refused.
            appendLog("MQTT: sign in to connect to the cloud");
        } else if (!savedBroker.trim().isEmpty()) {
            appendLog("MQTT: Restoring saved broker " + savedBroker);
            new Thread(() -> mqttManager.connect()).start();
        }
    }

    private void connectToBroker(final String url, final String username, final String password) {
        final String normalizedUrl = url.contains("://") ? url : "tcp://" + url;
        String profile = activeConnectionProfile();
        if ("cloud".equals(profile)) {
            appendLog("MQTT: the cloud connection is set up by signing in");
            return;
        }
        getSharedPreferences("connections", MODE_PRIVATE)
                .edit()
                .putString(profile + "_broker_url", normalizedUrl)
                .putString(profile + "_mqtt_username", username == null ? "" : username)
                .putString(profile + "_mqtt_password", password == null ? "" : password)
                .apply();
        appendLog("MQTT: Connecting to " + normalizedUrl + "...");
        if (mqttFragment != null) {
            mqttFragment.setStatus(false, true, normalizedUrl);
        }
        new Thread(() -> {
            final boolean ok = mqttManager.reconnectTo(normalizedUrl, username, password);
            runOnUiThread(() -> {
                appendLog("MQTT: " + (ok ? "Connected to " + normalizedUrl : "Failed to connect to " + normalizedUrl));
                refreshMqttTab();
            });
        }).start();
    }

    private void disconnectBroker() {
        appendLog("MQTT: Disconnecting...");
        new Thread(() -> {
            mqttManager.disconnect();
            runOnUiThread(this::refreshMqttTab);
        }).start();
    }

    private void refreshMqttTab() {
        if (mqttFragment == null || mqttManager == null) {
            return;
        }
        mqttFragment.setStatus(mqttManager.isConnected(), mqttManager.isConnecting(), mqttManager.getBrokerUrl());
        mqttFragment.setStats(mqttManager.getPublishCount(), mqttManager.getErrorCount());
    }

    private void setupRestControl() {
        String profile = activeConnectionProfile();
        String savedBackend = profileValue(profile, "backend_url", "");
        restApiClient.setBaseUrl(savedBackend);
        restApiClient.setBearerToken(profileValue(profile, "api_token", ""));
        if (mqttFragment != null) {
            mqttFragment.setBackendUrl(restApiClient.getBaseUrl());
        }
        if (restApiClient.getBaseUrl().isEmpty() && mqttFragment != null) {
            mqttFragment.setBackendStatus("untested",
                    "Enter the PC address, for example http://192.168.137.1:8080.");
        }
    }

    private void saveBackendUrl(String backendUrl, String apiToken) {
        if ("cloud".equals(activeConnectionProfile())) {
            appendLog("API: the cloud address is built into the app");
            return;
        }
        restApiClient.setBaseUrl(backendUrl);
        restApiClient.setBearerToken(apiToken);
        String profile = activeConnectionProfile();
        getSharedPreferences("connections", MODE_PRIVATE)
                .edit()
                .putString(profile + "_backend_url", restApiClient.getBaseUrl())
                .putString(profile + "_api_token", apiToken == null ? "" : apiToken.trim())
                .apply();
        if (mqttFragment != null) {
            mqttFragment.setBackendUrl(restApiClient.getBaseUrl());
        }
        appendLog("API: URL set to " + restApiClient.getBaseUrl());
        if (!restApiClient.getBaseUrl().isEmpty()) {
            checkBackendConnection(true);
        } else if (mqttFragment != null) {
            mqttFragment.setBackendStatus("untested", "No backend address is saved.");
        }
    }

    private String activeConnectionProfile() {
        return getSharedPreferences("connections", MODE_PRIVATE)
                .getString("active_profile", "cloud");
    }

    private String profileValue(String profile, String key, String fallback) {
        // The cloud endpoints are part of the build, not something a
        // participant types; values saved by older versions (including
        // mistyped ones) are ignored. The shared REST token is retired, so the
        // cloud never sends one: the signed-in account's token is used instead.
        if ("cloud".equals(profile)) {
            if ("backend_url".equals(key)) return InstanceConfig.backendUrl(this);
            if ("broker_url".equals(key)) return InstanceConfig.brokerUrl(this);
            if ("api_token".equals(key)) return "";
        }
        android.content.SharedPreferences preferences = getSharedPreferences("connections", MODE_PRIVATE);
        if (preferences.contains(profile + "_" + key)) {
            return preferences.getString(profile + "_" + key, fallback);
        }
        if ("local".equals(profile) && "broker_url".equals(key)) {
            return getSharedPreferences("mqtt", MODE_PRIVATE)
                    .getString("broker_url", fallback);
        }
        if ("local".equals(profile) && "backend_url".equals(key)) {
            return getSharedPreferences("backend", MODE_PRIVATE)
                    .getString("base_url", fallback);
        }
        return fallback;
    }

    private void selectConnectionProfile(String profile) {
        if (!"cloud".equals(profile)) profile = "local";
        getSharedPreferences("connections", MODE_PRIVATE)
                .edit().putString("active_profile", profile).apply();

        String broker = profileValue(profile, "broker_url", MQTTManager.DEFAULT_BROKER);
        String username = profileValue(profile, "mqtt_username", "");
        String password = profileValue(profile, "mqtt_password", "");
        String backend = profileValue(profile, "backend_url", "");
        String token = profileValue(profile, "api_token", "");
        restApiClient.setBaseUrl(backend);
        restApiClient.setBearerToken(token);
        statusTopicFilter = profileValue(profile, "status_topic_filter", "status/#");
        if (mqttFragment != null) {
            mqttFragment.setConnectionValues(broker, username, password, backend, token);
        }
        if (mqttManager != null) {
            mqttManager.setTopicPrefix(profileValue(profile, "topic_prefix", "sensors"));
            new Thread(() -> {
                mqttManager.disconnect();
                mqttManager.clearSubscriptions();
                if (!broker.trim().isEmpty()) {
                    mqttManager.reconnectTo(broker, username, password);
                }
                runOnUiThread(this::refreshMqttTab);
            }).start();
        }
        appendLog("Connection profile selected: " + ("cloud".equals(profile) ? "Cloud" : "Local"));
        if (!backend.trim().isEmpty()) checkBackendConnection(true);
        fetchParticipantMqttCredentials();
    }

    private void maybeCheckBackendConnection() {
        if (restApiClient.getBaseUrl().isEmpty() || backendHealthCheckInProgress) return;
        if (System.currentTimeMillis() - lastBackendHealthCheckMs >= 15000) {
            checkBackendConnection(false);
        }
    }

    private void checkBackendConnection(boolean restoreControlState) {
        if (restApiClient.getBaseUrl().isEmpty() || backendHealthCheckInProgress) return;
        backendHealthCheckInProgress = true;
        lastBackendHealthCheckMs = System.currentTimeMillis();
        if (mqttFragment != null) {
            mqttFragment.setBackendStatus("testing",
                    "Testing " + restApiClient.getBaseUrl() + "/health");
        }
        restApiClient.checkHealth(new RestApiClient.ResultCallback() {
            @Override
            public void onSuccess(com.google.gson.JsonObject response) {
                runOnUiThread(() -> {
                    backendHealthCheckInProgress = false;
                    lastBackendHealthCheckMs = System.currentTimeMillis();
                    String database = response.has("database")
                            ? response.get("database").getAsString() : "database unknown";
                    backendOnline = true;
                    if (mqttManager != null) {
                        mqttManager.setBatchIngest(response.has("batch_ingest")
                                && response.get("batch_ingest").getAsBoolean());
                        mqttManager.setGzipIngest(response.has("gzip_ingest")
                                && response.get("gzip_ingest").getAsBoolean());
                    }
                    mqttFragment.setBackendStatus("connected",
                            restApiClient.getBaseUrl() + " · " + database);
                    if (restoreControlState) restoreActiveControlState();
                    refreshRecordTab();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    backendHealthCheckInProgress = false;
                    lastBackendHealthCheckMs = System.currentTimeMillis();
                    backendOnline = false;
                    mqttFragment.setBackendStatus("failed", message);
                    appendLog("API UNAVAILABLE: " + message);
                    refreshRecordTab();
                });
            }
        });
    }

    private void restoreActiveControlState() {
        restApiClient.getActiveControlState(new RestApiClient.ResultCallback() {
            @Override
            public void onSuccess(com.google.gson.JsonObject response) {
                runOnUiThread(() -> {
                    experimentActive = response.has("active") && response.get("active").getAsBoolean();
                    if (mqttManager != null) {
                        if (experimentActive) mqttManager.getSessionGate().restoredActive();
                        else mqttManager.getSessionGate().restoredIdle();
                    }
                    activeEvidenceSessionIds.clear();
                    activeAnnotationId = null;
                    if (experimentActive) {
                        currentExperimentId = response.get("experiment_id").getAsLong();
                        currentExperimentLabel = response.has("experiment_label")
                                ? response.get("experiment_label").getAsString() : "Unnamed experiment";
                        currentExperimentStartedAtMs = response.has("started_at_ms")
                                && !response.get("started_at_ms").isJsonNull()
                                ? response.get("started_at_ms").getAsLong() : null;
                        String deviceRecordingKey = response.has("device_recording_ids")
                                ? "device_recording_ids" : "evidence_session_ids";
                        if (response.has(deviceRecordingKey)) {
                            for (com.google.gson.JsonElement value : response.getAsJsonArray(deviceRecordingKey)) {
                                activeEvidenceSessionIds.add(value.getAsLong());
                            }
                        }
                        if (response.has("active_annotation_ids") && response.getAsJsonArray("active_annotation_ids").size() > 0) {
                            activeAnnotationId = response.getAsJsonArray("active_annotation_ids").get(0).getAsLong();
                            // The control state carries the label's id, not its value.
                            if (activeLabelValue == null) activeLabelValue = "Current activity";
                        }
                        // Wearables that reconnected before the recording was
                        // known about were never told to start measuring.
                        for (String address : new ArrayList<>(connectedDevices.keySet())) {
                            resumeStreaming(address);
                        }
                        if (!restoredActiveWarningShown) {
                            restoredActiveWarningShown = true;
                            String started = currentExperimentStartedAtMs == null ? "an unknown time"
                                    : new java.text.SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())
                                    .format(new Date(currentExperimentStartedAtMs));
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("Existing recording restored")
                                    .setMessage("Experiment #" + currentExperimentId + " — " +
                                            currentExperimentLabel + " — started " + started +
                                            ".\n\nThis is not a new experiment. End this recording before starting another.")
                                    .setPositiveButton("Understood", null)
                                    .show();
                        }
                    } else {
                        currentExperimentId = null;
                        currentExperimentLabel = null;
                        currentExperimentStartedAtMs = null;
                    }
                    refreshRecordTab();
                });
            }

            @Override
            public void onError(String message) {
                reportRestError("load active control state", message);
            }
        });
    }

    /**
     * Guided control path: one user action starts the experiment and then creates
     * one device recording for every currently connected wearable.
     */
    private void startGuidedRecording(String name, String protocol) {
        if (recordingTransitionInProgress) return;
        if (restApiClient.getBaseUrl().isEmpty()) {
            Toast.makeText(this, "Sign in first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (experimentActive) {
            Toast.makeText(this, "Experiment #" + currentExperimentId + " (" +
                    currentExperimentLabel + ") is already active. End it first.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (connectedDevices.isEmpty()) {
            Toast.makeText(this, "Connect at least one wearable first", Toast.LENGTH_SHORT).show();
            return;
        }
        // One tap: a recording is named after who made it and when, unless
        // the participant typed a name under "More options".
        final String label = name != null && !name.trim().isEmpty()
                ? name.trim()
                : recorderName() + " · " + new SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(new Date());
        final Long studyId = recordFragment == null ? null : recordFragment.getSelectedStudyId();

        final List<RestApiClient.DeviceRecordingSpec> deviceRecordings = new ArrayList<>();
        for (Map.Entry<String, WearableDevice> entry : connectedDevices.entrySet()) {
            final String address = entry.getKey();
            final String type = deviceTypes.getOrDefault(address, "Unknown");
            deviceRecordings.add(new RestApiClient.DeviceRecordingSpec(type, address, null));
        }
        recordingTransitionInProgress = true;
        // Hold sensor payloads until the backend has the session: it rejects data for a
        // device with no session, yet the broker acknowledges it, so it would be lost.
        if (mqttManager != null) mqttManager.getSessionGate().beginStart();
        refreshRecordTab();
        restApiClient.startRecording(label, protocol, deviceRecordings, System.currentTimeMillis(), studyId,
                new RestApiClient.ResultCallback() {
                    @Override
                    public void onSuccess(com.google.gson.JsonObject response) {
                        runOnUiThread(() -> {
                            currentExperimentId = response.get("id").getAsLong();
                            currentExperimentLabel = label;
                            currentExperimentStartedAtMs = System.currentTimeMillis();
                            experimentActive = true;
                            activeEvidenceSessionIds.clear();
                            if (response.has("device_recordings")) {
                                for (com.google.gson.JsonElement item : response.getAsJsonArray("device_recordings")) {
                                    activeEvidenceSessionIds.add(item.getAsJsonObject().get("id").getAsLong());
                                }
                            }
                            if (mqttManager != null) {
                                int held = mqttManager.getHeldCount();
                                mqttManager.getSessionGate().startSucceeded();
                                if (held > 0) appendLog("MQTT: session active, releasing " + held + " held samples");
                            }
                            startConnectedWearableAcquisition();
                            recordingTransitionInProgress = false;
                            appendLog("API: Started experiment " + currentExperimentId
                                    + " with " + activeEvidenceSessionIds.size() + " device recording(s)");
                            Toast.makeText(MainActivity.this,
                                    "Recording started for " + activeEvidenceSessionIds.size() + " wearable(s)",
                                    Toast.LENGTH_SHORT).show();
                            refreshRecordTab();
                        });
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> {
                            recordingTransitionInProgress = false;
                            if (mqttManager != null) {
                                mqttManager.getSessionGate().startFailed();
                                int held = mqttManager.getHeldCount();
                                if (held > 0) {
                                    appendLog("MQTT: start failed, keeping " + held
                                            + " samples; they go to the next recording that starts");
                                    Toast.makeText(MainActivity.this, "Recording did not start; " + held
                                            + " samples are kept and not sent", Toast.LENGTH_LONG).show();
                                }
                            }
                            refreshRecordTab();
                        });
                        reportRestError("start recording", message);
                    }
                });
    }

    private void startConnectedWearableAcquisition() {
        for (Map.Entry<String, WearableDevice> entry : connectedDevices.entrySet()) {
            startAcquisition(entry.getKey(), entry.getValue());
        }
    }

    private void startAcquisition(String address, WearableDevice device) {
        // Each adapter declares its own start sequence (WearableDevice).
        for (String command : device.getRecordingStartCommands()) {
            sendDeviceCommand(device, address, command, new HashMap<>());
        }
    }

    private void stopConnectedWearableAcquisition() {
        for (Map.Entry<String, WearableDevice> entry : connectedDevices.entrySet()) {
            for (String command : entry.getValue().getRecordingStopCommands()) {
                sendDeviceCommand(entry.getValue(), entry.getKey(), command, new HashMap<>());
            }
        }
    }

    /** Backend stop is atomic: it closes the experiment, all device recordings, and any open activity label. */
    private void stopGuidedRecording() {
        if (recordingTransitionInProgress || currentExperimentId == null || !experimentActive) {
            Toast.makeText(this, "No active recording", Toast.LENGTH_SHORT).show();
            return;
        }
        recordingTransitionInProgress = true;
        refreshRecordTab();
        final long experimentId = currentExperimentId;
        stopConnectedWearableAcquisition();
        final long stoppedAtMs = System.currentTimeMillis();
        // Let the queued samples reach the cloud before the recording closes,
        // or the backend rejects them as belonging to an ended session.
        // Flush first (every pending payload published and confirmed, up to 10 s), then
        // end the session; the gate keeps publishing during the flush.
        final Runnable restStop = () ->
                restApiClient.stopRecording(experimentId, stoppedAtMs, stopRecordingCallback(experimentId));
        new Thread(() -> {
            if (mqttManager == null) {
                restStop.run();
                return;
            }
            final MQTTManager manager = mqttManager;
            boolean clean = manager.getSessionGate().finishRecording(
                    () -> manager.awaitPublishDrain(10_000), restStop);
            if (!clean) {
                final int unsent = manager.getQueuedCount();
                runOnUiThread(() -> {
                    appendLog("MQTT: stopped with " + unsent + " samples still unsent");
                    Toast.makeText(MainActivity.this, unsent + " samples were not sent before the stop",
                            Toast.LENGTH_LONG).show();
                });
            }
        }, "stop-recording").start();
    }

    private RestApiClient.ResultCallback stopRecordingCallback(final long experimentId) {
        return new RestApiClient.ResultCallback() {
            @Override
            public void onSuccess(com.google.gson.JsonObject response) {
                runOnUiThread(() -> {
                    if (mqttManager != null) mqttManager.getSessionGate().stopSucceeded();
                    experimentActive = false;
                    activeEvidenceSessionIds.clear();
                    activeAnnotationId = null;
                    activeLabelValue = null;
                    activeLabelStartedAtMs = null;
                    recordingTransitionInProgress = false;
                    appendLog("API: Ended recording experiment " + experimentId);
                    refreshRecordTab();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    if (mqttManager != null) mqttManager.getSessionGate().stopFailed();
                    recordingTransitionInProgress = false;
                    refreshRecordTab();
                });
                reportRestError("end recording", message);
            }
        };
    }

    /**
     * Label chips behave like a radio group that can also be empty: tapping
     * the running label stops it, tapping another stops it and starts the new
     * one, so the participant never has to stop before switching.
     */
    private void toggleActivityLabel(String label) {
        if (activeAnnotationId == null) {
            startActivityLabel(label);
            return;
        }
        final boolean sameLabel = label.equals(activeLabelValue);
        stopActivityLabel(() -> {
            if (!sameLabel) startActivityLabel(label);
        });
    }

    private void startActivityLabel(String labelValue) {
        if (currentExperimentId == null || !experimentActive) {
            Toast.makeText(this, "Start a recording first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (activeEvidenceSessionIds.isEmpty()) {
            Toast.makeText(this, "No wearable recordings are active", Toast.LENGTH_SHORT).show();
            return;
        }
        if (labelValue == null || labelValue.trim().isEmpty()) return;
        restApiClient.startAnnotation(currentExperimentId, "activity", labelValue.trim(),
                System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos(), new RestApiClient.ResultCallback() {
                    @Override
                    public void onSuccess(com.google.gson.JsonObject response) {
                        runOnUiThread(() -> {
                            activeAnnotationId = response.get("id").getAsLong();
                            activeLabelValue = labelValue.trim();
                            activeLabelStartedAtMs = System.currentTimeMillis();
                            appendLog("API: Started label " + labelValue.trim());
                            refreshRecordTab();
                        });
                    }

                    @Override
                    public void onError(String message) {
                        reportRestError("start label", message);
                    }
                });
    }

    private void stopActivityLabel(Runnable onStopped) {
        if (activeAnnotationId == null) return;
        final long annotationId = activeAnnotationId;
        restApiClient.stopAnnotation(annotationId, System.currentTimeMillis(), new RestApiClient.ResultCallback() {
            @Override
            public void onSuccess(com.google.gson.JsonObject response) {
                runOnUiThread(() -> {
                    activeAnnotationId = null;
                    activeLabelValue = null;
                    activeLabelStartedAtMs = null;
                    appendLog("API: Stopped label " + annotationId);
                    refreshRecordTab();
                    if (onStopped != null) onStopped.run();
                });
            }

            @Override
            public void onError(String message) {
                reportRestError("stop label", message);
            }
        });
    }

    private void reportRestError(String action, String message) {
        runOnUiThread(() -> {
            appendLog("API ERROR (" + action + "): " + message);
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        });
    }

    /** Push recording, connection and live state to the Record tab. Cheap; runs every second. */
    private void refreshRecordTab() {
        if (recordFragment == null) return;
        boolean signedIn = supabaseAuth != null && supabaseAuth.isSignedIn();
        int wearables = connectedDevices.size();
        String status;
        int unsent = mqttManager == null ? 0 : mqttManager.getQueuedCount();
        if (recordingTransitionInProgress) {
            status = unsent > 0 ? "Sending the last " + unsent + " samples…" : "Please wait…";
        } else if (experimentActive) {
            status = "Recording · " + elapsed(currentExperimentStartedAtMs) + " · "
                    + wearables + (wearables == 1 ? " wearable" : " wearables")
                    + (unsent > 100 ? " · " + unsent + " samples waiting to upload" : "");
        } else if (!signedIn) {
            status = "Sign in, then connect a wearable";
        } else if (wearables == 0) {
            status = "Connect a wearable to start";
        } else {
            status = "Ready · " + wearables + (wearables == 1 ? " wearable connected" : " wearables connected");
        }
        recordFragment.setRecordingState(experimentActive, recordingTransitionInProgress, status);
        recordFragment.setActiveLabel(activeAnnotationId == null ? null : activeLabelValue,
                elapsed(activeLabelStartedAtMs));

        boolean streaming = mqttManager != null && mqttManager.isConnected();
        String cloud = !signedIn ? "Not signed in"
                : streaming && backendOnline ? "Cloud online"
                : streaming || backendOnline ? "Cloud partly online"
                : "Cloud offline";
        recordFragment.setConnectionStatus(signedIn && streaming && backendOnline, cloud,
                wearables, rememberedWearables().size());
        recordFragment.setLive(latestLiveStatus);
        recordFragment.setLiveTraces(liveTracePpg, liveTraceAccel, liveTraceHr, liveTraceHrRange);
        recordFragment.setLiveDevices(buildLiveDevices(latestLiveData));
        for (HeadUpDisplay display : displays) display.refreshPreview(recordFragment);
    }

    /** "4:05" or "1:02:03" since {@code startedAtMs}; empty when unknown. */
    private static String elapsed(Long startedAtMs) {
        if (startedAtMs == null) return "";
        long seconds = Math.max(0, (System.currentTimeMillis() - startedAtMs) / 1000);
        long h = seconds / 3600, m = (seconds % 3600) / 60, sec = seconds % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
                : String.format(Locale.ROOT, "%d:%02d", m, sec);
    }

    /** Who a recording is attributed to, for naming it. */
    private String recorderName() {
        if (!displayName.isEmpty()) return displayName;
        String email = supabaseAuth == null ? "" : supabaseAuth.getSignedInEmail();
        return email == null || email.isEmpty() ? "Recording" : email;
    }

    private void appendLog(final String message) {
        // Also to logcat: the on-screen log is the only record of why a wearable
        // was or was not connected, and it is lost when the app restarts.
        android.util.Log.i("WearableApp", message);
        final String timePrefix = timeFormat.format(new Date());
        final String fullLine = "[" + timePrefix + "] " + message;
        
        runOnUiThread(() -> {
            logLines.add(fullLine);
            if (logLines.size() > MAX_LOG_LINES) {
                logLines.removeFirst();
            }
            if (connectedDevicesFragment != null) {
                connectedDevicesFragment.appendLog(fullLine);
            }
        });
    }

    private void setupDeviceDataListener(final WearableDevice device) {
        device.setDataListener(new WearableDevice.DataListener() {
            @Override
            public void onDataReceived(final SensorData data) {
                // Sensor evidence is published by the device adapter over MQTT.
                // The removed local Data tab no longer mirrors every sample on the UI thread.
            }

            @Override
            public void onCommandResponse(final String command, final boolean success, final String response) {
                for (HeadUpDisplay display : displays) {
                    if (display.handlesCommand(command)) {
                        runOnUiThread(() -> display.onCommandResponse(command, response));
                        return;
                    }
                }
                if ("BATTERY".equals(command)) {
                    try {
                        int percent = Integer.parseInt(response);
                        runOnUiThread(() -> updateBattery(device.getDeviceAddress(), percent));
                    } catch (NumberFormatException ignored) {
                        // A malformed reply is ignored; the next poll asks again.
                    }
                    return;
                }
                runOnUiThread(() -> appendLog("CMD RESP [" + command + "]: " + (success ? "SUCCESS" : "FAILED")));
            }
        });
    }

    /**
     * Rebuild the Devices tab from remembered, connected and connecting
     * wearables plus anything seen during a search, then the Record tab.
     */
    private void refreshDevices() {
        if (connectedDevicesFragment != null) {
            Map<String, ConnectedDevicesFragment.DeviceRow> rows = new LinkedHashMap<>();
            for (com.google.gson.JsonObject remembered : rememberedWearables().values()) {
                String address = jsonString(remembered, "address");
                // Names carry each part's side and group; seed them so
                // pairing works before the next scan has seen the part.
                deviceNames.putIfAbsent(address, jsonString(remembered, "name"));
                deviceTypes.putIfAbsent(address, jsonString(remembered, "type"));
                rows.put(address, deviceRow(address, jsonString(remembered, "type"), jsonString(remembered, "name")));
            }
            List<String> live = new ArrayList<>(connectedDevices.keySet());
            live.addAll(pendingConnections);
            for (String address : live) {
                if (rows.containsKey(address)) continue;
                String type = deviceTypes.getOrDefault(address, "Unknown");
                rows.put(address, deviceRow(address, type, deviceNames.getOrDefault(address, type)));
            }
            connectedDevicesFragment.setDevices(new ArrayList<>(rows.values()));

            List<ConnectedDevicesFragment.NearbyDevice> nearby = new ArrayList<>();
            for (Map.Entry<String, String> entry : nearbyDevices.entrySet()) {
                String address = entry.getKey();
                if (rows.containsKey(address)) continue;
                DeviceProfile nearbyProfile = WearableDeviceRegistry.profileOf(deviceTypes.get(address));
                String group = nearbyProfile.groupKeyOf(entry.getValue());
                nearby.add(new ConnectedDevicesFragment.NearbyDevice(address,
                        group.isEmpty() ? entry.getValue() : nearbyProfile.groupTitleOf(group),
                        group.isEmpty() ? deviceTypes.getOrDefault(address, "Wearable") : nearbyProfile.getGroupSubtitle()));
            }
            connectedDevicesFragment.setNearby(nearby);
            connectedDevicesFragment.setScanning(isScanning);
            connectedDevicesFragment.setReconnectStatus(reconnectStatus());
        }
        refreshRecordTab();
    }

    private ConnectedDevicesFragment.DeviceRow deviceRow(String address, String type, String name) {
        WearableDevice device = connectedDevices.get(address);
        String state;
        List<String> commands = new ArrayList<>();
        if (device != null && device.isConnected()) {
            state = ConnectedDevicesFragment.DeviceRow.CONNECTED;
            if (device.getAvailableCommands() != null) commands.addAll(device.getAvailableCommands());
        } else if (pendingConnections.contains(address)) {
            state = ConnectedDevicesFragment.DeviceRow.CONNECTING;
        } else if (pausedWearables().contains(address)) {
            state = ConnectedDevicesFragment.DeviceRow.PAUSED;
        } else {
            state = ConnectedDevicesFragment.DeviceRow.SEARCHING;
        }
        String partLabel = WearableDeviceRegistry.profileOf(type).partLabelOf(name);
        String label = partLabel != null ? partLabel
                : name == null || name.isEmpty() ? type : name;
        return new ConnectedDevicesFragment.DeviceRow(address, label, type, state, commands,
                batteryLevels.get(address));
    }

    private String reconnectStatus() {
        if (bleService != null && !bleService.isBluetoothEnabled()) {
            return "Bluetooth is off. Turn it on to connect your wearables.";
        }
        int missing = missingRememberedWearables();
        if (rememberedWearables().isEmpty()) {
            return "Wearables you connect are remembered and reconnect by themselves.";
        }
        if (missing == 0) {
            int paused = pausedWearables().size();
            return paused == 0 ? "All your wearables are connected."
                    : "Connected. " + paused + (paused == 1 ? " wearable is" : " wearables are")
                    + " paused until you tap it.";
        }
        return autoScanning
                ? "Looking for " + missing + (missing == 1 ? " wearable…" : " wearables…")
                : missing + (missing == 1 ? " wearable" : " wearables")
                        + " not found yet. Trying again in "
                        + (reconnectDelayMs >= 60000 ? (reconnectDelayMs / 60000) + " min" : (reconnectDelayMs / 1000) + " s")
                        + ".";
    }

    // ── Remembered wearables ─────────────────────────────────────────────

    /** Wearables this phone has connected to before, by address. */
    private Map<String, com.google.gson.JsonObject> rememberedWearables() {
        Map<String, com.google.gson.JsonObject> remembered = new LinkedHashMap<>();
        String json = getSharedPreferences("wearables", MODE_PRIVATE).getString("remembered", "[]");
        try {
            for (com.google.gson.JsonElement element : com.google.gson.JsonParser.parseString(json).getAsJsonArray()) {
                com.google.gson.JsonObject item = element.getAsJsonObject();
                remembered.put(jsonString(item, "address"), item);
            }
        } catch (RuntimeException ignored) {
            // A corrupt list is dropped rather than crashing the app.
        }
        return remembered;
    }

    private void saveRememberedWearables(Map<String, com.google.gson.JsonObject> remembered) {
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        for (com.google.gson.JsonObject item : remembered.values()) array.add(item);
        getSharedPreferences("wearables", MODE_PRIVATE).edit().putString("remembered", array.toString()).apply();
    }

    private void rememberWearable(String address, String type, String name) {
        Map<String, com.google.gson.JsonObject> remembered = rememberedWearables();
        com.google.gson.JsonObject item = new com.google.gson.JsonObject();
        item.addProperty("address", address);
        item.addProperty("type", type);
        item.addProperty("name", name == null || name.isEmpty() ? type : name);
        remembered.put(address, item);
        saveRememberedWearables(remembered);
    }

    private void forgetWearable(String address) {
        // Forgetting also deletes Android's pairing, so that connecting again
        // starts clean instead of reusing keys the wearable may have discarded.
        if (bleService != null) bleService.removeBond(address);
        repairedPairing.remove(address);
        Map<String, com.google.gson.JsonObject> remembered = rememberedWearables();
        remembered.remove(address);
        saveRememberedWearables(remembered);
        setWearablePaused(address, false);
        getSharedPreferences("wearables", MODE_PRIVATE).edit().remove("streaming_" + address).apply();
        if (connectedDevices.containsKey(address) || pendingConnections.contains(address)) {
            disconnectSingleDevice(address);
        }
        appendLog("Forgot wearable " + address);
        refreshDevices();
    }

    /**
     * Ask a wearable for its battery level: through the standard Battery
     * Service where it has one, otherwise with the adapter's own GET_BATTERY
     * command. Sent directly rather than through sendDeviceCommand
     * so the minute-by-minute poll does not fill the log.
     */
    private void requestBattery(String address) {
        if (bleService.readBatteryLevel(address)) return;
        WearableDevice device = connectedDevices.get(address);
        if (device != null && device.getAvailableCommands() != null
                && device.getAvailableCommands().contains("GET_BATTERY")) {
            device.sendCommand("GET_BATTERY", new HashMap<>());
        }
    }

    private void updateBattery(String address, int percent) {
        Integer previous = batteryLevels.put(address, percent);
        if (previous == null || previous != percent) {
            appendLog("Battery " + deviceNames.getOrDefault(address, address) + ": " + percent + "%");
        }
        refreshDevices();
    }

    // ── Resuming what a wearable was streaming ───────────────────────────

    /** Commands that switch a wearable's streaming off, and the one each cancels. */
    private static final Map<String, String> STOP_CANCELS = new HashMap<>();
    static {
        STOP_CANCELS.put("STOP_MEASUREMENT", "START_MEASUREMENT");
        STOP_CANCELS.put("STOP_EXERCISE", "START_EXERCISE");
        STOP_CANCELS.put("STOP_ALL_SENSORS", "START_ALL_SENSORS");
        STOP_CANCELS.put("STOP_MIC_STREAM", "START_MIC_STREAM");
    }

    /** Commands that switch streaming on and should be replayed after a reconnect. */
    private static final Set<String> STREAMING_STARTS = new HashSet<>(java.util.Arrays.asList(
            "START_MEASUREMENT", "START_EXERCISE", "START_ALL_SENSORS", "START_MIC_STREAM", "CONFIGURE_SENSOR"));

    /**
     * Keep a per-wearable list of the streaming commands in force, so a
     * wearable that drops out and reconnects, or an app that restarts, can be
     * put back exactly as it was: each wearable measuring, a wearable's chosen
     * sensors at their chosen rates. A stop command removes what it cancels.
     */
    private void rememberStreamingCommand(String address, String command, Map<String, Object> params) {
        boolean start = STREAMING_STARTS.contains(command);
        boolean stop = STOP_CANCELS.containsKey(command) || "STOP_SENSOR".equals(command);
        if (!start && !stop) return;

        String sensor = params == null ? "" : String.valueOf(params.getOrDefault("sensorType", ""));
        List<com.google.gson.JsonObject> kept = new ArrayList<>();
        for (com.google.gson.JsonObject entry : streamingCommands(address)) {
            String entryCommand = jsonString(entry, "command");
            String entrySensor = entry.has("params") && entry.getAsJsonObject("params").has("sensorType")
                    ? entry.getAsJsonObject("params").get("sensorType").getAsString() : "";
            boolean cancelled;
            if ("STOP_SENSOR".equals(command)) {
                cancelled = "CONFIGURE_SENSOR".equals(entryCommand)
                        && ("ALL".equals(sensor) || sensor.equals(entrySensor));
                if ("ALL".equals(sensor) && "START_ALL_SENSORS".equals(entryCommand)) cancelled = true;
            } else if (stop) {
                cancelled = STOP_CANCELS.get(command).equals(entryCommand)
                        // Stopping all of a wearable's sensors also ends each configured one.
                        || ("STOP_ALL_SENSORS".equals(command) && "CONFIGURE_SENSOR".equals(entryCommand));
            } else {
                // A repeat of the same start (or the same sensor reconfigured) replaces the old one.
                cancelled = entryCommand.equals(command) && entrySensor.equals(sensor);
            }
            if (!cancelled) kept.add(entry);
        }
        if (start) {
            com.google.gson.JsonObject entry = new com.google.gson.JsonObject();
            entry.addProperty("command", command);
            entry.add("params", new com.google.gson.Gson().toJsonTree(params == null ? new HashMap<>() : params));
            kept.add(entry);
        }
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        for (com.google.gson.JsonObject entry : kept) array.add(entry);
        getSharedPreferences("wearables", MODE_PRIVATE).edit()
                .putString("streaming_" + address, array.toString()).apply();
    }

    private List<com.google.gson.JsonObject> streamingCommands(String address) {
        List<com.google.gson.JsonObject> commands = new ArrayList<>();
        String json = getSharedPreferences("wearables", MODE_PRIVATE).getString("streaming_" + address, "[]");
        try {
            for (com.google.gson.JsonElement element : com.google.gson.JsonParser.parseString(json).getAsJsonArray()) {
                commands.add(element.getAsJsonObject());
            }
        } catch (RuntimeException ignored) {
            // A corrupt entry is dropped; the wearable then starts from defaults.
        }
        return commands;
    }

    /**
     * Put a reconnected wearable back into the state it was in. With nothing
     * remembered but a recording running, fall back to the recording's
     * defaults, so a wearable added mid-recording still streams.
     */
    private void resumeStreaming(String address) {
        WearableDevice device = connectedDevices.get(address);
        if (device == null || !device.isConnected()) return;
        List<com.google.gson.JsonObject> commands = streamingCommands(address);
        if (commands.isEmpty()) {
            if (experimentActive) startAcquisition(address, device);
            return;
        }
        String type = deviceTypes.getOrDefault(address, device.getDeviceType());
        appendLog("Resuming " + commands.size() + " streaming command(s) on " + deviceNames.getOrDefault(address, type));
        // Some wearables restart while away and need telling something again first.
        for (String first : WearableDeviceRegistry.profileOf(type).getResumeCommands()) {
            sendDeviceCommand(device, address, first, new HashMap<>());
        }
        for (com.google.gson.JsonObject entry : commands) {
            Map<String, Object> params = new HashMap<>();
            if (entry.has("params")) {
                for (Map.Entry<String, com.google.gson.JsonElement> param : entry.getAsJsonObject("params").entrySet()) {
                    com.google.gson.JsonElement value = param.getValue();
                    if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
                        params.put(param.getKey(), value.getAsBoolean());
                    } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                        params.put(param.getKey(), value.getAsNumber());
                    } else if (value.isJsonPrimitive()) {
                        params.put(param.getKey(), value.getAsString());
                    }
                }
            }
            sendDeviceCommand(device, address, jsonString(entry, "command"), params);
        }
    }

    private int missingRememberedWearables() {
        int missing = 0;
        Set<String> paused = pausedWearables();
        for (String address : rememberedWearables().keySet()) {
            if (paused.contains(address)) continue;
            if (!connectedDevices.containsKey(address) && !pendingConnections.contains(address)) missing++;
        }
        return missing;
    }

    /**
     * Look briefly for remembered wearables that are not connected, and
     * connect any that turn up. Runs at launch and then on a timer, so a
     * wearable switched on later, or one that dropped out, comes back without
     * the participant doing anything.
     */
    private void reconnectRememberedWearables() {
        if (bleService == null || !bleService.isBluetoothEnabled() || !hasScanPermission()) return;
        waitForRememberedWearables();
        if (isScanning || autoScanning || (missingRememberedWearables() == 0 && backgroundSince.isEmpty())) return;
        autoScanning = true;
        bleService.startDeviceDiscovery(AndroidBLEService.SCAN_TYPE_ALL, true);
        uiHandler.postDelayed(this::endAutoScan, AUTO_SCAN_MS);
        refreshDevices();
    }

    /**
     * Remembered wearables are connected by an auto-connect request instead of
     * by scanning for them. One that is not advertising (in its case, or held
     * by another app) was otherwise searched for by a scan every 20 s for as
     * long as the app ran, which also loaded the radio the other wearables
     * stream over.
     */
    private void waitForRememberedWearables() {
        Set<String> paused = pausedWearables();
        long now = System.currentTimeMillis();
        // A background request the stack has sat on for too long is renewed: on
        // some phones one made before the device was seen this session never
        // completes, while a fresh one does.
        for (Map.Entry<String, Long> waiting : new HashMap<>(backgroundSince).entrySet()) {
            if (now - waiting.getValue() < BACKGROUND_RENEW_MS) continue;
            String address = waiting.getKey();
            if (connectedDevices.containsKey(address)) continue;
            backgroundSince.remove(address);
            bleService.disconnect(address);
            pendingConnections.remove(address);
            pendingDevices.remove(address);
        }
        for (com.google.gson.JsonObject remembered : rememberedWearables().values()) {
            String address = jsonString(remembered, "address");
            String type = jsonString(remembered, "type");
            if (paused.contains(address) || connectedDevices.containsKey(address)
                    || pendingConnections.contains(address) || backgroundRefused.contains(address)) continue;
            deviceNames.put(address, jsonString(remembered, "name"));
            deviceTypes.put(address, type);
            appendLog("Waiting for " + DisplayNames.friendly(jsonString(remembered, "name")));
            beginBackgroundConnection(address, type);
        }
    }

    private void endAutoScan() {
        if (!autoScanning) return;
        autoScanning = false;
        if (!isScanning) bleService.stopDeviceDiscovery();
        refreshDevices();
    }

    private boolean hasScanPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Some wearables choose a new random Bluetooth address each time they restart
     * (some wearables do, after every trip to their case). The app recognised a
     * remembered wearable only by address, so it went on trying the old one,
     * which no longer exists, and every attempt timed out (GATT 147).
     *
     * When a wearable of a remembered kind advertises a remembered name from an
     * address the app does not know, and a remembered wearable of that kind and
     * name is not connected, the remembered entry takes on the new address.
     * Two wearables of one kind can share a name; each new address takes the next free entry.
     */
    private void adoptChangedAddress(String name, String address, String type) {
        if (name == null || name.isEmpty() || type == null || "Unknown".equals(type)) return;
        Map<String, com.google.gson.JsonObject> remembered = rememberedWearables();
        if (remembered.containsKey(address)) return;
        String stale = null;
        for (com.google.gson.JsonObject entry : remembered.values()) {
            String old = jsonString(entry, "address");
            if (!type.equals(jsonString(entry, "type")) || !name.equals(jsonString(entry, "name"))) continue;
            if (connectedDevices.containsKey(old)) continue;
            // An address that advertised in the last 30 s is still in use.
            Long seen = lastAdvertisedMs.get(old);
            if (seen != null && System.currentTimeMillis() - seen < 30000) continue;
            stale = old;
            break;
        }
        if (stale == null) return;
        com.google.gson.JsonObject entry = remembered.remove(stale);
        entry.addProperty("address", address);
        remembered.put(address, entry);
        saveRememberedWearables(remembered);
        // Stop waiting for the old address.
        if (pendingConnections.contains(stale) || backgroundSince.containsKey(stale)) {
            backgroundSince.remove(stale);
            bleService.disconnect(stale);
            pendingConnections.remove(stale);
            pendingDevices.remove(stale);
        }
        backgroundRefused.remove(stale);
        Long pause = pausedUntil.remove(stale);
        if (pause != null) pausedUntil.put(address, pause);
        String streaming = getSharedPreferences("wearables", MODE_PRIVATE).getString("streaming_" + stale, null);
        if (streaming != null) {
            getSharedPreferences("wearables", MODE_PRIVATE).edit()
                    .remove("streaming_" + stale).putString("streaming_" + address, streaming).apply();
        }
        appendLog(DisplayNames.friendly(name) + " now uses address " + address + " (was " + stale + ")");
    }

    /** When each address last advertised; a live address is never taken over. */
    private final Map<String, Long> lastAdvertisedMs = new HashMap<>();

    private void handleDiscoveredDevice(String name, String address, String type, int rssi) {
        lastAdvertisedMs.put(address, System.currentTimeMillis());
        deviceNames.put(address, name);
        deviceTypes.put(address, type);
        adoptChangedAddress(name, address, type);
        if (backgroundSince.containsKey(address) && !connectedDevices.containsKey(address)) {
            // Waiting for it in the background, and here it is: a direct
            // connection is faster than the controller's slow background scan.
            backgroundSince.remove(address);
            bleService.disconnect(address);
            pendingConnections.remove(address);
            pendingDevices.remove(address);
        }
        boolean busy = connectedDevices.containsKey(address) || pendingConnections.contains(address);
        if (!busy && partnerConnecting(address)) {
            // Another part of a wearable already being connected: the wearable
            // needs all its parts, so this one joins without a second tap.
            appendLog("Found the other arm of " + name + ", connecting");
            beginDeviceConnection(address, type);
            refreshDevices();
            return;
        }
        if (!busy && rememberedWearables().containsKey(address) && !pausedWearables().contains(address)) {
            appendLog("Found remembered " + type + " [" + address + "], reconnecting");
            beginDeviceConnection(address, type);
            if (autoScanning && missingRememberedWearables() == 0) endAutoScan();
            refreshDevices();
            return;
        }
        // Only the kind being added: TVs, headphones and unnamed beacons are
        // noise in the list and cannot be connected anyway.
        // A wearable made of several parts advertises one entry per part; list each group once.
        boolean partnerListed = false;
        DeviceProfile foundProfile = WearableDeviceRegistry.profileOf(type);
        String foundGroup = foundProfile.groupKeyOf(name);
        for (String listed : nearbyDevices.keySet()) {
            if (!foundGroup.isEmpty()
                    && foundGroup.equals(foundProfile.groupKeyOf(deviceNames.getOrDefault(listed, "")))) {
                partnerListed = true;
            }
        }
        if (isScanning && type.equals(searchType) && !busy && !partnerListed
                && !nearbyDevices.containsKey(address)) {
            nearbyDevices.put(address, name);
            appendLog("Found device: " + name + " [" + type + "] (" + rssi + "dBm)");
            refreshDevices();
        }
    }

    private void handleDeviceCommand(final String address, final String command) {
        final WearableDevice device = connectedDevices.get(address);
        if (device == null) {
            Toast.makeText(this, "Device not connected", Toast.LENGTH_SHORT).show();
            return;
        }

        if ("DISPLAY_TEXT".equals(command)) {
            showDisplayTextDialog(device, address);
            return;
        }
        if ("SET_LED_COLOR".equals(command)) {
            showLedColorDialog(device, address);
            return;
        }
        if ("SET_AUDIO_MODE".equals(command)) {
            showAudioModeDialog(device, address);
            return;
        }
        if ("CONFIGURE_SENSOR".equals(command)) {
            showConfigureSensorDialog(device, address);
            return;
        }
        if ("STOP_SENSOR".equals(command)) {
            showStopSensorDialog(device, address);
            return;
        }
        if ("PAIR_DEVICE".equals(command)) {
            boolean paired = bleService.createBond(address);
            appendLog(paired
                    ? "Bonding initiated/already bonded for " + address
                    : "Failed to initiate bonding for " + address);
            return;
        }
        if ("OPEN_BLUETOOTH_SETTINGS".equals(command)) {
            appendLog("Opening Bluetooth settings for media routing...");
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            return;
        }

        sendDeviceCommand(device, address, command, new HashMap<>());
    }

    private void showDisplayTextDialog(final WearableDevice device, final String address) {
        final EditText input = new EditText(this);
        input.setHint("Text to display");

        new AlertDialog.Builder(this)
                .setTitle("Display Text")
                .setView(input)
                .setPositiveButton("Send", (dialog, which) -> {
                    final String text = input.getText().toString();
                    final Map<String, Object> params = new HashMap<>();
                    params.put("text", text);
                    sendDeviceCommand(device, address, "DISPLAY_TEXT", params);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showLedColorDialog(final WearableDevice device, final String address) {
        final LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        final EditText rInput = createRgbInput("R (0-255)", "0");
        final EditText gInput = createRgbInput("G (0-255)", "255");
        final EditText bInput = createRgbInput("B (0-255)", "0");
        layout.addView(rInput);
        layout.addView(gInput);
        layout.addView(bInput);

        new AlertDialog.Builder(this)
                .setTitle("Set LED Color")
                .setView(layout)
                .setPositiveButton("Send", (dialog, which) -> {
                    try {
                        final int r = Integer.parseInt(rInput.getText().toString());
                        final int g = Integer.parseInt(gInput.getText().toString());
                        final int b = Integer.parseInt(bInput.getText().toString());
                        if (r < 0 || r > 255 || g < 0 || g > 255 || b < 0 || b > 255) {
                            Toast.makeText(this, "Values must be 0-255", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        final Map<String, Object> params = new HashMap<>();
                        params.put("r", r);
                        params.put("g", g);
                        params.put("b", b);
                        sendDeviceCommand(device, address, "SET_LED_COLOR", params);
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "Invalid RGB values", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private EditText createRgbInput(final String hint, final String defaultValue) {
        final EditText input = new EditText(this);
        input.setHint(hint);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(defaultValue);
        return input;
    }

    private void showAudioModeDialog(final WearableDevice device, final String address) {
        final EditText modeInput = new EditText(this);
        modeInput.setHint("Mode (0-3)");
        modeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        modeInput.setText("0");

        new AlertDialog.Builder(this)
                .setTitle("Set Audio Mode")
                .setView(modeInput)
                .setPositiveButton("Send", (dialog, which) -> {
                    try {
                        final int mode = Integer.parseInt(modeInput.getText().toString());
                        final Map<String, Object> params = new HashMap<>();
                        params.put("mode", mode);
                        sendDeviceCommand(device, address, "SET_AUDIO_MODE", params);
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "Invalid mode", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showConfigureSensorDialog(final WearableDevice device, final String address) {
        final LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 24, 24, 24);

        final Spinner sensorSpinner = new Spinner(this);
        String[] sensorTypes = {"ALL_SENSORS", "IMU", "TEMP_BARO", "PPG", "PULSOX", "OPTTEMP", "BONE_CONDUCTION", "MICRO"};
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, sensorTypes);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sensorSpinner.setAdapter(spinnerAdapter);

        final EditText rateInput = new EditText(this);
        rateInput.setHint("Sample rate (Hz)");
        rateInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        rateInput.setText("50");

        layout.addView(sensorSpinner);
        layout.addView(rateInput);

        new AlertDialog.Builder(this)
                .setTitle("Configure " + WearableDeviceRegistry.profileOf(device.getDeviceType()).getDisplayName() + " Sensor")
                .setView(layout)
                .setPositiveButton("Send", (dialog, which) -> {
                    try {
                        final String sensorType = String.valueOf(sensorSpinner.getSelectedItem());
                        final int sampleRate = Integer.parseInt(rateInput.getText().toString());
                        final Map<String, Object> params = new HashMap<>();
                        params.put("sensorType", sensorType);
                        params.put("sampleRate", sampleRate);
                        params.put("streaming", true);
                        sendDeviceCommand(device, address, "CONFIGURE_SENSOR", params);
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "Invalid sample rate", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showStopSensorDialog(final WearableDevice device, final String address) {
        final Spinner sensorSpinner = new Spinner(this);
        String[] sensorTypes = {"ALL", "IMU", "TEMP_BARO", "PPG", "PULSOX", "OPTTEMP", "BONE_CONDUCTION", "MICRO"};
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, sensorTypes);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sensorSpinner.setAdapter(spinnerAdapter);

        new AlertDialog.Builder(this)
                .setTitle("Stop " + WearableDeviceRegistry.profileOf(device.getDeviceType()).getDisplayName() + " Sensor")
                .setView(sensorSpinner)
                .setPositiveButton("Send", (dialog, which) -> {
                    final String sensorType = String.valueOf(sensorSpinner.getSelectedItem());
                    final Map<String, Object> params = new HashMap<>();
                    params.put("sensorType", sensorType);
                    sendDeviceCommand(device, address, "STOP_SENSOR", params);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void sendDeviceCommand(final WearableDevice device, final String address, final String command, final Map<String, Object> params) {
        rememberStreamingCommand(address, command, params);
        appendLog("Sending " + command + " to " + address);
        final boolean success = device.sendCommand(command, params);
        if (!success) {
            appendLog("Failed to send " + command + " to " + address);
        }
    }

    /**
     * A disconnect (or a cancelled connection) the wearer asked for. The wearable stays
     * disconnected, and is not searched for or reconnected, until they tap Connect on
     * it. All parts of a multi-part wearable go together.
     */
    private void disconnectByUser(final String address) {
        setWearablePaused(address, true);
        for (String partner : partnerParts(address)) setWearablePaused(partner, true);
        disconnectSingleDevice(address);
        for (String partner : partnerParts(address)) {
            if (connectedDevices.containsKey(partner) || pendingConnections.contains(partner)) {
                disconnectSingleDevice(partner);
            }
        }
        appendLog("Disconnected by you: reconnects in 10 minutes, or when you tap Connect");
        refreshDevices();
    }

    private void disconnectSingleDevice(final String address) {
        appendLog("Disconnecting " + address + "...");
        bleService.disconnect(address);
        handleDisconnectedState(address, false, 0);
    }

    private void disconnectAllDevices() {
        appendLog("Disconnecting all devices...");
        final Set<String> addresses = new HashSet<>(connectedDevices.keySet());
        addresses.addAll(pendingConnections);
        for (final String addr : addresses) {
            bleService.disconnect(addr);
            handleDisconnectedState(addr, false, 0);
        }
    }

    private void handleDisconnectedState(final String address, boolean appendDisconnectedLog,
                                         int gattStatus) {
        final WearableDevice device = connectedDevices.remove(address);
        if (device != null) {
            device.setConnected(false);
        }
        connectedSince.remove(address);
        pendingConnections.remove(address);
        pendingDevices.remove(address);

        // Preserve CSV data after disconnect so logs remain available for export.

        if (appendDisconnectedLog) {
            appendLog("DISCONNECTED: " + address + " (GATT " + gattStatus + ": " +
                    describeGattStatus(gattStatus) + ")");
        }
        if (gattStatus == 5 || gattStatus == 137) pairingRejectedAtMs.put(address, System.currentTimeMillis());
        boolean wasBackground = backgroundSince.remove(address) != null;
        if (wasBackground && gattStatus != 0) {
            // The stack refused to wait for this device (status 135 for a
            // device whose address it cannot resolve yet). Find it by scanning
            // instead, rather than asking again every second.
            backgroundRefused.add(address);
            appendLog("Background reconnect not available for " + address + "; scanning instead");
            refreshDevices();
            return;
        }
        // appendDisconnectedLog is true only for drops reported by the Bluetooth
        // stack, not for disconnects this app asked for.
        if (appendDisconnectedLog) reconnectAfterDrop(address, gattStatus);

        refreshDevices();
    }

    /**
     * A remembered wearable that dropped is asked for again straight away, as a
     * background connection that completes whenever it is back in range, rather
     * than waiting for the next scan (which backed off to minutes).
     */
    private void reconnectAfterDrop(final String address, int gattStatus) {
        // onDestroy disconnects everything; those callbacks must not reconnect.
        if (isFinishing() || isDestroyed()) return;
        final boolean remembered = rememberedWearables().containsKey(address);
        final String type = deviceTypes.getOrDefault(address,
                remembered ? jsonString(rememberedWearables().get(address), "type") : "Unknown");
        // A rejected pairing is handled for new wearables too: one that needs pairing
        // being added for the first time is not remembered until it connects,
        // which it never does while its stored pairing is refused.
        if ((gattStatus == 5 || gattStatus == 137) && !pausedWearables().contains(address)) {
            // The device refused the stored pairing keys: reset or paired
            // elsewhere since. Retrying with the same keys fails for ever, so the
            // pairing is deleted (every part) and made afresh, once.
            if (!repairedPairing.contains(address) && bleService.isBonded(address)) {
                Set<String> arms = new HashSet<>(partnerParts(address));
                arms.add(address);
                for (String arm : arms) {
                    repairedPairing.add(arm);
                    repairedAtMs.put(arm, System.currentTimeMillis());
                    bleService.removeBond(arm);
                }
                appendLog("Pairing rejected by " + deviceNames.getOrDefault(address, type)
                        + ": pairing again");
                Toast.makeText(this, "Pairing again with " + DisplayNames.friendly(
                        deviceNames.getOrDefault(address, type)) + ". Accept the request if asked.",
                        Toast.LENGTH_LONG).show();
                uiHandler.postDelayed(() -> {
                    if (!connectedDevices.containsKey(address) && !pendingConnections.contains(address)) {
                        // A foreground connection, so Android pairs as soon as the
                        // device asks for encryption.
                        beginDeviceConnection(address, type);
                        refreshDevices();
                    }
                }, 2000);
            } else if (System.currentTimeMillis() - repairedAtMs.getOrDefault(address, 0L) < 30000) {
                // The other arm's attempt was already under way, with the old
                // keys, when the pairing was deleted: try it again, fresh.
                uiHandler.postDelayed(() -> {
                    if (!connectedDevices.containsKey(address) && !pendingConnections.contains(address)) {
                        beginDeviceConnection(address, type);
                        refreshDevices();
                    }
                }, 2000);
            } else {
                setWearablePaused(address, true);
                appendLog("Pairing still rejected by " + deviceNames.getOrDefault(address, type)
                        + ". Unpair it in Android's Bluetooth settings, close any other app"
                        + " using it, then tap Connect.");
                Toast.makeText(this, DisplayNames.friendly(deviceNames.getOrDefault(address, type))
                        + " rejected pairing. Unpair it in Bluetooth settings, then tap Connect.",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (!remembered || pausedWearables().contains(address)) return;
        uiHandler.postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (connectedDevices.containsKey(address) || pendingConnections.contains(address)) return;
            if (pausedWearables().contains(address)) return;
            beginBackgroundConnection(address, type);
            refreshDevices();
        }, 1000);
    }

    private boolean beginBackgroundConnection(String address, String type) {
        if (backgroundRefused.contains(address)) return false;
        boolean started = beginDeviceConnection(address, type, true);
        if (started) backgroundSince.put(address, System.currentTimeMillis());
        return started;
    }

    private String describeGattStatus(int status) {
        switch (status) {
            case 0:
                return "normal/local disconnect";
            case 5:
            case 137:
                return "pairing rejected by the device";
            case 8:
                return "BLE link timeout; reconnecting";
            case 19:
                return "wearable terminated the connection";
            case 22:
                return "Android/local host terminated the connection";
            case 133:
                return "Android GATT error; automatic retry scheduled";
            case 147:
                return "GATT connection error; automatic retry scheduled";
            default:
                return "Bluetooth stack status";
        }
    }

    private WearableDevice createDeviceImplementation(String address, String type) {
        WearableDevice device = deviceRegistry.create(type, address, bleService);
        if (device != null) {
            device.setMQTTManager(mqttManager);
            device.setMQTTEnabled(true);
        }
        return device;
    }

    private boolean beginDeviceConnection(String address, String type) {
        backgroundSince.remove(address);
        return beginDeviceConnection(address, type, false);
    }

    private boolean beginDeviceConnection(String address, String type, boolean background) {
        WearableDevice device = createDeviceImplementation(address, type);
        if (!(device instanceof BleDeviceAdapter)) {
            appendLog("Unsupported device type: " + type);
            return false;
        }

        pendingDevices.put(address, device);
        pendingConnections.add(address);
        bleService.registerDeviceAdapter((BleDeviceAdapter) device);
        bleService.setDeviceType(address, type);
        boolean started = bleService.connect(address, background);
        if (!started) {
            pendingDevices.remove(address);
            pendingConnections.remove(address);
            bleService.unregisterDeviceAdapter(address);
        }
        return started;
    }

    private void checkPermissions() {
        final List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        } else {
            permissions.add(Manifest.permission.BLUETOOTH);
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN);
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        final List<String> toRequest = new ArrayList<>();
        for (final String p : permissions) {
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
            appendLog("Bluetooth LE is not supported on this phone");
        } else if (!bleService.isBluetoothEnabled()) {
            final Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BLUETOOTH);
            } else {
                checkPermissions();
            }
        } else {
            reconnectRememberedWearables();
            refreshDevices();
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
            reconnectRememberedWearables();
            refreshDevices();
        }
    }

    /**
     * "Add a wearable": search for one kind of wearable for a while. The
     * radio scan still sees everything, so remembered wearables keep
     * reconnecting, but only the chosen kind is offered in the list. A
     * background reconnection search already running is simply taken over.
     */
    private void startSearch(String deviceType) {
        if (!hasScanPermission()) {
            appendLog("Requesting Bluetooth permission...");
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION},
                    REQUEST_BLUETOOTH_PERMISSIONS);
            return;
        }
        searchType = deviceType;
        nearbyDevices.clear();
        if (!isScanning && !autoScanning) bleService.startDeviceDiscovery(AndroidBLEService.SCAN_TYPE_ALL);
        autoScanning = false;
        isScanning = true;
        appendLog("Searching for " + deviceType);
        // A timer from an earlier search must not cut this one short.
        final int search = ++searchGeneration;
        uiHandler.postDelayed(() -> {
            if (search == searchGeneration) endUserScan();
        }, USER_SCAN_MS);
        refreshDevices();
    }

    private void endUserScan() {
        if (!isScanning) return;
        bleService.stopDeviceDiscovery();
        isScanning = false;
        appendLog("Search stopped");
        refreshDevices();
    }

    private void connectToAddress(final String address) {
        if (connectedDevices.containsKey(address)) {
            Toast.makeText(this, "Already connected", Toast.LENGTH_SHORT).show();
            return;
        }
        if (pendingConnections.contains(address)) {
            // Waiting (perhaps in the background, perhaps on a refused pairing):
            // a tap means try now, so the old attempt is dropped.
            backgroundSince.remove(address);
            bleService.disconnect(address);
            pendingConnections.remove(address);
            pendingDevices.remove(address);
        }
        setWearablePaused(address, false);
        flapCounts.remove(address);
        resetReconnectBackoff();
        final String type = deviceTypes.getOrDefault(address, "Unknown");
        Set<String> arms = new HashSet<>(partnerParts(address));
        arms.add(address);
        boolean rejected = false;
        for (String arm : arms) rejected |= pairingRejectedAtMs.containsKey(arm);
        if (rejected) {
            // The stored pairing was refused last time; asking again with the
            // same keys can only fail the same way. Delete it (every arm) and
            // connect fresh, which pairs again.
            for (String arm : arms) {
                setWearablePaused(arm, false);
                pairingRejectedAtMs.remove(arm);
                repairedPairing.add(arm);
                repairedAtMs.put(arm, System.currentTimeMillis());
                if (pendingConnections.contains(arm)) {
                    bleService.disconnect(arm);
                    pendingConnections.remove(arm);
                    pendingDevices.remove(arm);
                }
                appendLog("Removing the old pairing for " + deviceNames.getOrDefault(arm, arm)
                        + ": " + bleService.removeBond(arm));
            }
            Toast.makeText(this, "Pairing again. Accept the pairing request if one appears.",
                    Toast.LENGTH_LONG).show();
            uiHandler.postDelayed(() -> {
                for (String arm : arms) {
                    if (!connectedDevices.containsKey(arm) && !pendingConnections.contains(arm)) {
                        beginDeviceConnection(arm, deviceTypes.getOrDefault(arm, type));
                    }
                }
                refreshDevices();
            }, 1500);
            refreshDevices();
            return;
        }
        if (WearableDeviceRegistry.profileOf(type).requiresBondBeforeConnect()) {
            boolean unpaired = false;
            for (String arm : arms) unpaired |= !bleService.isBonded(arm);
            if (unpaired) {
                pairPartsThenConnect(arms, type);
                return;
            }
        }
        appendLog("Connecting to " + deviceNames.getOrDefault(address, type) + " [" + address + "]");
        if (!beginDeviceConnection(address, type)) {
            appendLog("Connection failed to start for " + address);
        }
        // Connecting a multi-part wearable means connecting every part.
        for (String partner : partnerParts(address)) {
            if (!connectedDevices.containsKey(partner) && !pendingConnections.contains(partner)) {
                beginDeviceConnection(partner, type);
            }
        }
        refreshDevices();
    }

    /**
     * Some wearables (the profile says which) keep a connection only with a phone
     * they are paired with; an unpaired phone is dropped within milliseconds of
     * connecting. Pair each unpaired part here, one at a time (several at once
     * confuses them), then connect all of them.
     */
    private void pairPartsThenConnect(final Set<String> arms, final String type) {
        final List<String> queue = new ArrayList<>();
        for (String arm : arms) if (!bleService.isBonded(arm)) queue.add(arm);
        Toast.makeText(this, WearableDeviceRegistry.profileOf(type).getPairingStartText(),
                Toast.LENGTH_LONG).show();
        pairNextArm(queue, arms, type, 0);
        refreshDevices();
    }

    private void pairNextArm(final List<String> queue, final Set<String> arms, final String type, final int waitedS) {
        if (isFinishing() || isDestroyed()) return;
        if (queue.isEmpty()) {
            appendLog(WearableDeviceRegistry.profileOf(type).getPairedLog());
            for (String arm : arms) {
                if (!connectedDevices.containsKey(arm) && !pendingConnections.contains(arm)) {
                    beginDeviceConnection(arm, deviceTypes.getOrDefault(arm, type));
                }
            }
            refreshDevices();
            return;
        }
        String arm = queue.get(0);
        if (bleService.isBonded(arm)) {
            appendLog("Paired " + deviceNames.getOrDefault(arm, arm));
            queue.remove(0);
            uiHandler.postDelayed(() -> pairNextArm(queue, arms, type, 0), 1000);
            return;
        }
        if (waitedS == 0) {
            appendLog("Pairing " + deviceNames.getOrDefault(arm, arm) + ": " + bleService.createBond(arm));
        } else if (waitedS >= 30) {
            appendLog("Pairing " + deviceNames.getOrDefault(arm, arm)
                    + WearableDeviceRegistry.profileOf(type).getPairingFailLog());
            Toast.makeText(this, WearableDeviceRegistry.profileOf(type).getPairingFailText(),
                    Toast.LENGTH_LONG).show();
            refreshDevices();
            return;
        }
        uiHandler.postDelayed(() -> pairNextArm(queue, arms, type, waitedS + 1), 1000);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        CaptureService.stop(this);
        for (HeadUpDisplay display : displays) display.stopDebugTools();
        uiHandler.removeCallbacks(mqttStatusTick);
        uiHandler.removeCallbacks(reconnectTick);
        uiHandler.removeCallbacks(batteryTick);
        if (bleService != null) {
            bleService.stopDeviceDiscovery();
            isScanning = false;
            final Set<String> addresses = new HashSet<>(connectedDevices.keySet());
            addresses.addAll(pendingConnections);
            for (final String addr : addresses) {
                bleService.disconnect(addr);
            }
        }
        pendingConnections.clear();
        pendingDevices.clear();
        connectedDevices.clear();
        if (mqttManager != null) {
            mqttManager.disconnect();
        }
    }

    /**
     * Set up participant sign-in.
     *
     * The identity provider holds credentials; the backend decides what this
     * account may do. A signed-in participant's token takes precedence over the
     * deployment-wide bearer token, which remains as a fallback until every
     * client signs in.
     */
    private void setupAuth() {
        try {
            supabaseAuth = new SupabaseAuth(this);
        } catch (IllegalStateException e) {
            // Secure storage is unavailable; sign-in is disabled rather than
            // silently storing tokens in plain preferences.
            appendLog("Auth: secure storage unavailable, sign-in disabled");
            if (mqttFragment != null) {
                mqttFragment.setAuthState(false, "Sign-in unavailable",
                        "This device cannot store credentials securely.", false);
            }
            return;
        }
        supabaseAuth.configure(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY);
        loadStudies();
        // The provider runs on OkHttp's threads, so a token refresh here does
        // not block the UI.
        restApiClient.setCredentialProvider(() ->
                supabaseAuth == null ? "" : supabaseAuth.getValidAccessToken());
        refreshAuthUi(null);
        // A session restored from secure storage needs its broker account too;
        // the shared account is no longer accepted by the cloud broker.
        if (supabaseAuth.isSignedIn()) {
            fetchParticipantMqttCredentials();
            loadIdentity();
        }
    }

    private void signIn(String email, String password) {
        if (supabaseAuth == null) return;
        if (!supabaseAuth.isConfigured()) {
            refreshAuthUi("Sign-in is not configured in this build.");
            return;
        }
        if (mqttFragment != null) {
            mqttFragment.setAuthState(false, "Signing in", "Contacting the identity provider...", true);
        }
        authExecutor.execute(() -> {
            String error = supabaseAuth.signIn(email, password);
            runOnUiThread(() -> {
                refreshAuthUi(error);
                if (error == null) {
                    appendLog("Auth: signed in as " + supabaseAuth.getSignedInEmail());
                    // An account belongs to the cloud service, so signing in is
                    // what connects the phone to it: switching profile fetches
                    // the account's broker login and connects.
                    if (!"cloud".equals(activeConnectionProfile())) {
                        selectConnectionProfile("cloud");
                        if (mqttFragment != null) mqttFragment.setProfile("cloud");
                    } else {
                        if (!restApiClient.getBaseUrl().isEmpty()) {
                            checkBackendConnection(true);
                        }
                        fetchParticipantMqttCredentials();
                    }
                    loadIdentity();
                    loadStudies();
                } else {
                    appendLog("Auth: sign-in failed - " + error);
                }
            });
        });
    }

    private void signOut() {
        if (supabaseAuth == null) return;
        clearParticipantMqttCredentials();
        authExecutor.execute(() -> {
            supabaseAuth.signOut();
            runOnUiThread(() -> {
                displayName = "";
                appendLog("Auth: signed out");
                openLoginScreen();
            });
        });
    }

    /**
     * Swap the broker login for this participant's own account.
     *
     * The backend derives a credential per participant, and the broker ACL
     * lets that account publish only under {@code sensors/u<id>} and read only
     * {@code status/u<id>}, so no phone can see another participant's stream.
     * A deployment that still shares one account says so and nothing changes.
     */
    private void fetchParticipantMqttCredentials() {
        if (supabaseAuth == null || !supabaseAuth.isSignedIn() || restApiClient.getBaseUrl().isEmpty()) {
            return;
        }
        restApiClient.getMqttCredentials(new RestApiClient.ResultCallback() {
            @Override
            public void onSuccess(com.google.gson.JsonObject response) {
                boolean perUser = response.has("per_user_credentials")
                        && response.get("per_user_credentials").getAsBoolean();
                if (!perUser) {
                    runOnUiThread(() -> appendLog("MQTT: this deployment shares one broker account"));
                    return;
                }
                String username = response.get("username").getAsString();
                String password = response.get("password").getAsString();
                String topicPrefix = response.get("topic_prefix").getAsString();
                String statusTopic = response.get("status_topic").getAsString();
                runOnUiThread(() -> applyParticipantMqttCredentials(username, password, topicPrefix, statusTopic));
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> appendLog("MQTT: could not fetch your broker account - " + message));
            }
        });
    }

    private void applyParticipantMqttCredentials(String username, String password,
                                                 String topicPrefix, String statusTopic) {
        String profile = activeConnectionProfile();
        // Stored beside the profile's other broker settings, as before, so the
        // phone reconnects with the right account on its next launch.
        getSharedPreferences("connections", MODE_PRIVATE)
                .edit()
                .putString(profile + "_mqtt_username", username)
                .putString(profile + "_mqtt_password", password)
                .putString(profile + "_topic_prefix", topicPrefix)
                .putString(profile + "_status_topic_filter", statusTopic + "/#")
                .apply();
        statusTopicFilter = statusTopic + "/#";
        mqttManager.setTopicPrefix(topicPrefix);

        String broker = profileValue(profile, "broker_url", "");
        if (mqttFragment != null) {
            mqttFragment.setDefaults(broker, topicPrefix, mqttManager.getQoS());
            mqttFragment.setConnectionValues(broker, username, password,
                    restApiClient.getBaseUrl(), profileValue(profile, "api_token", ""));
        }
        appendLog("MQTT: using your own broker account " + username + " (" + topicPrefix + ")");
        // A second connection with the same client id makes the broker drop the
        // first ("session taken over"), so leave one that is already connected
        // or still connecting with this account alone.
        if (broker.trim().isEmpty()
                // Same name is not enough: two servers can both call this account
                // u2 with different passwords, and a phone moved between them kept
                // retrying the old password ("not authorised") forever.
                || (mqttManager.usesLogin(username, password)
                        && (mqttManager.isConnected() || mqttManager.isConnecting()))) {
            return;
        }
        new Thread(() -> {
            mqttManager.clearSubscriptions();
            boolean ok = mqttManager.reconnectTo(broker, username, password);
            runOnUiThread(() -> {
                appendLog(ok ? "MQTT: connected as " + username : "MQTT: could not connect as " + username);
                refreshMqttTab();
            });
        }).start();
    }

    /** Undo what sign-in set up, leaving any manually entered account alone. */
    private void clearParticipantMqttCredentials() {
        String profile = activeConnectionProfile();
        if (!profileValue(profile, "mqtt_username", "").matches("u\\d+")) return;
        getSharedPreferences("connections", MODE_PRIVATE)
                .edit()
                .remove(profile + "_mqtt_username")
                .remove(profile + "_mqtt_password")
                .remove(profile + "_topic_prefix")
                .remove(profile + "_status_topic_filter")
                .apply();
        statusTopicFilter = "status/#";
        mqttManager.setTopicPrefix("sensors");
        latestLiveStatus = "";
        liveTracePpg = liveTraceAccel = liveTraceHr = new float[0];
        liveTraceHrRange = "";
        new Thread(() -> {
            mqttManager.disconnect();
            mqttManager.clearSubscriptions();
        }).start();
        appendLog("MQTT: disconnected from your broker account");
    }

    /**
     * Live analysis from the backend: a summary once a second, and alerts when
     * a stream stalls, loses samples or the PPG signal degrades. Summaries
     * update the Record tab quietly; alerts are logged and shown.
     */
    private void handleLiveMessage(String payload) {
        com.google.gson.JsonObject message;
        try {
            message = com.google.gson.JsonParser.parseString(payload).getAsJsonObject();
        } catch (RuntimeException e) {
            return;
        }
        String event = jsonString(message, "event");
        if (!message.has("data") || !message.get("data").isJsonObject()) return;
        com.google.gson.JsonObject data = message.getAsJsonObject("data");

        if ("live.summary".equals(event)) {
            holdLastGood(data);
            latestLiveStatus = describeLiveSummary(data);
            readLiveTraces(data);
            latestLiveData = data;
            for (HeadUpDisplay display : displays) display.onLiveSummary(data);
            refreshRecordTab();
            return;
        }
        if (!event.startsWith("live.alert.")) return;
        String state = jsonString(data, "state");
        String severity = jsonString(data, "severity");
        String text = DisplayNames.friendly(jsonString(data, "message"));
        appendLog("LIVE " + state.toUpperCase(Locale.ROOT) + ": " + text);
        // No pop-up: the notification (at most every five minutes per alert) is enough,
        // and a toast for every alert made the phone unusable while a signal
        // hovered around the quality line.
        boolean showAutomatically = true;
        for (HeadUpDisplay display : displays) showAutomatically &= display.showsAutomatically();
        if (!showAutomatically) {
            // Testing a display's link: nothing but what the tester sends.
            notifyAlert(jsonString(data, "code") + ":" + jsonString(data, "stream_name") + ":"
                    + jsonString(data, "session_id"), state, severity, text);
            return;
        }
        // A wearer's display shows real problems only; informational alerts stay in the app.
        if ("info".equals(severity)) return;
        for (HeadUpDisplay display : displays) display.onAlert(state, text);
        notifyAlert(jsonString(data, "code") + ":" + jsonString(data, "stream_name") + ":"
                + jsonString(data, "session_id"), state, severity, text);
    }

    private final java.util.Map<String, com.google.gson.JsonObject> heldLiveSessions = new java.util.HashMap<>();

    /**
     * When a wearable's stream stalls (a short Bluetooth or network drop) the backend
     * stops computing its pulse and trace. Keep the last ones, frozen, so the heart
     * rate and waveform stay on screen and on the wearer's display until data returns.
     */
    private void holdLastGood(com.google.gson.JsonObject data) {
        if (!data.has("sessions") || !data.get("sessions").isJsonArray()) return;
        java.util.Set<String> present = new java.util.HashSet<>();
        for (com.google.gson.JsonElement element : data.getAsJsonArray("sessions")) {
            if (!element.isJsonObject()) continue;
            com.google.gson.JsonObject session = element.getAsJsonObject();
            String id = jsonString(session, "session_id");
            present.add(id);
            com.google.gson.JsonObject held = heldLiveSessions.get(id);
            if (held != null) {
                for (String key : new String[] {"ppg", "movement", "model"}) {
                    if ((!session.has(key) || session.get(key).isJsonNull()) && held.has(key)) {
                        session.add(key, held.get(key).deepCopy());
                    }
                }
                com.google.gson.JsonObject heldTrace = held.has("trace") && held.get("trace").isJsonObject()
                        ? held.getAsJsonObject("trace") : null;
                if (heldTrace != null) {
                    if (!session.has("trace") || !session.get("trace").isJsonObject()) {
                        session.add("trace", heldTrace.deepCopy());
                    } else {
                        com.google.gson.JsonObject trace = session.getAsJsonObject("trace");
                        for (String key : new String[] {"ppg", "accel", "hr_history", "display_hr_history"}) {
                            boolean empty = !trace.has(key) || !trace.get(key).isJsonArray()
                                    || trace.getAsJsonArray(key).size() == 0;
                            if (empty && heldTrace.has(key)) trace.add(key, heldTrace.get(key).deepCopy());
                        }
                    }
                }
            }
            heldLiveSessions.put(id, session.deepCopy());
        }
        heldLiveSessions.keySet().retainAll(present);
    }

    /** Take the waveform and trend to draw from the first recording that has any. */
    private void readLiveTraces(com.google.gson.JsonObject data) {
        liveTracePpg = liveTraceAccel = liveTraceHr = new float[0];
        liveTraceHrRange = "";
        if (!data.has("sessions") || !data.get("sessions").isJsonArray()) return;
        com.google.gson.JsonObject picked = LiveSummary.pickSession(data);
        for (com.google.gson.JsonElement element : data.getAsJsonArray("sessions")) {
            if (!element.isJsonObject()) continue;
            com.google.gson.JsonObject session = element.getAsJsonObject();
            if (picked != null && session != picked) continue;
            if (!session.has("trace") || !session.get("trace").isJsonObject()) continue;
            com.google.gson.JsonObject trace = session.getAsJsonObject("trace");
            float[] ppg = numberArray(trace, "ppg");
            float[] accel = numberArray(trace, "accel");
            float[] hr = new float[0];
            String range = "";
            // The trend of the heart rate actually shown (PaPaGei or rules).
            String historyKey = trace.has("display_hr_history") ? "display_hr_history" : "hr_history";
            if (trace.has(historyKey) && trace.get(historyKey).isJsonArray()) {
                com.google.gson.JsonArray pairs = trace.getAsJsonArray(historyKey);
                hr = new float[pairs.size()];
                float lo = Float.MAX_VALUE;
                float hi = -Float.MAX_VALUE;
                for (int i = 0; i < hr.length; i++) {
                    hr[i] = pairs.get(i).getAsJsonArray().get(1).getAsFloat();
                    lo = Math.min(lo, hr[i]);
                    hi = Math.max(hi, hr[i]);
                }
                if (hr.length > 0) range = Math.round(lo) + "\u2013" + Math.round(hi) + " bpm";
            }
            if (ppg.length > 1 || accel.length > 1 || hr.length > 1) {
                liveTracePpg = ppg;
                liveTraceAccel = accel;
                liveTraceHr = hr;
                liveTraceHrRange = range;
                return;
            }
        }
    }

    /**
     * One entry per recording wearable, each with its own heart rate, movement and
     * trends. The record screen used to plot one wearable chosen from all of them,
     * so with several wearables recording, some were never shown.
     */
    private List<com.wearable.controller.fragments.RecordFragment.LiveDevice> buildLiveDevices(
            com.google.gson.JsonObject data) {
        List<com.wearable.controller.fragments.RecordFragment.LiveDevice> devices = new ArrayList<>();
        if (data == null || !data.has("sessions") || !data.get("sessions").isJsonArray()) return devices;
        Map<String, Integer> typeCounts = new HashMap<>();
        for (com.google.gson.JsonElement element : data.getAsJsonArray("sessions")) {
            if (element.isJsonObject()) typeCounts.merge(jsonString(element.getAsJsonObject(), "device_type"), 1, Integer::sum);
        }
        for (com.google.gson.JsonElement element : data.getAsJsonArray("sessions")) {
            if (!element.isJsonObject()) continue;
            com.google.gson.JsonObject session = element.getAsJsonObject();
            String type = jsonString(session, "device_type");
            String name = DisplayNames.friendly(type);
            if (typeCounts.getOrDefault(type, 0) > 1) {
                // Two wearables of one kind share a type; tell them apart by the end of the address.
                String address = jsonString(session, "device_address");
                if (address.length() >= 5) name += " " + address.substring(address.length() - 5);
            }
            com.google.gson.JsonObject ppg = session.has("ppg") && session.get("ppg").isJsonObject()
                    ? session.getAsJsonObject("ppg") : null;
            Double shown = LiveSummary.shownHeartRate(ppg);
            String heartRate = shown != null ? Math.round(shown) + " bpm" : "-- bpm";
            List<String> detail = new ArrayList<>();
            if (shown != null) detail.add("papagei".equals(jsonString(ppg, "hr_source")) ? "PaPaGei" : "rules");
            if (ppg != null) detail.add(LiveSummary.signalLabel(jsonString(ppg, "quality")));
            if (session.has("movement") && session.get("movement").isJsonObject()) {
                detail.add(jsonString(session.getAsJsonObject("movement"), "state"));
            }
            int alerts = session.has("active_alerts") && session.get("active_alerts").isJsonArray()
                    ? session.getAsJsonArray("active_alerts").size() : 0;
            if (alerts > 0) detail.add(alerts + (alerts == 1 ? " alert" : " alerts"));
            if (shown == null && ppg != null && ppg.has("hr_reason_text") && !ppg.get("hr_reason_text").isJsonNull()) {
                detail.add(jsonString(ppg, "hr_reason_text"));
            }
            float[] movement = new float[0];
            float[] trend = new float[0];
            String range = "";
            if (session.has("trace") && session.get("trace").isJsonObject()) {
                com.google.gson.JsonObject trace = session.getAsJsonObject("trace");
                movement = numberArray(trace, "accel");
                String key = trace.has("display_hr_history") ? "display_hr_history" : "hr_history";
                if (trace.has(key) && trace.get(key).isJsonArray()) {
                    com.google.gson.JsonArray pairs = trace.getAsJsonArray(key);
                    trend = new float[pairs.size()];
                    float lo = Float.MAX_VALUE;
                    float hi = -Float.MAX_VALUE;
                    for (int i = 0; i < trend.length; i++) {
                        trend[i] = pairs.get(i).getAsJsonArray().get(1).getAsFloat();
                        lo = Math.min(lo, trend[i]);
                        hi = Math.max(hi, trend[i]);
                    }
                    if (trend.length > 0) range = Math.round(lo) + "\u2013" + Math.round(hi) + " bpm";
                }
            }
            devices.add(new com.wearable.controller.fragments.RecordFragment.LiveDevice(
                    name, type, heartRate, String.join(" · ", detail), movement, trend, range));
        }
        return devices;
    }

    private static float[] numberArray(com.google.gson.JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonArray()) return new float[0];
        com.google.gson.JsonArray array = object.getAsJsonArray(key);
        float[] out = new float[array.size()];
        for (int i = 0; i < out.length; i++) out[i] = array.get(i).getAsFloat();
        return out;
    }

    private String describeLiveSummary(com.google.gson.JsonObject data) {
        if (!data.has("sessions") || !data.get("sessions").isJsonArray()) return "";
        StringBuilder text = new StringBuilder("Live analysis");
        for (com.google.gson.JsonElement element : data.getAsJsonArray("sessions")) {
            if (!element.isJsonObject()) continue;
            com.google.gson.JsonObject session = element.getAsJsonObject();
            text.append("\n").append(DisplayNames.friendly(jsonString(session, "device_type"))).append(": ");
            List<String> parts = new ArrayList<>();
            if (session.has("ppg") && session.get("ppg").isJsonObject()) {
                com.google.gson.JsonObject ppg = session.getAsJsonObject("ppg");
                Double shown = LiveSummary.shownHeartRate(ppg);
                if (shown != null) {
                    parts.add("HR " + Math.round(shown) + " bpm" + LiveSummary.shownHeartRateSource(ppg));
                }
                parts.add("PPG " + jsonString(ppg, "quality"));
                if (shown == null && ppg.has("hr_reason_text") && !ppg.get("hr_reason_text").isJsonNull()) {
                    parts.add(jsonString(ppg, "hr_reason_text"));
                }
            }
            if (session.has("movement") && session.get("movement").isJsonObject()) {
                parts.add(jsonString(session.getAsJsonObject("movement"), "state"));
            }
            int alerts = session.has("active_alerts") && session.get("active_alerts").isJsonArray()
                    ? session.getAsJsonArray("active_alerts").size() : 0;
            if (alerts > 0) parts.add(alerts + (alerts == 1 ? " alert" : " alerts"));
            text.append(parts.isEmpty() ? "warming up" : String.join(" · ", parts));
        }
        // Each wearable has its own row below, with these details and its charts.
        return data.getAsJsonArray("sessions").size() > 0 ? "Live analysis" : text.toString();
    }

    private static final String ALERT_CHANNEL = "live_alerts";
    private static final long ALERT_REPEAT_MS = 5 * 60 * 1000L;
    private final Map<String, Long> lastAlertNotifiedMs = new HashMap<>();

    /**
     * Post a live alert as a notification. It reaches the lock screen with
     * the phone in a pocket, and it is how alerts reach a wearable that is
     * connected to its vendor's own app instead of this one: a Bluetooth
     * wearable often accepts one connection at a time, but the vendor's app
     * forwards notifications from apps the wearer allows. A cleared alert
     * replaces its raised one under the same id.
     */
    private void notifyAlert(String key, String state, String severity, String text) {
        android.app.NotificationManager manager =
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (manager.getNotificationChannel(ALERT_CHANNEL) == null) {
            android.app.NotificationChannel channel = new android.app.NotificationChannel(ALERT_CHANNEL,
                    "Live alerts", android.app.NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Problems with a recording, such as a loose sensor or a stalled stream");
            manager.createNotificationChannel(channel);
        }
        boolean raised = "raised".equals(state);
        // Informational alerts (motion) are worth seeing in the app but not
        // worth interrupting the wearer for.
        if (raised && "info".equals(severity)) return;
        // The same alert raised again within five minutes is not announced again.
        if (raised) {
            long now = System.currentTimeMillis();
            Long last = lastAlertNotifiedMs.get(key);
            if (last != null && now - last < ALERT_REPEAT_MS) return;
            lastAlertNotifiedMs.put(key, now);
        }
        android.app.PendingIntent open = android.app.PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                android.app.PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification notification = new androidx.core.app.NotificationCompat.Builder(this, ALERT_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(raised ? "Recording alert" : "Resolved")
                .setContentText(text)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setOnlyAlertOnce(!raised)
                .setAutoCancel(true)
                .setTimeoutAfter(raised ? 0 : 60000)
                .setContentIntent(open)
                .build();
        manager.notify(key.hashCode(), notification);
    }

    private static String jsonString(com.google.gson.JsonObject object, String key) {
        return LiveSummary.jsonString(object, key);
    }

    /** Other parts of the same wearable, seen or remembered, with the same group key as {@code address}. */
    private List<String> partnerParts(String address) {
        List<String> partners = new ArrayList<>();
        DeviceProfile profile = WearableDeviceRegistry.profileOf(deviceTypes.get(address));
        String group = profile.groupKeyOf(deviceNames.getOrDefault(address, ""));
        if (group.isEmpty()) return partners;
        for (Map.Entry<String, String> entry : deviceNames.entrySet()) {
            if (!entry.getKey().equals(address) && group.equals(profile.groupKeyOf(entry.getValue()))) {
                partners.add(entry.getKey());
            }
        }
        return partners;
    }

    private boolean partnerConnecting(String address) {
        for (String partner : partnerParts(address)) {
            if (connectedDevices.containsKey(partner) || pendingConnections.contains(partner)) return true;
        }
        return false;
    }

    /** What the displays need from this activity. */
    private void createDisplays() {
        HeadUpDisplay.Host host = new HeadUpDisplay.Host() {
            @Override public android.content.Context context() { return MainActivity.this; }
            @Override public android.os.Handler handler() { return uiHandler; }
            @Override public void log(String line) { appendLog(line); }
            @Override public Map<String, WearableDevice> connectedDevices() { return connectedDevices; }
            @Override public String deviceName(String address) { return deviceNames.getOrDefault(address, ""); }
            @Override public Integer battery(String address) { return batteryLevels.get(address); }
            @Override public boolean recordingActive() { return experimentActive; }
            @Override public String recordingElapsed() { return elapsed(currentExperimentStartedAtMs); }
            @Override public boolean cloudOnline() { return mqttManager != null && mqttManager.isConnected() && backendOnline; }
            @Override public com.google.gson.JsonObject latestLiveData() { return latestLiveData; }
            @Override public float[] livePulseTrace() { return liveTracePpg; }
            @Override public List<com.wearable.controller.fragments.RecordFragment.LiveDevice> liveDevices(
                    com.google.gson.JsonObject data) {
                return buildLiveDevices(data);
            }
        };
        for (DeviceProfile profile : deviceRegistry.getProfiles()) {
            HeadUpDisplay display = profile.createDisplay(host);
            if (display != null) displays.add(display);
        }
    }

    /** Back to the first screen: sign in, create an account, or stay a guest. */
    private void openLoginScreen() {
        startActivity(new Intent(this, LoginActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

    private void refreshAuthUi(String error) {
        refreshRecordIdentity();
        if (mqttFragment == null) return;
        if (supabaseAuth == null) {
            mqttFragment.setAuthState(false, "Sign-in unavailable",
                    "This device cannot store credentials securely.", false);
            return;
        }
        if (!supabaseAuth.isConfigured()) {
            mqttFragment.setAuthState(false, "Sign-in not configured",
                    "No identity provider is set in this build.", false);
            return;
        }
        if (supabaseAuth.isSignedIn()) {
            String email = supabaseAuth.getSignedInEmail();
            mqttFragment.setAuthState(true,
                    displayName.isEmpty() ? (email.isEmpty() ? "Signed in" : email) : displayName,
                    (email.isEmpty() ? "" : email + " · ") + "Recordings are attributed to this account.", false);
        } else {
            mqttFragment.setAuthState(false, "Signed out",
                    error != null ? error : "Sign in to contribute to a study.", false);
        }
    }

    private void refreshRecordIdentity() {
        if (recordFragment == null) return;
        if (supabaseAuth != null && supabaseAuth.isSignedIn()) {
            recordFragment.setIdentity(true, recorderName(), "Recording as participant");
        } else {
            recordFragment.setIdentity(false, "Sign in to start", "Not signed in");
        }
    }

    /** The account's display name, so the phone can say who is recording. */
    private void loadIdentity() {
        if (supabaseAuth == null || !supabaseAuth.isSignedIn() || restApiClient.getBaseUrl().isEmpty()) return;
        restApiClient.getMe(new RestApiClient.ResultCallback() {
            @Override
            public void onSuccess(com.google.gson.JsonObject response) {
                String name = jsonString(response, "display_name");
                runOnUiThread(() -> {
                    displayName = name;
                    refreshAuthUi(null);
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> appendLog("Auth: could not load your profile - " + message));
            }
        });
    }

    /**
     * Offer the signed-in account's studies on the Record tab, preselecting
     * the one used last time. The shared token has no identity, so the
     * endpoint refuses it and only "No study" is offered.
     */
    private void loadStudies() {
        if (recordFragment == null) return;
        long saved = getSharedPreferences("record", MODE_PRIVATE).getLong("study_id", -1);
        final Long preferred = saved < 0 ? null : saved;
        if (supabaseAuth == null || !supabaseAuth.isSignedIn() || restApiClient.getBaseUrl().isEmpty()) {
            recordFragment.setStudyOptions(new ArrayList<>(), null);
            return;
        }
        restApiClient.getStudies(new RestApiClient.ResultCallback() {
            @Override
            public void onSuccess(com.google.gson.JsonObject response) {
                List<RecordFragment.StudyOption> options = new ArrayList<>();
                if (response.has("studies") && response.get("studies").isJsonArray()) {
                    for (com.google.gson.JsonElement element : response.getAsJsonArray("studies")) {
                        if (!element.isJsonObject()) continue;
                        com.google.gson.JsonObject study = element.getAsJsonObject();
                        // A closed study no longer accepts recordings.
                        if ("closed".equals(jsonString(study, "status"))) continue;
                        long id = study.get("id").getAsLong();
                        String name = jsonString(study, "name");
                        options.add(new RecordFragment.StudyOption(id, name.isEmpty() ? "Study " + id : name));
                    }
                }
                runOnUiThread(() -> recordFragment.setStudyOptions(options, preferred));
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    recordFragment.setStudyOptions(new ArrayList<>(), null);
                    appendLog("Studies: could not load - " + message);
                });
            }
        });
    }

    /**
     * Create an account.
     *
     * The provider requires email confirmation, so a new account cannot sign in
     * until the link is followed. Say that plainly rather than leaving the
     * participant to discover it by failing to sign in.
     */
    private void signUp(String email, String password) {
        if (supabaseAuth == null) return;
        if (mqttFragment != null) {
            mqttFragment.setAuthState(false, "Creating account", "Contacting the identity provider...", true);
        }
        authExecutor.execute(() -> {
            String error = supabaseAuth.signUp(email, password);
            runOnUiThread(() -> {
                if (error == null) {
                    if (mqttFragment != null) {
                        mqttFragment.setAuthState(false, "Check your email",
                                "Account created. Confirm the address, then sign in.", false);
                    }
                    appendLog("Auth: account created for " + email.trim());
                } else {
                    refreshAuthUi(error);
                    appendLog("Auth: sign-up failed - " + error);
                }
            });
        });
    }

    /** Ask the provider to email a password-reset link. */
    private void resetPassword(String email) {
        if (supabaseAuth == null) return;
        if (mqttFragment != null) {
            mqttFragment.setAuthState(false, "Sending reset link", "Contacting the identity provider...", true);
        }
        authExecutor.execute(() -> {
            String error = supabaseAuth.requestPasswordReset(email);
            runOnUiThread(() -> {
                if (error == null) {
                    if (mqttFragment != null) {
                        // Same wording regardless of whether the address exists.
                        mqttFragment.setAuthState(false, "Signed out",
                                "If that address has an account, a reset link is on its way.", false);
                    }
                    appendLog("Auth: password reset requested");
                } else {
                    refreshAuthUi(error);
                    appendLog("Auth: password reset failed - " + error);
                }
            });
        });
    }
}
