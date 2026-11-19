package dev.codeservergo;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Updates the code-server bundle embedded inside the app's runtime.
 *
 * <p>The app was built around a known bundle version; this class checks GitHub for a newer one,
 * downloads it, and atomically swaps it into place — without asking the user to reinstall the APK.
 * The old bundle is kept until the swap succeeds, and is deleted afterwards.
 */
final class LocalEditorUpdate {

    static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/coder/code-server/releases/latest";

    /** Matches the bundle that ships in {@link Runtime#CODE_SERVER_URL}. */
    static final String BUNDLED_VERSION = "4.96.4";

    /** Mirror of where {@code LocalServer} looks for the editor. */
    static String bundledVersionTag() {
        return "v" + BUNDLED_VERSION;
    }

    static final class Release {
        String tag;       // v4.145.2
        String version;   // 4.145.2
        String assetUrl;  // …/code-server-4.145.2-linux-arm64.tar.gz
        long assetBytes;

        String humanReadableName() {
            return version;
        }
    }

    static String markerPath() {
        return "opt/cs/build-version";
    }

    /** Currently installed version inside the runtime, or {@link #BUNDLED_VERSION}. */
    static String installedVersion(Context app) {
        try {
            File marker = markerFile(app);
            if (marker != null && marker.exists()) {
                byte[] raw = new byte[256];
                java.io.InputStream in = new java.io.FileInputStream(marker);
                int n = in.read(raw);
                in.close();
                if (n > 0) {
                    String s = new String(raw, 0, n, StandardCharsets.UTF_8).trim();
                    if (!s.isEmpty()) return s;
                }
            }
        } catch (Exception ignored) {
        }
        return BUNDLED_VERSION;
    }

    private static File markerFile(Context app) {
        File rootfs = Runtime.rootfs(app);
        if (!rootfs.exists()) return null;
        return new File(rootfs, markerPath());
    }

    /** Fetch the latest release info, or null on failure. Network thread only. */
    static Release fetchLatest() throws Exception {
        HttpURLConnection conn = open(LATEST_RELEASE_API, 15_000);
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
            String body = readAll(conn.getInputStream());
            JSONObject json = new JSONObject(body);
            Release r = new Release();
            r.tag = json.optString("tag_name", "");
            if (r.tag.startsWith("v")) r.tag = r.tag.substring(1);
            r.version = r.tag;
            if (r.version.isEmpty()) return null;
            String expected = "code-server-" + r.version + "-linux-arm64.tar.gz";
            JSONArray assets = json.optJSONArray("assets");
            if (assets == null) return null;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a == null) continue;
                String name = a.optString("name", "");
                if (expected.equalsIgnoreCase(name)) {
                    r.assetUrl = a.optString("browser_download_url", null);
                    r.assetBytes = a.optLong("size", 0);
                    break;
                }
            }
            return r.assetUrl != null ? r : null;
        } finally {
            conn.disconnect();
        }
    }

    interface Callback {
        void onProgress(String stage, float fraction);
        void onDone(boolean ok, String message);
    }

    /** Download + swap a new bundle into the running tree. Runs on a background thread. */
    static void update(Context appContext, Callback cb) {
        final Context app = appContext.getApplicationContext();
        try {
            if (!Runtime.installed(app)) throw new IllegalStateException("local runtime not installed");

            final Release latest = fetchLatest();
            if (latest == null) throw new IllegalStateException("latest release lookup failed");

            File base = Runtime.base(app);
            File tmp = new File(base, "cs-update.tar.gz");
            File staging = new File(Runtime.rootfs(app), "opt/cs_staging");
            RuntimeInstaller.deleteRecursive(staging);
            staging.mkdirs();

            // download the new bundle
            long total = latest.assetBytes;
            String stage = "Downloading code-server " + latest.version
                    + " (" + (total > 0 ? (total / (1024 * 1024)) + " MB" : "…") + ")";
            download(latest.assetUrl, tmp, stage, cb);

            // unpack into staging – move existing aside, swap, then delete old
            cb.onProgress("Unpacking " + latest.version + "…", 0f);
            InputStream in = new BufferedInputStream(new java.io.FileInputStream(tmp), 128 * 1024);
            TarGz.extract(in, staging, new TarGz.Progress() {
                @Override
                public void onBytes(long done) {
                    cb.onProgress("Unpacking " + latest.version + "…", 1f);
                }
            });
            in.close();
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();

            File extracted = firstDirectory(staging);
            if (extracted == null || !new File(extracted, "out/node/entry.js").exists()) {
                throw new IllegalStateException("downloaded bundle looks incomplete");
            }

            // stop the editor if it is running (we are replacing it)
            boolean wasRunning = LocalServer.running();
            if (wasRunning) LocalServer.stop(app);

            // swap: opt/cs → opt/cs.old (kept until swap succeeds)
            File cs = new File(Runtime.rootfs(app), "opt/cs");
            File csOld = new File(Runtime.rootfs(app), "opt/cs.old");
            RuntimeInstaller.deleteRecursive(csOld);
            if (cs.exists() && !cs.renameTo(csOld)) {
                throw new IllegalStateException("could not move the current bundle aside");
            }
            if (!extracted.renameTo(cs)) {
                // rollback
                cs.delete();
                RuntimeInstaller.deleteRecursive(cs);
                if (csOld.exists()) csOld.renameTo(cs);
                throw new IllegalStateException("could not move the new bundle into place");
            }
            if (csOld.exists()) RuntimeInstaller.deleteRecursive(csOld);
            RuntimeInstaller.deleteRecursive(staging);

            // marker for the version we now have
            File marker = markerFile(app);
            if (marker != null) {
                try {
                    if (marker.getParentFile() != null) marker.getParentFile().mkdirs();
                    FileOutputStream fos = new FileOutputStream(marker);
                    fos.write(latest.version.getBytes(StandardCharsets.UTF_8));
                    fos.close();
                } catch (Exception ignored) {
                }
            }

            // shared fallback natives, in case the new bundle needs them restored
            RuntimeInstaller.ensureNativeModules(app);
            RuntimeInstaller.ensureExecutable(Runtime.rootfs(app));

            // start it again if it was running
            if (wasRunning) {
                LocalServer.start(app);
                LocalServer.waitHealthy(120000);
            }

            cb.onDone(true, "code-server " + latest.version + " installed");
        } catch (Throwable t) {
            cb.onDone(false, t.getClass().getSimpleName() + ": " + safeMessage(t));
        }
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null ? "" : m;
    }

    /** Download with progress callback. */
    private static void download(String url, File target, String stage, Callback cb)
            throws IOException {
        File part = new File(target.getAbsolutePath() + ".part");
        HttpURLConnection conn = null;
        InputStream in = null;
        FileOutputStream out = null;
        try {
            conn = open(url, 60_000);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
            long length = conn.getContentLengthLong();
            in = new BufferedInputStream(conn.getInputStream(), 128 * 1024);
            out = new FileOutputStream(part);
            byte[] buf = new byte[128 * 1024];
            long done = 0, lastReport = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (done - lastReport > 1024 * 1024) {
                    lastReport = done;
                    cb.onProgress(stage, length > 0 ? (float) done / length : 0f);
                }
            }
            out.flush();
            out.close();
            out = null;
            if (length > 0 && part.length() != length) {
                throw new IOException("download truncated (" + part.length() + "/" + length + ")");
            }
            if (part.length() < 8L * 1024 * 1024) throw new IOException("download too small");
            if (!part.renameTo(target)) throw new IOException("cannot finalise download");
        } finally {
            try { if (in != null) in.close(); } catch (IOException ignored) {}
            try { if (out != null) out.close(); } catch (IOException ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    private static HttpURLConnection open(String urlString, int timeoutMs) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "CodeServerGo");
        return conn;
    }

    private static File firstDirectory(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) return null;
        for (File f : kids) if (f.isDirectory()) return f;
        return null;
    }

    private static String readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
