package dev.codeservergo;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;

import java.io.File;
import java.security.MessageDigest;

/**
 * The signer certificate fingerprint pinned into the app's update check.
 *
 * <p>Why: CodeServer Go updates by downloading whatever its GitHub releases publish. Those release
 * APKs are signed with the AOSP test key (tools/aosp-testkey/), which is public — that is fine for
 * the installer, since Android consents to installation on user tap. In-app auto-install would be a
 * different story: with no pin, anything uploaded to the releases could be silently installed.
 *
 * <p>The pin makes the app accept a downloaded APK only when it is signed with this exact
 * certificate. This is not a cryptographic trust root (the test key is public), but it does two
 * useful things: catches accidental re-signing/mistakes, and stops casual replacement from casual
 * compromises of the release workflow.
 *
 * <p>If this value ever changes, change it together with the keystore before a release — and plan
 * an app uninstall for existing users (Android refuses updates signed under a different key).
 */
final class Signers {

    static final String EXPECTED_CERT_SHA256 =
            "a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc";

    private Signers() {
    }

    /** Fingerprint of the certificate that signed the given APK, or null when unreadable. */
    static String fingerprintOfApk(Context context, File apk) {
        PackageManager pm = context.getPackageManager();
        try {
            PackageInfo info = pm.getPackageArchiveInfo(apk.getAbsolutePath(),
                    android.os.Build.VERSION.SDK_INT >= 28
                            ? PackageManager.GET_SIGNING_CERTIFICATES
                            : PackageManager.GET_SIGNATURES);
            if (info == null) return null;
            if (Build.VERSION.SDK_INT >= 28) {
                SigningInfo si = info.signingInfo;
                if (si == null) return null;
                Signature[] signers = si.hasMultipleSigners()
                        ? si.getApkContentsSigners()
                        : si.getSigningCertificateHistory();
                return fingerprint(signers);
            } else {
                return fingerprint(info.signatures);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** Lowercase hex SHA-256 of the first signing certificate. */
    static String fingerprint(Signature[] signers) {
        if (signers == null || signers.length == 0) return null;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(signers[0].toByteArray());
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Whether the given APK is signed with our pinned key. */
    static boolean matchesPin(Context context, File apk) {
        String actual = fingerprintOfApk(context, apk);
        return actual != null && actual.equalsIgnoreCase(EXPECTED_CERT_SHA256);
    }
}
