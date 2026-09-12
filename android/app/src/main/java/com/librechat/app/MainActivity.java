package com.librechat.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import com.librechat.app.server.LocalServer;
import android.util.Log;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "LibreChatPrefs";
    private static final String PREF_SERVER_URL = "server_url";
    private static final String PREF_USE_INTERNAL_SERVER = "use_internal_server";
    private static final int PERMISSION_REQ_CODE = 1001;
    private static final int FILE_CHOOSER_REQ_CODE = 1002;

    private WebView webView;
    private ProgressBar progressBar;
    private LinearLayout errorView;
    private LinearLayout splashView;
    private EditText etServerUrl;
    private Button btnAutoDetect;
    private Button btnConnect;
    private Button btnInternalServer;
    private TextView tvAutoRetry;

    private LocalServer localServer;
    private SharedPreferences prefs;
    private UpdateManager updateManager;
    private ValueCallback<Uri[]> filePathCallback;
    private CountDownTimer retryTimer;
    private final ExecutorService networkExecutor = Executors.newFixedThreadPool(8);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .readTimeout(1500, TimeUnit.MILLISECONDS)
            .build();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        updateManager = new UpdateManager(this);

        initViews();
        setupWebView();
        setupBackPressedHandler();
        checkPermissions();

        // Default to running internal server on device out-of-the-box
        boolean useInternal = prefs.getBoolean(PREF_USE_INTERNAL_SERVER, true);
        if (useInternal) {
            startInternalServer();
        } else {
            String savedUrl = prefs.getString(PREF_SERVER_URL, null);
            if (savedUrl != null && !savedUrl.isEmpty()) {
                if (splashView != null) splashView.setVisibility(View.GONE);
                loadUrl(savedUrl);
            } else {
                startInternalServer();
            }
        }

        updateManager.checkForUpdates(false);
    }

    private void startInternalServer() {
        cancelRetryTimer();
        try {
            if (localServer == null) {
                localServer = new LocalServer(this, 8080);
            }
            if (!localServer.isAlive()) {
                localServer.start();
            }
            prefs.edit().putBoolean(PREF_USE_INTERNAL_SERVER, true).apply();
            String internalUrl = "http://127.0.0.1:8080";
            errorView.setVisibility(View.GONE);
            webView.setVisibility(View.VISIBLE);
            loadUrl(internalUrl);
        } catch (Exception e) {
            Log.e("MainActivity", "Failed to start internal server", e);
            if (splashView != null) splashView.setVisibility(View.GONE);
            showConnectionView("Could not start on-device server: " + e.getMessage());
        }
    }

    private void initViews() {
        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);
        errorView = findViewById(R.id.errorView);
        splashView = findViewById(R.id.splashView);
        etServerUrl = findViewById(R.id.etServerUrl);
        btnAutoDetect = findViewById(R.id.btnAutoDetect);
        btnConnect = findViewById(R.id.btnConnect);
        btnInternalServer = findViewById(R.id.btnInternalServer);
        tvAutoRetry = findViewById(R.id.tvAutoRetry);

        btnConnect.setOnClickListener(v -> {
            String url = etServerUrl.getText().toString().trim();
            if (!url.isEmpty()) {
                setServerUrl(url);
            } else {
                Toast.makeText(this, "Please enter a server address", Toast.LENGTH_SHORT).show();
            }
        });

        btnAutoDetect.setOnClickListener(v -> autoDetectLocalServer());
        btnInternalServer.setOnClickListener(v -> startInternalServer());
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        // Mobile Friendly Viewport and DPI
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(false); // Do NOT shrink or zoom out to desktop overview
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(100);
        settings.setDefaultTextEncodingName("utf-8");
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                // Block direct navigation to /api/ URLs in the WebView
                if (url.contains("/api/")) {
                    return true;
                }
                // Allow internal local server navigation
                String currentServer = prefs.getString(PREF_SERVER_URL, "http://127.0.0.1:8080");
                if (url.startsWith("http://127.0.0.1:8080") || (currentServer != null && url.startsWith(currentServer))) {
                    return false;
                }
                // Open external URLs in system browser
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, request.getUrl());
                    startActivity(intent);
                } catch (Exception ignored) {}
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                cancelRetryTimer();
                if (splashView != null) {
                    splashView.setVisibility(View.GONE);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    progressBar.setVisibility(View.GONE);
                    boolean internal = prefs.getBoolean(PREF_USE_INTERNAL_SERVER, true);
                    if (internal) {
                        mainHandler.postDelayed(() -> {
                            if (localServer != null && localServer.isAlive()) {
                                loadUrl("http://127.0.0.1:8080");
                            } else {
                                startInternalServer();
                            }
                        }, 500);
                    } else {
                        if (splashView != null) splashView.setVisibility(View.GONE);
                        showConnectionView("Could not connect to PC server (" + error.getDescription() + ")");
                        startAutoRetry();
                    }
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                if (newProgress == 100) {
                    progressBar.setVisibility(View.GONE);
                }
            }

            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage consoleMessage) {
                Log.d("LibreChatWeb", consoleMessage.message() + " [" + consoleMessage.sourceId() + ":" + consoleMessage.lineNumber() + "]");
                return true;
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    String[] resources = request.getResources();
                    request.grant(resources);
                });
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;

                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQ_CODE);
                } catch (Exception e) {
                    MainActivity.this.filePathCallback = null;
                    Toast.makeText(MainActivity.this, "Cannot open file picker: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }
        });
    }

    private void showConnectionView(String reason) {
        cancelRetryTimer();
        if (splashView != null) splashView.setVisibility(View.GONE);
        webView.setVisibility(View.GONE);
        errorView.setVisibility(View.VISIBLE);

        String current = prefs.getString(PREF_SERVER_URL, "http://192.168.1.100:3080");
        etServerUrl.setText(current);
        TextView tvMsg = findViewById(R.id.errorMessage);
        if (tvMsg != null && reason != null) {
            tvMsg.setText(reason);
        }
    }

    private void startAutoRetry() {
        cancelRetryTimer();
        retryTimer = new CountDownTimer(8000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                tvAutoRetry.setText("Retrying connection in " + (millisUntilFinished / 1000) + "s...");
            }

            @Override
            public void onFinish() {
                tvAutoRetry.setText("Retrying now...");
                String url = prefs.getString(PREF_SERVER_URL, null);
                if (url != null && !url.isEmpty()) {
                    loadUrl(url);
                }
            }
        }.start();
    }

    private void cancelRetryTimer() {
        if (retryTimer != null) {
            retryTimer.cancel();
            retryTimer = null;
        }
        if (tvAutoRetry != null) {
            tvAutoRetry.setText("");
        }
    }

    private void autoDetectLocalServer() {
        btnAutoDetect.setEnabled(false);
        btnAutoDetect.setText("Scanning...");

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        String ip = null;
        if (wm != null) {
            ip = Formatter.formatIpAddress(wm.getConnectionInfo().getIpAddress());
        }

        if (ip == null || ip.equals("0.0.0.0")) {
            Toast.makeText(this, "Please connect to Wi-Fi to scan local network", Toast.LENGTH_SHORT).show();
            btnAutoDetect.setEnabled(true);
            btnAutoDetect.setText("Auto-Detect");
            return;
        }

        String subnet = ip.substring(0, ip.lastIndexOf('.') + 1);
        final boolean[] found = {false};

        // Probe common and nearby IP addresses
        int myLastOctet = Integer.parseInt(ip.substring(ip.lastIndexOf('.') + 1));
        int[] probeOffsets = {0, 1, 2, -1, -2, 3, -3, 10, 20, 50, 100, 101, 102};

        for (int offset : probeOffsets) {
            int target = myLastOctet + offset;
            if (target < 1 || target > 254) continue;
            final String testUrl = "http://" + subnet + target + ":3080";

            networkExecutor.execute(() -> {
                if (found[0]) return;
                try {
                    Request req = new Request.Builder().url(testUrl).build();
                    Response res = httpClient.newCall(req).execute();
                    if (res.isSuccessful()) {
                        found[0] = true;
                        mainHandler.post(() -> {
                            etServerUrl.setText(testUrl);
                            Toast.makeText(MainActivity.this, "Found LibreChat: " + testUrl, Toast.LENGTH_LONG).show();
                            setServerUrl(testUrl);
                            btnAutoDetect.setEnabled(true);
                            btnAutoDetect.setText("Auto-Detect");
                        });
                    }
                    res.close();
                } catch (IOException ignored) {}
            });
        }

        mainHandler.postDelayed(() -> {
            if (!found[0]) {
                btnAutoDetect.setEnabled(true);
                btnAutoDetect.setText("Auto-Detect");
                Toast.makeText(MainActivity.this, "Could not auto-detect server. Please enter the PC IP manually (check LibreChat Desktop -> Server -> Mobile Access)", Toast.LENGTH_LONG).show();
            }
        }, 3500);
    }

    private void setServerUrl(String url) {
        String trimmed = url.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "http://" + trimmed;
        }
        prefs.edit()
                .putString(PREF_SERVER_URL, trimmed)
                .putBoolean(PREF_USE_INTERNAL_SERVER, false)
                .apply();
        loadUrl(trimmed);
    }

    private void loadUrl(String url) {
        cancelRetryTimer();
        errorView.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        webView.loadUrl(url);
    }

    private void setupBackPressedHandler() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    setEnabled(false);
                    onBackPressed();
                }
            }
        });
    }

    private void checkPermissions() {
        String[] perms;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms = new String[]{
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.CAMERA,
                    Manifest.permission.READ_MEDIA_IMAGES
            };
        } else {
            perms = new String[]{
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.CAMERA,
                    Manifest.permission.READ_EXTERNAL_STORAGE
            };
        }

        boolean need = false;
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                need = true;
                break;
            }
        }
        if (need) {
            ActivityCompat.requestPermissions(this, perms, PERMISSION_REQ_CODE);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_reload) {
            webView.reload();
            return true;
        } else if (id == R.id.action_use_internal_server) {
            startInternalServer();
            return true;
        } else if (id == R.id.action_change_server) {
            prefs.edit().putBoolean(PREF_USE_INTERNAL_SERVER, false).apply();
            showConnectionView(null);
            return true;
        } else if (id == R.id.action_check_updates) {
            updateManager.checkForUpdates(true);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQ_CODE) {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                } else if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    @Override
    protected void onDestroy() {
        if (localServer != null) {
            try {
                localServer.stop();
            } catch (Exception ignored) {}
        }
        cancelRetryTimer();
        networkExecutor.shutdown();
        super.onDestroy();
    }
}
