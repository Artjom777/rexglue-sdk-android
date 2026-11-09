package com.rexglue.app;

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

/**
 * Foreground Service that elevates process priority (oom_score_adj <= 1)
 * and holds a CPU WakeLock during heavy Xbox 360 decompilation & packaging.
 * Prevents Android Low Memory Killer (LMK) from killing the process when
 * utilizing full Dimensity 8400 CPU/RAM power on POCO X7 Pro.
 * Crash-safe implementation.
 */
public class CodegenForegroundService extends Service {
    private static final String TAG = "CodegenForeground";
    public static final String ACTION_START = "com.rexglue.app.action.START_FULL_POWER";
    public static final String ACTION_STOP = "com.rexglue.app.action.STOP_FULL_POWER";
    public static final String CHANNEL_ID = "rexglue_high_perf";
    private static final int NOTIFICATION_ID = 2026;

    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            createNotificationChannel();
        } catch (Throwable t) {
            Log.w(TAG, "Notification channel warning: " + t.getMessage());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (intent != null && ACTION_STOP.equals(intent.getAction())) {
                stopForegroundService();
                return START_NOT_STICKY;
            }
            startFullPowerMode();
        } catch (Throwable t) {
            Log.e(TAG, "onStartCommand safe caught: " + t.getMessage());
        }
        return START_NOT_STICKY;
    }

    private void startFullPowerMode() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && (wakeLock == null || !wakeLock.isHeld())) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReXGlue::FullPowerLock");
                wakeLock.acquire(120 * 60 * 1000L); // 120 minutes max timeout safeguard
                Log.i(TAG, "Acquired WakeLock for full performance execution");
            }
        } catch (Throwable e) {
            Log.w(TAG, "WakeLock acquisition warning: " + e.getMessage());
        }

        try {
            Notification notification = buildForegroundNotification(
                    "ReXGlue Studio (POCO X7 Pro)",
                    "Выполняется обработка на полной мощности (LMK защита активна)");

            if (Build.VERSION.SDK_INT >= 34) {
                try {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                } catch (Throwable t) {
                    try {
                        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                    } catch (Throwable t2) {
                        startForeground(NOTIFICATION_ID, notification);
                    }
                }
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            Log.i(TAG, "CodegenForegroundService active: oom_score_adj protected against LMK");
        } catch (Throwable t) {
            Log.w(TAG, "startForeground safe bypass: " + t.getMessage());
        }
    }

    private void stopForegroundService() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable ignored) {}
        wakeLock = null;

        try {
            stopForeground(true);
        } catch (Throwable ignored) {}

        try {
            stopSelf();
        } catch (Throwable ignored) {}

        Log.i(TAG, "CodegenForegroundService stopped");
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "ReXGlue High Performance Service",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Держит процесс ReXGlue в приоритете при интенсивных вычислениях");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildForegroundNotification(String title, String content) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        try {
            super.onDestroy();
            stopForegroundService();
        } catch (Throwable ignored) {}
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
