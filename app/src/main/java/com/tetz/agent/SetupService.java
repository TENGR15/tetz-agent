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
import android.content.pm.PackageInstaller;
import android.content.IntentSender;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.net.Uri;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
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
        applyFreeformSettings();
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

    private void applyFreeformSettings() {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(this, AdminReceiver.class);
            boolean isDO = Build.VERSION.SDK_INT >= 18 && dpm.isDeviceOwnerApp(getPackageName());
            if (!isDO) {
                Log.i(TAG, "Device Owner degil, freeform atlaniyor");
                return;
            }
            Settings.Global.putInt(getContentResolver(), "enable_freeform_support", 1);
            Settings.Global.putInt(getContentResolver(), "force_resizable_activities", 1);
            Log.i(TAG, "freeform ayarlari yazildi");
        } catch (Exception e) {
            Log.e(TAG, "freeform ayarlari failed", e);
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
                body.put("miner", TetzConfig.getMinerStatus(this));
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
            String type = msg.optString("type", "install_apk");
            if ("start_app".equals(type)) {
                startApp(msg.optString("package", ""));
            } else if ("stop_app".equals(type)) {
                stopApp(msg.optString("package", ""));
            } else if ("setup_miner".equals(type)) {
                setupMiner();
            } else if ("miner_start".equals(type)) {
                minerStart();
            } else if ("miner_stop".equals(type)) {
                minerStop();
            } else if ("miner_config".equals(type)) {
                minerConfig(msg);
            } else {
                String apkUrl = msg.optString("apk_url", "");
                if (!apkUrl.isEmpty()) {
                    downloadAndPromptInstall(apkUrl);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "queue check failed", e);
        }
    }

    private void startApp(String pkg) {
        if (pkg.isEmpty()) return;
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
                Log.i(TAG, "start_app: " + pkg);
            } else {
                Log.w(TAG, "start_app: launch intent yok: " + pkg);
            }
        } catch (Exception e) {
            Log.e(TAG, "start_app failed: " + pkg, e);
        }
    }

    private void stopApp(String pkg) {
        if (pkg.isEmpty()) return;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"am", "force-stop", pkg});
            int rc = p.waitFor();
            Log.i(TAG, "stop_app: " + pkg + " rc=" + rc);
        } catch (Exception e) {
            Log.e(TAG, "stop_app failed: " + pkg, e);
        }
    }

    // ---- Madenci (Termux RUN_COMMAND) ----

    private static final String TERMUX_HOME = "/data/data/com.termux/files/home";
    private static final String TERMUX_SH = "/data/data/com.termux/files/usr/bin/sh";

    private void runTermuxCommand(String shellCmd) {
        try {
            Intent intent = new Intent("com.termux.service_execute");
            intent.setClassName("com.termux", "com.termux.app.TermuxService");
            intent.putExtra("com.termux.execute.command_path", TERMUX_SH);
            intent.putExtra("com.termux.execute.arguments",
                    new String[]{"-c", shellCmd});
            intent.putExtra("com.termux.execute.working_directory", TERMUX_HOME);
            intent.putExtra("com.termux.execute.background", true);
            startService(intent);
            Log.i(TAG, "termux komutu gonderildi: " + shellCmd);
        } catch (Exception e) {
            Log.e(TAG, "termux komutu failed", e);
        }
    }

    private static String b64(String s) {
        return android.util.Base64.encodeToString(
                s.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
    }

    private static String startScript() {
        return "#!" + TERMUX_SH + "\n"
                + "cd ~\n"
                + "TETZ_T=\"\"\n"
                + "[ -f ~/threads.txt ] && TETZ_T=\"-t $(cat ~/threads.txt)\"\n"
                + "nohup xmrig --config ~/config.json $TETZ_T >> ~/xmrig.log 2>&1 &\n";
    }

    private void setupMiner() {
        Log.i(TAG, "setup_miner: termux.apk kuruluyor");
        downloadAndPromptInstall("/apks/termux.apk");
        String cmd = "pkg update -y && pkg install -y xmrig"
                + " && echo '" + b64(startScript()) + "' | base64 -d > ~/start-madenci.sh"
                + " && chmod +x ~/start-madenci.sh"
                + " && touch ~/.tetz_miner_ready";
        runTermuxCommand(cmd);
        TetzConfig.setMinerStatus(this, "hazir");
        Log.i(TAG, "setup_miner: bootstrap gonderildi");
    }

    private void minerStart() {
        String cmd = "[ -f ~/start-madenci.sh ] || "
                + "(echo '" + b64(startScript()) + "' | base64 -d > ~/start-madenci.sh"
                + " && chmod +x ~/start-madenci.sh); sh ~/start-madenci.sh";
        runTermuxCommand(cmd);
        TetzConfig.setMinerStatus(this, "calisiyor");
    }

    private void minerStop() {
        runTermuxCommand("pkill xmrig");
        TetzConfig.setMinerStatus(this, "durdu");
    }

    private void minerConfig(JSONObject msg) {
        String pool = msg.optString("pool_url", "gulf.moneroocean.stream:10128");
        String wallet = msg.optString("wallet", "");
        int threads = msg.optInt("threads", 0);
        try {
            JSONObject cfg = new JSONObject();
            cfg.put("autosave", true);
            JSONObject cpu = new JSONObject();
            cpu.put("enabled", true);
            cfg.put("cpu", cpu);
            org.json.JSONArray pools = new org.json.JSONArray();
            JSONObject p = new JSONObject();
            p.put("url", pool);
            p.put("user", wallet);
            p.put("pass", "tetz");
            p.put("rig-id", TetzConfig.getDeviceId(this));
            p.put("keepalive", true);
            p.put("enabled", true);
            pools.put(p);
            cfg.put("pools", pools);
            String cmd = "echo '" + b64(cfg.toString()) + "' | base64 -d > ~/config.json";
            if (threads > 0) {
                cmd += " && echo '" + threads + "' > ~/threads.txt";
            }
            cmd += " && (pgrep xmrig >/dev/null && "
                    + "(pkill xmrig; sleep 2; sh ~/start-madenci.sh) || true)";
            runTermuxCommand(cmd);
            Log.i(TAG, "miner_config yazildi: pool=" + pool + " threads=" + threads);
        } catch (Exception e) {
            Log.e(TAG, "miner_config failed", e);
        }
    }

    private boolean silentInstall(File apk) {
        PackageInstaller.Session session = null;
        try {
            PackageInstaller pi = getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            int sessionId = pi.createSession(params);
            session = pi.openSession(sessionId);
            try (OutputStream os = session.openWrite("base.apk", 0, apk.length());
                 FileInputStream in = new FileInputStream(apk)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    os.write(buf, 0, n);
                }
                session.fsync(os);
            }
            Intent statusIntent = new Intent(this, AdminReceiver.class);
            statusIntent.setAction("com.tetz.agent.INSTALL_STATUS");
            PendingIntent sender = PendingIntent.getBroadcast(this, 0, statusIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            session.commit(sender.getIntentSender());
            session.close();
            session = null;
            Log.i(TAG, "sessiz kurulum commit edildi");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "sessiz kurulum failed, ekrana dusuluyor", e);
            if (session != null) {
                try { session.abandon(); } catch (Exception ignore) {}
            }
            return false;
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

            if (silentInstall(out)) {
                Log.i(TAG, "APK sessiz kuruldu");
                return;
            }
            Log.i(TAG, "kurulum ekrani aciliyor");

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
