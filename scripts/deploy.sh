#!/usr/bin/env bash
# deploy.sh — install the app, re-register the accessibility service, and
# launch it in one step. Optionally builds first.
#
# Usage:
#   deploy.sh                 Install the existing debug APK, register, launch
#   deploy.sh --build         Also run assembleDebug first
#   deploy.sh --no-launch     Skip opening the app afterwards
#
# Re-registering the accessibility service is safe to run every time: it only
# appends the app's service to enabled_accessibility_services if it isn't
# already there, so other services already enabled on the device (Bitwarden,
# KDE Connect, SimpleWear, Samsung Game Booster, …) are untouched.
# The Android system deregisters the service on every reinstall, which is why
# this step has to run on every deploy, not just the first one.

set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

APK="app/build/outputs/apk/debug/app-debug.apk"
PKG="dev.lutergs.android_wm"  # applicationId == namespace (app/build.gradle.kts) — one identity, both roles
SVC="$PKG/$PKG.service.TilingAccessibilityService"

die() { echo "ERROR: $*" >&2; exit 1; }

require_adb() {
    command -v adb >/dev/null 2>&1 || die "adb not found in PATH"
    [[ "$(adb devices | grep -c 'device$')" -ge 1 ]] || die "No ADB device connected"
}

do_build() {
    echo "==> Building..."
    local java_home="${JAVA_HOME:-}"
    if [[ -z "$java_home" ]] && command -v /usr/libexec/java_home >/dev/null 2>&1; then
        # macOS: pin to 21 even if a newer JDK is the system default — AGP's
        # JdkImageTransform fails partway through jlink on JDK 26.
        java_home=$(/usr/libexec/java_home -v 21 2>/dev/null) || true
    fi
    [[ -n "$java_home" ]] || die "JAVA_HOME not set and JDK 21 not auto-detected. Export JAVA_HOME to a JDK 21 install."
    [[ -n "${ANDROID_HOME:-}" ]] || die "ANDROID_HOME not set."

    JAVA_HOME="$java_home" ANDROID_HOME="$ANDROID_HOME" gradle assembleDebug
}

do_install() {
    [[ -f "$APK" ]] || die "No APK at $APK — build first (deploy.sh --build)."
    echo "==> Installing $APK..."
    adb install -r "$APK"
}

do_register_accessibility() {
    echo "==> Registering accessibility service (preserving any others already enabled)..."
    local cur
    cur=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
    case "$cur" in
        *"$SVC"*)  ;;  # already registered — leave the list untouched
        ""|"null") adb shell settings put secure enabled_accessibility_services "$SVC" ;;
        *)         adb shell settings put secure enabled_accessibility_services "$cur:$SVC" ;;
    esac
    adb shell settings put secure accessibility_enabled 1
}

do_launch() {
    echo "==> Launching $PKG..."
    adb shell am start -n "$PKG/$PKG.MainActivity" >/dev/null
}

# --- main ---

build=0
launch=1
for arg in "$@"; do
    case "$arg" in
        --build)     build=1 ;;
        --no-launch) launch=0 ;;
        -h|--help)
            echo "Usage: deploy.sh [--build] [--no-launch]"
            exit 0
            ;;
        *) die "Unknown argument: $arg (see --help)" ;;
    esac
done

require_adb
[[ "$build" -eq 1 ]] && do_build
do_install
do_register_accessibility
[[ "$launch" -eq 1 ]] && do_launch

echo "Done. Tap 'Show Floating Widget' in the app to bring up the widget."
