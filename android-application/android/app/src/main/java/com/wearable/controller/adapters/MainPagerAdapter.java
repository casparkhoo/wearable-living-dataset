package com.wearable.controller.adapters;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.wearable.controller.fragments.ConnectedDevicesFragment;
import com.wearable.controller.fragments.MqttFragment;
import com.wearable.controller.fragments.RecordFragment;

/**
 * Three tabs, each a single job:
 *   0 Record   — who is recording, start/stop, activity labels, live analysis
 *   1 Devices  — remembered wearables, adding one, logs
 *   2 Settings — account, connection, appearance, advanced endpoints
 *
 * Browsing past recordings belongs to the web console, not the phone.
 */
public class MainPagerAdapter extends FragmentStateAdapter {

    public static final int NUM_TABS = 3;
    public static final int RECORD_TAB = 0;
    public static final int DEVICES_TAB = 1;
    public static final int SETTINGS_TAB = 2;

    private final RecordFragment recordFragment = new RecordFragment();
    private final ConnectedDevicesFragment connectedDevicesFragment = new ConnectedDevicesFragment();
    private final MqttFragment mqttFragment = new MqttFragment();

    public MainPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case DEVICES_TAB:
                return connectedDevicesFragment;
            case SETTINGS_TAB:
                return mqttFragment;
            case RECORD_TAB:
            default:
                return recordFragment;
        }
    }

    @Override
    public int getItemCount() {
        return NUM_TABS;
    }

    public RecordFragment getRecordFragment() {
        return recordFragment;
    }

    public ConnectedDevicesFragment getConnectedDevicesFragment() {
        return connectedDevicesFragment;
    }

    public MqttFragment getMqttFragment() {
        return mqttFragment;
    }
}
