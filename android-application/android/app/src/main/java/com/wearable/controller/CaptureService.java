package com.wearable.controller;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

/**
 * Keeps capture alive while the app is not on screen.
 *
 * The Bluetooth and broker connections are owned by MainActivity. Without this
 * service Android treats the app as a background app as soon as the screen turns
 * off or another app is opened: it may freeze or kill the process, and the
 * wearables drop. A foreground service, shown as a notification, tells Android
 * the app is doing work the user asked for, so the process keeps running with
 * the screen off. While a recording is open it also holds a partial wake lock,
 * so the timers that publish samples, check the connections and reconnect
 * wearables keep running when the phone would otherwise sleep.
 *
 * MainActivity calls {@link #update} once a second with the current state; the
 * service only changes the notification when that state changes.
 */
public class CaptureService extends Service {
    private static final String TAG = "CaptureService";
    private static final String CHANNEL = "capture";
    private static final int NOTIFICATION_ID = 41;
    private static final String EXTRA_WEARABLES = "wearables";
    private static final String EXTRA_RECORDING = "recording";
    private static final String EXTRA_LABEL = "label";
    private static final String ACTION_STOP = "com.wearable.controller.STOP_CAPTURE_SERVICE";

    private static boolean running;
    private static String lastState = "";

    private PowerManager.WakeLock wakeLock;

    /** Start the service, or update its notification, from the activity. */
    public static void update(Context context, int wearables, boolean recording, String label) {
        String state = wearables + "|" + recording + "|" + label;
        if (running && state.equals(lastState)) return;
        lastState = state;
        Intent intent = new Intent(context, CaptureService.class)
                .putExtra(EXTRA_WEARABLES, wearables)
                .putExtra(EXTRA_RECORDING, recording)
                .putExtra(EXTRA_LABEL, label == null ? "" : label);
        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (RuntimeException e) {
            // Android 12+ refuses to start a foreground service from the background.
            // The activity starts it while on screen, so this only happens if the
            // service was stopped by the system meanwhile; the next update made
            // while the app is open starts it again.
            Log.w(TAG, "Could not start capture service: " + e.getMessage());
            lastState = "";
        }
    }

    /** Stop the service when the activity is finishing for good. */
    public static void stop(Context context) {
        lastState = "";
        if (!running) return;
        context.stopService(new Intent(context, CaptureService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        int wearables = intent != null ? intent.getIntExtra(EXTRA_WEARABLES, 0) : 0;
        boolean recording = intent != null && intent.getBooleanExtra(EXTRA_RECORDING, false);
        String label = intent != null ? intent.getStringExtra(EXTRA_LABEL) : "";

        Notification notification = buildNotification(wearables, recording, label);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        running = true;
        holdWakeLock(recording);
        // If Android kills the process, the connections it owned are gone with it;
        // restarting an empty service would only show a misleading notification.
        return START_NOT_STICKY;
    }

    private Notification buildNotification(int wearables, boolean recording, String label) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Capture",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shown while the app keeps your wearables connected");
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent tap = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String devices = wearables == 1 ? "1 wearable connected" : wearables + " wearables connected";
        String title = recording ? "Recording" + (label != null && !label.isEmpty() ? ": " + label : "") : "Ready to record";
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(wearables == 0 ? "Looking for your wearables" : devices)
                .setContentIntent(tap)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }

    private void holdWakeLock(boolean recording) {
        if (recording && wakeLock == null) {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wearable:capture");
            wakeLock.setReferenceCounted(false);
            // Bounded, so a recording that is never stopped cannot hold the phone
            // awake for ever; renewed by the next update while still recording.
            wakeLock.acquire(12 * 60 * 60 * 1000L);
            Log.i(TAG, "Wake lock held for the recording");
        } else if (!recording && wakeLock != null) {
            releaseWakeLock();
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null) {
            if (wakeLock.isHeld()) wakeLock.release();
            wakeLock = null;
            Log.i(TAG, "Wake lock released");
        }
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        running = false;
        lastState = "";
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
