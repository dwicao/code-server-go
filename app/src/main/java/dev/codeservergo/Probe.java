package dev.codeservergo;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * On-device diagnostics for the bundled runtime, written to
 * {@code Download/CodeServerGo/diagnostics.log}.
 *
 * <p>It answers the three questions that matter when proot refuses to start the editor:
 * is the rootfs complete and are its symlinks intact, can the guest's dynamic loader be reached,
 * and does any proot invocation actually execute the bundled node.
 */
final class Probe {

    private Probe() {
    }

    static String run(Context context) {
        final Context app = context.getApplicationContext();
        File rootfs = Runtime.rootfs(app);

        StringBuilder r = new StringBuilder();
        r.append("CodeServer Go diagnostics\n");
        r.append("device : ").append(android.os.Build.MANUFACTURER).append(' ')
                .append(android.os.Build.MODEL).append(" / API ")
                .append(android.os.Build.VERSION.SDK_INT).append('\n');
        r.append("app    : targetSdk ").append(app.getApplicationInfo().targetSdkVersion)
                .append('\n');
        r.append("rootfs : ").append(rootfs.getAbsolutePath()).append('\n');
        r.append("---\n");

        // 1. Is the tree complete? (reference from a healthy extraction:
        //    ~2564 files, ~194 symlinks, ~105 MB)
        int[] counts = new int[2];
        long[] bytes = new long[1];
        walk(rootfs, counts, bytes, 0);
        r.append(String.format("tree   : files=%d symlinks=%d size=%dMB%n",
                counts[0], counts[1], bytes[0] / (1024 * 1024)));

        // 2. The loader chain that every glibc binary depends on.
        String[] paths = {
                "lib", "lib64", "usr/lib",
                "lib/ld-linux-aarch64.so.1",
                "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
                "usr/bin/bash", "usr/bin/env",
                "opt/cs/lib/node", "opt/cs/out/node/entry.js", "opt/cs/bin/code-server"
        };
        for (String rel : paths) {
            File f = new File(rootfs, rel);
            String link = "";
            try {
                if (java.nio.file.Files.isSymbolicLink(f.toPath())) {
                    link = " -> " + java.nio.file.Files.readSymbolicLink(f.toPath());
                }
            } catch (Exception ignored) {
            }
            r.append(String.format("  %-46s exists=%-5s exec=%-5s len=%d%s%n",
                    rel, f.exists(), f.canExecute(), f.length(), link));
        }
        r.append("---\n");

        // 3. Which proot invocations actually work.
        probe(r, app, "node --version (direct)", "normal",
                "/opt/cs/lib/node", "--version");
        probe(r, app, "node --version (explicit loader)", "loader",
                "/opt/cs/lib/node", "--version");
        probe(r, app, "bash -c echo (direct)", "normal",
                "/usr/bin/bash", "-c", "echo GUEST_OK");
        probe(r, app, "bash -c echo (explicit loader)", "loader",
                "/usr/bin/bash", "-c", "echo GUEST_OK");
        probeVerbose(r, app);

        String text = r.toString();
        CrashLog.exportText(app, "diagnostics.log", text);
        return text;
    }

    private static void probe(StringBuilder r, Context app, String label, String mode,
                              String program, String... args) {
        List<String> cmd = command(app, mode, program, args);
        Out out = exec(app, cmd, 20);
        r.append(label).append("  [").append(mode).append("]\n");
        r.append(indent(out.text));
        r.append("---\n");
    }

    private static void probeVerbose(StringBuilder r, Context app) {
        List<String> cmd = new ArrayList<>();
        cmd.add(Runtime.proot(app).getAbsolutePath());
        cmd.add("--link2symlink");
        cmd.add("-0");
        cmd.add("-v");
        cmd.add("3");
        cmd.add("-r");
        cmd.add(Runtime.rootfs(app).getAbsolutePath());
        cmd.add("-b");
        cmd.add("/dev");
        cmd.add("-b");
        cmd.add("/proc");
        cmd.add("/opt/cs/lib/node");
        cmd.add("--version");
        Out out = exec(app, cmd, 20);
        String[] lines = out.text.split("\n");
        StringBuilder tail = new StringBuilder();
        for (int i = Math.max(0, lines.length - 22); i < lines.length; i++) {
            tail.append(lines[i]).append('\n');
        }
        r.append("verbose -v 3 (last 22 lines)\n");
        r.append(indent(tail.toString()));
        r.append("---\n");
    }

    private static List<String> command(Context app, String mode, String program,
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
        cmd.add("/sdcard:/sdcard");
        cmd.add("-b");
        cmd.add(Runtime.tmp(app).getAbsolutePath() + ":/tmp");
        cmd.add("-w");
        cmd.add("/");
        if ("loader".equals(mode)) cmd.add("/lib/ld-linux-aarch64.so.1");
        cmd.add(program);
        for (String a : args) cmd.add(a);
        return cmd;
    }

    private static final class Out {
        final String text;

        Out(String text) {
            this.text = text;
        }
    }

    private static Out exec(Context app, List<String> cmd, int timeoutSeconds) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        RuntimeInstaller.applyProotEnv(app, pb);
        Process p = null;
        try {
            p = pb.start();
            final InputStream in = p.getInputStream();
            final StringBuilder out = new StringBuilder();
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = in.read(buf)) > 0 && out.length() < 6000) {
                            out.append(new String(buf, 0, n, "UTF-8"));
                        }
                    } catch (Exception ignored) {
                    }
                }
            });
            reader.start();
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Out("TIMEOUT after " + timeoutSeconds + "s\n"
                        + out.toString().trim() + "\n");
            }
            reader.join(1200);
            String text = out.toString().trim();
            return new Out("exit=" + p.exitValue() + "\n"
                    + (text.isEmpty() ? "(no output)" : text) + "\n");
        } catch (Throwable t) {
            return new Out("spawn failed: " + t.getClass().getSimpleName() + ": "
                    + t.getMessage() + "\n");
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
        }
    }

    private static void walk(File dir, int[] counts, long[] bytes, int depth) {
        if (depth > 14 || dir == null) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            try {
                if (java.nio.file.Files.isSymbolicLink(f.toPath())) {
                    counts[1]++;
                } else if (f.isDirectory()) {
                    walk(f, counts, bytes, depth + 1);
                } else {
                    counts[0]++;
                    bytes[0] += f.length();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static String indent(String block) {
        StringBuilder sb = new StringBuilder();
        for (String line : block.split("\n")) sb.append("    ").append(line).append('\n');
        return sb.toString();
    }

    @SuppressWarnings("unused")
    private static String readHead(File f) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] b = new byte[4];
            return in.read(b) == 4 ? new String(b, 0, 4, "ISO-8859-1") : "";
        } catch (Exception e) {
            return "";
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
        }
    }
}
