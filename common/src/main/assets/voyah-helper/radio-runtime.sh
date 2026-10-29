#!/system/bin/sh
# Optional OEM-radio UI agent. No app launch, APK replacement, tuner commands or CAN access.
set -u
umask 077
ROOT=/data/local/voyahplay-root-helper
MODE=${1:-tick}
case "$MODE" in tick|stop) ;; *) exit 2;; esac
[ "$(id -u)" = 0 ] || exit 1
[ ! -L "$ROOT" ] && [ "$(stat -c '%u:%a' "$ROOT" 2>/dev/null)" = '0:700' ] || exit 1
[ "$(cat "$ROOT/owner" 2>/dev/null)" = voyahplay-root-helper-v1 ] || exit 1
private_file() { [ ! -L "$1" ] && [ "$(stat -c '%u:%a' "$1" 2>/dev/null)" = '0:600' ]; }
number() { case "$1" in ''|*[!0-9]*) return 1;; *) return 0;; esac; }
radio_apk_path() {
    case "$1" in ''|*[!A-Za-z0-9_./+=~@-]*|*'/../'*|*'/./'*) return 1;; esac
    case "$1" in /data/app/?*.apk|/system/app/?*.apk|/system/priv-app/?*.apk|/product/app/?*.apk|/product/priv-app/?*.apk|/vendor/app/?*.apk|/vendor/priv-app/?*.apk) return 0;; *) return 1;; esac
}
identity() { [ -r "/proc/$1/stat" ] && awk '{sub(/^.*\) /, ""); if ($1 != "Z" && $1 != "X") print $20}' "/proc/$1/stat"; }
args() { timeout 2 dd "if=/proc/$1/cmdline" bs=4096 count=1 2>/dev/null | tr '\000' '\n'; }
boot=$(cat /proc/sys/kernel/random/boot_id) || exit 1
case "$boot" in ''|*[!a-fA-F0-9-]*) exit 1;; esac
# The supervisor and a settings request can arrive together. A unique owner filename
# also prevents two stale-lock cleaners from removing a newly acquired owner's file.
lock=$ROOT/radio-runtime.lock
busy() { echo RADIO_LABELS_BUSY=1; exit 1; }
if ! mkdir "$lock" 2>/dev/null; then
    [ ! -L "$lock" ] && [ "$(stat -c '%u:%a' "$lock" 2>/dev/null)" = '0:700' ] || busy
    set -- "$lock"/owner-*
    [ "$#" = 1 ] && private_file "$1" || busy
    read lock_boot lock_pid lock_start < "$1"
    case "$lock_boot" in ''|*[!a-fA-F0-9-]*) busy;; esac
    number "$lock_pid" && number "$lock_start" || busy
    [ "$1" = "$lock/owner-$lock_boot-$lock_pid-$lock_start" ] || busy
    if [ "$lock_boot" = "$boot" ] && [ "$(identity "$lock_pid" 2>/dev/null)" = "$lock_start" ]; then busy; fi
    rm "$1" && rmdir "$lock" && mkdir "$lock" || busy
fi
self_start=$(identity $$)
number "$self_start" || { rmdir "$lock"; exit 1; }
lock_owner=$lock/owner-$boot-$$-$self_start
trap 'rm -f "$lock_owner"; rmdir "$lock" 2>/dev/null' EXIT
trap 'exit 1' TERM INT
printf '%s %s %s\n' "$boot" "$$" "$self_start" > "$lock_owner" || exit 1
chmod 600 "$lock_owner"
record=$ROOT/radio-agent.pid
alive() {
    private_file "$record" || return 1
    read a_boot a_pid a_start target_pid target_start < "$record"
    [ "$a_boot" = "$boot" ] && number "$a_pid" && number "$a_start" && number "$target_pid" && number "$target_start" || return 1
    [ "$(identity "$a_pid" 2>/dev/null)" = "$a_start" ] || return 1
    [ "$(awk '/^Uid:/ { print $2 " " $3 " " $4 " " $5 }' "/proc/$a_pid/status")" = '0 0 0 0' ] || return 1
    a_args=$(args "$a_pid")
    [ "$(printf '%s\n' "$a_args" | head -n 1)" = /data/local/tmp/frida-inject ] &&
        printf '%s\n' "$a_args" | grep -Fxq "$ROOT/radio-labels.js"
}
stop() {
    /system/bin/settings put global voyahplay_radio_labels_enabled 0 >/dev/null || return 1
    if alive; then
        # Restore visible rows before detaching. If the UI is blocked, keep the disabled agent
        # attached and retry later instead of stranding a modified view or killing the radio app.
        if [ "$(identity "$target_pid" 2>/dev/null)" = "$target_start" ]; then
            restored=0
            for step in 1 2 3 4; do
                if private_file "$ROOT/radio-agent.log" &&
                    grep '^VOYAH_RADIO_LABELS_STATE ' "$ROOT/radio-agent.log" | tail -n 1 |
                    grep -q "^VOYAH_RADIO_LABELS_STATE pid=$target_pid enabled=0 modified=0 error=$"; then restored=1; break; fi
                sleep 1
            done
            [ "$restored" = 1 ] || { echo RADIO_LABELS_RESTORE_PENDING=1; return 1; }
        fi
        [ "$(identity "$a_pid" 2>/dev/null)" = "$a_start" ] || return 1
        kill -TERM "$a_pid" || return 1
        for step in 1 2 3 4; do [ "$(identity "$a_pid" 2>/dev/null)" = "$a_start" ] || break; sleep 1; done
        [ "$(identity "$a_pid" 2>/dev/null)" = "$a_start" ] && return 1
    fi
    rm -f "$record"
    echo RADIO_LABELS_AGENT_STOPPED=1
}
if [ "$MODE" = stop ]; then stop; exit $?; fi
if ! private_file "$ROOT/radio-labels.conf" || [ "$(cat "$ROOT/radio-labels.conf")" != 1 ]; then
    [ ! -e "$record" ] || stop
    exit $?
fi
pid=$(pidof com.pateo.rdsapp 2>/dev/null || true)
if ! number "$pid"; then [ ! -e "$record" ] || stop; exit $?; fi
start=$(identity "$pid")
number "$start" || exit 1
if alive && [ "$target_pid" = "$pid" ] && [ "$target_start" = "$start" ]; then exit 0; fi
[ ! -e "$record" ] || stop || exit 1
[ "$(args "$pid" | head -n 1)" = com.pateo.rdsapp ] || exit 1
entry=$(cmd package list packages --user 0 -U com.pateo.rdsapp) || exit 1
uid=$(printf '%s\n' "$entry" | sed -n 's/^package:com\.pateo\.rdsapp[[:space:]]*uid:\([0-9]*\)[[:space:]]*$/\1/p')
number "$uid" && [ "$uid" = 1000 ] || exit 1
[ "$(awk '/^Uid:/ { print $2 " " $3 " " $4 " " $5 }' "/proc/$pid/status")" = "$uid $uid $uid $uid" ] || exit 1
paths=$(pm path --user 0 com.pateo.rdsapp) || exit 1
[ "$(printf '%s\n' "$paths" | grep -c '^package:')" = 1 ] || exit 1
apk=$(printf '%s\n' "$paths" | sed -n 's/^package://p')
radio_apk_path "$apk" || { echo RADIO_LABELS_PATH_UNSUPPORTED=1; exit 1; }
[ "$(sha256sum "$apk" | awk '{print $1}')" = 36b4d73a889aa78325f6a6ff0c8113c6b13605620a5a4b045209fbff36449cca ] || { echo RADIO_LABELS_APK_UNSUPPORTED=1; exit 1; }
[ "$(sha256sum /data/local/tmp/frida-inject | awk '{print $1}')" = 0d99be0e44d4ca3449056fa0a0b868fca1cf66c8ad704508949945afc07706aa ] || exit 1
private_file "$ROOT/radio-labels.js" && private_file "$ROOT/radio-labels.sha256" || exit 1
sha256sum -c "$ROOT/radio-labels.sha256" >/dev/null 2>&1 || exit 1
for file in "$record" "$ROOT/radio-agent.log"; do [ ! -L "$file" ] || exit 1; done
[ "$(identity "$pid" 2>/dev/null)" = "$start" ] || exit 1
/system/bin/settings put global voyahplay_radio_labels_enabled 1 >/dev/null || exit 1
: > "$ROOT/radio-agent.log"; chmod 600 "$ROOT/radio-agent.log"
nohup env FRIDA_NO_ETERNALIZE=1 FRIDA_SKIP_SELINUX_PATCH=1 /data/local/tmp/frida-inject \
    -p "$pid" -s "$ROOT/radio-labels.js" </dev/null >> "$ROOT/radio-agent.log" 2>&1 &
agent=$!
agent_start=$(identity "$agent")
number "$agent_start" || exit 1
printf '%s %s %s %s %s\n' "$boot" "$agent" "$agent_start" "$pid" "$start" > "$record"
chmod 600 "$record"
echo "RADIO_LABELS_AGENT_PID=$agent"
