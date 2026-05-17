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

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;

public class MainActivity extends AppCompatActivity {

    // ═══ CONFIG ═══
    private static final String WEBSITE_URL = "https://unitrack.poribortonkf.com";

    // ════════════════════════════════════════════════════════════
    // GOOGLE SIGN-IN — Replace with your Firebase Web Client ID
    //
    // Find it at: Firebase Console → Authentication → Sign-in method
    //   → Google → Web SDK configuration → Web client ID
    //
    // Format: "123456789012-abcdefghij.apps.googleusercontent.com"
    // ════════════════════════════════════════════════════════════
    private static final String WEB_CLIENT_ID = "331086944541-vb52qorhqoocmsv29jocqdkp9jde7vkh.apps.googleusercontent.com";

    private static final int PERM_FINE_LOCATION = 1001;
    private static final int PERM_BG_LOCATION   = 1002;
    private static final int PERM_NOTIFICATION  = 1003;
    private static final int PERM_CAMERA        = 1004;

    private static final int FILE_CHOOSER_REQ   = 2001;
    private static final int GOOGLE_SIGN_IN_REQ = 3001;

    private WebView webView;
    private ProgressBar progressBar;
    private LinearLayout layoutPermission;
    private LinearLayout layoutNoNet;
    private TextView textPermission;

    private ValueCallback<Uri[]> filePathCallback;
    private GoogleSignInClient googleSignInClient;

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
        setupGoogleSignIn();
        startPermissionFlow();
    }

    private void setupGoogleSignIn() {
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(WEB_CLIENT_ID)
            .requestEmail()
            .requestProfile()
            .build();
        googleSignInClient = GoogleSignIn.getClient(this, gso);
    }

    /**
     * Called from AndroidBridge.signInWithGoogle() when JS triggers Google login.
     * Signs out first so user can pick a different account each time.
     */
    public void startGoogleSignIn() {
        googleSignInClient.signOut().addOnCompleteListener(this, task -> {
            Intent signInIntent = googleSignInClient.getSignInIntent();
            startActivityForResult(signInIntent, GOOGLE_SIGN_IN_REQ);
        });
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
        ws.setUserAgentString(ws.getUserAgentString() + " UnitrackerApp/1.1");

        webView.addJavascriptInterface(new AndroidBridge(this), "Android");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String url = req.getUrl().toString();
                if (url.contains("poribortonkf.com") || url.contains("firebase")
                        || url.contains("googleapis") || url.contains("openstreetmap")
                        || url.contains("gstatic.com") || url.contains("unpkg.com")
                        || url.contains("googleusercontent")) {
                    return false;
                }
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
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, true);
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }

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

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                    .setMessage(message)
                    .setPositiveButton("OK", (d, w) -> result.confirm())
                    .setOnCancelListener(d -> result.confirm())
                    .show();
                return true;
            }

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

        // File chooser result
        if (req == FILE_CHOOSER_REQ && filePathCallback != null) {
            Uri[] uris = WebChromeClient.FileChooserParams.parseResult(res, data);
            filePathCallback.onReceiveValue(uris);
            filePathCallback = null;
            return;
        }

        // Google Sign-In result
        if (req == GOOGLE_SIGN_IN_REQ) {
            Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
            try {
                GoogleSignInAccount account = task.getResult(ApiException.class);
                String idToken = account != null ? account.getIdToken() : null;
                if (idToken != null && !idToken.isEmpty()) {
                    // Escape the token for safe JS injection
                    String safeToken = idToken.replace("\\", "\\\\").replace("\"", "\\\"");
                    String js = "if(window.onGoogleSignInSuccess){window.onGoogleSignInSuccess(\""
                        + safeToken + "\");}";
                    webView.evaluateJavascript(js, null);
                } else {
                    callJsError("No ID token returned. Make sure Web Client ID is configured correctly.");
                }
            } catch (ApiException e) {
                String errMsg;
                int code = e.getStatusCode();
                // Common error codes
                if (code == 10) {
                    errMsg = "Configuration error (code 10). Check that SHA-1 fingerprint is added to Firebase.";
                } else if (code == 12501) {
                    // User cancelled - silent
                    return;
                } else if (code == 7) {
                    errMsg = "Network error. Check your internet connection.";
                } else {
                    errMsg = "Google sign-in failed (code " + code + ")";
                }
                callJsError(errMsg);
            }
        }
    }

    private void callJsError(String msg) {
        String safeMsg = msg.replace("\\", "\\\\").replace("\"", "\\\"");
        String js = "if(window.onGoogleSignInError){window.onGoogleSignInError(\"" + safeMsg + "\");}";
        webView.evaluateJavascript(js, null);
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
