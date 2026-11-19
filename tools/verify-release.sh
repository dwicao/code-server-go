#!/usr/bin/env bash
#
# Sanity gate run on every CI build before archiving/releasing:
# - the built APK must be package dev.codeservergo with the expected versionName/versionCode
# - the APK must be signed by the pinned AOSP test key (not some other key)
# - the expected certificate DN naming "Android" must match
#
# Usage: tools/verify-release.sh [expected-versionName]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK_GLOB="${1:-$ROOT/dist/CodeServerGo-*.apk}"
PINNED_SHA256="a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc"

APK=$(ls -t $APK_GLOB 2>/dev/null | head -1 || true)
if [ -z "$APK" ]; then echo "verify-release: no APK matched $APK_GLOB" >&2; exit 2; fi
echo ">> verifying $APK"

AAPT=$(command -v aapt || true)
if [ -z "$AAPT" ]; then
  for candidate in /usr/lib/android-sdk/build-tools/debian/aapt \
                   /usr/lib/android-sdk/build-tools/*/aapt; do
    [ -x "$candidate" ] && AAPT="$candidate" && break
  done
fi
[ -n "$AAPT" ] || { echo "verify-release: aapt not found" >&2; exit 2; }

"$AAPT" dump badging "$APK" > /tmp/badging.txt
PKG=$(grep -oE "package: name='[^']+'" /tmp/badging.txt | sed "s/package: name='//;s/'//")
VC=$(grep -oE "versionCode='[^']+'" /tmp/badging.txt | sed "s/versionCode='//;s/'//")
VN=$(grep -oE "versionName='[^']+'" /tmp/badging.txt | sed "s/versionName='//;s/'//")

[ "$PKG" = "dev.codeservergo" ] || { echo "verify-release: package is $PKG, expected dev.codeservergo" >&2; exit 1; }

# cross-check against version.json (source of truth)
if VN_EXPECTED=$(python3 -c 'import json;print(json.load(open("'"$ROOT"'/version.json"))["versionName"])' 2>/dev/null); then
    [ "$VN" = "$VN_EXPECTED" ] || { echo "verify-release: versionName $VN != version.json $VN_EXPECTED" >&2; exit 1; }
fi
if VC_EXPECTED=$(python3 -c 'import json;print(json.load(open("'"$ROOT"'/version.json"))["versionCode"])' 2>/dev/null); then
    [ "$VC" = "$VC_EXPECTED" ] || { echo "verify-release: versionCode $VC != version.json $VC_EXPECTED" >&2; exit 1; }
fi

apksigner verify --print-certs "$APK" > /tmp/print-certs.txt
SIG_SHA256=$(grep -oE "certificate SHA-256 digest: [0-9a-f]+" /tmp/print-certs.txt | head -1 | cut -d' ' -f4)
SIG_DN=$(grep -E "^Signer #1 certificate DN:" /tmp/print-certs.txt | sed 's/^Signer #1 certificate DN: //')

[ "$SIG_SHA256" = "$PINNED_SHA256" ] || {
    echo "verify-release: signer fingerprint $SIG_SHA256 != pinned $PINNED_SHA256" >&2
    echo "verify-release: APK was signed with something other than the release key; refusing" >&2
    exit 1
}
echo "$SIG_DN" | grep -q "CN=android\|O=Android" || {
    echo "verify-release: unexpected DN: $SIG_DN" >&2
    exit 1
}

echo "ok: package=$PKG versionName=$VN versionCode=$VC"
echo "ok: signer SHA-256 $SIG_SHA256 matches pin (AOSP test key)"
