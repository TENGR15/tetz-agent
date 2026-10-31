package com.tetz.agent;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        boolean adbOn = Settings.Global.getInt(getContentResolver(),
                Settings.Global.ADB_ENABLED, 0) == 1;

        String text = "TETZ Agent\n\n"
                + "Isim: " + TetzConfig.getWorkerName(this) + "\n"
                + "Cihaz ID: " + TetzConfig.getDeviceId(this) + "\n"
                + "Model: " + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "Android: " + Build.VERSION.RELEASE + "\n"
                + "Kayit: " + (TetzConfig.isRegistered(this) ? "TAMAM" : "BEKLIYOR") + "\n"
                + "ADB: " + (adbOn ? "ACIK" : "KAPALI") + "\n"
                + "Master: " + TetzConfig.getMasterUrl(this);

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(16);
        tv.setGravity(Gravity.CENTER);
        layout.addView(tv);
        setContentView(layout);

        SetupService.start(this);
    }
}
