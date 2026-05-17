package com.unitrack.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

/**
 * Bridge between JavaScript (web app) and Android native code.
 * Methods exposed here are callable from the web page as `Android.methodName()`.
 */
public class AndroidBridge {

    private final Context ctx;

    public AndroidBridge(Context c) {
        this.ctx = c;
    }

    /** Returns true so the web app knows it's running inside the Android wrapper */
    @JavascriptInterface
    public boolean isApp() {
        return true;
    }

    /** Show a native Toast */
    @JavascriptInterface
    public void showToast(String msg) {
        if (ctx instanceof Activity) {
            ((Activity) ctx).runOnUiThread(() ->
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show());
        }
    }

    /**
     * Called by web app when user logs in.
     * Starts the foreground location service that keeps tracking
     * even when the app is in the background.
     */
    @JavascriptInterface
    public void startTracking(String uid, String name, String circleId, String firebaseToken) {
        if (uid == null || uid.isEmpty() || circleId == null || circleId.isEmpty()) {
            return;
        }

        SharedPreferences prefs = ctx.getSharedPreferences(BootReceiver.PREFS, Context.MODE_PRIVATE);
        prefs.edit()
            .putString("uid", uid)
            .putString("name", name != null ? name : "User")
            .putString("circleId", circleId)
            .putString("firebaseToken", firebaseToken != null ? firebaseToken : "")
            .putBoolean("tracking", true)
            .apply();

        Intent svc = new Intent(ctx, LocationForegroundService.class);
        svc.setAction(LocationForegroundService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(svc);
        } else {
            ctx.startService(svc);
        }
    }

    /** Called by web app when user logs out — stops the service */
    @JavascriptInterface
    public void stopTracking() {
        SharedPreferences prefs = ctx.getSharedPreferences(BootReceiver.PREFS, Context.MODE_PRIVATE);
        prefs.edit().putBoolean("tracking", false).apply();
        Intent svc = new Intent(ctx, LocationForegroundService.class);
        svc.setAction(LocationForegroundService.ACTION_STOP);
        ctx.startService(svc);
    }

    /** Called by web app when active circle changes — service writes to new path */
    @JavascriptInterface
    public void setActiveCircle(String circleId) {
        if (circleId == null) return;
        SharedPreferences prefs = ctx.getSharedPreferences(BootReceiver.PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString("circleId", circleId).apply();
    }

    /** Web app refreshes Firebase token periodically */
    @JavascriptInterface
    public void updateToken(String firebaseToken) {
        if (firebaseToken == null) return;
        SharedPreferences prefs = ctx.getSharedPreferences(BootReceiver.PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString("firebaseToken", firebaseToken).apply();
    }

    /** Returns whether background location permission has been granted */
    @JavascriptInterface
    public boolean hasBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true;
        return ctx.checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
               == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Called by web app when user taps "Continue with Google".
     * Triggers native Google Sign-In which bypasses Google's WebView OAuth block.
     * On success, MainActivity calls back into JavaScript via window.onGoogleSignInSuccess(idToken)
     * On error, MainActivity calls window.onGoogleSignInError(message)
     */
    @JavascriptInterface
    public void signInWithGoogle() {
        if (ctx instanceof MainActivity) {
            ((Activity) ctx).runOnUiThread(() -> {
                ((MainActivity) ctx).startGoogleSignIn();
            });
        }
    }
}
