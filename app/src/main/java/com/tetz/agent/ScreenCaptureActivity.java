package com.tetz.agent;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.util.Log;

public class ScreenCaptureActivity extends Activity {
    private static final String TAG = "TetzScreenReq";
    private static final int REQ = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MediaProjectionManager mpm = (MediaProjectionManager)
                getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ) {
            if (resultCode == RESULT_OK && data != null) {
                ScreenService.setProjection(resultCode, data);
                startService(new Intent(this, ScreenService.class));
                Log.i(TAG, "ekran paylasimi onaylandi");
            } else {
                Log.w(TAG, "ekran paylasimi reddedildi");
            }
        }
        finish();
    }
}
