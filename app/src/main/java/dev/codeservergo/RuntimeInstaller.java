package dev.codeservergo;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Downloads and unpacks the local runtime: proot (from the APK) + Ubuntu base rootfs + the
 * code-server arm64 bundle. Everything lands in app-private storage, which proot then treats as
 * {@code /}.
 *
 * <p>This runs on a background thread and reports coarse progress; each step is idempotent, so a
 * half-finished install resumes from whatever is still missing on the next attempt.
 */
final class RuntimeInstaller {

    interface Callback {
        /** stage: human readable label, fraction: 0..1 of that stage. */
        void onProgress(String stage, float fraction);

        void onDone(boolean ok, String message);
    }

    private RuntimeInstaller() {
    }

    static void install(Context context, Callback cb) {
        Context app = context.getApplicationContext();
        try {
            File base = Runtime.base(app);
            File rootfs = Runtime.rootfs(app);
            base.mkdirs();
            rootfs.mkdirs();

            cb.onProgress("Preparing proot\u2026", 0f);
            installProot(app);

            for (String dir : new String[]{"proc", "sys", "dev", "tmp", "root", "opt"}) {
                new File(rootfs, dir).mkdirs();
            }
            writeIfMissing(new File(rootfs, "etc/hosts"),
                    "127.0.0.1\tlocalhost\n::1\tlocalhost\n10.0.2.2\thost.local\n");

            if (!new File(rootfs, "bin/bash").exists()) {
                fetchAndExtract(app, Runtime.ROOTFS_URL, new File(base, "rootfs.tar.gz"),
                        rootfs, "Downloading base system (28 MB)\u2026", cb);
            }

            if (!Runtime.codeServer(app).exists()) {
                File staging = new File(rootfs, "opt/cs_staging");
                deleteRecursive(staging);
                staging.mkdirs();
                fetchAndExtract(app, Runtime.CODE_SERVER_URL, new File(base, "cs.tar.gz"),
                        staging, "Downloading editor (104 MB)\u2026", cb);
                File inner = firstDirectory(staging);
                if (inner == null) throw new IOException("editor archive had no directory");
                File target = new File(rootfs, "opt/cs");
                deleteRecursive(target);
                if (!inner.renameTo(target)) {
                    // Rename across the same filesystem should work; fall back to a copy.
                    copyTree(inner, target);
                }
                deleteRecursive(staging);
            }

            // record the bundle version so the updater can compare against the latest release
            writeBundleVersion(app, LocalEditorUpdate.BUNDLED_VERSION);

            ensureNativeModules(app);
            fixPermissions(rootfs);
            int repaired = ensureExecutable(rootfs);
            appendLog(app, "ensureExecutable: fixed " + repaired + " files\n");

            // Smoke test: can Android execute proot from app storage at all? If the targetSdk
            // trick is rejected by this device, fail here with a readable message instead of a
            // mysterious "editor did not answer" later.
            String prootInfo = prootSelfTest(app);
            appendLog(app, "proot self-test: " + prootInfo + "\n");
            // The banner spells "PRoot" as ASCII art, so check for a usable signal instead.
            boolean prootOk = !prootInfo.startsWith("spawn failed")
                    && !prootInfo.contains("Permission denied")
                    && !prootInfo.contains("timeout");
            if (!prootOk) {
                exportLog(app);
                throw new IOException("proot could not run on this device: " + prootInfo);
            }

            if (!Runtime.installed(app)) throw new IOException("runtime incomplete after install");
            Runtime.tmp(app).mkdirs();
            exportLog(app);
            cb.onDone(true, "Runtime ready");
        } catch (Throwable t) {
            appendLog(app, "INSTALL FAILED: " + t + "\n");
            exportLog(app);
            cb.onDone(false, t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    // ------------------------------------------------------------- proot bits

    /**
     * Copies proot and the bionic libraries it links against out of the APK. They must be real
     * files with the executable bit set — the app targets API 28 precisely so this is allowed.
     */
    static void installProot(Context app) throws IOException {
        File bin = Runtime.bin(app);
        bin.mkdirs();
        String[] files = {"proot", "loader", "loader32",
                "libtalloc.so.2", "libandroid-shmem.so", "libtermux-exec.so"};
        AssetManager assets = app.getAssets();
        for (String name : files) {
            File out = new File(bin, name);
            InputStream in = null;
            OutputStream fos = null;
            try {
                in = new BufferedInputStream(assets.open("proot/" + name), 32 * 1024);
                fos = new FileOutputStream(out);
                byte[] buf = new byte[32 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            } finally {
                closeQuietly(in);
                closeQuietly(fos);
            }
            out.setReadable(true, true);
            out.setExecutable(true, true);
        }
        Runtime.proot(app).setExecutable(true, true);
        // proot execs its own loader binary; without it every guest execve fails with EACCES.
        new File(bin, "loader").setExecutable(true, true);
        new File(bin, "loader32").setExecutable(true, true);
    }

    /** Runs {@code proot --version} and returns whatever it printed. */
    static String prootSelfTest(Context app) {
        ProcessBuilder pb = new ProcessBuilder(Runtime.proot(app).getAbsolutePath(), "--version");
        pb.redirectErrorStream(true);
        pb.environment().put("LD_LIBRARY_PATH", Runtime.bin(app).getAbsolutePath());
        applyProotEnv(app, pb);
        Process p = null;
        try {
            p = pb.start();
            final java.io.InputStream in = p.getInputStream();
            final StringBuilder out = new StringBuilder();
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = in.read(buf)) > 0) out.append(new String(buf, 0, n, "UTF-8"));
                    } catch (Exception ignored) {
                    }
                }
            });
            reader.start();
            if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "timeout; output so far: " + out;
            }
            reader.join(2000);
            String text = out.toString().trim();
            return text.isEmpty() ? ("exit " + p.exitValue() + " with no output") : text;
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
        }
    }

    private static void appendLog(Context app, String text) {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(Runtime.log(app), true);
            fos.write(text.getBytes("UTF-8"));
        } catch (Exception ignored) {
        } finally {
            closeQuietly(fos);
        }
    }

    // ------------------------------------------------------------- networking

    private static void fetchAndExtract(Context app, String url, File archive, File dest,
                                        String stage, Callback cb) throws IOException {
        download(url, archive, stage, cb);
        cb.onProgress("Unpacking\u2026", 0f);
        final long total = Math.max(1, archive.length() * 6);
        InputStream in = null;
        try {
            in = new BufferedInputStream(new FileInputStream(archive), 128 * 1024);
            TarGz.extract(in, dest, new TarGz.Progress() {
                @Override
                public void onBytes(long done) {
                    cb.onProgress("Unpacking\u2026", Math.min(1f, (float) done / total));
                }
            });
        } finally {
            closeQuietly(in);
        }
        //noinspection ResultOfMethodCallIgnored
        archive.delete();
    }

    private static void download(String url, File target, String stage, Callback cb)
            throws IOException {
        File part = new File(target.getAbsolutePath() + ".part");
        HttpURLConnection conn = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "CodeServerGo");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " for " + url);
            }
            long length = conn.getContentLengthLong();
            in = new BufferedInputStream(conn.getInputStream(), 128 * 1024);
            out = new FileOutputStream(part);
            byte[] buf = new byte[128 * 1024];
            long done = 0;
            long lastReport = 0;
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
            if (part.length() < 1024 * 1024) throw new IOException("download too small");
            if (length > 0 && part.length() != length) throw new IOException("download truncated");
            if (!part.renameTo(target)) throw new IOException("cannot finalise download");
        } finally {
            closeQuietly(in);
            closeQuietly(out);
            if (conn != null) conn.disconnect();
        }
    }

    // ---------------------------------------------------------------- helpers

    private static File firstDirectory(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) return null;
        for (File f : kids) if (f.isDirectory()) return f;
        return null;
    }

    /**
     * Walks the binary directories and makes every ELF file executable.
     *
     * <p>tar modes are applied during extraction, but if anything in the chain drops them the
     * guest's bash and node become "Permission denied" from proot's point of view. This pass is
     * cheap (a few thousand files) and turns that failure mode into a non-issue.
     */
    static int ensureExecutable(File rootfs) {
        int fixed = 0;
        String[] dirs = {
                "bin", "sbin", "usr/bin", "usr/sbin",
                "usr/lib", "usr/lib/aarch64-linux-gnu", "lib", "lib64",
                "usr/local/bin", "opt/cs/bin", "opt/cs/lib"
        };
        byte[] magic = new byte[]{0x7f, 'E', 'L', 'F'};
        for (String d : dirs) {
            fixed += chmodElf(new File(rootfs, d), magic, 0);
        }
        return fixed;
    }

    private static int chmodElf(File dir, byte[] magic, int depth) {
        if (depth > 2 || dir == null || !dir.isDirectory()) return 0;
        File[] kids = dir.listFiles();
        if (kids == null) return 0;
        int n = 0;
        for (File f : kids) {
            if (f.isDirectory()) {
                n += chmodElf(f, magic, depth + 1);
                continue;
            }
            if (f.canExecute() || java.nio.file.Files.isSymbolicLink(f.toPath())) continue;
            if (isElf(f, magic) && f.setExecutable(true, false)) n++;
        }
        return n;
    }

    private static boolean isElf(File f, byte[] magic) {
        FileInputStream in = null;
        try {
            if (f.length() < 4) return false;
            in = new FileInputStream(f);
            byte[] head = new byte[4];
            if (in.read(head) != 4) return false;
            for (int i = 0; i < 4; i++) if (head[i] != magic[i]) return false;
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            closeQuietly(in);
        }
    }

    /**
     * code-server requires argon2 at startup even with {@code --auth none}. In the release tarball
     * that native module is a hard link, which not every Android filesystem lets us recreate, so a
     * copy ships inside the APK as a guaranteed fallback.
     */
    static void ensureNativeModules(Context app) {
        restoreIfMissing(app, "cs/argon2.node",
                "opt/cs/node_modules/argon2/lib/binding/napi-v3/argon2.node");
        restoreIfMissing(app, "cs/watcher.node",
                "lib/vscode/node_modules/@parcel/watcher/build/Release/watcher.node");
    }

    static boolean ensureArgon2(Context app) {
        File target = new File(Runtime.rootfs(app),
                "opt/cs/node_modules/argon2/lib/binding/napi-v3/argon2.node");
        return restoreIfMissing(app, "cs/argon2.node",
                "opt/cs/node_modules/argon2/lib/binding/napi-v3/argon2.node")
                || (target.exists() && target.length() > 1000);
    }

    /**
     * Native modules that live in the tarball as long-path hard links. Older builds of our
     * extractor dropped exactly these, so they also ship in the APK and are restored on demand.
     */
    private static boolean restoreIfMissing(Context app, String asset, String rel) {
        File target = new File(Runtime.rootfs(app), rel);
        try {
            File parent = target.getParentFile();
            if (parent != null) parent.mkdirs();
            InputStream in = new BufferedInputStream(app.getAssets().open(asset), 32 * 1024);
            OutputStream out = new FileOutputStream(target);
            try {
                byte[] buf = new byte[32 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                closeQuietly(in);
                closeQuietly(out);
            }
            target.setExecutable(true, false);
            appendLog(app, "restored " + rel + " from assets (" + target.length() + " bytes)\n");
            return true;
        } catch (Throwable t) {
            appendLog(app, "restore " + rel + " failed: " + t + "\n");
            return false;
        }
    }

    /** Environment proot itself needs: its libraries and its loader. */
    static void applyProotEnv(Context app, ProcessBuilder pb) {
        File bin = Runtime.bin(app);
        pb.environment().put("LD_LIBRARY_PATH", bin.getAbsolutePath());
        pb.environment().put("PROOT_TMP_DIR", Runtime.tmp(app).getAbsolutePath());
        pb.environment().put("PROOT_NO_SECCOMP", "1");
        pb.environment().put("PROOT_LOADER", new File(bin, "loader").getAbsolutePath());
        pb.environment().put("PROOT_LOADER_32", new File(bin, "loader32").getAbsolutePath());
    }

    /** Copies the runtime log somewhere readable so failures can be diagnosed without adb. */
    static void exportLog(Context app) {
        File log = Runtime.log(app);
        if (!log.exists()) return;
        try {
            byte[] buf = new byte[(int) Math.min(log.length(), 64 * 1024)];
            FileInputStream in = new FileInputStream(log);
            int n = in.read(buf);
            in.close();
            CrashLog.exportText(app, "local-runtime.log", new String(buf, 0, Math.max(n, 0), "UTF-8"));
        } catch (Exception ignored) {
        }
    }

    /** Writes the bundle-version marker for the in-app updater. */
    private static void writeBundleVersion(Context app, String version) {
        try {
            File marker = new File(Runtime.rootfs(app), LocalEditorUpdate.markerPath());
            if (marker.getParentFile() != null) marker.getParentFile().mkdirs();
            FileOutputStream fos = new FileOutputStream(marker);
            fos.write(version.getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignored) {
        }
    }

    /** tar modes are preserved, but Android's storage can drop the exec bit: be explicit. */
    private static void fixPermissions(File rootfs) {
        new File(rootfs, "bin/bash").setExecutable(true, false);
        File cs = new File(rootfs, "opt/cs");
        new File(cs, "bin/code-server").setExecutable(true, false);
        markExecutables(cs, 0);
    }

    private static void markExecutables(File dir, int depth) {
        if (depth > 2) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) {
                markExecutables(f, depth + 1);
            } else if (f.getName().equals("node") || f.getName().equals("code-server")) {
                f.setExecutable(true, false);
            }
        }
    }

    private static void writeIfMissing(File f, String content) throws IOException {
        if (f.exists()) return;
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        FileOutputStream fos = new FileOutputStream(f);
        try {
            fos.write(content.getBytes("UTF-8"));
        } finally {
            closeQuietly(fos);
        }
    }

    private static void copyTree(File from, File to) throws IOException {
        if (from.isDirectory()) {
            to.mkdirs();
            File[] kids = from.listFiles();
            if (kids != null) for (File k : kids) copyTree(k, new File(to, k.getName()));
        } else {
            InputStream in = new FileInputStream(from);
            OutputStream out = new FileOutputStream(to);
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                closeQuietly(in);
                closeQuietly(out);
            }
        }
    }

    static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }
}
