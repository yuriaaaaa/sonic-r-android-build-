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

    private static final long DUCK_INTERVAL_MS = 5 * 60 * 1000;

    private boolean mRunning;
    private volatile String mDuckStatus = "";
    private Thread mDuck;
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
            startDuckDns();
        }
        showRunning();
        return START_STICKY;
    }

    private void showRunning() {
        goForeground("Rooms are open on " + LauncherActivity.localAddresses()
            + ". Keep this phone on charge and on Wi-Fi." + mDuckStatus);
    }

    /*
     * Keeps a DuckDNS name on this network's public IP: DuckDNS records the
     * address the update comes from. The token never leaves this phone except
     * to duckdns.org.
     */
    private void startDuckDns() {
        android.content.SharedPreferences prefs = LauncherActivity.prefs(this);
        String domain = prefs.getString(LauncherActivity.KEY_DUCK_DOMAIN, "").trim()
            .replaceFirst("^https?://", "").replaceFirst("(?i)\\.duckdns\\.org.*$", "")
            .replaceFirst("[/:].*$", "");
        String token = prefs.getString(LauncherActivity.KEY_DUCK_TOKEN, "").trim();
        if (domain.isEmpty() || token.isEmpty()) return;
        mDuck = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                // On mobile data the name would point at the carrier's address, where
                // nobody can reach this hub; only update from Wi-Fi or Ethernet.
                String result = onHomeNetwork() ? duckUpdate(domain, token)
                    : "not updated (this phone isn't on Wi-Fi)";
                mDuckStatus = "\nDuckDNS " + domain + ".duckdns.org: " + result;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(this::showRunning);
                try {
                    Thread.sleep(DUCK_INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "duckdns");
        mDuck.setDaemon(true);
        mDuck.start();
    }

    private boolean onHomeNetwork() {
        android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
        android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return caps != null && (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET));
    }

    private static String duckUpdate(String domain, String token) {
        java.net.HttpURLConnection c = null;
        try {
            java.net.URL url = new java.net.URL("https://www.duckdns.org/update?domains="
                + java.net.URLEncoder.encode(domain, "UTF-8") + "&token="
                + java.net.URLEncoder.encode(token, "UTF-8") + "&ip=");
            c = (java.net.HttpURLConnection) url.openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            byte[] buf = new byte[16];
            int n = c.getInputStream().read(buf);
            String body = n > 0 ? new String(buf, 0, n, "UTF-8").trim() : "";
            String time = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT)
                .format(new java.util.Date());
            return body.startsWith("OK") ? "updated " + time
                : "refused (check the name and token)";
        } catch (Exception e) {
            return "couldn't reach DuckDNS, will retry";
        } finally {
            if (c != null) c.disconnect();
        }
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
        if (mDuck != null) mDuck.interrupt();
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
