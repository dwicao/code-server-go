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

    /** True on Android 11+ when the user granted all-files access. */
    static boolean needsAllFilesAccess(Context context) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            try {
                return !android.os.Environment.isExternalStorageManager();
            } catch (Throwable t) {
                return true;
            }
        }
        return false;
    }

    /** Opens the settings screen where the user grants all-files access. */
    static void openAllFilesAccessSettings(Context context) {
        try {
            android.content.Intent intent =
                    new android.content.Intent(android.provider.Settings
                            .ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(android.net.Uri.parse("package:" + context.getPackageName()));
            context.startActivity(intent);
        } catch (Throwable t) {
            try {
                context.startActivity(new android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Throwable ignored) {
            }
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
