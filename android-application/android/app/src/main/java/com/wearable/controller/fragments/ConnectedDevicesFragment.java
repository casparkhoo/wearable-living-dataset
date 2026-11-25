package com.wearable.controller.fragments;

import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.wearable.controller.DisplayNames;
import com.wearable.controller.R;
import com.wearable.controller.WearableDeviceRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Wearables: the ones this phone remembers, with their live state, and a
 * single button to find and add a new one. Tapping a remembered wearable
 * offers its actions; tapping a nearby one connects it. Logs sit behind a
 * toggle at the bottom because only a developer needs them.
 */
public class ConnectedDevicesFragment extends Fragment {
    /** A Cancel tap this soon after Connect is taken as a double tap and ignored. */
    private static final long CANCEL_GUARD_MS = 2000;
    private final java.util.Map<String, Long> lastChipTapMs = new java.util.HashMap<>();


    public interface ActionListener {
        /** Search for one kind of wearable, chosen by the participant. */
        void onStartSearch(String deviceType);

        void onStopSearch();

        void onConnectNearby(String address);

        void onConnectNow(String address);

        void onDisconnect(String address);

        void onForget(String address);

        void onCommand(String address, String command);
    }

    /** A remembered or currently connected wearable. */
    public static class DeviceRow {
        public static final String CONNECTED = "connected";
        public static final String CONNECTING = "connecting";
        public static final String SEARCHING = "searching";
        /** Not reconnected automatically until the participant asks. */
        public static final String PAUSED = "paused";

        public final String address;
        public final String name;
        public final String type;
        public final String state;
        public final List<String> commands;
        /** Battery percentage when the wearable reports one, otherwise null. */
        public final Integer battery;

        public DeviceRow(String address, String name, String type, String state, List<String> commands,
                         Integer battery) {
            this.address = address;
            this.name = name;
            this.type = type;
            this.state = state;
            this.commands = commands == null ? new ArrayList<>() : commands;
            this.battery = battery;
        }
    }

    /** A wearable seen while searching that is not remembered yet. */
    public static class NearbyDevice {
        public final String address;
        public final String name;
        public final String type;

        public NearbyDevice(String address, String name, String type) {
            this.address = address;
            this.name = name;
            this.type = type;
        }
    }

    private static final int MAX_LOG_LINES = 200;

    private ActionListener actionListener;
    private LinearLayout devicesContainer;
    private LinearLayout nearbyContainer;
    private View nearbyCard;
    private TextView nearbyHint;
    private TextView reconnectStatus;
    private Button scanButton;
    private Button logsToggle;
    private ScrollView logScroll;
    private TextView logText;

    private List<DeviceRow> devices = new ArrayList<>();
    private List<NearbyDevice> nearby = new ArrayList<>();
    private List<String> supportedTypes = new ArrayList<>();
    private String searchType = "";
    private boolean scanning;
    private String reconnectText = "";
    private boolean logsOpen;
    private final List<String> logs = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_connected_devices, container, false);
        devicesContainer = view.findViewById(R.id.devices_container);
        nearbyContainer = view.findViewById(R.id.devices_nearby_container);
        nearbyCard = view.findViewById(R.id.devices_nearby_card);
        nearbyHint = view.findViewById(R.id.devices_nearby_hint);
        reconnectStatus = view.findViewById(R.id.devices_reconnect_status);
        scanButton = view.findViewById(R.id.devices_scan_button);
        logsToggle = view.findViewById(R.id.devices_logs_toggle);
        logScroll = view.findViewById(R.id.devices_log_scroll);
        logText = view.findViewById(R.id.devices_log_text);

        scanButton.setOnClickListener(v -> {
            if (actionListener == null) return;
            if (scanning) {
                actionListener.onStopSearch();
            } else {
                chooseWearableType();
            }
        });
        logsToggle.setOnClickListener(v -> {
            logsOpen = !logsOpen;
            applyLogs();
        });

        render();
        applyLogs();
        return view;
    }

    public void setActionListener(ActionListener listener) {
        this.actionListener = listener;
    }

    /** The kinds of wearable the app can connect, offered when adding one. */
    public void setSupportedTypes(List<String> types) {
        supportedTypes = new ArrayList<>(types);
    }

    /**
     * Ask which wearable is being added before searching, so the list only
     * ever shows that kind instead of every Bluetooth device in the room.
     */
    private void chooseWearableType() {
        if (getContext() == null || supportedTypes.isEmpty()) return;
        String[] labels = new String[supportedTypes.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = describeType(supportedTypes.get(i));
        new AlertDialog.Builder(getContext())
                .setTitle("Which wearable are you adding?")
                .setItems(labels, (dialog, which) -> {
                    searchType = supportedTypes.get(which);
                    actionListener.onStartSearch(searchType);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static String describeType(String type) {
        return WearableDeviceRegistry.profileOf(type).getPickerLabel();
    }

    public void setDevices(List<DeviceRow> rows) {
        devices = new ArrayList<>(rows);
        render();
    }

    public void setNearby(List<NearbyDevice> rows) {
        nearby = new ArrayList<>(rows);
        render();
    }

    public void setScanning(boolean scanning) {
        this.scanning = scanning;
        render();
    }

    public void setReconnectStatus(String text) {
        reconnectText = text == null ? "" : text;
        render();
    }

    public void appendLog(String line) {
        logs.add(line);
        while (logs.size() > MAX_LOG_LINES) logs.remove(0);
        if (logsOpen) applyLogs();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Updates arrive from Bluetooth and network callbacks, sometimes while
        // this page has no view; onCreateView renders the cached state again.
        // (getView() cannot be the test: it is still null inside onCreateView.)
        devicesContainer = null;
        logsToggle = null;
    }

    private boolean attached() {
        return devicesContainer != null && getContext() != null;
    }

    private void render() {
        if (!attached()) return;
        Context context = getContext();
        if (!reconnectText.isEmpty()) reconnectStatus.setText(reconnectText);

        devicesContainer.removeAllViews();
        if (devices.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText("No wearables yet. Tap “Add a wearable” below.");
            empty.setTextColor(ContextCompat.getColor(context, R.color.warm_muted));
            empty.setTextSize(14f);
            empty.setPadding(0, dp(12), 0, dp(12));
            devicesContainer.addView(empty);
        }
        for (DeviceRow device : devices) {
            int color;
            String label;
            switch (device.state) {
                case DeviceRow.CONNECTED:
                    color = R.color.status_green;
                    label = "Connected";
                    break;
                case DeviceRow.CONNECTING:
                    color = R.color.status_amber;
                    label = "Connecting… keep it close and switched on";
                    break;
                case DeviceRow.PAUSED:
                    color = R.color.warm_muted;
                    label = "Disconnected by you. Tap Connect to bring it back";
                    break;
                default:
                    color = R.color.warm_muted;
                    label = "Searching… make sure it is on and nearby";
            }
            String detail = device.type.equals(device.name) ? label : device.type + " · " + label;
            if (device.battery != null && DeviceRow.CONNECTED.equals(device.state)) {
                detail += " · " + device.battery + "% battery";
                // A nearly flat wearable is worth flagging before a recording dies with it.
                if (device.battery <= 15) color = R.color.status_amber;
            }
            boolean connected = DeviceRow.CONNECTED.equals(device.state);
            boolean connecting = DeviceRow.CONNECTING.equals(device.state);
            // The thing most often wanted is one tap away: Connect when the
            // wearable is not connected, Cancel while it is being connected.
            // The row itself still opens the longer list.
            // While connecting the main button retries now. It used to read Cancel: a
            // wearable the app was waiting for in the background showed Cancel, and a tap
            // meant as "connect" cancelled and paused it. Cancel is now its own small
            // button beside Retry (and stays in the row's menu), so neither is mistaken
            // for the other and a wearable stuck connecting can always be stopped.
            View row = deviceCard(device, detail, color, connected ? "⋯" : connecting ? "Retry" : "Connect");
            row.setOnClickListener(v -> showActions(device));
            if (!connected) {
                View action = ((LinearLayout) row).getChildAt(((LinearLayout) row).getChildCount() - 1);
                action.setPadding(dp(14), dp(10), dp(14), dp(10));
                action.setBackgroundResource(R.drawable.bg_status_chip);
                action.setOnClickListener(v -> {
                    if (actionListener == null) return;
                    long now = android.os.SystemClock.elapsedRealtime();
                    Long last = lastChipTapMs.get(device.address);
                    // Connect, or retry now while connecting; repeated taps within
                    // the guard time are ignored so one attempt is not restarted twice.
                    if (connecting && last != null && now - last < CANCEL_GUARD_MS) return;
                    actionListener.onConnectNow(device.address);
                    lastChipTapMs.put(device.address, now);
                });
            }
            if (connecting) {
                TextView cancel = new TextView(context);
                cancel.setText("Cancel");
                cancel.setTextSize(15f);
                cancel.setTextColor(ContextCompat.getColor(context, R.color.warm_muted));
                cancel.setTypeface(cancel.getTypeface(), android.graphics.Typeface.BOLD);
                cancel.setPadding(dp(14), dp(10), dp(14), dp(10));
                cancel.setOnClickListener(v -> {
                    if (actionListener != null) actionListener.onDisconnect(device.address);
                });
                ((LinearLayout) row).addView(cancel);
            }
            devicesContainer.addView(row);
        }

        scanButton.setText(scanning ? "Stop searching" : "Add a wearable");
        boolean showNearby = scanning || !nearby.isEmpty();
        nearbyCard.setVisibility(showNearby ? View.VISIBLE : View.GONE);
        String what = searchType.isEmpty() ? "wearables" : searchType;
        nearbyHint.setText(nearby.isEmpty()
                ? "Looking for " + what + "… switch it on and keep it close."
                : "Tap it to connect. It will be remembered.");
        nearbyContainer.removeAllViews();
        for (NearbyDevice device : nearby) {
            View row = row(device.name, device.type, R.color.brand, "Connect");
            row.setOnClickListener(v -> {
                if (actionListener != null) actionListener.onConnectNearby(device.address);
            });
            nearbyContainer.addView(row);
        }
    }

    /**
     * A wearable as a card: a drawing of it, its name and state, a battery bar when it
     * reports one, and a trailing action. The last child is the trailing action, which
     * the caller restyles for wearables that are not connected.
     */
    private View deviceCard(DeviceRow device, String detail, int colorRes, String trailing) {
        Context context = getContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(76));
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackgroundResource(R.drawable.bg_device_card);
        row.setClickable(true);
        row.setFocusable(true);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, 0, 0, dp(10));
        row.setLayoutParams(rowParams);

        com.wearable.controller.views.DeviceIconView icon = new com.wearable.controller.views.DeviceIconView(context);
        boolean live = DeviceRow.CONNECTED.equals(device.state);
        icon.set(WearableDeviceRegistry.profileOf(device.type).getIconKey(), ContextCompat.getColor(context, live ? colorRes : R.color.warm_muted));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        iconParams.setMarginEnd(dp(14));
        row.addView(icon, iconParams);

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(context);
        name.setText(DisplayNames.title(device.type, device.name));
        name.setTextSize(16f);
        name.setTextColor(ContextCompat.getColor(context, R.color.warm_text));
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        TextView sub = new TextView(context);
        sub.setText(DisplayNames.friendly(detail));
        sub.setTextSize(13f);
        sub.setTextColor(ContextCompat.getColor(context, colorRes == R.color.brand ? R.color.warm_muted : colorRes));
        texts.addView(name);
        texts.addView(sub);
        if (WearableDeviceRegistry.profileOf(device.type).hasFixedTitle() && device.name != null
                && !device.name.equals(DisplayNames.title(device.type, device.name))) {
            // The advertised Bluetooth name is only for telling two of the same kind apart.
            TextView ble = new TextView(context);
            ble.setText(device.name);
            ble.setTextSize(10f);
            ble.setTextColor(ContextCompat.getColor(context, R.color.warm_muted));
            texts.addView(ble);
        }
        if (device.battery != null && live) {
            com.wearable.controller.views.BatteryBarView bar = new com.wearable.controller.views.BatteryBarView(context);
            bar.setPercent(device.battery);
            LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(5));
            barParams.topMargin = dp(8);
            texts.addView(bar, barParams);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView hint = new TextView(context);
        hint.setText(trailing);
        hint.setTextSize(15f);
        hint.setTextColor(ContextCompat.getColor(context, R.color.brand));
        hint.setTypeface(hint.getTypeface(), android.graphics.Typeface.BOLD);
        row.addView(hint);
        return row;
    }

    /** A large tappable row: dot, name and detail, and a trailing hint. */
    private View row(String title, String detail, int colorRes, String trailing) {
        Context context = getContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(64));
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
        row.setBackgroundResource(R.drawable.bg_device_card);
        row.setClickable(true);
        row.setFocusable(true);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, 0, 0, dp(8));
        row.setLayoutParams(rowParams);

        View dot = new View(context);
        dot.setBackgroundResource(R.drawable.bg_dot);
        dot.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(context, colorRes)));
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(12), dp(12));
        dotParams.setMarginEnd(dp(14));
        row.addView(dot, dotParams);

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(context);
        name.setText(title);
        name.setTextSize(16f);
        name.setTextColor(ContextCompat.getColor(context, R.color.warm_text));
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        TextView sub = new TextView(context);
        sub.setText(detail);
        sub.setTextSize(13f);
        sub.setTextColor(ContextCompat.getColor(context, colorRes == R.color.brand ? R.color.warm_muted : colorRes));
        texts.addView(name);
        texts.addView(sub);
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView hint = new TextView(context);
        hint.setText(trailing);
        hint.setTextSize(15f);
        hint.setTextColor(ContextCompat.getColor(context, R.color.brand));
        hint.setTypeface(hint.getTypeface(), android.graphics.Typeface.BOLD);
        row.addView(hint);
        return row;
    }

    private void showActions(DeviceRow device) {
        if (actionListener == null || getContext() == null) return;
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (DeviceRow.CONNECTED.equals(device.state)) {
            labels.add("Disconnect");
            actions.add(() -> actionListener.onDisconnect(device.address));
            for (String command : device.commands) {
                labels.add(prettyCommand(command));
                actions.add(() -> actionListener.onCommand(device.address, command));
            }
        } else if (DeviceRow.CONNECTING.equals(device.state)) {
            labels.add("Cancel connecting");
            actions.add(() -> actionListener.onDisconnect(device.address));
        } else {
            labels.add(DeviceRow.PAUSED.equals(device.state) ? "Connect now" : "Try to connect now");
            actions.add(() -> actionListener.onConnectNow(device.address));
        }
        labels.add("Forget this wearable");
        actions.add(() -> actionListener.onForget(device.address));

        new AlertDialog.Builder(getContext())
                .setTitle(device.name)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> actions.get(which).run())
                .setNegativeButton("Close", null)
                .show();
    }

    /** START_MEASUREMENT -> Start measurement. */
    private static String prettyCommand(String command) {
        String words = command.replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
        return words.isEmpty() ? command : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private void applyLogs() {
        if (logsToggle == null || getContext() == null) return;
        logsToggle.setText(logsOpen ? "Hide logs" : "Show logs");
        logScroll.setVisibility(logsOpen ? View.VISIBLE : View.GONE);
        if (!logsOpen) return;
        StringBuilder text = new StringBuilder();
        for (String line : logs) text.append(line).append('\n');
        logText.setText(logs.isEmpty() ? "Nothing logged yet." : text.toString());
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private int dp(int value) {
        // getResources() throws rather than returning null once detached.
        Context context = getContext();
        if (context == null) {
            return value;
        }
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }
}
