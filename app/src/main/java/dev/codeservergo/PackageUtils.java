package dev.codeservergo;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

/** Package-installer permission helpers. */
final class PackageUtils {

    private PackageUtils() {
    }

    /** True on Android 8+ when the user has not enabled "install unknown apps" for this app. */
    static boolean needsUnknownSourcesPermission(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        try {
            return !context.getPackageManager().canRequestPackageInstalls();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Opens the settings screen where the user grants the permission. */
    static void openUnknownSourcesSettings(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
            intent.setData(Uri.parse("package:" + context.getPackageName()));
            context.startActivity(intent);
        } catch (Throwable ignored) {
            // fall back silently; the install intent will complain as usual
        }
    }
}
