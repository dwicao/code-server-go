package dev.codeservergo;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the local code-server inside the bundled Ubuntu rootfs through proot, and stops it again.
 *
 * <p>proot is what makes this possible without root: it translates filesystem and process syscalls
 * so a normal glibc userland (and Node, and VS Code's extension host) runs inside the app sandbox.
 * Because it is a user-space emulation, child processes and terminals work exactly as on a server.
 */
final class LocalServer {

    private static final int MAX_LINES = 400;

    private static Process process;
    private static Thread pump;
    private static final ArrayDeque<String> RING = new ArrayDeque<>();

    private LocalServer() {
    }

    static synchronized boolean running() {
        return process != null && process.isAlive();
    }

    /**
     * How the guest program is started: directly, or by naming the guest's own ELF loader
     * explicitly. Both are legal proot invocations; some Android kernels accept only the second.
     */
    private static final String MODE_NORMAL = "normal";
    private static final String MODE_LOADER = "loader";
    private static String detectedMode;

    static synchronized void start(Context context) throws IOException {
        if (running()) return;
        final Context app = context.getApplicationContext();
        if (!Runtime.installed(app)) throw new IOException("runtime not installed");

        File rootfs = Runtime.rootfs(app);
        // Self-heal: make sure proot, its loader and its libraries are present and executable.
        RuntimeInstaller.installProot(app);
        RuntimeInstaller.ensureNativeModules(app);
        RuntimeInstaller.ensureExecutable(rootfs);
        Runtime.tmp(app).mkdirs();
        new File(rootfs, "root").mkdirs();

        String mode = detectedMode;
        if (mode == null) {
            if (selfTest(app, MODE_NORMAL)) {
                mode = MODE_NORMAL;
            } else if (selfTest(app, MODE_LOADER)) {
                mode = MODE_LOADER;
            } else {
                log(app, "self-test failed in both launch modes\n");
                throw new IOException("the guest runtime cannot be executed (see log)");
            }
            detectedMode = mode;
            log(app, "launch mode: " + mode + "\n");
        }

        // bin/code-server is a /bin/sh script; call the bundled node directly so proot never has
        // to translate a shebang.
        List<String> cmd = buildCommand(app, mode,
                "/opt/cs/lib/node",
                "/opt/cs/out/node/entry.js",
                "--auth", "none",
                "--bind-addr", "127.0.0.1:" + Runtime.PORT,
                "--disable-telemetry",
                "--disable-update-check",
                "--user-data-dir", "/root/.local/share/code-server",
                Runtime.FOLDER);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        applyEnv(app, pb, true);

        log(app, "\n=== starting (" + mode + "): " + String.join(" ", cmd) + "\n");
        try {
            process = pb.start();
        } catch (IOException e) {
            log(app, "spawn failed: " + e + "\n");
            throw e;
        }
        startPump(app);
    }

    /** proot needs its libs and a writable temp dir; the rest is the guest's environment. */
    private static void applyEnv(Context app, ProcessBuilder pb, boolean guestEnv) {
        RuntimeInstaller.applyProotEnv(app, pb);
        pb.environment().put("TMPDIR", Runtime.tmp(app).getAbsolutePath());
        if (guestEnv) {
            pb.environment().put("HOME", "/root");
            pb.environment().put("PATH", "/opt/cs/bin:/usr/local/bin:/usr/bin:/bin");
            pb.environment().put("LANG", "C.UTF-8");
        }
    }

    /** Builds a proot command line for the given launch mode. */
    private static List<String> buildCommand(Context app, String mode, String program,
                                             String... args) {
        File rootfs = Runtime.rootfs(app);
        List<String> cmd = new ArrayList<>();
        cmd.add(Runtime.proot(app).getAbsolutePath());
        cmd.add("--link2symlink");
        cmd.add("-0");
        cmd.add("-r");
        cmd.add(rootfs.getAbsolutePath());
        cmd.add("-b");
        cmd.add("/dev");
        cmd.add("-b");
        cmd.add("/proc");
        cmd.add("-b");
        cmd.add("/sys");
        cmd.add("-b");
        cmd.add("/sdcard:/sdcard");
        cmd.add("-b");
        cmd.add("/storage:/storage");
        cmd.add("-b");
        cmd.add(Runtime.tmp(app).getAbsolutePath() + ":/tmp");
        cmd.add("-w");
        cmd.add(Runtime.FOLDER);
        if (MODE_LOADER.equals(mode)) cmd.add("/lib/ld-linux-aarch64.so.1");
        cmd.add(program);
        for (String a : args) cmd.add(a);
        return cmd;
    }

    /** Executes the bundled node inside the guest and checks that it really ran. */
    private static boolean selfTest(Context app, String mode) {
        List<String> cmd = buildCommand(app, mode, "/opt/cs/lib/node", "--version");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        applyEnv(app, pb, false);
        Process p = null;
        try {
            p = pb.start();
            final java.io.InputStream in = p.getInputStream();
            final StringBuilder out = new StringBuilder();
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        byte[] buf = new byte[2048];
                        int n;
                        while ((n = in.read(buf)) > 0 && out.length() < 3000) {
                            out.append(new String(buf, 0, n, "UTF-8"));
                        }
                    } catch (Exception ignored) {
                    }
                }
            });
            reader.start();
            if (!p.waitFor(12, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log(app, "self-test " + mode + ": TIMEOUT\n");
                return false;
            }
            reader.join(800);
            String text = out.toString();
            log(app, "self-test " + mode + " exit=" + p.exitValue() + "\n" + text + "\n");
            return text.contains("v2") || text.contains("v1");
        } catch (Throwable t) {
            log(app, "self-test " + mode + " failed: " + t + "\n");
            return false;
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
        }
    }

    /** Streams proot's output into the log file and the in-memory ring buffer. */
    private static void startPump(final Context app) {
        final Process p = process;
        pump = new Thread(new Runnable() {
            @Override
            public void run() {
                BufferedReader reader = null;
                try {
                    reader = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (LocalServer.class) {
                            RING.addLast(line);
                            while (RING.size() > MAX_LINES) RING.removeFirst();
                        }
                        log(app, line + "\n");
                    }
                } catch (Exception ignored) {
                } finally {
                    if (reader != null) {
                        try {
                            reader.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            }
        }, "proot-log");
        pump.setDaemon(true);
        pump.start();
    }

    static synchronized void stop(Context context) {
        Context app = context.getApplicationContext();
        Process p = process;
        if (p != null) {
            p.destroy();
            try {
                p.waitFor();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            if (p.isAlive()) p.destroyForcibly();
        }
        process = null;
        pump = null;
        // proot children can outlive the parent; kill anything still referencing our rootfs.
        killOrphans(app);
    }

    private static void killOrphans(Context app) {
        String needle = Runtime.rootfs(app).getAbsolutePath();
        File procs = new File("/proc");
        File[] entries = procs.listFiles();
        if (entries == null) return;
        int self = android.os.Process.myPid();
        for (File e : entries) {
            if (!e.isDirectory()) continue;
            int pid;
            try {
                pid = Integer.parseInt(e.getName());
            } catch (NumberFormatException nfe) {
                continue;
            }
            if (pid == self) continue;
            try {
                File cmdline = new File(e, "cmdline");
                byte[] raw = new byte[4096];
                FileInputStream in = new FileInputStream(cmdline);
                int n = in.read(raw);
                in.close();
                if (n <= 0) continue;
                String cmd = new String(raw, 0, n, "UTF-8");
                if (cmd.contains(needle)) {
                    android.os.Process.sendSignal(pid, 9);
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** Polls the editor's HTTP port until it answers (the extension host takes a few seconds). */
    static boolean waitHealthy(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!running()) return false;
            if (probe()) return true;
            try {
                Thread.sleep(700);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return probe();
    }

    private static boolean probe() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(
                    "http://127.0.0.1:" + Runtime.PORT + "/").openConnection();
            conn.setConnectTimeout(1200);
            conn.setReadTimeout(1500);
            conn.setRequestProperty("User-Agent", "CodeServerGo");
            int code = conn.getResponseCode();
            return code > 0 && code < 500;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    static String tail(int lines) {
        synchronized (LocalServer.class) {
            int skip = Math.max(0, RING.size() - lines);
            StringBuilder sb = new StringBuilder();
            int i = 0;
            for (String l : RING) {
                if (i++ >= skip) sb.append(l).append('\n');
            }
            return sb.toString();
        }
    }

    private static void log(Context app, String text) {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(Runtime.log(app), true);
            fos.write(text.getBytes("UTF-8"));
        } catch (Exception ignored) {
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
