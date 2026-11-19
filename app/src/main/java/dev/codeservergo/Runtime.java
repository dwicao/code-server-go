package dev.codeservergo;

import android.content.Context;

import java.io.File;

/** Where the bundled runtime lives on the device. */
final class Runtime {

    static final int PORT = 8080;
    static final String FOLDER = "/sdcard/Projects";

    /** Approximate download size shown in the UI. */
    static final long DOWNLOAD_BYTES = 133L * 1024 * 1024;

    static final String ROOTFS_URL =
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/"
                    + "ubuntu-base-24.04.3-base-arm64.tar.gz";
    static final String CODE_SERVER_URL =
            "https://github.com/coder/code-server/releases/download/v4.96.4/"
                    + "code-server-4.96.4-linux-arm64.tar.gz";

    private Runtime() {
    }

    static File base(Context c) {
        return new File(c.getFilesDir(), "runtime");
    }

    static File rootfs(Context c) {
        return new File(base(c), "rootfs");
    }

    /** proot and its bionic libraries, shipped inside the APK. */
    static File bin(Context c) {
        return new File(base(c), "bin");
    }

    static File proot(Context c) {
        return new File(bin(c), "proot");
    }

    static File codeServer(Context c) {
        return new File(rootfs(c), "opt/cs/bin/code-server");
    }

    static File tmp(Context c) {
        return new File(base(c), "tmp");
    }

    static File log(Context c) {
        return new File(base(c), "local-server.log");
    }

    static boolean installed(Context c) {
        return proot(c).exists() && codeServer(c).exists()
                && new File(rootfs(c), "bin/bash").exists();
    }

    static String url() {
        return "http://127.0.0.1:" + PORT + "/?folder=" + FOLDER;
    }
}
