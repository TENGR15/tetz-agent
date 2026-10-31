package com.tetz.agent;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

public class TetzConfig {
    private static final String PREFS = "tetz_config";
    private static final String KEY_MASTER_URL = "master_url";
    private static final String KEY_CHECKSUM = "apk_checksum";
    private static final String KEY_REGISTERED = "registered";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_WORKER_NAME = "worker_name";

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getMasterUrl(Context ctx) {
        return prefs(ctx).getString(KEY_MASTER_URL, "http://100.95.70.43:8000");
    }

    public static void setMasterUrl(Context ctx, String url) {
        prefs(ctx).edit().putString(KEY_MASTER_URL, url).apply();
    }

    public static String getApkChecksum(Context ctx) {
        return prefs(ctx).getString(KEY_CHECKSUM, "");
    }

    public static void setApkChecksum(Context ctx, String checksum) {
        prefs(ctx).edit().putString(KEY_CHECKSUM, checksum).apply();
    }

    public static boolean isRegistered(Context ctx) {
        return prefs(ctx).getBoolean(KEY_REGISTERED, false);
    }

    public static void setRegistered(Context ctx, boolean registered) {
        prefs(ctx).edit().putBoolean(KEY_REGISTERED, registered).apply();
    }

    public static String getWorkerName(Context ctx) {
        return prefs(ctx).getString(KEY_WORKER_NAME, "");
    }

    public static void setWorkerName(Context ctx, String name) {
        prefs(ctx).edit().putString(KEY_WORKER_NAME, name).apply();
    }

    public static String getDeviceId(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String id = p.getString(KEY_DEVICE_ID, null);
        if (id == null) {
            id = "TETZ-" + UUID.randomUUID().toString().replace("-", "")
                    .substring(0, 8).toUpperCase();
            p.edit().putString(KEY_DEVICE_ID, id).apply();
        }
        return id;
    }
}
