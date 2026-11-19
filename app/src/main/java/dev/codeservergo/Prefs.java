package dev.codeservergo;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** All persisted user settings. One place, so nothing is hidden in random fields. */
final class Prefs {

    static final int THEME_DARK = 0;
    static final int THEME_LIGHT = 1;
    static final int THEME_AUTO = 2;

    private static final String NAME = "codeservergo";
    private static final String K_THEME = "theme";
    private static final String K_LAST_SERVER = "last_server";
    private static final String K_DESKTOP_UA = "desktop_ua";
    private static final String K_KEEP_AWAKE = "keep_awake";
    private static final String K_EXTRA_KEYS = "extra_keys";
    private static final String K_ZOOM = "zoom_";
    private static final String K_UPLOAD_DIR = "upload_dir";
    private static final String K_TRUSTED = "trusted_certs";

    private final SharedPreferences sp;
    private final Context ctx;

    Prefs(Context c) {
        ctx = c.getApplicationContext();
        sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    // --- theme -----------------------------------------------------------

    int theme() {
        return sp.getInt(K_THEME, THEME_AUTO);
    }

    void setTheme(int theme) {
        sp.edit().putInt(K_THEME, theme).apply();
    }

    /** True when the palette currently in use still matches the setting (light/dark switch). */
    boolean isCurrentTheme(boolean darkNow) {
        switch (theme()) {
            case THEME_DARK:
                return darkNow;
            case THEME_LIGHT:
                return !darkNow;
            default:
                int mode = ctx.getResources().getConfiguration().uiMode
                        & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
                boolean systemDark = mode != android.content.res.Configuration.UI_MODE_NIGHT_NO;
                return systemDark == darkNow;
        }
    }

    // --- session ---------------------------------------------------------

    String lastServerId() {
        return sp.getString(K_LAST_SERVER, null);
    }

    void setLastServerId(String id) {
        sp.edit().putString(K_LAST_SERVER, id).apply();
    }

    // --- editor behaviour ------------------------------------------------

    boolean desktopUserAgent() {
        return sp.getBoolean(K_DESKTOP_UA, true);
    }

    void setDesktopUserAgent(boolean v) {
        sp.edit().putBoolean(K_DESKTOP_UA, v).apply();
    }

    boolean keepAwake() {
        return sp.getBoolean(K_KEEP_AWAKE, true);
    }

    void setKeepAwake(boolean v) {
        sp.edit().putBoolean(K_KEEP_AWAKE, v).apply();
    }

    boolean extraKeys() {
        return sp.getBoolean(K_EXTRA_KEYS, true);
    }

    void setExtraKeys(boolean v) {
        sp.edit().putBoolean(K_EXTRA_KEYS, v).apply();
    }

    float zoom(String serverId) {
        return sp.getFloat(K_ZOOM + serverId, 1f);
    }

    void setZoom(String serverId, float zoom) {
        sp.edit().putFloat(K_ZOOM + serverId, zoom).apply();
    }

    /** Folder the file picker should open in (so uploads start in /sdcard/Projects). */
    String uploadDir() {
        return sp.getString(K_UPLOAD_DIR, null);
    }

    void setUploadDir(String uri) {
        sp.edit().putString(K_UPLOAD_DIR, uri).apply();
    }

    // --- certificate pinning for self-signed code-server ------------------

    Set<String> trustedCerts() {
        Set<String> stored = sp.getStringSet(K_TRUSTED, null);
        return stored == null ? new HashSet<String>() : new HashSet<>(stored);
    }

    boolean isTrusted(String host, String sha256) {
        return trustedCerts().contains(host + "|" + sha256);
    }

    void trust(String host, String sha256) {
        Set<String> set = trustedCerts();
        set.add(host + "|" + sha256);
        sp.edit().putStringSet(K_TRUSTED, set).apply();
    }

    void forgetTrusted() {
        sp.edit().remove(K_TRUSTED).apply();
    }

    static String versionName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.0.0";
        }
    }
}
