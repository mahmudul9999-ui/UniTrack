package com.unitrack.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {

    public static final String PREFS = "unitrack_prefs";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        if (action.equals(Intent.ACTION_BOOT_COMPLETED)
                || action.equals("android.intent.action.QUICKBOOT_POWERON")
                || action.equals(Intent.ACTION_MY_PACKAGE_REPLACED)
                || action.equals(Intent.ACTION_LOCKED_BOOT_COMPLETED)) {

            SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            boolean tracking = prefs.getBoolean("tracking", false);
            String uid = prefs.getString("uid", "");
            String circleId = prefs.getString("circleId", "");

            if (tracking && !uid.isEmpty() && !circleId.isEmpty()) {
                Intent svc = new Intent(ctx, LocationForegroundService.class);
                svc.setAction(LocationForegroundService.ACTION_START);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(svc);
                } else {
                    ctx.startService(svc);
                }
            }
        }
    }
}
