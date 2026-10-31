package com.tetz.agent;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

public class TetzAccessibilityService extends AccessibilityService {
    private static final String TAG = "TetzA11y";
    private static TetzAccessibilityService instance;

    @Override
    public void onServiceConnected() {
        instance = this;
        Log.i(TAG, "accessibility baglandi");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    public static boolean tap(float x, float y) {
        TetzAccessibilityService svc = instance;
        if (svc == null) {
            Log.w(TAG, "tap: servis aktif degil");
            return false;
        }
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(path, 0, 100));
        boolean ok = svc.dispatchGesture(b.build(), null, null);
        Log.i(TAG, "tap " + x + "," + y + " -> " + ok);
        return ok;
    }

    public static boolean swipe(float x1, float y1, float x2, float y2) {
        TetzAccessibilityService svc = instance;
        if (svc == null) return false;
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(path, 0, 300));
        boolean ok = svc.dispatchGesture(b.build(), null, null);
        Log.i(TAG, "swipe -> " + ok);
        return ok;
    }
}
