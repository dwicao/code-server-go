package dev.codeservergo;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Checks for a newer release on GitHub and, if the user agrees, downloads the APK and hands it to
 * the system package installer.
 *
 * <p>Source of truth: each release (see .github/workflows/release.yml) uploads a {@code version.json}
 * such as {@code releases/latest/download/version.json}. The app polls it, compares versions and
 * installs the attached APK. Updates are signed with the same AOSP test key as the releases, which
 * is what lets Android replace the app in place.
 */
final class UpdateChecker {

    static final String MANIFEST_URL =
            "https://github.com/dwicao/code-server-go/releases/latest/download/version.json";

    static final String APK_MIME = "application/vnd.android.package-archive";

    interface Callback {
        void onUpdateAvailable(ReleaseInfo info);

        void onUpToDate(String currentVersionName, long currentVersionCode);

        void onError(String message);
    }

    /** Parsed fields of version.json. */
    static final class ReleaseInfo {
        int versionCode;
        String versionName;
        String apkUrl;
        String sha256;
        String notes;
    }

    private UpdateChecker() {
    }

    /** Fires a background check. Results arrive on the main thread. */
    static void check(Context context, boolean forced, Callback callback) {
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                ReleaseInfo info;
                try {
                    info = fetchRelease();
                } catch (Throwable t) {
                    ui.post(() -> callback.onError(t.getClass().getSimpleName() + ": "
                            + t.getMessage()));
                    return;
                }
                Prefs prefs = new Prefs(context);
                prefs.setLastUpdateCheckAgeMs(System.currentTimeMillis());
                if (info == null) {
                    ui.post(() -> callback.onError("release manifest could not be parsed"));
                    return;
                }
                if (!isUpdateAvailable(context, info)) {
                    final long code = localVersionCode(context);
                    final String name = Prefs.versionName(context);
                    ui.post(() -> callback.onUpToDate(name, code));
                    return;
                }
                ui.post(() -> callback.onUpdateAvailable(info));
            }
        }, "update-check").start();
    }

    static boolean isUpdateAvailable(Context context, ReleaseInfo remote) {
        return Versions.isUpdateAvailable(remote.versionCode, remote.versionName,
                (int) localVersionCode(context), Prefs.versionName(context));
    }

    static ReleaseInfo fetchRelease() throws Exception {
        HttpURLConnection conn = open(MANIFEST_URL, 20_000);
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code);
            }
            String body = readAll(conn.getInputStream());
            JSONObject json = new JSONObject(body);
            ReleaseInfo info = new ReleaseInfo();
            info.versionCode = json.optInt("versionCode", 0);
            info.versionName = json.optString("versionName", "");
            info.apkUrl = json.optString("apkUrl", null);
            if (info.apkUrl == null) {
                info.apkUrl = json.optString("downloadUrl",
                        "https://github.com/dwicao/code-server-go/releases/latest/download/CodeServerGo-"
                                + info.versionName + ".apk");
            }
            info.sha256 = json.optString("sha256", null);
            info.notes = json.optString("notes", "");
            // sanity check
            if (info.versionName.isEmpty() || info.apkUrl.indexOf("http") != 0) return null;
            return info;
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Downloads the APK with the system download manager (progress notification + content URI).
     * Results arrive asynchronously on the download worker thread; callers must post to the UI.
     */
    static void downloadApk(Context context, ReleaseInfo info, Callback2 callback) {
        android.app.DownloadManager dm =
                (android.app.DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) {
            callback.onFailed("Download service unavailable");
            return;
        }
        String fileName = "CodeServerGo-" + info.versionName + ".apk";

        // clear any partial previous update
        android.app.DownloadManager.Request req =
                new android.app.DownloadManager.Request(Uri.parse(info.apkUrl));
        req.setTitle("CodeServer Go " + info.versionName);
        req.setDescription("Downloading update");
        req.setMimeType(APK_MIME);
        req.setDestinationInExternalFilesDir(context, null, fileName);
        req.setNotificationVisibility(
                android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

        long id;
        try {
            id = dm.enqueue(req);
        } catch (Exception e) {
            callback.onFailed(e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }
        final long downloadId = id;
        final File destDir = context.getExternalFilesDir(null);
        if (destDir != null) {
            // best effort cleanup of stale partial downloads of the same name
            new File(destDir, fileName).delete();
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                android.app.DownloadManager.Query q =
                        new android.app.DownloadManager.Query().setFilterById(downloadId);
                // at most 5 minutes
                for (int i = 0; i < 600; i++) {
                    android.database.Cursor c = dm.query(q);
                    int status = -1;
                    if (c != null) {
                        try {
                            if (c.moveToFirst()) {
                                status = c.getInt(c.getColumnIndexOrThrow(
                                        android.app.DownloadManager.COLUMN_STATUS));
                            }
                        } finally {
                            c.close();
                        }
                    }
                    if (status == android.app.DownloadManager.STATUS_SUCCESSFUL) {
                        verifyAndReport(context, dm, downloadId, info, callback);
                        return;
                    }
                    if (status == android.app.DownloadManager.STATUS_FAILED) {
                        callback.onFailed("download failed (status " + status + ")");
                        return;
                    }
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        callback.onFailed("interrupted");
                        return;
                    }
                }
                dm.remove(downloadId);
                callback.onFailed("download timed out");
            }
        }, "update-watch").start();
    }

    private static void verifyAndReport(Context context, android.app.DownloadManager dm,
                                        long id, ReleaseInfo info, Callback2 callback) {
        try {
            Uri local = dm.getUriForDownloadedFile(id);
            if (info.sha256 != null && !info.sha256.isEmpty() && local != null) {
                File file = new File(local.getPath()); // file:// or content:// with path
                if (!file.exists()) {
                    // content:// URIs (documents) are not File-backed here — skip sha check
                } else {
                    String actual = sha256File(file);
                    if (!actual.equalsIgnoreCase(info.sha256)) {
                        dm.remove(id);
                        callback.onFailed("sha256 mismatch — download discarded");
                        return;
                    }
                }
            }
            callback.onDownloaded(local);
        } catch (Throwable t) {
            callback.onFailed(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    interface Callback2 {
        void onDownloaded(Uri contentUri);

        void onFailed(String message);
    }

    static long localVersionCode(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0)
                    .getLongVersionCode();
        } catch (Throwable t) {
            return 0;
        }
    }

    static String sha256File(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        InputStream in = new BufferedInputStream(new java.io.FileInputStream(file), 64 * 1024);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        } finally {
            in.close();
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static HttpURLConnection open(String urlString, int timeoutMs) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "CodeServerGo");
        return conn;
    }

    private static String readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }
}
