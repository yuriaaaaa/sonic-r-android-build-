package com.yuriaaaaa.sonicr;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.widget.Toast;

/**
 * "Run hub only": keeps a sonic-r-netplay hub running on this phone (TCP 8080,
 * UDP 9000-9001) without starting a race, so a spare phone can be the always-on
 * server everyone hosts and finds rooms on. Runs in its own process; stopping
 * the service ends that process, which closes the hub's ports.
 */
public class HubService extends Service {
    static final String ACTION_STOP = "com.yuriaaaaa.sonicr.STOP_HUB";
    private static final String CHANNEL = "hub";
    private static final int NOTIFICATION_ID = 7847;

    private boolean mRunning;
    private PowerManager.WakeLock mWake;
    private WifiManager.WifiLock mWifi;

    static void start(Context context) {
        Intent i = new Intent(context, HubService.class);
        if (Build.VERSION.SDK_INT >= 26)
            context.startForegroundService(i);
        else
            context.startService(i);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // startForegroundService needs startForeground soon, even if the hub then fails.
        goForeground("Starting the hub…");
        if (!mRunning) {
            String error = Netplay.available() ? Netplay.nativeStartHub()
                : "Online rooms aren't included in this build.";
            if (error != null) {
                Toast.makeText(this, "The hub couldn't start. " + error, Toast.LENGTH_LONG).show();
                stopSelf();
                return START_NOT_STICKY;
            }
            mRunning = true;
            holdLocks();
        }
        goForeground("Rooms are open on " + LauncherActivity.localAddresses()
            + ". Keep this phone on charge and on Wi-Fi.");
        return START_STICKY;
    }

    private void goForeground(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Online rooms hub",
                NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        Intent stop = new Intent(this, HubService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stop,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 0,
            new Intent(this, LauncherActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = b.setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Sonic R hub running")
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(new Notification.Action.Builder(null, "Stop hub", stopPi).build())
            .build();
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else
            startForeground(NOTIFICATION_ID, n);
    }

    /* Keeps the CPU and Wi-Fi awake with the screen off, so the hub stays reachable. */
    private void holdLocks() {
        PowerManager pm = getSystemService(PowerManager.class);
        mWake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sonicr:hub");
        mWake.acquire();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        mWifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "sonicr:hub");
        mWifi.acquire();
    }

    @Override
    public void onDestroy() {
        if (mWake != null && mWake.isHeld()) mWake.release();
        if (mWifi != null && mWifi.isHeld()) mWifi.release();
        super.onDestroy();
        // The hub has no stop call; ending the process frees its ports.
        if (mRunning) android.os.Process.killProcess(android.os.Process.myPid());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
