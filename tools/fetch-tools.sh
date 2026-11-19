#!/usr/bin/env bash
#
# Fetches the two build inputs that are too large to keep in git:
#   tools/android-34/android.jar  (compile-time Android API 34 stubs)
#   tools/r8.jar                  (d8 dexer, runs on any JVM)
#
# The proot binaries under app/src/main/assets ARE committed — they are small and the app
# cannot work without them.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="$ROOT/tools"
mkdir -p "$TOOLS/android-34"

fetch() { # url dest
    if command -v curl >/dev/null 2>&1; then
        curl -fL --retry 3 -o "$2" "$1"
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O "$2" "$1"
    else
        echo "error: need curl or wget" >&2
        exit 1
    fi
}

if [ ! -f "$TOOLS/android-34/android.jar" ]; then
    echo ">> android.jar (API 34, ~26 MB)"
    tmp="$(mktemp -d)"
    fetch "https://dl.google.com/android/repository/platform-34-ext7_r03.zip" "$tmp/platform.zip"
    (cd "$tmp" && unzip -oq platform.zip android-34/android.jar)
    mv "$tmp/android-34/android.jar" "$TOOLS/android-34/android.jar"
    rm -rf "$tmp"
fi

if [ ! -f "$TOOLS/r8.jar" ]; then
    echo ">> r8.jar (dexer, ~16 MB)"
    fetch "https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.3.37/r8-8.3.37.jar" \
        "$TOOLS/r8.jar"
fi

echo "ok:"
ls -lh "$TOOLS/android-34/android.jar" "$TOOLS/r8.jar"
