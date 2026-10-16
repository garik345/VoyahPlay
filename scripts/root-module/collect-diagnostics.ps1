# PowerShell 5.1+. Read existing module diagnostics; no hooks or window changes.
[CmdletBinding()]
param([string]$ProjectRoot, [string]$Serial, [string]$Adb = 'adb', [switch]$AgentOrigin)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (Get-Variable PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) { $PSNativeCommandUseErrorActionPreference = $false }
if (-not $ProjectRoot) {
    $directory = [System.IO.DirectoryInfo]::new($PSScriptRoot)
    while ($null -ne $directory) {
        if (Test-Path -LiteralPath (Join-Path $directory.FullName 'common/src/main/assets/voyah-root/module.sh')) { $ProjectRoot = $directory.FullName; break }
        $directory = $directory.Parent
    }
}
if (-not $ProjectRoot) { $ProjectRoot = $PSScriptRoot }
$ProjectRoot = (Resolve-Path -LiteralPath $ProjectRoot).Path
$script:AdbExe = (Get-Command $Adb -CommandType Application -ErrorAction Stop).Source
$script:Target = @(); if ($Serial) { $script:Target = @('-s', $Serial) }
$runName = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N')
$reportDirectory = Join-Path $ProjectRoot ('build/voyah-root-diagnostics/' + $runName)
[void][System.IO.Directory]::CreateDirectory($reportDirectory)
$script:Report = Join-Path $reportDirectory 'report.txt'
$utf8 = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllText($script:Report, "VoyahPlay root diagnostics 1.3`nTime: $(Get-Date -Format o)`nAgent origin: $($AgentOrigin.IsPresent)`nREAD ONLY: no injection, policy writes, restarts or module operations.`n", $utf8)
function Write-Report([string]$Line) {
    [System.IO.File]::AppendAllText($script:Report, $Line + "`n", [System.Text.UTF8Encoding]::new($false))
}
function Invoke-DiagnosticAdb {
    param([string[]]$Arguments, [switch]$AllowFailure)
    Write-Host ('ADB ' + ($Arguments -join ' '))
    Write-Report ('ADB ' + ($Arguments -join ' '))
    $saved = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $lines = @(& $script:AdbExe @script:Target @Arguments 2>&1 | ForEach-Object { $_.ToString() })
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $saved }
    foreach ($line in $lines) { Write-Report $line }
    if ($code -ne 0 -and -not $AllowFailure) { throw "ADB exit code $code; diagnostics are in $script:Report" }
    return ($lines -join "`n")
}
$remote = '/data/local/tmp/voyahplay-diagnostics-' + [guid]::NewGuid().ToString('N') + '.sh'
$local = Join-Path $reportDirectory 'collector.sh'
$collector = @'
#!/system/bin/sh
# Only reads existing state and bounded tails; does not run the module or injector.
LC_ALL=C; LANG=C; export LC_ALL LANG
[ "$(id -u)" = 0 ] || { echo 'ERROR root required'; exit 1; }
ROOT=/data/local/voyahplay-fullscreen
printf 'DEVICE_TIME='; date '+%F %T %z'
printf 'UPTIME='; cat /proc/uptime
printf 'BOOT_ID='; cat /proc/sys/kernel/random/boot_id
printf 'SDK='; getprop ro.build.version.sdk
printf 'ABI='; getprop ro.product.cpu.abilist
printf 'SELINUX='; getenforce
if [ -L "$ROOT" ] || [ "$(head -c 128 "$ROOT/owner" 2>/dev/null)" != voyahplay-fullscreen-v1 ]; then
    echo 'ERROR module directory is absent or owner is not recognized'; exit 2
fi
dump_file() {
    printf '\n=== %s (last 131072 bytes) ===\n' "$1"
    if [ -L "$1" ]; then echo 'SKIPPED symlink'
    elif [ -r "$1" ]; then tail -c 131072 "$1"; printf '\n'
    else echo 'MISSING or unreadable'; fi
}
for name in version enabled fault fault-boot system-server.state system-server.attempt launcher.state launcher.attempt loader.pid; do
    dump_file "$ROOT/$name"
done
printf '\n=== Current global status ===\n'
for key in voyahplay_fullscreen_system voyahplay_fullscreen_launcher voyahplay_fullscreen_policy voyahplay_fullscreen_system_error; do
    printf '%s=' "$key"; settings get global "$key"
done
for name in system-server.log launcher.log loader.log; do dump_file "$ROOT/$name"; done
if [ ! -L /data/local/voyahplay-root-helper ]; then dump_file /data/local/voyahplay-root-helper/server.log; fi
printf '\n=== Optional existing VoyahTune status/config (read only) ===\n'
dump_file /data/local/open_voyah/vd_hooks/status.v1
for key in voyahtune_freeform voyahtune_fullscreen_apps voyahtune_win_left voyahtune_win_top voyahtune_win_right voyahtune_win_bottom; do
    printf '%s=' "$key"; settings get global "$key"
done
printf '\n=== Matching installed packages (identity clues, not ownership proof) ===\n'
pm list packages | awk 'tolower($0) ~ /voyah|kachalin|restoremode|frida|tweak|big.town.anative/'
printf '\n=== Payload checksums (no execution) ===\n'
for name in module.sh system-server.js launcher.js frida-inject; do
    if [ -f "$ROOT/payload/$name" ] && [ ! -L "$ROOT/payload/$name" ]; then sha256sum "$ROOT/payload/$name"; else printf 'MISSING %s\n' "$name"; fi
done
for package in system_server com.qinggan.app.launcher; do
    printf '\n=== Process %s ===\n' "$package"
    process_pid=$(pidof "$package" 2>/dev/null)
    case "$process_pid" in ''|*[!0-9]*) echo 'MISSING or ambiguous PID'; continue;; esac
    printf 'PID=%s\n' "$process_pid"
    awk '{print "START_TICKS=" $22}' "/proc/$process_pid/stat" 2>&1
    awk '/^(Name|PPid|TracerPid|Uid):/ {print substr($0,1,300)}' "/proc/$process_pid/status" 2>&1
    # Never put maps contents in shell variables or arguments.
    awk '
        { total++; if(tolower($0) ~ /frida|gum-js|gumjs/){ matches++; if(matches<=24) print substr($0,1,400);
            if(tolower($0) ~ /frida-agent.*\.so/ && $3 ~ /^0+$/) images[$4 ":" $5]=1 } }
        END { for(image in images) image_count++; printf "MAP_LINES=%d AGENT_MATCHES=%d AGENT_ELF_IMAGES=%d\n", total, matches, image_count; if(total==0)exit 2 }
    ' "/proc/$process_pid/maps" 2>&1
done
if [ "${1-}" = agent-origin ]; then
    printf '\n=== Agent origin: existing processes (names and parent PIDs) ===\n'
    ps -A -o USER,PID,PPID,NAME 2>&1 | awk '
        NR==1 {print;next}
        $3!=2 && $4 !~ /^\[/ && ($1=="root" || tolower($0) ~ /system_server|qinggan.app.launcher|frida|voyah|kachalin|inject|tweak/) {
            count++; if(count<=160)print substr($0,1,400)
        } END {printf "PROCESS_ROWS=%d LIMIT=160\n",count}'
    printf '\n=== Root shell/injector paths, script/target arguments and parent chains ===\n'
    ps -A -o USER,PID,PPID,NAME 2>/dev/null | awk '
        $1=="root" && $2 ~ /^[0-9]+$/ && ($4 ~ /(^|\/)(sh|bash|ash)$/ || tolower($4) ~ /frida|inject|voyah|tweak/) {
            count++; if(count<=40)print $2
        }' | while IFS= read -r origin_pid; do
        origin_exe=$(readlink "/proc/$origin_pid/exe" 2>/dev/null)
        printf '\nPID=%s EXE=%s\n' "$origin_pid" "$origin_exe"
        awk '/^(Name|PPid|Uid):/ {print substr($0,1,300)}' "/proc/$origin_pid/status" 2>&1
        if [ -r "/proc/$origin_pid/cmdline" ]; then
            head -c 16384 "/proc/$origin_pid/cmdline" | tr '\000' '\n' | awk '
                NR==1 {print "ARGV0=" substr($0,1,500);next}
                $0 ~ /^[-a-zA-Z0-9_.\/ ]+\.(sh|js|rc)$/ {if(++count<=8)print "PATH=" substr($0,1,500)}'
            # Only known Frida options are interpreted. Other arguments and
            # inline shell/JavaScript expressions are not printed.
            case "$origin_exe" in *frida*|*inject*)
                printf 'CWD='; readlink "/proc/$origin_pid/cwd" 2>&1
                head -c 16384 "/proc/$origin_pid/cmdline" | tr '\000' '\n' | awk '
                    wanted!="" {
                        if(wanted=="script" && $0 ~ /^[-a-zA-Z0-9_.\/ ]+$/)print "SCRIPT_ARGUMENT=" substr($0,1,500)
                        else if(wanted=="target" && $0 ~ /^[a-zA-Z0-9_.:-]+$/)print "TARGET_ARGUMENT=" substr($0,1,300)
                        else print toupper(wanted) "_ARGUMENT=[omitted: not a simple name/path]"
                        wanted="";next
                    }
                    $0 ~ /^(-s|--script|-l|--load)$/ {wanted="script";next}
                    $0 ~ /^(-p|--pid|-n|--name)$/ {wanted="target";next}
                    $0 ~ /^--(script|load)=[a-zA-Z0-9_.\/-]+$/ {print "SCRIPT_ARGUMENT=" substr($0,index($0,"=")+1,500)}
                    $0 ~ /^--(pid|name)=[a-zA-Z0-9_.:-]+$/ {print "TARGET_ARGUMENT=" substr($0,index($0,"=")+1,300)}'
                origin_parent=$(awk '/^PPid:/ {print $2}' "/proc/$origin_pid/status" 2>/dev/null)
                origin_depth=0
                while [ "$origin_depth" -lt 4 ]; do
                    case "$origin_parent" in ''|0|*[!0-9]*) break;; esac
                    printf 'PARENT depth=%s PID=%s\n' "$origin_depth" "$origin_parent"
                    awk '/^(Name|PPid|Uid):/ {print substr($0,1,300)}' "/proc/$origin_parent/status" 2>&1
                    printf 'PARENT_EXE='; readlink "/proc/$origin_parent/exe" 2>&1
                    head -c 16384 "/proc/$origin_parent/cmdline" 2>/dev/null | tr '\000' '\n' | awk '
                        NR==1 || ($0 ~ /^\// && $0 ~ /\.(sh|js|rc)$/) || $0 ~ /^--nice-name=[a-zA-Z0-9_.:-]+$/ {if(++count<=6)print "PARENT_PATH=" substr($0,1,500)}'
                    origin_parent=$(awk '/^PPid:/ {print $2}' "/proc/$origin_parent/status" 2>/dev/null)
                    origin_depth=$((origin_depth+1))
                done;;
            esac
        fi
    done
    printf '\n=== Existing init service names and states ===\n'
    getprop | awk '/^\[init\.svc\./ { if(++count<=220)print substr($0,1,300) }
        END {printf "INIT_SERVICE_ROWS=%d LIMIT=220\n",count}'
    printf '\n=== Known package identity (installation is not ownership proof) ===\n'
    for origin_package in com.shilapi.xcertplay ru.kachalin.voyahtweaks ru.big.town.anative; do
        printf '\nPACKAGE=%s\n' "$origin_package"
        pm path "$origin_package" 2>&1
        dumpsys package "$origin_package" 2>/dev/null | awk '
            /^[ \t]*(userId=|codePath=|versionCode=|versionName=|firstInstallTime=|lastUpdateTime=)/ {
                if(++count<=12)print substr($0,1,500)
            }'
    done
    printf '\n=== Existing startup and injection files (bounded metadata, no APK contents) ===\n'
    for origin_dir in /data/local /data/adb /data/adb/service.d /data/adb/post-fs-data.d /system/etc/init.d; do
        ls -ld "$origin_dir" 2>&1
    done
    # No recursive /data/app or app-private-data scan. Symlinks are not followed.
    for origin_dir in /data/local /data/adb /system/etc/init.d; do
        if [ -d "$origin_dir" ] && [ ! -L "$origin_dir" ]; then
            printf '\nSEARCH_ROOT=%s DEPTH=4\n' "$origin_dir"
            find "$origin_dir" -maxdepth 4 -type f \( -name '*.sh' -o -name '*.rc' -o -name '*.js' -o -iname '*frida*' -o -iname '*inject*' \) -print 2>/dev/null | awk '
                {count++; if(count<=200)print substr($0,1,500)}
                END {printf "FILE_ROWS=%d LIMIT=200\n",count}'
        fi
    done
    scan_origin_definition() {
        [ "$1" = "$0" ] && return 0
        case "$1" in "$ROOT"/*) return 0;; esac
        [ -f "$1" ] && [ -r "$1" ] && [ ! -L "$1" ] || return 0
        # Bound the input as well as the output. Never execute a discovered file.
        head -c 131072 "$1" | awk -v origin_file="$1" '
            {
                line=tolower($0)
                injection=(line ~ /frida|gum-js|gumjs|vd_bypass|launcherdock|voyahplay-fullscreen|open_voyah|voyahtweak|ru\.big\.town\.anative/)
                startup=(origin_file ~ /\.rc$/ && line ~ /^[ \t]*(service|exec|exec_background|import)[ \t]/ && line ~ /\/data\/|inject|tweak|voyah/)
                if(injection || startup) {
                    matches++
                    if(matches<=24) {
                        if(!header++){print "\nSOURCE=" origin_file " (first 131072 bytes, max 24 matches)"}
                        if(line ~ /password|passwd|secret|token|authorization|credential|certificate|private[_ -]?key|api[_-]?key/)
                            printf "%d: [matching line omitted: possible credential marker]\n",NR
                        else printf "%d: %s\n",NR,substr($0,1,500)
                    }
                }
            }'
    }
    printf '\n=== Matching startup definitions (first 240 candidate files per group) ===\n'
    for origin_dir in /system/etc/init /vendor/etc/init /product/etc/init /system_ext/etc/init /odm/etc/init; do
        if [ -d "$origin_dir" ] && [ ! -L "$origin_dir" ]; then
            find "$origin_dir" -maxdepth 2 -type f -name '*.rc' -print 2>/dev/null
        fi
    done | awk 'NR<=240' | while IFS= read -r origin_file; do scan_origin_definition "$origin_file"; done
    printf '\n=== Observed external injector checksum (no execution) ===\n'
    if [ -f /data/local/tmp/frida-inject ] && [ ! -L /data/local/tmp/frida-inject ]; then
        sha256sum /data/local/tmp/frida-inject
    fi
    for origin_file in /init.rc /system/etc/install-recovery.sh /system/bin/install-recovery.sh; do
        scan_origin_definition "$origin_file"
    done
    for origin_dir in /data/local /data/adb /system/etc/init.d; do
        if [ -d "$origin_dir" ] && [ ! -L "$origin_dir" ]; then
            find "$origin_dir" -maxdepth 4 -type f \( -name '*.sh' -o -name '*.rc' \) -print 2>/dev/null
        fi
    done | awk 'NR<=240' | while IFS= read -r origin_file; do scan_origin_definition "$origin_file"; done
    printf '\n=== Selected existing startup/hook sources from report 20261016-011621 ===\n'
    # Fixed list of observed paths. Do not execute them or read authentication scripts.
    for origin_file in /system/etc/start_ota_services.sh /system/etc/init.logcat.sh /system/etc/init.logkernel.sh /data/local/tmp/ss11.js /data/local/tmp/ss9.js /data/local/tmp/launcher.js /data/local/tmp/virtualdisplay.js /data/local/tmp/complex.js; do
        printf '\nSOURCE_FILE=%s (first 131072 bytes)\n' "$origin_file"
        if [ -L "$origin_file" ]; then echo 'SKIPPED symlink'
        elif [ -f "$origin_file" ] && [ -r "$origin_file" ]; then
            sha256sum "$origin_file"
            # ASCII transport avoids invalid-byte errors while preserving JS/shell syntax.
            # Non-ASCII comments/strings in this view are omitted; original files stay intact.
            head -c 131072 "$origin_file" | tr -cd '\11\12\15\40-\176' | awk '
                {
                    line=tolower($0)
                    if(NR>1200)next
                    if(line ~ /password|passwd|secret|token|authorization|credential|certificate|private[_ -]?key|api[_-]?key/)
                        printf "%d: [line omitted: possible credential marker]\n",NR
                    else printf "%d: %s\n",NR,substr($0,1,4000)
                } END {printf "SOURCE_LINES=%d LIMIT=1200\n",NR}'
        else echo 'MISSING or unreadable'; fi
    done
    printf '\n=== Matching existing log entries (last 6000 main/system entries, max 120 matches) ===\n'
    # RDS and other OEM logs may contain invalid UTF-8. Feed only ASCII to awk.
    logcat -b main -b system -d -t 6000 -v threadtime 2>&1 | tr -cd '\11\12\15\40-\176' | awk '
        {
            line=tolower($0)
            if(line ~ /frida|gum-js|gumjs|vd_bypass|launcherdock|voyahplay.fullscreen|open_voyah/ && line !~ /password|passwd|secret|token|authorization|credential|certificate|private[_ -]?key|api[_-]?key/) {
                count++; if(count<=120)print substr($0,1,700)
            }
        } END {printf "LOG_MATCHES=%d LIMIT=120\n",count}'
    printf '\nAGENT_ORIGIN_COLLECTION_COMPLETE=1\n'
fi
printf '\nCOLLECTION_COMPLETE=1\n'
'@
$pushed = $false
try {
    [void](Invoke-DiagnosticAdb -Arguments @('get-state'))
    $identity = Invoke-DiagnosticAdb -Arguments @('shell','su','0','id')
    if ($identity -notmatch '(?m)^uid=0(?:\(|\s|$)') { throw 'Root through ADB was not confirmed.' }
    [System.IO.File]::WriteAllText($local, $collector.Replace("`r`n","`n").Replace("`r","`n") + "`n", $utf8)
    [void](Invoke-DiagnosticAdb -Arguments @('push',$local,$remote)); $pushed = $true
    $collectorArguments = @('shell','su','0','sh',$remote)
    if ($AgentOrigin) { $collectorArguments += 'agent-origin' }
    $result = Invoke-DiagnosticAdb -Arguments $collectorArguments
    if ($AgentOrigin -and $result -notmatch '(?m)^AGENT_ORIGIN_COLLECTION_COMPLETE=1\r?$') { throw 'Agent origin collection was incomplete; send the report ZIP.' }
    if ($result -notmatch '(?m)^COLLECTION_COMPLETE=1\r?$') { throw 'Collection was incomplete; send the report ZIP.' }
    Write-Host 'DONE. Existing diagnostics collected; module and windows were not changed.'
} catch { Write-Report ('ERROR: ' + $_.Exception.Message); throw }
finally {
    if ($pushed) {
        try { [void](Invoke-DiagnosticAdb -Arguments @('shell','rm','-f',$remote) -AllowFailure) }
        catch { Write-Report ('Temporary cleanup failed: ' + $_.Exception.Message) }
    }
    $zipPath = $reportDirectory + '.zip'
    try {
        Compress-Archive -Path (Join-Path $reportDirectory '*') -DestinationPath $zipPath -CompressionLevel Optimal
        Write-Host "Report ZIP: $zipPath"
    } catch { Write-Host "ZIP failed: $($_.Exception.Message). Send $script:Report" }
}
