#!/system/bin/sh
# Root-only launcher and supervisor for the helper and the scoped top-edge bridge.
set -u
umask 077
ROOT=/data/local/voyahplay-root-helper
CLASS=com.shilapi.xcertplay.voyah.root.VoyahRootHelper
VERSION=1.2.1
MODE=${1:-status}
case "$MODE" in boot|start|status|stop|watch|quiesce) ;; *) exit 2;; esac
[ "$(id -u)" = 0 ] || exit 1
[ ! -L "$ROOT" ] && [ "$(stat -c '%u:%a' "$ROOT" 2>/dev/null)" = '0:700' ] || exit 1
[ ! -L "$ROOT/owner" ] && [ "$(cat "$ROOT/owner" 2>/dev/null)" = 'voyahplay-root-helper-v1' ] || exit 1
private_file() { [ ! -L "$1" ] && [ "$(stat -c '%u:%a' "$1" 2>/dev/null)" = '0:600' ]; }
number() { case "$1" in ''|*[!0-9]*) return 1;; *) return 0;; esac; }
identity() { [ -r "/proc/$1/stat" ] && awk '{sub(/^.*\) /, ""); if ($1 != "Z" && $1 != "X") print $20}' "/proc/$1/stat"; }
# Bound reads: this firmware's tr can spin on /proc/PID/cmdline when PID exits mid-read.
process_args() { timeout 2 dd "if=/proc/$1/cmdline" bs=4096 count=1 2>/dev/null | tr '\000' '\n'; }
boot_id=$(cat /proc/sys/kernel/random/boot_id) || exit 1
case "$boot_id" in ''|*[!a-fA-F0-9-]*) exit 1;; esac
record() {
    [ "$MODE" = boot ] || return 0
    printf 'BOOT_ID=%s\nBOOT_RESULT=%s\nBOOT_HELPER_VERSION=%s\n' "$boot_id" "$1" "$VERSION" > "$ROOT/boot-report.txt"
}
enabled() { private_file "$ROOT/autostart.conf" && [ "$(cat "$ROOT/autostart.conf")" = 1 ]; }
fail() { record "error:$1"; printf 'ERROR %s\n' "$1"; exit 1; }
probe() { CLASSPATH="$ROOT/app.apk" /system/bin/app_process /system/bin "$CLASS" --probe "$uid"; }
run_check() { CLASSPATH="$ROOT/app.apk" /system/bin/app_process /system/bin "$CLASS" --check "$1"; }
server_alive() {
    private_file "$ROOT/server.pid" || return 1
    read server_boot server_pid server_start < "$ROOT/server.pid"
    [ "$server_boot" = "$boot_id" ] && number "$server_pid" && number "$server_start" || return 1
    [ "$(identity "$server_pid" 2>/dev/null)" = "$server_start" ] || return 1
    [ "$(awk '/^Uid:/ { print $2 " " $3 " " $4 " " $5 }' "/proc/$server_pid/status")" = '0 0 0 0' ] &&
        [ "$(process_args "$server_pid" | head -n 1)" = voyahplay-root-helper ]
}
agent_paths() {
    case "${1:-top}" in
        top) agent_kind=top; agent_script=top-bounds.js; agent_hash=top.sha256; agent_probe=voyahplay_top_bounds_probe;;
        status) agent_kind=status; agent_script=status-bar.js; agent_hash=status.sha256; agent_probe=voyahplay_status_bar_probe;;
        *) return 1;;
    esac
    agent_record=$ROOT/$agent_kind-agent.pid
}
agent_alive() {
    agent_paths "${1:-top}" || return 1
    private_file "$agent_record" || return 1
    read agent_boot agent_pid agent_start agent_system < "$agent_record"
    [ "$agent_boot" = "$boot_id" ] && number "$agent_pid" && number "$agent_start" && number "$agent_system" || return 1
    [ "$(identity "$agent_pid" 2>/dev/null)" = "$agent_start" ] || return 1
    [ "$(awk '/^Uid:/ { print $2 " " $3 " " $4 " " $5 }' "/proc/$agent_pid/status")" = '0 0 0 0' ] || return 1
    agent_args=$(process_args "$agent_pid")
    [ "$(printf '%s\n' "$agent_args" | head -n 1)" = /data/local/tmp/frida-inject ] &&
        printf '%s\n' "$agent_args" | grep -Fxq "$ROOT/$agent_script"
}
stop_agent() {
    agent_paths "${1:-top}" || return 1
    if agent_alive "$agent_kind"; then
        # Only the root-owned child recorded for this exact private script is signalled.
        [ "$(identity "$agent_pid" 2>/dev/null)" = "$agent_start" ] || return 1
        kill -TERM "$agent_pid" || return 1
        for agent_wait in $(seq 1 8); do
            [ "$(identity "$agent_pid" 2>/dev/null)" = "$agent_start" ] || break
            sleep 1
        done
        [ "$(identity "$agent_pid" 2>/dev/null)" = "$agent_start" ] && return 1
    fi
    rm -f "$agent_record"
    if [ "$agent_kind" = top ]; then /system/bin/settings delete global voyahplay_top_bounds_lease >/dev/null; fi
    /system/bin/settings delete global "$agent_probe" >/dev/null
}
stop_watch() {
    if private_file "$ROOT/supervisor.lock/identity"; then
        read watch_boot watch_pid watch_start < "$ROOT/supervisor.lock/identity"
        if [ "$watch_boot" = "$boot_id" ] && number "$watch_pid" && number "$watch_start" &&
                [ "$(identity "$watch_pid" 2>/dev/null)" = "$watch_start" ]; then
            [ "$(awk '/^Uid:/ { print $2 " " $3 " " $4 " " $5 }' "/proc/$watch_pid/status")" = '0 0 0 0' ] || return 1
            process_args "$watch_pid" | grep -Fxq "$ROOT/runtime.sh" || return 1
            [ "$(identity "$watch_pid" 2>/dev/null)" = "$watch_start" ] || return 1
            kill -TERM "$watch_pid" || return 1
            for watch_wait in $(seq 1 8); do
                [ "$(identity "$watch_pid" 2>/dev/null)" = "$watch_start" ] || return 0
                sleep 1
            done
            return 1
        fi
    fi
}
start_agent() {
    agent_paths "${1:-top}" || return 1
    private_file "$ROOT/$agent_script" && private_file "$ROOT/$agent_hash" || return 1
    sha256sum -c "$ROOT/$agent_hash" >/dev/null 2>&1 || return 1
    if [ "$agent_kind" = top ]; then target_name=system_server; else target_name=com.qinggan.systemui; fi
    system_pid=$(pidof "$target_name")
    number "$system_pid" || return 1
    [ "$(awk '/^Uid:/ { print $2 }' "/proc/$system_pid/status")" = 1000 ] &&
        [ "$(process_args "$system_pid" | head -n 1)" = "$target_name" ] || return 1
    if agent_alive "$agent_kind"; then
        [ "$agent_system" = "$system_pid" ] && return 0
        stop_agent "$agent_kind" || return 1
    fi
    if [ "$agent_kind" = status ]; then
        actual_ui=$(sha256sum /system/priv-app/SystemUI-release-signed/SystemUI-release-signed.apk | awk '{print $1}')
        [ "$actual_ui" = 28f5d93eaec22b755b339193a733bd0d4160c496cf301bcd2fcfb88c36ade162 ] || return 1
    fi
    actual_inject=$(sha256sum /data/local/tmp/frida-inject | awk '{print $1}')
    [ "$actual_inject" = 0d99be0e44d4ca3449056fa0a0b868fca1cf66c8ad704508949945afc07706aa ] || return 1
    nohup env FRIDA_NO_ETERNALIZE=1 FRIDA_SKIP_SELINUX_PATCH=1 /data/local/tmp/frida-inject \
        -p "$system_pid" -s "$ROOT/$agent_script" </dev/null >> "$ROOT/$agent_kind-agent.log" 2>&1 &
    new_agent=$!
    new_start=$(identity "$new_agent")
    number "$new_agent" && number "$new_start" || return 1
    [ ! -L "$agent_record" ] || return 1
    printf '%s %s %s %s\n' "$boot_id" "$new_agent" "$new_start" "$system_pid" > "$agent_record"
    chmod 600 "$agent_record"
    echo "${agent_kind}_AGENT_PID=$new_agent"
}
start_watch() {
    if private_file "$ROOT/supervisor.lock/identity"; then
        read watch_boot watch_pid watch_start < "$ROOT/supervisor.lock/identity"
        if [ "$watch_boot" = "$boot_id" ] && number "$watch_pid" && number "$watch_start" &&
                [ "$(identity "$watch_pid" 2>/dev/null)" = "$watch_start" ]; then return 0; fi
    fi
    nohup /system/bin/sh "$ROOT/runtime.sh" watch </dev/null >> "$ROOT/supervisor.log" 2>&1 &
}
complete() {
    start_agent || echo TOP_AGENT_START_FAILED=1
    start_agent status || echo STATUS_AGENT_START_FAILED=1
    start_watch
}
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
if [ "$MODE" = quiesce ] || [ "$MODE" = stop ]; then
    [ ! -L "$ROOT/supervisor.paused" ] || fail supervisor-pause-link
    printf '1\n' > "$ROOT/supervisor.paused"; chmod 600 "$ROOT/supervisor.paused"
    stop_watch || fail supervisor-stop
    stop_agent status || fail status-agent-stop
    stop_agent || fail top-agent-stop
    [ "$MODE" = quiesce ] && exit 0
    CLASSPATH="$ROOT/app.apk" /system/bin/app_process /system/bin "$CLASS" --stop "$uid"
    exit $?
fi
entry=$(cmd package list packages --user 0 -U com.shilapi.xcertplay) || fail package-manager
current_uid=$(printf '%s\n' "$entry" | sed -n 's/^package:com\.shilapi\.xcertplay[[:space:]]*uid:\([0-9]*\)[[:space:]]*$/\1/p')
sha256sum -c "$ROOT/apk.sha256" >/dev/null 2>&1 || fail snapshot-checksum
if [ "$MODE" = watch ]; then
    if ! mkdir "$ROOT/supervisor.lock" 2>/dev/null; then
        private_file "$ROOT/supervisor.lock/identity" || fail supervisor-lock-incomplete
        read watch_boot watch_pid watch_start < "$ROOT/supervisor.lock/identity"
        if [ "$watch_boot" = "$boot_id" ] && number "$watch_pid" && number "$watch_start" &&
                [ "$(identity "$watch_pid" 2>/dev/null)" = "$watch_start" ]; then exit 0; fi
        rm "$ROOT/supervisor.lock/identity" && rmdir "$ROOT/supervisor.lock" && mkdir "$ROOT/supervisor.lock" || fail supervisor-lock
    fi
    printf '%s %s %s\n' "$boot_id" "$$" "$(identity $$)" > "$ROOT/supervisor.lock/identity"
    chmod 600 "$ROOT/supervisor.lock/identity"
    trap 'rm -f "$ROOT/supervisor.lock/identity"; rmdir "$ROOT/supervisor.lock" 2>/dev/null' EXIT
    trap 'exit 0' TERM INT
    echo "SUPERVISOR_READY=1 PID=$$"
    while :; do
        if [ ! -e "$ROOT/supervisor.paused" ]; then
            entry=$(cmd package list packages --user 0 -U com.shilapi.xcertplay 2>/dev/null)
            current_uid=$(printf '%s\n' "$entry" | sed -n 's/^package:com\.shilapi\.xcertplay[[:space:]]*uid:\([0-9]*\)[[:space:]]*$/\1/p')
            uid=$(cat "$ROOT/allowed.uid")
            if number "$current_uid" && [ "$current_uid" != "$uid" ]; then
                # No root capability follows a package-name match alone after reinstall.
                if run_check "$current_uid" >/dev/null 2>&1; then
                    old_stop=0
                    CLASSPATH="$ROOT/app.apk" /system/bin/app_process /system/bin "$CLASS" --stop "$uid" >/dev/null 2>&1 || old_stop=$?
                    if [ "$old_stop" = 0 ] || [ "$old_stop" = 2 ]; then
                        for rebind_wait in $(seq 1 10); do server_alive || break; sleep 1; done
                        if ! server_alive; then
                            [ ! -L "$ROOT/allowed.uid.new" ] || fail uid-staging-link
                            printf '%s\n' "$current_uid" > "$ROOT/allowed.uid.new"; chmod 600 "$ROOT/allowed.uid.new"
                            mv "$ROOT/allowed.uid.new" "$ROOT/allowed.uid"
                            uid=$current_uid
                            echo "HELPER_TRUSTED_UID_REBOUND=$uid"
                        fi
                    fi
                else echo HELPER_UID_REBIND_SIGNER_REJECTED=1; fi
            fi
            if [ "$current_uid" = "$uid" ]; then
                if ! server_alive; then /system/bin/sh "$ROOT/runtime.sh" start || echo HELPER_RECOVERY_FAILED=1
                else
                    start_agent || echo TOP_AGENT_RECOVERY_FAILED=1
                    start_agent status || echo STATUS_AGENT_RECOVERY_FAILED=1
                fi
            fi
        fi
        sleep 5
    done
fi
[ ! -L "$ROOT/supervisor.paused" ] || fail supervisor-pause-link
rm -f "$ROOT/supervisor.paused"
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
if [ "$current_uid" != "$uid" ]; then
    number "$current_uid" && run_check "$current_uid" >/dev/null 2>&1 || fail snapshot-uid-signer-changed
    server_alive && fail old-helper-still-running
    [ ! -L "$ROOT/allowed.uid.new" ] || fail uid-staging-link
    printf '%s\n' "$current_uid" > "$ROOT/allowed.uid.new"; chmod 600 "$ROOT/allowed.uid.new"
    mv "$ROOT/allowed.uid.new" "$ROOT/allowed.uid"
    uid=$current_uid
    echo "HELPER_TRUSTED_UID_REBOUND=$uid"
fi
code=0
answer=$(probe 2>&1) || code=$?
if [ "$code" = 0 ]; then
    printf '%s\n' "$answer" | grep -q '^HELPER_VERSION=1\.2\.1$' || fail helper-version-differs
    record already-running
    printf '%s\n' "$answer"
    complete
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
        complete
        exit 0
    fi
    [ "$code" = 2 ] || fail helper-probe-ambiguous
done
fail helper-start-timeout
