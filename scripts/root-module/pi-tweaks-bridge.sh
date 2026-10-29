#!/system/bin/sh
# Offline preparation for the exact PI evidence of 2026-10-28. No injection/reboot.
set -eu
umask 077
ROOT=/data/local/voyahplay-tweaks-bounds-test
OWNER=voyahplay-tweaks-bounds-test-v1
CURRENT=/data/local/tmp/ss11.bin
# Stage inside private root storage, on the same /data filesystem as CURRENT.
PENDING=$ROOT/ss11.bin.pending
ORIGINAL=8a5e9b9c612dd3ad09ee3218383bbef6c352835f7238011271a92bb3317011e2
PATCHED=575735ed6f6074f1c33483c8df5c8984e51e6dc0fba328b1e4179ca10b9f9d7d
MODE=${1:-status}
INSTALLER_REVISION=1.0.2
fail() { echo "ERROR $*"; exit 1; }
# Android mksh reserves `hash` as a tracked-alias command. Keep this name unique.
voyah_file_sha256() { [ -f "$1" ] && [ ! -L "$1" ] && sha256sum "$1" | awk '{print $1}'; }
private() { [ ! -L "$1" ] && [ "$(stat -c '%u:%a' "$1")" = '0:600' ]; }
backup() {
    [ ! -L "$ROOT" ] && [ "$(stat -c '%u:%a' "$ROOT")" = '0:700' ] || fail backup-directory
    private "$ROOT/owner" && [ "$(cat "$ROOT/owner")" = "$OWNER" ] || fail backup-owner
    private "$ROOT/ss11.bin.original" && [ "$(voyah_file_sha256 "$ROOT/ss11.bin.original")" = "$ORIGINAL" ] || fail backup-checksum
}
check() {
    echo "PI_INSTALLER_REVISION=$INSTALLER_REVISION"
    [ "$(getprop ro.build.version.sdk)" = 30 ] || fail Android-11-required
    services_hash=$(sha256sum /system/framework/services.jar | awk '{print $1}') || fail unreadable-PI-services
    case "$services_hash" in
        483fedea7707833454272f84a64692078996127d64abac63f13863b76246a579)
            expected_ui=5d5f7498d92ee68e927bc0b891daedf15978d079b85a254399112bad98d2dd6b; pi_profile=OTA7.0-build86;;
        262bfd515f5c181a556477cadbdee1e574ead0ef1acff158dc3fa25bd8b84c38)
            expected_ui=b676a3a426ba3b59fabb9d98355ff0e0ac7b07d7ad6545ac44d9cbf85f1700c1; pi_profile=OTA7.2-build35;;
        *) fail unreviewed-PI-services;;
    esac
    [ "$(sha256sum /system/priv-app/SystemUI-release-signed/SystemUI-release-signed.apk | awk '{print $1}')" = "$expected_ui" ] || fail unreviewed-PI-SystemUI
    echo "PI_FIRMWARE_PROFILE=$pi_profile"
    injector_hash=$(voyah_file_sha256 /data/local/tmp/frida-inject) || fail unreadable-injector
    echo "INJECTOR_ACTUAL_SHA256=$injector_hash"
    [ "$injector_hash" = 0d99be0e44d4ca3449056fa0a0b868fca1cf66c8ad704508949945afc07706aa ] || fail unreviewed-injector
    [ ! -e /data/local/voyahplay-fullscreen/enabled ] || fail legacy-fullscreen-enabled
    [ ! -e /data/local/tmp/.voyahplay-ss11-bounds.new ] && [ ! -L /data/local/tmp/.voyahplay-ss11-bounds.new ] || fail legacy-pending-path-exists
    current_hash=$(voyah_file_sha256 "$CURRENT") || fail unreadable-bridge
    case "$current_hash" in "$ORIGINAL"|"$PATCHED") ;; *) fail unreviewed-bridge;; esac
    if [ -e "$ROOT" ] || [ -L "$ROOT" ]; then backup
    else [ "$current_hash" = "$ORIGINAL" ] || fail patched-bridge-without-original-backup; fi
    echo "PI_FIRMWARE_VERIFIED=1"
    echo "CURRENT_BRIDGE_SHA256=$current_hash"
}
clear_lease() {
    settings delete global voyahplay_tweaks_bounds_test >/dev/null || fail lease-clear
    lease=$(settings get global voyahplay_tweaks_bounds_test)
    [ "$lease" = null ] || [ -z "$lease" ] || fail lease-clear-readback
}
replace() {
    source=$1; expected=$2
    [ ! -e "$PENDING" ] && [ ! -L "$PENDING" ] || fail pending-path-exists
    cp -p "$CURRENT" "$PENDING" || fail stage-metadata
    cat "$source" > "$PENDING" && [ "$(voyah_file_sha256 "$PENDING")" = "$expected" ] || fail stage-checksum
    [ "$(voyah_file_sha256 "$CURRENT")" = "$current_hash" ] || fail bridge-changed-during-install
    mv "$PENDING" "$CURRENT" || fail atomic-replace
    [ "$(voyah_file_sha256 "$CURRENT")" = "$expected" ] || fail installed-checksum
    echo REBOOT_REQUIRED=1
}
[ "$(id -u)" = 0 ] || fail root-required
case "$MODE" in check|install|restore|status) ;; *) fail unknown-operation;; esac
check
if [ "$MODE" = check ] || [ "$MODE" = status ]; then
    echo "BOUNDS_PROBE_JSON=$(settings get global voyahplay_tweaks_bounds_probe)"
    exit 0
fi
LOCK=/data/local/voyahplay-tweaks-bounds-test.lock
mkdir "$LOCK" || fail another-install-or-stale-lock
trap 'rmdir "$LOCK"' EXIT
# Repeat after obtaining the exclusive installation lock.
check
if [ "$MODE" = restore ]; then
    backup; clear_lease
    if [ "$current_hash" = "$ORIGINAL" ]; then echo ALREADY_ORIGINAL=1
    else replace "$ROOT/ss11.bin.original" "$ORIGINAL"; echo ORIGINAL_RESTORED=1; fi
    exit 0
fi
upload=${2:-}
case "$upload" in /data/local/tmp/voyah-bounds-*.bin) ;; *) fail unexpected-upload-path;; esac
[ "$(voyah_file_sha256 "$upload")" = "$PATCHED" ] || fail uploaded-patch-checksum
if [ ! -e "$ROOT" ]; then
    mkdir "$ROOT"; chmod 700 "$ROOT"
    printf '%s\n' "$OWNER" > "$ROOT/owner"
    cp -p "$CURRENT" "$ROOT/ss11.bin.original"; chmod 600 "$ROOT/ss11.bin.original"
    backup
fi
clear_lease
if [ "$current_hash" = "$PATCHED" ]; then echo ALREADY_INSTALLED=1
else
    cat /proc/sys/kernel/random/boot_id > "$ROOT/install-boot"
    replace "$upload" "$PATCHED"
    echo PI_BRIDGE_INSTALLED=1
fi
echo "BACKUP=$ROOT/ss11.bin.original"
