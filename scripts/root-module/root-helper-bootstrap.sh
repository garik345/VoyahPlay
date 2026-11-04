#!/system/bin/sh
# One-time root installation. SIGTERM is limited to this helper's verified old process.
set -eu
umask 077
ROOT=/data/local/voyahplay-root-helper
OWNER=voyahplay-root-helper-v1
CLASS=com.shilapi.xcertplay.voyah.root.VoyahRootHelper
MODE=${1:-status}
QUIESCE_UPLOAD=${2:-}
QUIESCE_SHA=${3:-}
QUIESCE_STAGED=
locked=0
fail() { echo "ERROR $*"; exit 1; }
number() { case "$1" in ''|*[!0-9]*) return 1;; *) return 0;; esac; }
identity() { [ -r "/proc/$1/stat" ] && awk '{sub(/^.*\) /, ""); if ($1 != "Z" && $1 != "X") print $20}' "/proc/$1/stat"; }
process_args() { timeout 2 dd "if=/proc/$1/cmdline" bs=4096 count=1 2>/dev/null | tr '\000' '\n'; }
run_apk() { CLASSPATH="$1" /system/bin/app_process /system/bin "$CLASS" "$2" "$3"; }
stop_owned_helper() {
    for stop_attempt in $(seq 1 12); do
        stop_code=0
        stop_result=$(run_apk "$ROOT/app.apk" --stop "$old_uid" 2>&1) || stop_code=$?
        printf '%s\n' "$stop_result"
        [ "$stop_code" = 0 ] && return 0
        # A foreground lease may still be finishing after the top agent detaches.
        printf '%s\n' "$stop_result" | grep -q '^HELPER_BUSY:' || return 1
        sleep 1
    done
    return 1
}
helper_live() { [ "$helper_boot" = "$boot_id" ] && [ "$(identity "$helper_pid" || true)" = "$helper_start" ]; }
verify_helper_target() {
    helper_live || return 1
    [ "$helper_pid" -gt 1 ] || fail 'invalid old helper PID'
    helper_uid=$(awk '/^Uid:/ { print $2 " " $3 " " $4 " " $5 }' "/proc/$helper_pid/status")
    helper_name=$(process_args "$helper_pid" | head -n 1)
    [ "$helper_uid" = '0 0 0 0' ] && [ "$helper_name" = voyahplay-root-helper ] || fail 'old PID does not identify the owned root helper; no signal sent'
    helper_live
}
radio_lock_diagnostics() {
    # Evidence only. Do not delete an incomplete lock or signal its owner.
    radio_lock=$ROOT/radio-runtime.lock
    echo 'RADIO_LOCK_DIAGNOSTICS=1'
    echo "RADIO_LOCK_BOOT=$boot_id"
    if [ ! -e "$radio_lock" ] && [ ! -L "$radio_lock" ]; then echo RADIO_LOCK_MISSING=1; return; fi
    if [ -L "$radio_lock" ]; then echo RADIO_LOCK_SYMLINK=1; return; fi
    [ -d "$radio_lock" ] || { echo RADIO_LOCK_NOT_DIRECTORY=1; return; }
    printf 'RADIO_LOCK_METADATA='; stat -c '%u:%a' "$radio_lock" 2>/dev/null || true
    set -- "$radio_lock"/owner-*
    if [ "$#" = 1 ] && [ ! -e "$1" ] && [ ! -L "$1" ]; then echo RADIO_LOCK_OWNER_COUNT=0; return; fi
    echo "RADIO_LOCK_OWNER_COUNT=$#"
    diagnostic_count=0
    for radio_owner in "$@"; do
        [ -e "$radio_owner" ] || continue
        diagnostic_count=$((diagnostic_count + 1)); [ "$diagnostic_count" -le 4 ] || break
        [ -f "$radio_owner" ] && [ ! -L "$radio_owner" ] &&
            [ "$(stat -c '%u:%a' "$radio_owner" 2>/dev/null)" = '0:600' ] || continue
        radio_owner_size=$(stat -c '%s' "$radio_owner" 2>/dev/null)
        number "$radio_owner_size" && [ "$radio_owner_size" -le 512 ] || continue
        printf 'RADIO_LOCK_OWNER='; head -c 512 "$radio_owner"; echo
        read radio_boot radio_pid radio_start < "$radio_owner" || continue
        number "$radio_pid" && number "$radio_start" || continue
        radio_actual=$(identity "$radio_pid" || true)
        echo "RADIO_LOCK_ACTUAL_START=$radio_actual"
        if [ "$radio_boot" = "$boot_id" ] && [ "$radio_actual" = "$radio_start" ]; then
            echo RADIO_LOCK_OWNER_ALIVE=1
            printf 'RADIO_LOCK_OWNER_UID='; awk '/^Uid:/ { print $2 " " $3 " " $4 " " $5 }' "/proc/$radio_pid/status" 2>/dev/null || true
            echo 'RADIO_LOCK_OWNER_ARGS_BEGIN'
            process_args "$radio_pid" | head -n 8
            echo 'RADIO_LOCK_OWNER_ARGS_END'
        else echo RADIO_LOCK_OWNER_ALIVE=0; fi
    done
}
quiesce_owned_runtime() {
    quiesce_attempt=0
    while :; do
        quiesce_attempt=$((quiesce_attempt + 1))
        quiesce_code=0
        quiesce_output=$(sh "${QUIESCE_STAGED:-$ROOT/runtime.sh}" quiesce 2>&1) || quiesce_code=$?
        printf '%s\n' "$quiesce_output"
        [ "$quiesce_code" = 0 ] && return 0
        # stop_watch may leave its already-running radio child finishing a bounded operation.
        # Retry only this explicit transient result; all permission/restore/identity errors stop.
        if [ "$quiesce_code" = 1 ] &&
                printf '%s\n' "$quiesce_output" | grep -Fxq RADIO_LABELS_BUSY=1 &&
                printf '%s\n' "$quiesce_output" | grep -Fxq 'ERROR radio-agent-stop'; then
            if [ "$quiesce_attempt" -lt 8 ]; then
                echo "HELPER_QUIESCE_WAIT=radio-busy ATTEMPT=$quiesce_attempt"
                sleep 1
                continue
            fi
            radio_lock_diagnostics
            echo 'HELPER_QUIESCE_REPAIR=Reboot multimedia normally, then retry INSTALL.cmd; if repeated, send this report.'
        fi
        return "$quiesce_code"
    done
}
[ "$(id -u)" = 0 ] || fail 'launch with adb shell su 0 sh'
[ "$(getprop ro.build.version.sdk)" = 30 ] || fail 'Android 11 required'
case "$MODE" in install|start|status|stop|enableautostart|disableautostart|removeautostart) ;; *) fail 'unknown bootstrap mode';; esac
if [ ! -d "$ROOT" ]; then
    [ ! -e "$ROOT" ] && [ ! -L "$ROOT" ] || fail 'unexpected helper destination'
    [ "$MODE" = install ] || { echo HELPER_NOT_INSTALLED; exit 2; }
    mkdir "$ROOT"; chmod 700 "$ROOT"
    printf '%s\n' "$OWNER" > "$ROOT/owner"; chmod 600 "$ROOT/owner"
fi
[ ! -L "$ROOT" ] && [ "$(stat -c '%u:%a' "$ROOT")" = '0:700' ] || fail 'helper directory is not private root-owned storage'
[ ! -L "$ROOT/owner" ] && [ "$(cat "$ROOT/owner")" = "$OWNER" ] || fail 'unknown helper directory owner'
entry=$(cmd package list packages --user 0 -U com.shilapi.xcertplay)
uid=$(printf '%s\n' "$entry" | sed -n 's/^package:com\.shilapi\.xcertplay[[:space:]]*uid:\([0-9]*\)[[:space:]]*$/\1/p')
old_uid=$(cat "$ROOT/allowed.uid" 2>/dev/null || true)
if ! number "$uid" || [ "$uid" -lt 10000 ] || [ "$uid" -ge 20000 ]; then
    case "$MODE" in
        status|stop) number "$old_uid" || fail 'cannot identify installed helper UID'; uid=$old_uid; echo APP_ABSENT=1;;
        *) fail 'cannot identify primary-user VoyahPlay UID';;
    esac
fi
echo "APP_UID=$uid"
echo "HELPER_SNAPSHOT_UID=${old_uid:-none}"
echo EXPECTED_HELPER_VERSION=1.2.16
if [ "$MODE" = status ]; then
    [ -f "$ROOT/app.apk" ] && number "$old_uid" || { echo HELPER_NOT_INSTALLED; exit 2; }
    if [ "$uid" != "$old_uid" ]; then
        echo HELPER_UID_MATCH=0
        echo "HELPER_REPAIR=Run root-helper.ps1 -Mode Install after installing the current APK"
    else
        echo HELPER_UID_MATCH=1
    fi
    run_apk "$ROOT/app.apk" --probe "$old_uid"
    exit $?
fi
if [ "$MODE" = start ]; then
    [ -f "$ROOT/runtime.sh" ] || fail 'install helper 1.2.16 first'
    sh "$ROOT/runtime.sh" start
    exit $?
fi
boot_id=$(cat /proc/sys/kernel/random/boot_id) || fail 'boot identity unavailable'
if ! mkdir "$ROOT/setup.lock" 2>/dev/null; then
    [ -r "$ROOT/setup.lock/identity" ] || fail 'setup lock has no identity; inspect before retrying'
    read lock_boot lock_pid lock_start < "$ROOT/setup.lock/identity"
    # Older helpers stored only PID/start. Respect a live old installer too.
    if number "$lock_boot"; then lock_start=$lock_pid; lock_pid=$lock_boot; lock_boot=$boot_id; fi
    number "$lock_pid" && number "$lock_start" || fail 'invalid setup lock identity'
    if [ "$lock_boot" = "$boot_id" ] && number "$lock_pid" && [ "$(identity "$lock_pid" || true)" = "$lock_start" ]; then
        fail 'another helper setup is running'
    fi
    rm "$ROOT/setup.lock/identity"; rmdir "$ROOT/setup.lock"; mkdir "$ROOT/setup.lock"
fi
locked=1
printf '%s %s %s\n' "$boot_id" "$$" "$(identity $$)" > "$ROOT/setup.lock/identity"
cleanup() {
    rm -f "$ROOT/app.apk.new"
    [ -z "$QUIESCE_STAGED" ] || rm -f "$QUIESCE_STAGED"
    if [ "$locked" = 1 ]; then rm -f "$ROOT/setup.lock/identity"; rmdir "$ROOT/setup.lock"; fi
}
trap cleanup EXIT
if [ -n "$QUIESCE_UPLOAD" ]; then
    case "$MODE" in install|stop) ;; *) fail 'shutdown payload is only accepted for install/stop';; esac
    case "$QUIESCE_UPLOAD" in /data/local/tmp/voyahplay-helper-*-quiesce.sh) ;; *) fail 'invalid shutdown upload path';; esac
    case "$QUIESCE_SHA" in ''|*[!a-f0-9]*) fail 'invalid shutdown checksum';; esac
    [ "${#QUIESCE_SHA}" = 64 ] && [ -f "$QUIESCE_UPLOAD" ] && [ ! -L "$QUIESCE_UPLOAD" ] || fail 'invalid shutdown upload'
    shutdown_target=$ROOT/quiesce-runtime.install-$$.sh
    [ ! -e "$shutdown_target" ] && [ ! -L "$shutdown_target" ] || fail 'shutdown staging already exists'
    (set -C; cat "$QUIESCE_UPLOAD" > "$shutdown_target") || fail 'cannot stage shutdown payload'
    QUIESCE_STAGED=$shutdown_target
    chmod 600 "$QUIESCE_STAGED"
    [ "$(sha256sum "$QUIESCE_STAGED" | awk '{print $1}')" = "$QUIESCE_SHA" ] || fail 'shutdown checksum mismatch'
    echo HELPER_QUIESCE_SOURCE=installer
fi
if [ "$MODE" = install ]; then
    paths=$(pm path --user 0 com.shilapi.xcertplay)
    apk=$(printf '%s\n' "$paths" | sed -n 's/^package://p' | grep '/base.apk$' | head -n 1)
    if [ -z "$apk" ]; then apk=$(printf '%s\n' "$paths" | sed -n 's/^package://p' | head -n 1); fi
    case "$apk" in /data/app/*.apk|/system/app/*.apk|/system/priv-app/*.apk) ;; *) fail 'unexpected installed APK path';; esac
    [ -f "$apk" ] && [ ! -L "$ROOT/app.apk.new" ] || fail 'invalid installed/staging APK path'
    cp "$apk" "$ROOT/app.apk.new"; chmod 600 "$ROOT/app.apk.new"
    candidate=$(run_apk "$ROOT/app.apk.new" --check "$uid") || fail 'rebuild and install automotive 1.2.16 first'
    printf '%s\n' "$candidate"
    printf '%s\n' "$candidate" | grep -Fxq 'HELPER_VERSION=1.2.16' || fail 'APK helper version differs; rebuild and install automotive 1.2.16 before updating the helper'
    run_apk "$ROOT/app.apk.new" --retire-legacy "$uid" || fail 'old payload retirement incomplete; read the reason above'
fi
if [ "$MODE" = install ] || [ "$MODE" = stop ]; then
    if [ -f "$ROOT/runtime.sh" ] && grep -q '^VERSION=1\.2\.' "$ROOT/runtime.sh"; then
        [ ! -L "$ROOT/runtime.sh" ] && [ "$(stat -c '%u:%a' "$ROOT/runtime.sh")" = '0:600' ] || fail 'untrusted runtime'
        quiesce_owned_runtime || fail 'supervisor/top-edge agent did not pause; snapshot and UID were not replaced'
    fi
    helper_record_loaded=0
    helper_owned_seen=0
    probe_ambiguous=0
    if [ -e "$ROOT/server.pid" ] || [ -L "$ROOT/server.pid" ]; then
        [ -r "$ROOT/server.pid" ] || fail 'helper process record is unreadable'
        [ ! -L "$ROOT/server.pid" ] && [ "$(stat -c '%u:%a' "$ROOT/server.pid")" = '0:600' ] || fail 'helper process record is not private root-owned storage'
        helper_record=$(cat "$ROOT/server.pid")
        read helper_boot helper_pid helper_start < "$ROOT/server.pid"
        if number "$helper_boot"; then helper_start=$helper_pid; helper_pid=$helper_boot; helper_boot=$boot_id; fi
        number "$helper_pid" && number "$helper_start" || fail 'invalid helper process record'
        helper_record_loaded=1
        if helper_live && verify_helper_target; then helper_owned_seen=1; fi
    fi
    if [ -f "$ROOT/app.apk" ] && number "$old_uid"; then
        code=0
        probe_output=$(run_apk "$ROOT/app.apk" --probe "$old_uid" 2>&1) || code=$?
        printf '%s\n' "$probe_output"
        if [ "$code" = 0 ]; then stop_owned_helper || fail 'helper refused shutdown'
        elif [ "$code" = 1 ] && printf '%s\n' "$probe_output" | grep -q '^IOException: Helper is stopping$'; then
            echo HELPER_STOP_ALREADY_ACCEPTED=1
        elif [ "$code" = 1 ] && [ "$helper_owned_seen" = 1 ]; then
            # A probe may itself wake a previously stopped accept and lose its reply on exit.
            # Continue only if that exact owned process actually exits; never signal this ambiguity.
            probe_ambiguous=1
        elif [ "$code" != 2 ]; then fail 'helper state is ambiguous'; fi
    fi
    if [ "$helper_record_loaded" = 1 ]; then
        if helper_live; then
            # A read-only connection wakes accept() in versions that closed its FD from another thread.
            wake_code=0
            run_apk "$ROOT/app.apk" --probe "$old_uid" >/dev/null 2>&1 || wake_code=$?
            if [ "$probe_ambiguous" = 1 ] && [ "$wake_code" = 2 ]; then
                probe_ambiguous=0
                echo HELPER_STOP_TRANSPORT_CLOSED=1
            fi
        fi
        count=0
        while helper_live && [ "$count" -lt 10 ]; do sleep 1; count=$((count+1)); done
        [ "$probe_ambiguous" != 1 ] || ! helper_live || fail 'owned helper probe is ambiguous and its process is still alive; no signal sent'
        if helper_live && verify_helper_target; then
            # Clear only our geometry lease before terminating our unresponsive old VM.
            /system/bin/settings delete global voyahplay_tweaks_bounds_test >/dev/null || fail 'cannot clear the owned helper lease'
            [ "$(/system/bin/settings get global voyahplay_tweaks_bounds_test)" = null ] || fail 'owned helper lease is still present; no signal sent'
            if [ -e "$ROOT/server.pid" ]; then
                [ ! -L "$ROOT/server.pid" ] && [ "$(cat "$ROOT/server.pid")" = "$helper_record" ] || fail 'helper process record changed; no signal sent'
            fi
            if helper_live && verify_helper_target; then
                echo "HELPER_STOP_METHOD=SIGTERM PID=$helper_pid"
                /system/bin/kill -TERM "$helper_pid" || { helper_live && fail 'owned helper SIGTERM failed'; true; }
            fi
            count=0
            while helper_live && [ "$count" -lt 5 ]; do sleep 1; count=$((count+1)); done
        fi
        helper_live && fail 'owned helper still alive; snapshot was not replaced'
        rm -f "$ROOT/server.pid"
    fi
fi
if [ "$MODE" = stop ]; then echo HELPER_STOPPED=1; exit 0; fi
if [ "$MODE" != install ]; then
    [ -f "$ROOT/app.apk" ] && [ "$old_uid" = "$uid" ] || fail 'snapshot/UID differs; run -Mode Install'
    case "$MODE" in
        enableautostart) run_apk "$ROOT/app.apk" --autostart-on "$uid";;
        disableautostart) run_apk "$ROOT/app.apk" --autostart-off "$uid";;
        removeautostart) run_apk "$ROOT/app.apk" --remove-autostart "$uid";;
    esac
    exit $?
fi
mv "$ROOT/app.apk.new" "$ROOT/app.apk"
printf '%s\n' "$uid" > "$ROOT/allowed.uid"; chmod 600 "$ROOT/allowed.uid"
sha256sum "$ROOT/app.apk" > "$ROOT/apk.sha256"; chmod 600 "$ROOT/apk.sha256"
boot_code=0
run_apk "$ROOT/app.apk" --install-autostart "$uid" || boot_code=$?
rm "$ROOT/setup.lock/identity"; rmdir "$ROOT/setup.lock"; locked=0
[ -f "$ROOT/runtime.sh" ] || fail 'runtime asset missing from APK'
sh "$ROOT/runtime.sh" start || fail 'helper did not start'
[ "$boot_code" = 0 ] || fail 'helper started, but autostart setup is incomplete; send this report'
echo 'DONE helper ready; autostart installed; legacy payload removed'
