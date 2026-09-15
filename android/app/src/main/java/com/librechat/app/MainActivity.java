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
import android.provider.MediaStore;
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
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Date;
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
    private Uri pendingCameraUri;
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

        installCrashHandler();
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
                    /** Only media capture is needed in-app (voice input); grant
                     *  nothing else to whatever page happens to be loaded. */
                    java.util.List<String> granted = new java.util.ArrayList<>();
                    for (String resource : request.getResources()) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)
                                || PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                            granted.add(resource);
                        }
                    }
                    if (granted.isEmpty()) {
                        request.deny();
                    } else {
                        request.grant(granted.toArray(new String[0]));
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;
                pendingCameraUri = null;

                Intent intent = buildChooserIntent(fileChooserParams);
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQ_CODE);
                } catch (Exception e) {
                    /** Fall back to the WebView's own intent before giving up. */
                    try {
                        startActivityForResult(fileChooserParams.createIntent(), FILE_CHOOSER_REQ_CODE);
                        return true;
                    } catch (Exception ignored) {}
                    MainActivity.this.filePathCallback = null;
                    Toast.makeText(MainActivity.this, "Cannot open file picker: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }
        });
    }

    /**
     * Prefers a real media experience over the document browser: the system
     * photo picker for images on Android 13+, gallery/document providers before
     * that, and the camera with a FileProvider target when the page requests
     * capture. Everything else keeps the WebView's own intent.
     */
    private Intent buildChooserIntent(WebChromeClient.FileChooserParams fileChooserParams) {
        boolean wantsImage = acceptsImages(fileChooserParams);
        if (!wantsImage) {
            return fileChooserParams.createIntent();
        }
        if (fileChooserParams.isCaptureEnabled()) {
            Intent capture = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            try {
                File dir = new File(getCacheDir(), "camera");
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                File photo = new File(dir, "capture-" + System.currentTimeMillis() + ".jpg");
                pendingCameraUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photo);
                capture.putExtra(MediaStore.EXTRA_OUTPUT, pendingCameraUri);
                capture.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                if (capture.resolveActivity(getPackageManager()) != null) {
                    return capture;
                }
            } catch (Exception e) {
                Log.w("MainActivity", "Camera capture unavailable", e);
                pendingCameraUri = null;
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent picker = new Intent(MediaStore.ACTION_PICK_IMAGES);
            picker.setType("image/*");
            picker.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 10);
            if (picker.resolveActivity(getPackageManager()) != null) {
                return picker;
            }
        }
        Intent gallery = new Intent(Intent.ACTION_GET_CONTENT);
        gallery.addCategory(Intent.CATEGORY_OPENABLE);
        gallery.setType("image/*");
        gallery.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        return gallery;
    }

    private static boolean acceptsImages(WebChromeClient.FileChooserParams params) {
        String[] types = params.getAcceptTypes();
        if (types == null || types.length == 0) {
            return false;
        }
        for (String type : types) {
            if (type == null || type.trim().isEmpty()) {
                continue;
            }
            String lower = type.toLowerCase(java.util.Locale.ROOT).trim();
            if (lower.startsWith("image/") || lower.contains("heic") || lower.contains("heif")
                    || lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png")
                    || lower.contains(".webp") || lower.contains(".gif")) {
                return true;
            }
        }
        /** A bare wildcard or an empty accept list is the generic document flow. */
        return false;
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
        } else if (id == R.id.action_share_logs) {
            shareLogs();
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
            if (resultCode == RESULT_OK) {
                if (data != null && data.getData() != null) {
                    results = new Uri[]{data.getData()};
                } else if (data != null && data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (pendingCameraUri != null) {
                    /** ACTION_IMAGE_CAPTURE returns no data URI when EXTRA_OUTPUT
                     *  was supplied; the FileProvider target is the result. */
                    results = new Uri[]{pendingCameraUri};
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
            pendingCameraUri = null;
        }
    }

    @Override
    protected void onDestroy() {
        /** Nothing may outlive the Activity: the delayed retry/status runnables
         *  used to resurrect a stopped server bound to a dead context, which
         *  then wedged the next launch on the port. */
        mainHandler.removeCallbacksAndMessages(null);
        cancelRetryTimer();
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
            webView = null;
        }
        if (localServer != null) {
            try {
                localServer.stop();
            } catch (Exception ignored) {
            }
            localServer = null;
        }
        networkExecutor.shutdown();
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // Diagnostics
    // ------------------------------------------------------------------

    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                writeCrashLog(thread, throwable);
            } catch (Throwable ignored) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }

    private void writeCrashLog(Thread thread, Throwable throwable) {
        File dir = new File(getFilesDir(), "logs");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        StringWriter stack = new StringWriter();
        PrintWriter writer = new PrintWriter(stack);
        writer.println("Time: " + new Date());
        writer.println("Thread: " + thread.getName());
        writer.println("Version: " + appVersion());
        writer.println("Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        throwable.printStackTrace(writer);
        writer.flush();
        File target = new File(dir, "crash-" + System.currentTimeMillis() + ".log");
        try (FileWriter fileWriter = new FileWriter(target)) {
            fileWriter.write(stack.toString());
        } catch (IOException ignored) {
        }
        pruneLogs(dir, 8);
    }

    private void pruneLogs(File dir, int keep) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= keep) {
            return;
        }
        java.util.Arrays.sort(files, java.util.Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < files.length - keep; i++) {
            files[i].delete();
        }
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private void shareLogs() {
        File dir = new File(getFilesDir(), "logs");
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            Toast.makeText(this, "No logs recorded yet", Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.Arrays.sort(files, java.util.Comparator.comparingLong(File::lastModified));
        StringBuilder builder = new StringBuilder();
        builder.append("LibreChat Android logs (").append(files.length).append(" files)\n\n");
        for (File file : files) {
            builder.append("===== ").append(file.getName()).append(" =====\n");
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                byte[] data = new byte[(int) file.length()];
                int read = in.read(data);
                builder.append(new String(data, 0, Math.max(read, 0)));
            } catch (Exception e) {
                builder.append("(unreadable: ").append(e.getMessage()).append(")\n");
            }
            builder.append("\n");
        }
        try {
            File out = new File(getCacheDir(), "librechat-logs.txt");
            try (FileWriter writer = new FileWriter(out)) {
                writer.write(builder.toString());
            }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", out);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Share LibreChat logs"));
        } catch (Exception e) {
            Toast.makeText(this, "Could not share logs: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
