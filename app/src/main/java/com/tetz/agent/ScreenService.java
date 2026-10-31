package com.tetz.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;

public class ScreenService extends Service {
    private static final String TAG = "TetzScreen";
    private static final String CHANNEL_ID = "tetz_screen";
    private static final long FRAME_INTERVAL_MS = 800;
    private static final int JPEG_QUALITY = 50;

    private static int projectionResultCode;
    private static Intent projectionData;

    private volatile boolean running;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private Thread captureThread;

    public static void setProjection(int resultCode, Intent data) {
        projectionResultCode = resultCode;
        projectionData = data;
    }

    public static boolean hasProjection() {
        return projectionData != null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "TETZ Ekran", NotificationManager.IMPORTANCE_LOW));
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        startForeground(2, b.setContentTitle("TETZ Ekran")
                .setContentText("Ekran paylasimi aktif")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true).build());

        if (projectionData == null) {
            Log.w(TAG, "projection izni yok, servis beklemiyor");
            stopSelf();
            return;
        }
        MediaProjectionManager mpm = (MediaProjectionManager)
                getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(projectionResultCode, projectionData);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = dm.widthPixels / 2, h = dm.heightPixels / 2;
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        display = projection.createVirtualDisplay("tetz", w, h,
                dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, null);

        running = true;
        captureThread = new Thread(this::captureLoop);
        captureThread.start();
        Log.i(TAG, "ekran yakalama basladi " + w + "x" + h);
    }

    private void captureLoop() {
        while (running) {
            try {
                Image img = reader.acquireLatestImage();
                if (img != null) {
                    byte[] jpeg = toJpeg(img);
                    img.close();
                    if (jpeg != null) {
                        postFrame(jpeg);
                    }
                }
                Thread.sleep(FRAME_INTERVAL_MS);
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                Log.e(TAG, "kare hatasi", e);
                try { Thread.sleep(2000); } catch (InterruptedException ie) { break; }
            }
        }
    }

    private byte[] toJpeg(Image img) {
        try {
            Image.Plane plane = img.getPlanes()[0];
            ByteBuffer buf = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * img.getWidth();
            Bitmap bmp = Bitmap.createBitmap(
                    img.getWidth() + rowPadding / pixelStride, img.getHeight(),
                    Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(buf);
            Bitmap cropped = Bitmap.createBitmap(bmp, 0, 0,
                    img.getWidth(), img.getHeight());
            bmp.recycle();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos);
            cropped.recycle();
            return baos.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "jpeg hatasi", e);
            return null;
        }
    }

    private void postFrame(byte[] jpeg) {
        try {
            String url = TetzConfig.getMasterUrl(this) + "/screen/"
                    + TetzConfig.getDeviceId(this);
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "image/jpeg");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jpeg);
            }
            conn.getResponseCode();
            conn.disconnect();
        } catch (Exception e) {
            Log.e(TAG, "kare gonderme failed", e);
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        if (captureThread != null) captureThread.interrupt();
        if (display != null) display.release();
        if (reader != null) reader.close();
        if (projection != null) projection.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
