package dev.codeservergo;

/**
 * Version comparison for the in-app updater. Pure Java so it can be unit tested.
 *
 * <p>The updater trusts {@code versionCode} (a monotonic integer, the way Android means it) and
 * only falls back to comparing dotted names when a manifest omits it.
 */
final class Versions {

    private Versions() {
    }

    static boolean isUpdateAvailable(int remoteCode, String remoteName,
                                     int localCode, String localName) {
        if (remoteCode > 0) return remoteCode > localCode;
        return compareNames(remoteName, localName) > 0;
    }

    /**
     * Compares dotted version names ("1.2.0" vs "1.1.10"). Returns {@code >0} when {@code a} is
     * newer, {@code <0} when older and {@code 0} when equal.
     */
    static int compareNames(String a, String b) {
        if (a == null || b == null) return 0;
        String[] left = a.trim().split("\\.");
        String[] right = b.trim().split("\\.");
        int len = Math.max(left.length, right.length);
        for (int i = 0; i < len; i++) {
            int l = number(left, i);
            int r = number(right, i);
            if (l != r) return l < r ? -1 : 1;
        }
        return 0;
    }

    private static int number(String[] parts, int index) {
        if (index >= parts.length) return 0;
        String raw = parts[index].replaceAll("[^0-9].*$", "").trim();
        if (raw.isEmpty()) return 0;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
