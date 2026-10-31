package com.tetz.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.net.Uri;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SetupService extends Service {
    private static final String TAG = "TetzSetup";
    private static final String CHANNEL_ID = "tetz_setup";
    private static final int NOTIF_ID = 1;
    private static final long HEARTBEAT_INTERVAL_SEC = 15;

    private ScheduledExecutorService scheduler;

    public static void start(Context ctx) {
        Intent svc = new Intent(ctx, SetupService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(svc);
        } else {
            ctx.startService(svc);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(NOTIF_ID, buildNotification());
        grantSecureSettings();
        enableAdbOverNetwork();
        registerToMaster();
        startHeartbeat();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String masterUrl = intent.getStringExtra("master_url");
            if (masterUrl != null && !masterUrl.isEmpty()) {
                TetzConfig.setMasterUrl(this, masterUrl);
                Log.i(TAG, "master_url QR'dan alindi: " + masterUrl);
            }
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (scheduler != null) scheduler.shutdownNow();
        super.onDestroy();
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "TETZ Setup", NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("TETZ Agent")
                .setContentText("Cihaz yonetimi aktif")
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void grantSecureSettings() {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(this, AdminReceiver.class);
            if (Build.VERSION.SDK_INT >= 23) {
                dpm.setPermissionGrantState(admin, getPackageName(),
                        android.Manifest.permission.WRITE_SECURE_SETTINGS,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                Log.i(TAG, "WRITE_SECURE_SETTINGS granted");
            }
        } catch (Exception e) {
            Log.e(TAG, "grantSecureSettings failed", e);
        }
    }

    private void enableAdbOverNetwork() {
        try {
            Settings.Global.putInt(getContentResolver(), Settings.Global.ADB_ENABLED, 1);
        } catch (Exception e) {
            Log.e(TAG, "adb_enabled failed", e);
        }
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            sp.getMethod("set", String.class, String.class)
                    .invoke(null, "service.adb.tcp.port", "5555");
            Log.i(TAG, "adb tcp port 5555 set");
        } catch (Exception e) {
            Log.e(TAG, "setprop failed", e);
        }
    }

    private void registerToMaster() {
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("device_id", TetzConfig.getDeviceId(this));
                body.put("model", Build.MANUFACTURER + " " + Build.MODEL);
                body.put("android", Build.VERSION.RELEASE);
                String resp = postForBody(
                        TetzConfig.getMasterUrl(this) + "/register", body.toString());
                if (resp != null) {
                    TetzConfig.setRegistered(this, true);
                    String name = new JSONObject(resp).optString("name", "");
                    if (!name.isEmpty()) {
                        TetzConfig.setWorkerName(this, name);
                    }
                    Log.i(TAG, "registered to master, name=" + name);
                }
            } catch (Exception e) {
                Log.e(TAG, "register failed", e);
            }
        }).start();
    }

    private void startHeartbeat() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("device_id", TetzConfig.getDeviceId(this));
                post(TetzConfig.getMasterUrl(this) + "/heartbeat", body.toString());
            } catch (Exception e) {
                Log.e(TAG, "heartbeat failed", e);
            }
            checkQueue();
        }, HEARTBEAT_INTERVAL_SEC, HEARTBEAT_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    private void checkQueue() {
        try {
            String url = TetzConfig.getMasterUrl(this) + "/queue/"
                    + TetzConfig.getDeviceId(this);
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int code = conn.getResponseCode();
            if (code != 200) {
                conn.disconnect();
                return;
            }
            StringBuilder sb = new StringBuilder();
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) {
                    sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
            }
            conn.disconnect();
            JSONObject msg = new JSONObject(sb.toString());
            String apkUrl = msg.optString("apk_url", "");
            if (!apkUrl.isEmpty()) {
                downloadAndPromptInstall(apkUrl);
            }
        } catch (Exception e) {
            Log.e(TAG, "queue check failed", e);
        }
    }

    private void downloadAndPromptInstall(String apkPath) {
        try {
            String full = apkPath.startsWith("http")
                    ? apkPath : TetzConfig.getMasterUrl(this) + apkPath;
            Log.i(TAG, "APK indiriliyor: " + full);
            HttpURLConnection conn = (HttpURLConnection) new URL(full).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            File out = new File(getCacheDir(), "update.apk");
            try (InputStream in = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                }
            }
            conn.disconnect();
            Log.i(TAG, "APK indirildi: " + out.length() + " bayt");

            Uri uri = new Uri.Builder()
                    .scheme("content")
                    .authority(ApkProvider.AUTHORITY)
                    .path("update.apk")
                    .build();
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "APK indirme/kurma failed", e);
        }
    }

    private int post(String urlStr, String json) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setDoOutput(true);
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(data);
        }
        int code = conn.getResponseCode();
        conn.disconnect();
        return code;
    }

    private String postForBody(String urlStr, String json) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setDoOutput(true);
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(data);
        }
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            conn.disconnect();
            return null;
        }
        StringBuilder sb = new StringBuilder();
        try (InputStream in = conn.getInputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
        }
        conn.disconnect();
        return sb.toString();
    }
}
