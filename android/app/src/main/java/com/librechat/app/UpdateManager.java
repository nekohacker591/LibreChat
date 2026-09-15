package com.librechat.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class UpdateManager {

    private static final String DEFAULT_REPO = "nekohacker591/LibreChat";
    private final Activity activity;
    private final OkHttpClient client;
    private final Handler mainHandler;

    public UpdateManager(Activity activity) {
        this.activity = activity;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void checkForUpdates(boolean isManual) {
        SharedPreferences prefs = activity.getSharedPreferences("LibreChatPrefs", Context.MODE_PRIVATE);
        String repo = prefs.getString("update_repo", DEFAULT_REPO);
        String url = "https://api.github.com/repos/" + repo + "/releases/latest";

        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "LibreChat-Android")
                .build();

        if (isManual) {
            Toast.makeText(activity, "Checking for updates...", Toast.LENGTH_SHORT).show();
        }

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, java.io.IOException e) {
                if (isManual) {
                    toastSafe("Update check failed: " + e.getMessage(), Toast.LENGTH_LONG);
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws java.io.IOException {
                if (!response.isSuccessful()) {
                    if (isManual) {
                        toastSafe("No release found or rate-limited.", Toast.LENGTH_SHORT);
                    }
                    return;
                }

                try {
                    String jsonStr = response.body().string();
                    JSONObject release = new JSONObject(jsonStr);
                    String tagName = release.optString("tag_name", "").replace("v", "");
                    String currentVersion = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;

                    if (isNewerVersion(tagName, currentVersion)) {
                        JSONArray assets = release.optJSONArray("assets");
                        String apkDownloadUrl = null;
                        String apkName = "librechat-update.apk";

                        if (assets != null) {
                            for (int i = 0; i < assets.length(); i++) {
                                JSONObject asset = assets.getJSONObject(i);
                                String name = asset.optString("name", "");
                                if (name.endsWith(".apk")) {
                                    apkDownloadUrl = asset.optString("browser_download_url");
                                    apkName = name;
                                    break;
                                }
                            }
                        }

                        final String finalApkUrl = apkDownloadUrl;
                        final String finalApkName = apkName;
                        final String releaseNotes = release.optString("body", "Bug fixes and improvements.");

                        mainHandler.post(() -> {
                            if (!isActivityUsable()) {
                                return;
                            }
                            if (finalApkUrl != null) {
                                showUpdatePrompt(tagName, finalApkUrl, finalApkName, releaseNotes);
                            } else if (isManual) {
                                Toast.makeText(activity, "Release v" + tagName + " found, but no APK is attached.", Toast.LENGTH_LONG).show();
                            }
                        });
                    } else if (isManual) {
                        toastSafe("LibreChat is up to date (v" + currentVersion + ").", Toast.LENGTH_SHORT);
                    }
                } catch (Exception e) {
                    if (isManual) {
                        toastSafe("Error parsing update: " + e.getMessage(), Toast.LENGTH_SHORT);
                    }
                }
            }
        });
    }

    /** The activity may be gone by the time a background reply lands; showing a
     *  dialog or toast on a destroyed context kills the process. */
    private boolean isActivityUsable() {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    private void toastSafe(String message, int duration) {
        mainHandler.post(() -> {
            if (!isActivityUsable()) {
                return;
            }
            try {
                Toast.makeText(activity, message, duration).show();
            } catch (Exception ignored) {
            }
        });
    }

    private boolean isNewerVersion(String latest, String current) {
        if (latest == null || latest.isEmpty() || current == null) return false;
        String[] lParts = latest.split("[.-]");
        String[] cParts = current.split("[.-]");
        int len = Math.max(lParts.length, cParts.length);
        for (int i = 0; i < len; i++) {
            int lNum = i < lParts.length ? parseNumber(lParts[i]) : 0;
            int cNum = i < cParts.length ? parseNumber(cParts[i]) : 0;
            if (lNum > cNum) return true;
            if (lNum < cNum) return false;
        }
        return false;
    }

    private int parseNumber(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    private void showUpdatePrompt(String version, String downloadUrl, String fileName, String notes) {
        if (!isActivityUsable()) {
            return;
        }
        try {
            new AlertDialog.Builder(activity)
                    .setTitle("Update Available: v" + version)
                    .setMessage("A new version of LibreChat is available.\n\nChanges:\n" + notes + "\n\nWould you like to download and install it now?")
                    .setPositiveButton("Download & Install", (dialog, which) -> downloadAndInstallApk(downloadUrl, fileName))
                    .setNegativeButton("Later", null)
                    .show();
        } catch (Exception ignored) {
        }
    }

    private void downloadAndInstallApk(String url, String fileName) {
        if (!isActivityUsable()) {
            return;
        }
        ProgressDialog progress = new ProgressDialog(activity);
        progress.setTitle("Downloading Update");
        progress.setMessage("Please wait while the update downloads...");
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setMax(100);
        progress.setCancelable(false);
        try {
            progress.show();
        } catch (Exception ignored) {
            return;
        }

        Request request = new Request.Builder().url(url).build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, java.io.IOException e) {
                mainHandler.post(() -> {
                    dismissSafe(progress);
                    toastSafe("Download failed: " + e.getMessage(), Toast.LENGTH_LONG);
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws java.io.IOException {
                if (!response.isSuccessful()) {
                    mainHandler.post(() -> {
                        dismissSafe(progress);
                        toastSafe("Download error: HTTP " + response.code(), Toast.LENGTH_SHORT);
                    });
                    return;
                }

                try {
                    File cacheDir = new File(activity.getExternalCacheDir(), "updates");
                    if (!cacheDir.exists()) cacheDir.mkdirs();
                    File apkFile = new File(cacheDir, fileName);

                    InputStream is = response.body().byteStream();
                    OutputStream os = new FileOutputStream(apkFile);
                    long totalBytes = response.body().contentLength();
                    byte[] buffer = new byte[8192];
                    long bytesRead = 0;
                    int read;
                    final int[] lastPct = {0};

                    while ((read = is.read(buffer)) != -1) {
                        os.write(buffer, 0, read);
                        bytesRead += read;
                        if (totalBytes > 0) {
                            int pct = (int) ((bytesRead * 100) / totalBytes);
                            /** Post at most one frame per 2%: the old code queued
                             *  a runnable per 8 KB chunk. */
                            if (pct - lastPct[0] >= 2 || pct == 100) {
                                lastPct[0] = pct;
                                mainHandler.post(() -> {
                                    if (!isActivityUsable()) {
                                        return;
                                    }
                                    try {
                                        progress.setProgress(pct);
                                    } catch (Exception ignored) {
                                    }
                                });
                            }
                        }
                    }

                    os.flush();
                    os.close();
                    is.close();

                    mainHandler.post(() -> {
                        dismissSafe(progress);
                        if (isActivityUsable()) {
                            installApk(apkFile);
                        }
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> {
                        dismissSafe(progress);
                        toastSafe("Failed saving update: " + e.getMessage(), Toast.LENGTH_LONG);
                    });
                }
            }
        });
    }

    private void dismissSafe(ProgressDialog progress) {
        if (!isActivityUsable()) {
            return;
        }
        try {
            progress.dismiss();
        } catch (Exception ignored) {
        }
    }

    private void installApk(File apkFile) {
        if (!isActivityUsable()) {
            return;
        }
        try {
            Uri apkUri;
            Intent intent = new Intent(Intent.ACTION_VIEW);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                apkUri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", apkFile);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                apkUri = Uri.fromFile(apkFile);
            }
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(activity, "Error launching installer: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
