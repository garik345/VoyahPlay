// Exercise the portable launcher and real installer against a local fake ADB executable.
// The fixture never invokes a real ADB client or changes a connected device.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const {spawnSync} = require('node:child_process');
const {test} = require('node:test');
const root = path.resolve(__dirname, '../..');
const fixture = path.join(root, 'build', `root-helper-portable-tests-${process.pid}`, 'Проверка с пробелами');
fs.mkdirSync(path.join(fixture, 'adb'), {recursive: true});
for (const name of ['start-root-helper.ps1', 'root-helper.ps1', 'root-helper-bootstrap.sh'])
    fs.copyFileSync(path.join(root, 'scripts/root-module', name), path.join(fixture, name));
for (const name of ['prepare-pi-fullscreen.ps1','pi-tweaks-bridge.sh'])
    fs.copyFileSync(path.join(root,'scripts/root-module',name),path.join(fixture,name));
fs.mkdirSync(path.join(fixture,'pi-bridge'));
fs.copyFileSync(path.join(root,'scripts/root-module/pi-bridge/ss11.bin'),path.join(fixture,'pi-bridge/ss11.bin'));
fs.writeFileSync(path.join(fixture,'VoyahPlay.apk'),'fixture APK');
fs.writeFileSync(path.join(fixture,'pi-package.json'),JSON.stringify({apkSha256:crypto.createHash('sha256').update('fixture APK').digest('hex')}));
for (const name of ['AdbWinApi.dll', 'AdbWinUsbApi.dll']) fs.writeFileSync(path.join(fixture, 'adb', name), 'test fixture');
const cs = String.raw`
using System;
using System.IO;
class FakeAdb {
    static int Main(string[] args) {
        File.AppendAllText(Environment.GetEnvironmentVariable("VOYAH_TEST_ADB_LOG"), String.Join("\t",args)+"\n");
        string scenario=Environment.GetEnvironmentVariable("VOYAH_TEST_ADB_SCENARIO");
        if(args.Length==2 && args[0]=="devices" && args[1]=="-l") {
            if(scenario=="AdbFailure") { Console.Error.WriteLine("fixture ADB failure"); return 1; }
            Console.WriteLine("List of devices attached");
            if(scenario=="NoDevices") return 0;
            if(scenario=="Multiple") {
                Console.WriteLine("CAR-ONE device product:h97 model:Voyah transport_id:1");
                Console.WriteLine("CAR-TWO device product:h97 model:Voyah transport_id:2");
            } else {
                string state=scenario=="Unauthorized"?"unauthorized":scenario=="Offline"?"offline":"device";
                Console.WriteLine("CAR-ONE "+state+" product:h97 model:Voyah transport_id:1");
            }
            return 0;
        }
        if(args.Length<3 || args[0]!="-s" || (args[1]!="CAR-ONE" && args[1]!="CAR-TWO")) return 80;
        string command=String.Join(" ",args,2,args.Length-2);
        if(command=="shell getprop ro.build.version.sdk") { Console.WriteLine(scenario=="WrongAndroid"?"29":"30"); return 0; }
        if(command=="shell pm path --user 0 com.shilapi.xcertplay") {
            if(scenario!="NoApp") Console.WriteLine("package:/data/app/fixture/base.apk");
            return 0;
        }
        if(command=="shell su 0 id") { Console.WriteLine(scenario=="NoRoot"?"uid=2000(shell)":"uid=0(root)"); return 0; }
        if(command=="get-state") { Console.WriteLine("device"); return 0; }
        if(args[2]=="push") {
            if(args.Length!=5 || !File.Exists(args[3]) || !(args[4].StartsWith("/data/local/tmp/voyahplay-helper-") || args[4].StartsWith("/data/local/tmp/voyah-bounds-"))) return 81;
            Console.WriteLine("1 file pushed"); return 0;
        }
        if(args.Length==5 && args[2]=="install" && args[3]=="-r") {
            if(scenario=="PiApkFailure") { Console.WriteLine("INSTALL_FAILED_UPDATE_INCOMPATIBLE"); return 1; }
            Console.WriteLine("Success"); return 0;
        }
        if(args.Length>=8 && args[2]=="shell" && args[3]=="su" && args[4]=="0" && args[5]=="sh" && args[6].StartsWith("/data/local/tmp/voyah-bounds-")) {
            if(scenario=="PiWrongFirmware" && args[7]=="check") { Console.WriteLine("ERROR unreviewed-PI-services"); return 1; }
            if(scenario=="PiBridgeFailure" && args[7]=="install") { Console.WriteLine("ERROR backup-checksum"); return 1; }
            Console.WriteLine("PI_FIRMWARE_VERIFIED=1");
            if(args[7]=="install" || args[7]=="restore") Console.WriteLine("REBOOT_REQUIRED=1");
            return 0;
        }
        if(args.Length==8 && args[2]=="shell" && args[3]=="su" && args[4]=="0" && args[5]=="sh") {
            if(!args[6].StartsWith("/data/local/tmp/voyahplay-helper-")) return 82;
            if(scenario=="InstallFailure" && args[7]=="install") { Console.WriteLine("ERROR fixture install failure"); return 1; }
            Console.WriteLine("HELPER_VERSION="+(scenario=="OldHelper"?"1.2.6":"1.2.10"));
            Console.WriteLine("HELPER_READY=1");
            Console.WriteLine("CAPABILITIES=TWEAKS_BOUNDS_1_1");
            Console.WriteLine("BOOT_MOUNT_READ_ONLY="+(scenario=="Reboot"?"0":"1"));
            if(scenario=="Reboot") Console.WriteLine("REBOOT_REQUIRED=1");
            return 0;
        }
        if(args.Length==6 && args[2]=="shell" && args[3]=="rm" && args[4]=="-f" && args[5].StartsWith("/data/local/tmp/voyahplay-helper-")) return 0;
        if(args.Length==8 && args[2]=="shell" && args[3]=="su" && args[4]=="0" && args[5]=="rm" && args[6]=="-f" && args[7].StartsWith("/data/local/tmp/voyah-bounds-")) return 0;
        if(args.Length==9 && args[2]=="shell" && args[3]=="su" && args[4]=="0" && args[5]=="rm" && args[6]=="-f" && args[7].StartsWith("/data/local/tmp/voyah-bounds-") && args[8].StartsWith("/data/local/tmp/voyah-bounds-")) return 0;
        Console.Error.WriteLine("Unexpected fake ADB request: "+command); return 83;
    }
}
`;
const csPath = path.join(fixture, 'FakeAdb.cs');
fs.writeFileSync(csPath, cs, 'utf8');
const compilePath = path.join(fixture, 'compile.ps1');
fs.writeFileSync(compilePath, "$ErrorActionPreference='Stop'\r\nAdd-Type -Path (Join-Path $PSScriptRoot 'FakeAdb.cs') -OutputAssembly (Join-Path $PSScriptRoot 'adb/adb.exe') -OutputType ConsoleApplication\r\n", 'utf8');
const compiled = spawnSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', compilePath], {encoding: 'utf8', timeout: 30000});
assert.equal(compiled.status, 0, compiled.stdout + compiled.stderr);
let runNumber = 0;
function run(scenario, options = {}) {
    const log = path.join(fixture, `adb-${++runNumber}.txt`);
    const args = ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(fixture, 'start-root-helper.ps1'), '-Mode', options.mode || 'Install'];
    if (options.serial) args.push('-Serial', options.serial);
    if (options.pi) args.push('-PreparePiFullscreen');
    if (options.restore) args.push('-RestorePiFullscreen');
    const result = spawnSync('powershell.exe', args, {encoding: 'utf8', timeout: 30000, input: options.input || '',
        cwd: path.dirname(fixture), env: {...process.env, VOYAH_TEST_ADB_SCENARIO: scenario, VOYAH_TEST_ADB_LOG: log}});
    assert(!result.error, result.error?.message);
    const calls = fs.existsSync(log) ? fs.readFileSync(log, 'utf8').trim().split('\n').map(line => line.split('\t')) : [];
    return {...result, calls, output: result.stdout + result.stderr};
}
function operations(result) { return result.calls.filter(a => a[2]==='shell' && a[5]==='sh').map(a => a[7]); }
function noInstall(result) { assert(!result.calls.some(a => a[2] === 'push'), result.output); }

test('single device installs then verifies using the real installer, outside the project tree', () => {
    const r = run('Ready');
    assert.equal(r.status, 0, r.output);
    assert.deepEqual(operations(r), ['install', 'status']);
    assert(r.calls.slice(1).every(a => a[0]==='-s' && a[1]==='CAR-ONE'));
    assert(r.output.includes('Root helper 1.2.10 установлен, состояние проверено.'));
    assert(fs.existsSync(path.join(fixture, 'build/voyah-root-helper')));
});
test('STATUS checks without installing or requiring an app still installed', () => {
    const r = run('NoApp', {mode: 'Status'});
    assert.equal(r.status, 0, r.output);
    assert.deepEqual(operations(r), ['status']);
    assert(!r.calls.some(a => a.includes('pm')));
});
test('no device, unauthorized and offline stop before sending the bootstrap', () => {
    for (const state of ['NoDevices','Unauthorized','Offline','AdbFailure']) {
        const r = run(state);
        assert.equal(r.status, 1, r.output); noInstall(r);
        assert(!r.calls.some(a => a[2] === 'shell'));
    }
});
test('wrong Android, missing app and missing root stop before installation', () => {
    for (const state of ['WrongAndroid','NoApp','NoRoot']) {
        const r = run(state);
        assert.equal(r.status, 1, r.output); noInstall(r);
    }
});
test('multiple devices require a choice, then every request targets only the chosen serial', () => {
    const r = run('Multiple', {input: '99\r\n2\r\n'});
    assert.equal(r.status, 0, r.output);
    assert(r.output.includes('Введите номер из списка.'));
    assert(r.calls.slice(1).every(a => a[0]==='-s' && a[1]==='CAR-TWO'));
    assert.deepEqual(operations(r), ['install', 'status']);
});
test('explicit serial is respected and an unknown serial performs no remote operation', () => {
    const chosen = run('Multiple', {serial: 'CAR-TWO'});
    assert.equal(chosen.status, 0, chosen.output);
    assert(chosen.calls.slice(1).every(a => a[1]==='CAR-TWO'));
    const missing = run('Multiple', {serial: 'MISSING'});
    assert.equal(missing.status, 1, missing.output); noInstall(missing);
    assert.equal(missing.calls.length, 1);
});
test('cancel or end of input at device choice cannot default to a vehicle', () => {
    for (const input of ['0\r\n','']) {
        const r = run('Multiple', {input});
        assert.equal(r.status, 2, r.output); noInstall(r);
        assert.equal(r.calls.length, 1);
    }
});
test('installer failure and old helper version cannot show success or run follow-up status', () => {
    for (const state of ['InstallFailure','OldHelper']) {
        const r = run(state);
        assert.equal(r.status, 1, r.output);
        assert.deepEqual(operations(r), ['install']);
        assert(!r.output.includes('Готово. Root helper'));
        assert(r.calls.some(a => a[2]==='shell' && a[3]==='rm'));
    }
});
test('reboot indication becomes a readable instruction and never reboots the vehicle', () => {
    const r = run('Reboot');
    assert.equal(r.status, 0, r.output);
    assert(r.output.includes('Перезагрузите мультимедиа обычным способом'));
    assert(!r.calls.some(a => a.includes('reboot')));
});
test('Windows scripts preserve UTF-8 Russian text and pause with the original exit code', () => {
    const launcher = fs.readFileSync(path.join(root, 'scripts/root-module/start-root-helper.ps1'));
    assert.deepEqual([...launcher.subarray(0,3)], [0xef,0xbb,0xbf]);
    for (const name of ['install-root-helper.cmd','status-root-helper.cmd']) {
        const text = fs.readFileSync(path.join(root, 'scripts/root-module', name), 'ascii');
        assert(!/(?<!\r)\n/.test(text));
        assert(text.includes('DisableDelayedExpansion'));
        assert(text.includes('"%~dp0start-root-helper.ps1"'));
        assert(text.includes('set "VOYAH_HELPER_EXIT=%ERRORLEVEL%"'));
        assert(text.includes('\r\npause\r\nexit /b %VOYAH_HELPER_EXIT%'));
    }
});
test('PI installer checks firmware before APK, bridge and helper; keeps one selected serial', () => {
    const r=run('Ready',{pi:true}); assert.equal(r.status,0,r.output);
    assert(r.calls.slice(1).every(a=>a[1]==='CAR-ONE'));
    const apkIndex=r.calls.findIndex(a=>a[2]==='install');
    const checkIndex=r.calls.findIndex(a=>a[6]?.startsWith('/data/local/tmp/voyah-bounds-') && a[7]==='check');
    const patchIndex=r.calls.findIndex(a=>a[6]?.startsWith('/data/local/tmp/voyah-bounds-') && a[7]==='install');
    const helperIndex=r.calls.findIndex(a=>a[6]?.startsWith('/data/local/tmp/voyahplay-helper-') && a[7]==='install');
    assert(checkIndex>=0 && checkIndex<apkIndex && apkIndex<patchIndex && patchIndex<helperIndex,r.output);
    assert(r.output.includes('Перезагрузите мультимедиа обычным способом'));
    assert(!r.calls.some(a=>a.includes('reboot')));
});
test('PI firmware rejection prevents APK update, bridge replacement and helper installation', () => {
    const r=run('PiWrongFirmware',{pi:true}); assert.equal(r.status,1,r.output);
    assert(!r.calls.some(a=>a[2]==='install' || a[7]==='install'));
    assert(r.calls.some(a=>a.includes('rm')));
});
test('failed APK update prevents bridge replacement and helper installation', () => {
    const r=run('PiApkFailure',{pi:true}); assert.equal(r.status,1,r.output);
    assert(!r.calls.some(a=>a[7]==='install'));
});
test('PI bridge error prevents helper installation and a false success', () => {
    const r=run('PiBridgeFailure',{pi:true}); assert.equal(r.status,1,r.output);
    assert(!r.calls.some(a=>a[6]?.startsWith('/data/local/tmp/voyahplay-helper-')));
    assert(!r.output.includes('Готово. Root helper'));
});
test('PI restore changes neither APK nor helper and does not reboot automatically', () => {
    const r=run('Ready',{restore:true,mode:'Status'}); assert.equal(r.status,0,r.output);
    assert(r.calls.some(a=>a[7]==='restore'));
    assert(!r.calls.some(a=>a[2]==='install' || a[7]==='install' || a.includes('reboot')));
    assert(!r.calls.some(a=>a[6]?.startsWith('/data/local/tmp/voyahplay-helper-')));
});
