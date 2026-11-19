#!/usr/bin/env bash
#
# CodeServer Go — build without Android Studio and without Gradle.
#
# Why: on a phone there is no x86-64 toolchain for Gradle + AGP, but aapt2, d8 and
# apksigner all have aarch64 builds. This script drives them directly, so the whole
# app can be rebuilt on the device itself (or on any Linux box).
#
#   ./build.sh            build + sign a debug-friendly APK into dist/
#   ANDROID_JAR=/path ./build.sh
#
# Requirements (Ubuntu arm64, e.g. inside proot):
#   apt-get install -y openjdk-17-jdk-headless android-sdk-build-tools apksigner zipalign
#   plus an android.jar for API 34 (see README for the one-line download).

set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
APP="$ROOT/app/src/main"
OUT="$ROOT/build"
DIST="$ROOT/dist"
MANIFEST="$APP/AndroidManifest.xml"

APP_ID="dev.codeservergo"
VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-1.0.0}"
# targetSdk 28 is deliberate: Android only allows executing binaries from app storage for
# apps targeting API <= 28. That is what makes the bundled proot + rootfs runtime work
# (the same trade-off Termux makes). Sideload-only consequence; nothing else needs it.
MIN_SDK=26
TARGET_SDK=28

# ---------------------------------------------------------------- toolchain

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"

find_tool() {
    local name="$1"; shift
    if [ -n "${!name-}" ]; then echo "${!name}"; return; fi
    for candidate in "$@"; do
        if [ -x "$candidate" ]; then echo "$candidate"; return; fi
    done
    command -v "${name,,}" 2>/dev/null || true
}

AAPT2="${AAPT2:-$(find_tool AAPT2 \
    "${SDK_ROOT:+$SDK_ROOT/build-tools/34.0.0/aapt2}" \
    /usr/lib/android-sdk/build-tools/debian/aapt2 \
    /usr/lib/android-sdk/build-tools/*/aapt2 \
    "$ROOT/tools/aapt2")}"
ZIPALIGN="${ZIPALIGN:-$(find_tool ZIPALIGN \
    /usr/lib/android-sdk/build-tools/debian/zipalign \
    /usr/lib/android-sdk/build-tools/*/zipalign \
    /usr/bin/zipalign)}"
APKSIGNER="${APKSIGNER:-$(command -v apksigner || true)}"
KEYTOOL="${KEYTOOL:-$(command -v keytool || true)}"
ANDROID_JAR="${ANDROID_JAR:-${SDK_ROOT:+$SDK_ROOT/platforms/android-34/android.jar}}"
ANDROID_JAR="${ANDROID_JAR:-$ROOT/tools/android-34/android.jar}"
D8_JAR="${D8_JAR:-$ROOT/tools/r8.jar}"

die() { echo "error: $*" >&2; exit 1; }

[ -n "$AAPT2" ] && [ -x "$AAPT2" ] || die "aapt2 not found. Set AAPT2=/path/to/aapt2"
[ -x "$ZIPALIGN" ] || die "zipalign not found. Set ZIPALIGN=/path/to/zipalign"
[ -f "$ANDROID_JAR" ] || die "android.jar not found. Set ANDROID_JAR=/path/to/android-34/android.jar"
[ -f "$D8_JAR" ] || die "r8.jar (dexer) not found. Set D8_JAR=/path/to/r8.jar"
command -v javac >/dev/null || die "javac not found (install openjdk-17-jdk-headless)"

JAVA="${JAVA:-java}"
[ -n "$APKSIGNER" ] || die "apksigner not found (apt install apksigner)"

echo "==> tools"
echo "    aapt2   : $AAPT2"
echo "    android : $ANDROID_JAR"
echo "    d8      : $D8_JAR"
echo "    signer  : $APKSIGNER"

# ---------------------------------------------------------------- resources

rm -rf "$OUT"
mkdir -p "$OUT/compiled" "$OUT/gen" "$OUT/classes" "$OUT/dex" "$DIST"

echo "==> compiling resources"
"$AAPT2" compile --dir "$APP/res" -o "$OUT/compiled/res.zip"

echo "==> linking resources"
"$AAPT2" link \
    -o "$OUT/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$MANIFEST" \
    -R "$OUT/compiled/res.zip" \
    -A "$APP/assets" \
    --java "$OUT/gen" \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME" \
    --auto-add-overlay

# ------------------------------------------------------------------- compile

echo "==> compiling java"
SOURCES=$(find "$OUT/gen" "$APP/java" -name '*.java' | sort)
set +e
# android.jar has no java.lang.invoke.LambdaMetafactory stub, so Java 8 language
# features are compiled against the JDK 8 API surface with android.jar on the
# classpath instead of as bootclasspath. d8 does the desugaring afterwards.
javac -nowarn -encoding UTF-8 --release 8 \
    -classpath "$ANDROID_JAR" \
    -d "$OUT/classes" \
    $SOURCES
JAVAC_STATUS=$?
set -e
if [ $JAVAC_STATUS -ne 0 ]; then
    die "javac failed (exit $JAVAC_STATUS)"
fi
find "$OUT/classes" -name '*.class' | grep -q . || die "javac produced no classes"

echo "==> dexing"
"$JAVA" -cp "$D8_JAR" com.android.tools.r8.D8 \
    --release \
    --min-api "$MIN_SDK" \
    --lib "$ANDROID_JAR" \
    --output "$OUT/dex" \
    $(find "$OUT/classes" -name '*.class' | sort)

# ------------------------------------------------------------------- package

echo "==> packaging"
python3 - "$OUT/base.apk" "$OUT/dex" <<'PY'
import os, sys, zipfile
apk, dexdir = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    for name in sorted(os.listdir(dexdir)):
        if name.endswith(".dex"):
            z.write(os.path.join(dexdir, name), name)
PY

echo "==> aligning"
"$ZIPALIGN" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "==> signing"
KEYSTORE="$ROOT/keystore/codeservergo.jks"
STORE_PASS="${STORE_PASS:-codeservergo}"
KEY_ALIAS="${KEY_ALIAS:-codeservergo}"
if [ ! -f "$KEYSTORE" ]; then
    mkdir -p "$ROOT/keystore"
    [ -n "$KEYTOOL" ] || die "keytool not found, cannot create a signing key"
    "$KEYTOOL" -genkeypair -v \
        -keystore "$KEYSTORE" \
        -storepass "$STORE_PASS" \
        -keypass "$STORE_PASS" \
        -alias "$KEY_ALIAS" \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=CodeServer Go, O=CodeServer Go, C=ID" >/dev/null 2>&1 \
        || die "keytool failed"
    echo "    created $KEYSTORE (keep it: it identifies updates of this app)"
fi

APK="$DIST/CodeServerGo-${VERSION_NAME}.apk"
"$APKSIGNER" sign \
    --ks "$KEYSTORE" \
    --ks-pass "pass:$STORE_PASS" \
    --key-pass "pass:$STORE_PASS" \
    --ks-key-alias "$KEY_ALIAS" \
    --min-sdk-version "$MIN_SDK" \
    --out "$APK" \
    "$OUT/aligned.apk"

"$APKSIGNER" verify --min-sdk-version "$MIN_SDK" "$APK"

echo
echo "==> done"
ls -lh "$APK" | awk '{print "    APK: "$9" ("$5")"}'
echo "    install: adb install -r \"$APK\""
