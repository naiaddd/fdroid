#!/bin/bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
DEPLOY="$ROOT/deploy.sh"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

# deploy.sh is intentionally ignored and self-mutating. Extract only the
# preflight function so this test cannot bump versions or run a deployment.
awk '
    /^preflight_didact_split_apks\(\)/ { emit = 1 }
    emit { print }
    emit && /^}$/ { exit }
' "$DEPLOY" >"$TMP/preflight.sh"
grep -q '^preflight_didact_split_apks()' "$TMP/preflight.sh" || \
    fail "preflight function was not found"

build_dir="$TMP/build/app/outputs/flutter-apk"
mkdir -p "$build_dir"

make_apk() {
    abi=$1
    apk="$build_dir/app-${abi}-release.apk"
    files="$TMP/files-$abi"
    mkdir -p "$files/lib/$abi"
    for lib in libsqlite3.so libpdfium.so libapp.so libflutter.so; do
        printf '%s\n' "$lib" >"$files/lib/$abi/$lib"
    done
    (cd "$files" && zip -q -r "$apk" lib)
}

make_apk arm64-v8a
make_apk armeabi-v7a

(cd "$TMP" && bash -c 'source "$1"; preflight_didact_split_apks' _ \
    "$TMP/preflight.sh") >"$TMP/pass.out" 2>&1 || \
    fail "valid split APKs failed preflight"
grep -q 'preflight passed' "$TMP/pass.out" || \
    fail "successful preflight was not reported"

# Rebuild one split without SQLite. The preflight must fail before any copy.
rm -f "$TMP/files-arm64-v8a/lib/arm64-v8a/libsqlite3.so"
(cd "$TMP/files-arm64-v8a" && \
    zip -q -r -FS "$build_dir/app-arm64-v8a-release.apk" lib)
if (cd "$TMP" && bash -c 'source "$1"; preflight_didact_split_apks' _ \
    "$TMP/preflight.sh") >"$TMP/fail.out" 2>&1; then
    fail "missing native library was accepted"
fi
grep -q 'lib/arm64-v8a/libsqlite3.so' "$TMP/fail.out" || \
    fail "missing library was not reported"

echo "ok: Didact split APK native-library preflight"
