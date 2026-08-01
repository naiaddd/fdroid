#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
DEPLOY="$ROOT/deploy.sh"
FAKE="$ROOT/tests/fake-iconctl.sh"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

run_preflight() {
    mode=$1
    shift
    log="$TMP/iconctl.log"
    out="$TMP/out"
    ICONCTL_PATH="$FAKE" ICONCTL_LOG="$log" \
        "$DEPLOY" --icon-preflight --icons="$mode" "$@" \
        >"$out" 2>&1 || fail "preflight ($mode) failed"
    test -s "$log" || fail "iconctl was not called for $mode"
}

run_preflight require didact babyboolraw
test "$(cat "$TMP/iconctl.log")" = \
    "preflight --mode require didact babyboolraw" || fail "mode/app parsing"

run_preflight auto didact
test "$(cat "$TMP/iconctl.log")" = \
    "preflight --mode auto didact" || fail "auto mode parsing"

run_preflight auto
test "$(cat "$TMP/iconctl.log")" = \
    "preflight --mode auto indicium industria anetmon sentry actions tutor didact brine acetone babybool babyboolraw chatter updater" \
    || fail "no-args all-app selection"

ICONCTL_PATH="$TMP/not-used" "$DEPLOY" --icon-preflight --icons=skip didact \
    >"$TMP/skip.out" 2>&1 || fail "skip mode failed"
grep -q 'Skipping icon preflight' "$TMP/skip.out" || fail "skip message"

if ICONCTL_PATH="$FAKE" ICONCTL_LOG="$TMP/invalid.log" \
    "$DEPLOY" --icon-preflight --icons=bad didact >"$TMP/bad.out" 2>&1; then
    fail "invalid mode was accepted"
fi
grep -q 'Invalid --icons mode' "$TMP/bad.out" || fail "invalid mode message"

# Choice 5 makes no version change, so this exercises ordering without a
# build or a persistent version edit.  The preflight banner must precede the
# first bump prompt.
ICONCTL_PATH="$FAKE" ICONCTL_LOG="$TMP/order.log" \
    "$DEPLOY" --icons=auto didact 5 >"$TMP/order.out" 2>&1 || \
    fail "normal deploy ordering run failed"
preflight_line=$(grep -n 'Running icon preflight' "$TMP/order.out" | head -1 | cut -d: -f1)
bump_line=$(grep -n 'Didact — current version' "$TMP/order.out" | head -1 | cut -d: -f1)
test -n "$preflight_line" && test -n "$bump_line" || fail "ordering markers missing"
test "$preflight_line" -lt "$bump_line" || fail "preflight ran after bump"

echo "ok: deploy icon modes, selection, and pre-bump ordering"
