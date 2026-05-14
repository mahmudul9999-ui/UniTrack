package com.unitrack.app;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service that keeps GPS tracking active in the background.
 * Survives app being closed, screen off, and (mostly) being killed by Android.
 *
 * Writes GPS to: locations/{circleId}/{uid}
 *
 * IMPORTANT: Firebase rules for this Firebase project must allow
 * `locations/{cid}/{uid}` to be writable even without auth token, because
 * the auth token expires after 1 hour and the lost user must keep reporting.
 */
public class LocationForegroundService extends Service implements LocationListener {

    public static final String ACTION_START   = "com.unitrack.app.START";
    public static final String ACTION_STOP    = "com.unitrack.app.STOP";
    public static final String ACTION_RESTART = "com.unitrack.app.RESTART";

    private static final String TAG       = "UniTrackSvc";
    private static final String CHAN_ID   = "unitrack_location";
    private static final int    NOTIF_ID  = 1338;

    // ═══ CONFIG ═══
    // CHANGE THIS to your NEW Firebase project's database URL
    private static final String FIREBASE_URL = "https://unitrack-app-29c39-default-rtdb.asia-southeast1.firebasedatabase.app";

    private LocationManager locationManager;
    private NotificationManager notificationManager;
    private AlarmManager alarmManager;
    private PowerManager.WakeLock wakeLock;
    private Handler mainHandler;
    private HandlerThread heartbeatThread;
    private Handler heartbeatHandler;
    private ExecutorService networkThread;

    private String uid = "";
    private String userName = "";
    private String circleId = "";
    private String firebaseToken = "";

    private Location lastLocation = null;
    private boolean isRunning = false;

    private static final long LOCATION_INTERVAL_MS = 20000;  // 20s
    private static final long LOCATION_MIN_DIST_M  = 5;
    private static final long HEARTBEAT_MS         = 25000;  // 25s
    private static final long WATCHDOG_MS          = 4 * 60 * 1000;  // 4 min

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate");
        locationManager     = (LocationManager) getSystemService(LOCATION_SERVICE);
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        alarmManager        = (AlarmManager) getSystemService(ALARM_SERVICE);
        mainHandler         = new Handler(Looper.getMainLooper());
        networkThread       = Executors.newSingleThreadExecutor();

        createNotificationChannel();

        // CRITICAL: must call startForeground IMMEDIATELY in onCreate
        // to avoid ForegroundServiceDidNotStartInTimeException on Android 12+
        startForeground(NOTIF_ID, buildNotification("Starting UniTrack..."));

        loadCredentials();
        acquireWakeLock();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        Log.d(TAG, "onStartCommand action=" + action);

        if (ACTION_STOP.equals(action)) {
            shutdown();
            return START_NOT_STICKY;
        }

        loadCredentials();
        if (uid.isEmpty() || circleId.isEmpty()) {
            Log.w(TAG, "No credentials — stopping");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!isRunning) {
            isRunning = true;
            startLocationUpdates();
            startHeartbeat();
            scheduleWatchdog();
        }

        return START_STICKY;  // restart if killed
    }

    private void loadCredentials() {
        SharedPreferences prefs = getSharedPreferences(BootReceiver.PREFS, MODE_PRIVATE);
        uid           = prefs.getString("uid", "");
        userName      = prefs.getString("name", "User");
        circleId      = prefs.getString("circleId", "");
        firebaseToken = prefs.getString("firebaseToken", "");
    }

    @SuppressWarnings("MissingPermission")
    private void startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "No location permission");
            return;
        }
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    LOCATION_INTERVAL_MS, LOCATION_MIN_DIST_M, this, Looper.getMainLooper());
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    LOCATION_INTERVAL_MS, LOCATION_MIN_DIST_M, this, Looper.getMainLooper());
            }
        } catch (Exception e) {
            Log.e(TAG, "startLocationUpdates: " + e.getMessage());
        }
    }

    private void stopLocationUpdates() {
        try { locationManager.removeUpdates(this); } catch (Exception ignored) {}
    }

    @Override
    public void onLocationChanged(@Nullable Location loc) {
        if (loc == null) return;
        lastLocation = loc;
        pushLocation(loc.getLatitude(), loc.getLongitude(), loc.getAccuracy());
        updateNotification(String.format("GPS active (±%dm)", (int) loc.getAccuracy()));
    }

    @Override public void onProviderEnabled(String p) {}
    @Override public void onProviderDisabled(String p) {}
    @Override public void onStatusChanged(String p, int s, Bundle b) {}

    // ═══ HEARTBEAT — re-push lastLocation every 25s ═══
    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatThread = new HandlerThread("UniTrackHeartbeat");
        heartbeatThread.start();
        heartbeatHandler = new Handler(heartbeatThread.getLooper());
        heartbeatHandler.postDelayed(heartbeatRunner, HEARTBEAT_MS);
    }

    private void stopHeartbeat() {
        if (heartbeatHandler != null) heartbeatHandler.removeCallbacks(heartbeatRunner);
        if (heartbeatThread != null) heartbeatThread.quitSafely();
        heartbeatHandler = null;
        heartbeatThread  = null;
    }

    private final Runnable heartbeatRunner = new Runnable() {
        @Override
        public void run() {
            if (lastLocation != null && isRunning) {
                pushLocation(lastLocation.getLatitude(), lastLocation.getLongitude(),
                    lastLocation.getAccuracy());
            }
            if (heartbeatHandler != null) {
                heartbeatHandler.postDelayed(this, HEARTBEAT_MS);
            }
        }
    };

    // ═══ WATCHDOG — AlarmManager restarts service if killed ═══
    private void scheduleWatchdog() {
        Intent i = new Intent(this, LocationForegroundService.class);
        i.setAction(ACTION_RESTART);
        PendingIntent pi = PendingIntent.getService(this, 99, i,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        long when = System.currentTimeMillis() + WATCHDOG_MS;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, when, pi);
        }
    }

    // ═══ FIREBASE REST API — writes location ═══
    private void pushLocation(double lat, double lng, double acc) {
        if (uid.isEmpty() || circleId.isEmpty()) return;
        networkThread.execute(() -> {
            try {
                JSONObject d = new JSONObject();
                d.put("uid", uid);
                d.put("name", userName);
                d.put("lat", lat);
                d.put("lng", lng);
                d.put("acc", acc);
                d.put("lastSeen", System.currentTimeMillis());
                d.put("online", true);
                // Note: don't overwrite sos/photoURL/label/role - the web handles those
                String path = "/locations/" + circleId + "/" + uid + ".json";
                httpPatchWithRetry(path, d.toString());
            } catch (Exception e) {
                Log.e(TAG, "pushLocation: " + e.getMessage());
            }
        });
    }

    /** PATCH so we only update fields, not overwrite (preserves sos/photoURL etc.) */
    private void httpPatchWithRetry(String path, String body) throws Exception {
        int code = httpPatchOnce(path, body, firebaseToken);
        if (code == 401 || code == 403) {
            Log.w(TAG, "Auth failed (HTTP " + code + ") — retrying without token");
            code = httpPatchOnce(path, body, null);
            if (code != 200) {
                Log.e(TAG, "Firebase write failed. Ensure rules allow .write on locations.");
            }
        }
    }

    private int httpPatchOnce(String path, String body, String token) throws Exception {
        String urlStr = FIREBASE_URL + path;
        if (token != null && !token.isEmpty()) urlStr += "?auth=" + token;
        URL url = new URL(urlStr);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        try {
            c.setRequestMethod("PATCH");
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            c.setConnectTimeout(10_000);
            c.setReadTimeout(10_000);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            return c.getResponseCode();
        } finally {
            c.disconnect();
        }
    }

    // ═══ NOTIFICATION ═══
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHAN_ID, "UniTrack Location",
                NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Keeps your location active for your circle");
            ch.setShowBadge(false);
            notificationManager.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHAN_ID)
            .setContentTitle("UniTrack Active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    private void updateNotification(String text) {
        notificationManager.notify(NOTIF_ID, buildNotification(text));
    }

    // ═══ WAKE LOCK ═══
    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UniTrack::Loc");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(60 * 60 * 1000L);  // 1 hour, will be re-acquired by service restart
    }

    // ═══ LIFECYCLE ═══
    private void shutdown() {
        isRunning = false;
        stopHeartbeat();
        stopLocationUpdates();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        // Mark offline
        if (!uid.isEmpty() && !circleId.isEmpty()) {
            networkThread.execute(() -> {
                try {
                    JSONObject d = new JSONObject();
                    d.put("online", false);
                    httpPatchWithRetry("/locations/" + circleId + "/" + uid + ".json", d.toString());
                } catch (Exception ignored) {}
            });
        }
        stopForeground(true);
        stopSelf();
    }

    @Nullable @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.d(TAG, "onTaskRemoved — scheduling restart");
        Intent restart = new Intent(getApplicationContext(), LocationForegroundService.class);
        restart.setAction(ACTION_RESTART);
        PendingIntent pi = PendingIntent.getService(getApplicationContext(), 88, restart,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        long when = System.currentTimeMillis() + 1000;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, when, pi);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        stopHeartbeat();
        stopLocationUpdates();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (networkThread != null) networkThread.shutdown();
        Log.d(TAG, "onDestroy");
    }
}
