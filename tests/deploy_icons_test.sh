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
    "preflight --mode auto indicium industria anetmon sentry actions tutor didact brine acetone babybool babyboolraw chatter camrelay updater" \
    || fail "no-args all-app selection"

ICONCTL_PATH="$TMP/not-used" "$DEPLOY" --icon-preflight --icons=skip didact \
    >"$TMP/skip.out" 2>&1 || fail "skip mode failed"
grep -q 'Skipping icon preflight' "$TMP/skip.out" || fail "skip message"

if ICONCTL_PATH="$FAKE" ICONCTL_LOG="$TMP/invalid.log" \
    "$DEPLOY" --icon-preflight --icons=bad didact >"$TMP/bad.out" 2>&1; then
    fail "invalid mode was accepted"
fi
grep -q 'Invalid --icons mode' "$TMP/bad.out" || fail "invalid mode message"

ICONCTL_PATH="$TMP/not-used" "$DEPLOY" --icons=skip camrelay 5 \
    >"$TMP/camrelay.out" 2>&1 || fail "CamRelay selection failed"
grep -q 'CamRelay — current version: 0.1.0' "$TMP/camrelay.out" || \
    fail "CamRelay version wiring missing"
jq -e '.camrelay == {packageName:"io.github.camrelay.android", displayName:"CamRelay"}' \
    "$ROOT/apps.json" >/dev/null || fail "CamRelay updater manifest entry missing"
grep -q 'app/build/outputs/apk/release/app-release.apk.*camrelay.apk' \
    "$DEPLOY" || fail "CamRelay release APK staging missing"
grep -q 'create_github_release "CamRelay" "camrelay"' "$DEPLOY" || \
    fail "CamRelay GitHub release wiring missing"

ICONCTL_PATH="$FAKE" ICONCTL_LOG="$TMP/cohort.log" \
    "$DEPLOY" --updater-cohort=glendel --icon-preflight --icons=require \
    >"$TMP/cohort.out" 2>&1 || fail "cohort preflight failed"
test "$(cat "$TMP/cohort.log")" = \
    "preflight --mode require updater" || fail "cohort preflight selection"
grep -q 'no versions bumped' "$TMP/cohort.out" || \
    fail "cohort preflight did not stop before deployment"

# A source-repo commit must contain only the version file and icon files
# returned by preflight. An unrelated dirty file must remain uncommitted.
awk '
    /^_icon_files_for_app\(\)/ { emit = 1 }
    emit { print }
    emit && /^}$/ { exit }
' "$DEPLOY" >"$TMP/scope-functions.sh"
awk '
    /^_push_one_app\(\)/ { emit = 1 }
    emit { print }
    emit && /^}$/ { exit }
' "$DEPLOY" >>"$TMP/scope-functions.sh"

scope_repo="$TMP/scope-repo"
mkdir -p "$scope_repo"
git -C "$scope_repo" init -q
git -C "$scope_repo" config user.email test@example.invalid
git -C "$scope_repo" config user.name test
printf 'version: 0.1.0+1\n' >"$scope_repo/pubspec.yaml"
printf 'old icon\n' >"$scope_repo/icon.png"
printf 'base\n' >"$scope_repo/notes.txt"
git -C "$scope_repo" add .
git -C "$scope_repo" commit -qm baseline
printf 'version: 0.1.1+2\n' >"$scope_repo/pubspec.yaml"
printf 'new icon\n' >"$scope_repo/icon.png"
printf 'unrelated release work\n' >"$scope_repo/notes.txt"
scope_report="$TMP/scope-report.json"
printf '{"apps":[{"app":"didact","applied":{"android":["%s/icon.png"]}}]}\n' \
    "$scope_repo" >"$scope_report"
bash -c '
    source "$1"
    NONINTERACTIVE=true
    ICON_PREFLIGHT_REPORT="$2"
    _push_one_app Didact "$3" "Didact 0.1.1+2" "scope test" pubspec.yaml
' _ "$TMP/scope-functions.sh" "$scope_report" "$scope_repo" \
    >"$TMP/scope.out" 2>&1 || fail "scope commit failed"
scope_files=$(git -C "$scope_repo" show --format= --name-only HEAD | sort | tr '\n' ' ')
test "$scope_files" = "icon.png pubspec.yaml " || \
    fail "scope commit included wrong files"
test "$(git -C "$scope_repo" status --short)" = " M notes.txt" || \
    fail "unrelated dirty file was staged or committed"

# Updater paths are resolved relative to the Updater project, then prefixed
# once for the surrounding fdroid workspace commit.
updater_dir="$TMP/fdroid/updater"
mkdir -p "$updater_dir/app/src/main/res"
printf 'icon\n' >"$updater_dir/app/src/main/res/icon.png"
printf '{"apps":[{"app":"updater","applied":{"android":["%s/app/src/main/res/icon.png"]}}]}\n' \
    "$updater_dir" >"$TMP/updater-report.json"
updater_path=$(bash -c '
    source "$1"
    ICON_PREFLIGHT_REPORT="$2"
    _icon_files_for_app updater "$3"
' _ "$TMP/scope-functions.sh" "$TMP/updater-report.json" "$updater_dir")
test "$updater_path" = "app/src/main/res/icon.png" || \
    fail "updater icon path was not workspace-relative exactly once"

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
