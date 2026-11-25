package com.wearable.controller;

import android.content.Context;
import android.os.Handler;

import com.google.gson.JsonObject;
import com.wearable.controller.fragments.RecordFragment;

import java.util.List;
import java.util.Map;

/**
 * Drives wearables that show the live dashboard to their wearer (the DeviceProfile
 * says which: {@code hasDisplay()}). MainActivity talks to every display only through
 * this interface; what is sent to the device, and when, is the display's business.
 * Everything runs on the main thread.
 */
public interface HeadUpDisplay {

    /** What a display needs from the app. */
    interface Host {
        Context context();
        Handler handler();
        void log(String line);
        /** Connected wearables by address. */
        Map<String, WearableDevice> connectedDevices();
        /** Advertised name by address. */
        String deviceName(String address);
        Integer battery(String address);
        boolean recordingActive();
        /** "4:05" since the recording started; empty when unknown. */
        String recordingElapsed();
        boolean cloudOnline();
        /** The latest live summary from the backend, or null. */
        JsonObject latestLiveData();
        /** The pulse trace the Record tab draws. */
        float[] livePulseTrace();
        /** One row per recording wearable, as the Record tab lists them. */
        List<RecordFragment.LiveDevice> liveDevices(JsonObject data);
    }

    /** The profile type this display drives. */
    String deviceType();

    /** True for the command responses (from the adapter) this display wants to see. */
    boolean handlesCommand(String command);

    void onCommandResponse(String command, String response);

    /** A wearable of this display's type finished connecting. */
    void onDeviceConnected(String address);

    /** A new live summary arrived. */
    void onLiveSummary(JsonObject data);

    /** False while a tester has switched the automatic display off (debug builds). */
    boolean showsAutomatically();

    /** A real alert was raised or cleared; the banner goes up if the wearer is looking. */
    void onAlert(String state, String text);

    /** Update the Record tab's preview of what the wearer sees. */
    void refreshPreview(RecordFragment fragment);

    /** Debug builds only: flags file and the adb broadcast receiver. */
    void startDebugTools();

    void stopDebugTools();
}
