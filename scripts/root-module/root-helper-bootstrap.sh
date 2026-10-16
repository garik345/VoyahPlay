#!/system/bin/sh
# One-time root installation. SIGTERM is limited to this helper's verified old process.
set -eu
umask 077
ROOT=/data/local/voyahplay-root-helper
OWNER=voyahplay-root-helper-v1
CLASS=com.shilapi.xcertplay.voyah.root.VoyahRootHelper
MODE=${1:-status}
locked=0
fail() { echo "ERROR $*"; exit 1; }
number() { case "$1" in ''|*[!0-9]*) return 1;; *) return 0;; esac; }
identity() { [ -r "/proc/$1/stat" ] && awk '{sub(/^.*\) /, ""); if ($1 != "Z" && $1 != "X") print $20}' "/proc/$1/stat"; }
run_apk() { CLASSPATH="$1" /system/bin/app_process /system/bin "$CLASS" "$2" "$3"; }
helper_live() { [ "$helper_boot" = "$boot_id" ] && [ "$(identity "$helper_pid" || true)" = "$helper_start" ]; }
verify_helper_target() {
    helper_live || return 1
    [ "$helper_pid" -gt 1 ] || fail 'invalid old helper PID'
    helper_uid=$(awk '/^Uid:/ { print $2 " " $3 " " $4 " " $5 }' "/proc/$helper_pid/status")
    helper_name=$(tr '\000' '\n' < "/proc/$helper_pid/cmdline" | head -n 1)
    [ "$helper_uid" = '0 0 0 0' ] && [ "$helper_name" = voyahplay-root-helper ] || fail 'old PID does not identify the owned root helper; no signal sent'
    helper_live
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
if [ "$MODE" = status ]; then
    [ -f "$ROOT/app.apk" ] && number "$old_uid" || { echo HELPER_NOT_INSTALLED; exit 2; }
    run_apk "$ROOT/app.apk" --probe "$old_uid"
    exit $?
fi
if [ "$MODE" = start ]; then
    [ -f "$ROOT/runtime.sh" ] || fail 'install helper 1.1.2 first'
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
    if [ "$locked" = 1 ]; then rm -f "$ROOT/setup.lock/identity"; rmdir "$ROOT/setup.lock"; fi
}
trap cleanup EXIT
if [ "$MODE" = install ]; then
    paths=$(pm path --user 0 com.shilapi.xcertplay)
    apk=$(printf '%s\n' "$paths" | sed -n 's/^package://p' | grep '/base.apk$' | head -n 1)
    if [ -z "$apk" ]; then apk=$(printf '%s\n' "$paths" | sed -n 's/^package://p' | head -n 1); fi
    case "$apk" in /data/app/*.apk|/system/app/*.apk|/system/priv-app/*.apk) ;; *) fail 'unexpected installed APK path';; esac
    [ -f "$apk" ] && [ ! -L "$ROOT/app.apk.new" ] || fail 'invalid installed/staging APK path'
    cp "$apk" "$ROOT/app.apk.new"; chmod 600 "$ROOT/app.apk.new"
    candidate=$(run_apk "$ROOT/app.apk.new" --check "$uid") || fail 'rebuild and install automotive 1.1.2 first'
    printf '%s\n' "$candidate"
    printf '%s\n' "$candidate" | grep -Fxq 'HELPER_VERSION=1.1.2' || fail 'APK helper version differs; rebuild and install automotive 1.1.2 before updating the helper'
    run_apk "$ROOT/app.apk.new" --retire-legacy "$uid" || fail 'old payload retirement incomplete; read the reason above'
fi
if [ "$MODE" = install ] || [ "$MODE" = stop ]; then
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
        if [ "$code" = 0 ]; then run_apk "$ROOT/app.apk" --stop "$old_uid" || fail 'helper refused shutdown'
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
