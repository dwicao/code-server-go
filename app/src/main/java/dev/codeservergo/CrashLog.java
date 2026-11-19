package dev.codeservergo;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Writes an uncaught-exception report to a place the user (and a file manager) can actually read.
 *
 * <p>A hand-built APK that dies on launch is useless without a stack trace, and on a phone there is
 * no adb. So the trace goes to {@code Download/CodeServerGo/crash-*.txt} through MediaStore (no
 * storage permission needed on Android 10+), with an app-private file as a fallback.
 */
final class CrashLog {

    private static final String TAG = "CodeServerGo";
    static final String FOLDER = "CodeServerGo";

    private CrashLog() {
    }

    static void install(final Context context) {
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                save(app, error, thread);
            } catch (Throwable ignored) {
                // Never mask the original crash.
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    /** Drops a text file into Download/CodeServerGo so it can be inspected off-device. */
    static boolean exportText(Context app, String displayName, String text) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, displayName);
            values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            values.put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
            Uri uri = app.getContentResolver()
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return false;
            try (OutputStream out = app.getContentResolver().openOutputStream(uri)) {
                if (out == null) return false;
                out.write(text.getBytes("UTF-8"));
                out.flush();
                return true;
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Saves right now, from a catch block in an activity. */
    static void saveNow(Context app, Throwable error) {
        try {
            save(app.getApplicationContext(), error, Thread.currentThread());
        } catch (Throwable ignored) {
        }
    }

    private static void save(Context app, Throwable error, Thread thread) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("CodeServer Go " + Prefs.versionName(app) + " crash report");
        pw.println("time     : " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date()));
        pw.println("thread   : " + thread.getName());
        pw.println("device   : " + Build.MANUFACTURER + " " + Build.MODEL + " / API "
                + Build.VERSION.SDK_INT);
        pw.println("uiMode   : " + app.getResources().getConfiguration().uiMode
                + "  widthDp=" + app.getResources().getConfiguration().screenWidthDp);
        pw.println("---");
        error.printStackTrace(pw);
        pw.flush();
        String text = sw.toString();

        Log.e(TAG, text);

        // 1) Downloads/CodeServerGo — visible in any file manager.
        if (exportText(app, "crash-" + System.currentTimeMillis() + ".txt", text)) return;

        // 2) App-specific external dir (always writable, no permission).
        try {
            File dir = app.getExternalFilesDir(null);
            if (dir != null) {
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, "crash-" + System.currentTimeMillis() + ".txt");
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(text.getBytes("UTF-8"));
                }
                return;
            }
        } catch (Throwable ignored) {
        }

        // 3) Internal storage, last resort.
        try {
            File f = new File(app.getFilesDir(), "crash.txt");
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(text.getBytes("UTF-8"));
            }
        } catch (Throwable ignored) {
        }
    }
}
