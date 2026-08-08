#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
HELPER="$ROOT/tools/github_release.sh"
FAKE="$ROOT/tests/fake-gh-release.sh"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }

mkdir -p "$TMP/bin" "$TMP/state"
ln -s "$FAKE" "$TMP/bin/gh"
: >"$TMP/calls"
: >"$TMP/state/fail-create"
printf 'version=0.1.2+7\n' >"$TMP/version"
printf 'first-release-bytes\n' >"$TMP/app.apk"
first_hash=$(sha256sum "$TMP/app.apk" | awk '{print $1}')

if PATH="$TMP/bin:$PATH" GH_STATE="$TMP/state" GH_CALLS="$TMP/calls" \
    RELEASE_ARTIFACT_DIR="$TMP/artifacts" "$HELPER" \
    --repo example/repo --tag app-v0.1.2+7 --title 'App 0.1.2+7' \
    --notes notes --asset "$TMP/app.apk" >"$TMP/first.out" 2>&1; then
    fail "interrupted create unexpectedly succeeded"
fi
test -f "$TMP/state/created" || fail "interrupted create did not leave draft"
test -f "$TMP/artifacts/app-v0.1.2+7/assets.sha256" || \
    fail "asset checksum manifest was not written"

# Simulate a later build overwriting the staging file.  The retry must upload
# the durable first-run bytes, without changing the app version.
printf 'different-second-build-bytes\n' >"$TMP/app.apk"
rm -f "$TMP/app.apk"
PATH="$TMP/bin:$PATH" GH_STATE="$TMP/state" GH_CALLS="$TMP/calls" \
    RELEASE_ARTIFACT_DIR="$TMP/artifacts" "$HELPER" \
    --repo example/repo --tag app-v0.1.2+7 --title 'App 0.1.2+7' \
    --notes notes --asset "$TMP/app.apk" >"$TMP/second.out" 2>&1 || \
    fail "resume failed"

test -f "$TMP/state/published" || fail "resume did not publish draft"
test "$(cat "$TMP/version")" = 'version=0.1.2+7' || \
    fail "resume changed the version"
test "$(sha256sum "$TMP/state/asset.app.apk" | awk '{print $1}')" = "$first_hash" || \
    fail "resume uploaded changed staging bytes"
grep -q -- '--clobber' "$TMP/calls" || fail "upload did not use --clobber"
grep -q 'release edit app-v0.1.2+7' "$TMP/calls" || \
    fail "resume did not explicitly publish"
grep -q 'Verifying authenticated GitHub API visibility' "$TMP/second.out" || \
    fail "public API verification was not logged"

echo "PASS: resumable GitHub release core"
