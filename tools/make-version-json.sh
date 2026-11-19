#!/usr/bin/env bash
#
# Produces the version.json that is attached to every GitHub release. The app's in-app
# updater fetches it from releases/latest/download/version.json.
#
#   tools/make-version-json.sh <tag> <apk-file> [notes] > version.json
#
# <tag>      release tag, e.g. v1.2.0
# <apk-file> path to the built, signed APK (used to compute sha256 and the asset name)
set -euo pipefail
SRC_VERSION_JSON="${SRC_VERSION_JSON:-$PWD/version.json}"

if [ ! -f "$SRC_VERSION_JSON" ]; then SRC_VERSION_JSON="$(dirname "$0")/../version.json"; fi

TAG="${1:-}"
APK="${2:-}"
NOTES="${3:-}"

[ -n "$TAG" ] && [ -f "$APK" ] || { echo "usage: make-version-json.sh v1.2.0 <apk> [notes]" >&2; exit 2; }

REPO="${GITHUB_REPOSITORY:-dwicao/code-server-go}"

VERSION_NAME="${TAG#v}"
VERSION_CODE="$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['versionCode'])" "$SRC_VERSION_JSON")"
SHA256="$(sha256sum "$APK" | awk '{print $1}')"
APK_NAME="$(basename "$APK")"
APK_URL="https://github.com/$REPO/releases/download/$TAG/$APK_NAME"
RELEASE_URL="https://github.com/$REPO/releases/tag/$TAG"

if [ "$APK" = "$SRC_VERSION_JSON" ]; then SRC_VERSION_JSON=""; fi

if [ -z "$NOTES" ]; then
    NOTES="$(python3 -c "import json,sys;print(json.load(open(sys.argv[1])).get('notes',''))" "${SRC_VERSION_JSON:-$PWD/version.json}")"
fi

python3 - "$VERSION_NAME" "$VERSION_CODE" "$APK_URL" "$SHA256" "$APK_NAME" "$NOTES" "$RELEASE_URL" <<'PY'
import json, sys
print(json.dumps({
    "versionName": sys.argv[1],
    "versionCode": int(sys.argv[2]),
    "apkUrl": sys.argv[3],
    "sha256": sys.argv[4],
    "apkName": sys.argv[5],
    "notes": sys.argv[6],
    "releaseUrl": sys.argv[7],
}, indent=2))
PY
