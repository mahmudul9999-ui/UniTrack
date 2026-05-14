package com.unitrack.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.webkit.GeolocationPermissions;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    // ═══ CONFIG — Change WEBSITE_URL if you change the subdomain ═══
    private static final String WEBSITE_URL = "https://unitrack.poribortonkf.com";

    private static final int PERM_FINE_LOCATION = 1001;
    private static final int PERM_BG_LOCATION   = 1002;
    private static final int PERM_NOTIFICATION  = 1003;
    private static final int PERM_CAMERA        = 1004;

    private WebView webView;
    private ProgressBar progressBar;
    private LinearLayout layoutPermission;
    private LinearLayout layoutNoNet;
    private TextView textPermission;

    // File chooser for profile photo upload
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_REQ = 2001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView           = findViewById(R.id.webview);
        progressBar       = findViewById(R.id.progressBar);
        layoutPermission  = findViewById(R.id.layoutPermission);
        layoutNoNet       = findViewById(R.id.layoutNoNet);
        textPermission    = findViewById(R.id.textPermission);

        setupWebView();
        startPermissionFlow();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setGeolocationEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setUserAgentString(ws.getUserAgentString() + " UniTrackApp/1.0");

        webView.addJavascriptInterface(new AndroidBridge(this), "Android");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String url = req.getUrl().toString();
                // Stay inside UniTrack — open external links in browser
                if (url.contains("poribortonkf.com") || url.contains("firebase")
                        || url.contains("googleapis") || url.contains("openstreetmap")
                        || url.contains("gstatic.com") || url.contains("unpkg.com")
                        || url.contains("googleusercontent")) {
                    return false;
                }
                // External links: open in Chrome
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(i);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap fav) {
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, android.webkit.WebResourceError err) {
                if (req.isForMainFrame()) {
                    showNoNet();
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            // Grant geolocation to website (we already have OS permission)
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, true);
            }

            // Grant camera (for profile photo)
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }

            // Native confirm dialog
            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                    .setMessage(message)
                    .setPositiveButton("OK", (d, w) -> result.confirm())
                    .setNegativeButton("Cancel", (d, w) -> result.cancel())
                    .setOnCancelListener(d -> result.cancel())
                    .show();
                return true;
            }

            // Native alert dialog
            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                    .setMessage(message)
                    .setPositiveButton("OK", (d, w) -> result.confirm())
                    .setOnCancelListener(d -> result.confirm())
                    .show();
                return true;
            }

            // File chooser for profile photo
            @Override
            public boolean onShowFileChooser(WebView wv, ValueCallback<Uri[]> fpc, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = fpc;
                try {
                    Intent intent = params.createIntent();
                    startActivityForResult(intent, FILE_CHOOSER_REQ);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == FILE_CHOOSER_REQ && filePathCallback != null) {
            Uri[] uris = WebChromeClient.FileChooserParams.parseResult(res, data);
            filePathCallback.onReceiveValue(uris);
            filePathCallback = null;
        }
    }

    // ════════════════════════════════════════════════════════════
    // PERMISSIONS
    // ════════════════════════════════════════════════════════════
    private void startPermissionFlow() {
        layoutPermission.setVisibility(View.GONE);
        if (!hasFineLocation()) {
            requestFineLocation();
        } else if (!hasBackgroundLocation()) {
            requestBackgroundLocation();
        } else {
            requestBatteryWhitelist();
            loadWebsite();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERM_NOTIFICATION);
            }
        }
    }

    private boolean hasFineLocation() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true;
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestFineLocation() {
        layoutPermission.setVisibility(View.VISIBLE);
        textPermission.setText("UniTrack needs location access to share your position with your circle members. Please tap 'Allow' on the next screen.");
        ActivityCompat.requestPermissions(this,
            new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                         Manifest.permission.ACCESS_COARSE_LOCATION}, PERM_FINE_LOCATION);
    }

    private void requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            requestBatteryWhitelist();
            loadWebsite();
            return;
        }
        layoutPermission.setVisibility(View.VISIBLE);
        textPermission.setText("To keep sharing your location when the app is in the background (even when phone is in your pocket), please select 'Allow all the time' on the next screen.");
        new android.os.Handler().postDelayed(() -> {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, PERM_BG_LOCATION);
        }, 1500);
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;

        if (code == PERM_FINE_LOCATION) {
            if (granted) {
                if (!hasBackgroundLocation()) {
                    requestBackgroundLocation();
                } else {
                    requestBatteryWhitelist();
                    loadWebsite();
                }
            } else {
                Toast.makeText(this, "Location permission denied. UniTrack cannot work without location.",
                    Toast.LENGTH_LONG).show();
                loadWebsite();
            }
        } else if (code == PERM_BG_LOCATION) {
            if (!granted) {
                Toast.makeText(this,
                    "For best tracking: Settings > Apps > UniTrack > Location > 'Allow all the time'",
                    Toast.LENGTH_LONG).show();
            }
            requestBatteryWhitelist();
            loadWebsite();
        }
    }

    // ════════════════════════════════════════════════════════════
    // BATTERY OPTIMIZATION WHITELIST
    // ════════════════════════════════════════════════════════════
    private void requestBatteryWhitelist() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;

        SharedPreferences p = getSharedPreferences(BootReceiver.PREFS, MODE_PRIVATE);
        if (p.getBoolean("battery_asked", false)) return;

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
            p.edit().putBoolean("battery_asked", true).apply();
            return;
        }

        new AlertDialog.Builder(this)
            .setTitle("Keep Tracking Active")
            .setMessage("To ensure UniTrack keeps your location updated even when:\n\n" +
                        "• Your phone is in your pocket\n" +
                        "• The screen is off\n" +
                        "• The app is in the background\n\n" +
                        "Please tap 'Allow' on the next screen to disable battery optimization for UniTrack.")
            .setPositiveButton("Continue", (d, w) -> {
                try {
                    @SuppressLint("BatteryLife")
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    try {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    } catch (Exception ignored) {}
                }
                p.edit().putBoolean("battery_asked", true).apply();
            })
            .setNegativeButton("Later", (d, w) -> p.edit().putBoolean("battery_asked", true).apply())
            .setCancelable(false)
            .show();
    }

    // ════════════════════════════════════════════════════════════
    // WEBSITE LOADING
    // ════════════════════════════════════════════════════════════
    private void loadWebsite() {
        layoutPermission.setVisibility(View.GONE);
        layoutNoNet.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        webView.loadUrl(WEBSITE_URL);
    }

    private void showNoNet() {
        webView.setVisibility(View.GONE);
        layoutNoNet.setVisibility(View.VISIBLE);
    }

    public void retryConnection(View v) {
        if (isOnline()) {
            loadWebsite();
        } else {
            Toast.makeText(this, "Still no internet connection", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isOnline() {
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
            getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
