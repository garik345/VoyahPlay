#!/system/bin/sh
# Root-only launcher. The boot hook starts this in the background; no injection is used.
set -u
umask 077
ROOT=/data/local/voyahplay-root-helper
CLASS=com.shilapi.xcertplay.voyah.root.VoyahRootHelper
VERSION=1.1.2
MODE=${1:-status}
case "$MODE" in boot|start|status|stop) ;; *) exit 2;; esac
[ "$(id -u)" = 0 ] || exit 1
[ ! -L "$ROOT" ] && [ "$(stat -c '%u:%a' "$ROOT" 2>/dev/null)" = '0:700' ] || exit 1
[ ! -L "$ROOT/owner" ] && [ "$(cat "$ROOT/owner" 2>/dev/null)" = 'voyahplay-root-helper-v1' ] || exit 1
private_file() { [ ! -L "$1" ] && [ "$(stat -c '%u:%a' "$1" 2>/dev/null)" = '0:600' ]; }
number() { case "$1" in ''|*[!0-9]*) return 1;; *) return 0;; esac; }
identity() { [ -r "/proc/$1/stat" ] && awk '{sub(/^.*\) /, ""); if ($1 != "Z" && $1 != "X") print $20}' "/proc/$1/stat"; }
boot_id=$(cat /proc/sys/kernel/random/boot_id) || exit 1
case "$boot_id" in ''|*[!a-fA-F0-9-]*) exit 1;; esac
record() {
    [ "$MODE" = boot ] || return 0
    printf 'BOOT_ID=%s\nBOOT_RESULT=%s\nBOOT_HELPER_VERSION=%s\n' "$boot_id" "$1" "$VERSION" > "$ROOT/boot-report.txt"
}
enabled() { private_file "$ROOT/autostart.conf" && [ "$(cat "$ROOT/autostart.conf")" = 1 ]; }
fail() { record "error:$1"; printf 'ERROR %s\n' "$1"; exit 1; }
probe() { CLASSPATH="$ROOT/app.apk" /system/bin/app_process /system/bin "$CLASS" --probe "$uid"; }
if [ "$MODE" = boot ]; then
    enabled || { record disabled; exit 0; }
    record waiting-for-android
    ready=0
    for step in $(seq 1 90); do
        enabled || { record disabled; exit 0; }
        if [ "$(getprop sys.boot_completed)" = 1 ]; then
            entry=$(cmd package list packages --user 0 -U com.shilapi.xcertplay 2>/dev/null)
            case "$entry" in *'package:com.shilapi.xcertplay uid:'*) ready=1; break;; esac
        fi
        sleep 2
    done
    [ "$ready" = 1 ] || fail android-not-ready
fi
private_file "$ROOT/app.apk" && private_file "$ROOT/allowed.uid" && private_file "$ROOT/apk.sha256" || fail snapshot-permissions
uid=$(cat "$ROOT/allowed.uid")
number "$uid" && [ "$uid" -ge 10000 ] && [ "$uid" -lt 20000 ] || fail invalid-snapshot-uid
if [ "$MODE" = status ]; then probe; exit $?; fi
if [ "$MODE" = stop ]; then
    CLASSPATH="$ROOT/app.apk" /system/bin/app_process /system/bin "$CLASS" --stop "$uid"
    exit $?
fi
entry=$(cmd package list packages --user 0 -U com.shilapi.xcertplay) || fail package-manager
current_uid=$(printf '%s\n' "$entry" | sed -n 's/^package:com\.shilapi\.xcertplay[[:space:]]*uid:\([0-9]*\)[[:space:]]*$/\1/p')
[ "$current_uid" = "$uid" ] || fail snapshot-uid-changed
sha256sum -c "$ROOT/apk.sha256" >/dev/null 2>&1 || fail snapshot-checksum
if ! mkdir "$ROOT/setup.lock" 2>/dev/null; then
    [ -r "$ROOT/setup.lock/identity" ] || fail setup-lock-incomplete
    read lock_boot lock_pid lock_start < "$ROOT/setup.lock/identity"
    if number "$lock_boot"; then lock_start=$lock_pid; lock_pid=$lock_boot; lock_boot=$boot_id; fi
    number "$lock_pid" && number "$lock_start" || fail invalid-setup-lock
    if [ "$lock_boot" = "$boot_id" ] && number "$lock_pid" && [ "$(identity "$lock_pid" 2>/dev/null)" = "$lock_start" ]; then
        record setup-already-running
        printf 'HELPER_SETUP_RUNNING=1\n'
        exit 0
    fi
    rm "$ROOT/setup.lock/identity" && rmdir "$ROOT/setup.lock" && mkdir "$ROOT/setup.lock" || fail stale-setup-lock
fi
printf '%s %s %s\n' "$boot_id" "$$" "$(identity $$)" > "$ROOT/setup.lock/identity"
cleanup() { rm -f "$ROOT/setup.lock/identity"; rmdir "$ROOT/setup.lock" 2>/dev/null; }
trap cleanup EXIT
code=0
answer=$(probe 2>&1) || code=$?
if [ "$code" = 0 ]; then
    printf '%s\n' "$answer" | grep -q '^HELPER_VERSION=1\.1\.2$' || fail helper-version-differs
    record already-running
    printf '%s\n' "$answer"
    exit 0
fi
[ "$code" = 2 ] || fail helper-probe-ambiguous
if [ -r "$ROOT/server.pid" ]; then
    private_file "$ROOT/server.pid" || fail helper-process-record-permissions
    read helper_boot helper_pid helper_start < "$ROOT/server.pid"
    if number "$helper_boot"; then helper_start=$helper_pid; helper_pid=$helper_boot; helper_boot=$boot_id; fi
    number "$helper_pid" && number "$helper_start" || fail invalid-helper-process-record
    if [ "$helper_boot" = "$boot_id" ] && [ "$(identity "$helper_pid" 2>/dev/null)" = "$helper_start" ]; then
        fail helper-process-not-answering
    fi
fi
CLASSPATH="$ROOT/app.apk" nohup /system/bin/app_process /system/bin --nice-name=voyahplay-root-helper "$CLASS" --serve "$uid" </dev/null >> "$ROOT/server.log" 2>&1 &
for step in $(seq 1 12); do
    sleep 1
    code=0
    answer=$(probe 2>&1) || code=$?
    if [ "$code" = 0 ]; then
        record ready
        printf '%s\n' "$answer"
        exit 0
    fi
    [ "$code" = 2 ] || fail helper-probe-ambiguous
done
fail helper-start-timeout
