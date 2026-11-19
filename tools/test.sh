#!/usr/bin/env bash
#
# Unit tests for the parts of the app that do not need Android: URL handling, version
# comparison and the tar.gz extractor.
#
#   tools/test.sh
#
# Exits non-zero on failure (i.e. suitable for CI). Everything else is exercised
# end-to-end on the device builds.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MAIN_SRC="$ROOT/app/src/main/java/dev/codeservergo"
TEST_SRC="$ROOT/app/src/test/java/dev/codeservergo"
OUT="$ROOT/build/test-classes"
LIB="$ROOT/tools/test-lib"

mkdir -p "$OUT"

# Fetch the JUnit jars the first time if they are missing.
if [ ! -f "$LIB/junit-4.13.2.jar" ]; then
    echo ">> fetching junit"
    mkdir -p "$LIB"
    curl -fL --retry 3 -o "$LIB/junit-4.13.2.jar" \
        "https://repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar"
    curl -fL --retry 3 -o "$LIB/hamcrest-core-1.3.jar" \
        "https://repo1.maven.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar"
fi

echo "==> compiling main sources (pure-java subset)"
javac -nowarn -encoding UTF-8 -d "$OUT" \
    "$MAIN_SRC/Urls.java" \
    "$MAIN_SRC/Versions.java" \
    "$MAIN_SRC/TarGz.java" \
    "$MAIN_SRC/Server.java" \
    "$ROOT/tools/test-shim/org/json/JSONException.java" \
    "$ROOT/tools/test-shim/org/json/JSONObject.java"

echo "==> compiling tests"
javac -nowarn -encoding UTF-8 -cp "$OUT:$LIB/junit-4.13.2.jar:$LIB/hamcrest-core-1.3.jar" \
    -d "$OUT" "$TEST_SRC"/*.java

echo "==> running tests"
java -cp "$OUT:$LIB/junit-4.13.2.jar:$LIB/hamcrest-core-1.3.jar" \
    org.junit.runner.JUnitCore \
    dev.codeservergo.UrlsTest \
    dev.codeservergo.VersionsTest \
    dev.codeservergo.TarGzTest
