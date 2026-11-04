// Real Windows coordinator with isolated child launchers; never contacts a real ADB device.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const assert=require('node:assert/strict');
const {test}=require('node:test');
const {spawnSync}=require('node:child_process');
const root=path.resolve(__dirname,'../..');
const base=path.join(root,'build',`ru-recovery-tests-${process.pid}`,'Проверка с пробелами');
fs.mkdirSync(path.join(base,'helper/adb'),{recursive:true});
fs.mkdirSync(path.join(base,'tweaks-bridge/bridge'),{recursive:true});
const powershell='C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe';
const cs=String.raw`
using System; using System.IO;
class FakeAdb { static int Main(string[] a) {
File.AppendAllText(Environment.GetEnvironmentVariable("VP_RECOVERY_LOG"),"adb\t"+String.Join("\t",a)+"\n");
string s=Environment.GetEnvironmentVariable("VP_RECOVERY_SCENARIO"),c=String.Join(" ",a);
if(c=="devices -l") {Console.WriteLine("List of devices attached"); if(s=="Missing")return 0;
Console.WriteLine("CAR-ONE "+(s=="Offline"?"offline":s=="Unauthorized"?"unauthorized":"device")+" model:Voyah");
if(s=="Multiple")Console.WriteLine("CAR-TWO device model:Voyah"); return 0;}
if(a.Length<3||a[0]!="-s"||(a[1]!="CAR-ONE"&&a[1]!="CAR-TWO"))return 88;
c=String.Join(" ",a,2,a.Length-2);
if(c=="shell su 0 id"){Console.WriteLine(s=="NoRoot"?"uid=2000(shell)":"uid=0(root)");return 0;}
if(c=="shell getprop ro.build.version.sdk"){Console.WriteLine(s=="OldAndroid"?"29":"30");return 0;}
if(c=="shell pm path --user 0 com.shilapi.xcertplay"){if(s!="NoApp")Console.WriteLine("package:/data/app/owner/base.apk");return 0;}
if(c=="shell pm list packages ru.kachalin.voyahtweaks"){if(s!="NoTweaks")Console.WriteLine("package:ru.kachalin.voyahtweaks");return 0;}
return 89; }}
`;
fs.writeFileSync(path.join(base,'compile.ps1'),`$src=@'\n${cs}\n'@\nAdd-Type -TypeDefinition $src -OutputAssembly (Join-Path $PSScriptRoot 'helper/adb/adb.exe') -OutputType ConsoleApplication\n`);
let r=spawnSync(powershell,['-NoProfile','-ExecutionPolicy','Bypass','-File',path.join(base,'compile.ps1')],{encoding:'utf8',timeout:30000});
assert.equal(r.status,0,r.stdout+r.stderr);
const launcher=String.raw`
param([string]$Mode,[string]$Serial,[string]$ProjectRoot,[string]$Adb)
$name=if($PSScriptRoot.EndsWith('tweaks-bridge')){'bridge'}elseif($PSCommandPath.EndsWith('root-helper.ps1') -and -not $PSCommandPath.EndsWith('start-root-helper.ps1')){'autostart'}else{'helper'}
Add-Content -LiteralPath $env:VP_RECOVERY_LOG -Value ($name+[char]9+$Mode+[char]9+$Serial) -Encoding utf8
$s=$env:VP_RECOVERY_SCENARIO
if($name -eq 'bridge') {
 if($Mode -eq 'Install') { if($s -eq 'BridgeFailure'){exit 3}; if($s -ne 'MissingInstallMarker'){Write-Output 'RU_BRIDGE_INSTALLED=1'};exit 0 }
 if($Mode -eq 'Restore'){Write-Output 'ORIGINAL_RESTORED=1';exit 0}
 if($s -in @('BeforeReboot','StaleProbe')){exit 4}
 if($s -ne 'MissingLoadedMarker'){Write-Output 'BRIDGE_LOADED=1'};exit 0
}
if($name -eq 'autostart') {if($s -eq 'AutostartFailure'){exit 5};Write-Output 'AUTOSTART_ENABLED=1';exit 0}
if($s -eq 'HelperFailure'){exit 6}
Write-Output 'HELPER_READY=1'
Write-Output $(if($s -eq 'WrongVersion'){'HELPER_VERSION=1.2.15'}else{'HELPER_VERSION=1.2.16'})
Write-Output $(if($s -eq 'WrongUid'){'HELPER_UID_MATCH=0'}else{'HELPER_UID_MATCH=1'})
Write-Output $(if($s -eq 'WritableMount'){'BOOT_MOUNT_READ_ONLY=0'}else{'BOOT_MOUNT_READ_ONLY=1'})
Write-Output 'AUTOSTART_INSTALLED=1'
Write-Output $(if($s -eq 'DisabledAutostart'){'AUTOSTART_ENABLED=0'}else{'AUTOSTART_ENABLED=1'})
if($s -ne 'NoClusterCapability'){Write-Output 'CLUSTER_AUTO_CAPABILITY=VOYAH_CLUSTER_AUTO_1'}
exit 0
`;
const required=['start-ru-recovery.ps1','INSTALL.cmd','STATUS.cmd','RESTORE_BRIDGE.cmd','README.txt',
 'helper/start-root-helper.ps1','helper/root-helper.ps1','helper/root-helper-bootstrap.sh','helper/helper-quiesce.sh',
 'helper/adb/adb.exe','helper/adb/AdbWinApi.dll','helper/adb/AdbWinUsbApi.dll','helper/FILES-SHA256.json',
 'tweaks-bridge/start-ru-bridge.ps1','tweaks-bridge/bridge-installer.sh','tweaks-bridge/bridge/ss11.bin',
 'tweaks-bridge/bridge/verification.json','tweaks-bridge/FILES-SHA256.json'];
for(const file of required)if(!fs.existsSync(path.join(base,file)))fs.writeFileSync(path.join(base,file),'fixture');
fs.writeFileSync(path.join(base,'start-ru-recovery.ps1'),'\uFEFF'+fs.readFileSync(path.join(root,'scripts/root-module/start-ru-recovery.ps1'),'utf8').replace(/^\uFEFF/,''));
for(const file of ['helper/start-root-helper.ps1','helper/root-helper.ps1','tweaks-bridge/start-ru-bridge.ps1'])fs.writeFileSync(path.join(base,file),'\uFEFF'+launcher);
function manifest() { fs.writeFileSync(path.join(base,'FILES-SHA256.json'),JSON.stringify(required.map(file=>({file,sha256:crypto.createHash('sha256').update(fs.readFileSync(path.join(base,file))).digest('hex')})))); }
manifest();let counter=0;
function run(scenario='Ready',mode='Install',extra=[],input) {
 const log=path.join(base,`commands-${++counter}.txt`);
 const p=spawnSync(powershell,['-NoProfile','-ExecutionPolicy','Bypass','-File',path.join(base,'start-ru-recovery.ps1'),'-Mode',mode,...extra],
 {encoding:'utf8',input,timeout:30000,env:{...process.env,VP_RECOVERY_LOG:log,VP_RECOVERY_SCENARIO:scenario}});
 assert(!p.error,p.error?.message);
 const calls=fs.existsSync(log)?fs.readFileSync(log,'utf8').replaceAll('\uFEFF','').split(/\r?\n/).filter(Boolean):[];
 return {...p,text:p.stdout+p.stderr,calls,stages:calls.filter(c=>!c.startsWith('adb\t'))};
}
test('install checks bridge, installs helper and enables its boot hook in order; reboot remains manual',()=>{
 const r=run();assert.equal(r.status,0,r.text);
 assert.deepEqual(r.stages,['bridge\tInstall\tCAR-ONE','helper\tInstall\tCAR-ONE','autostart\tEnableAutoStart\tCAR-ONE']);
 assert(r.text.includes('INSTALL_WRITTEN=1'));assert(r.text.includes('REBOOT_REQUIRED=1'));assert(!r.text.includes('RECOVERY_READY=1'));
 assert(!r.calls.some(c=>c.includes('\treboot')||c.includes('\tinstall\t-r')||c.includes('uninstall')));
});
test('device, root, Android and installed-app preflight failures never start a child installer',()=>{
 for(const s of ['Missing','Offline','Unauthorized','NoRoot','OldAndroid','NoApp','NoTweaks']) {
  const r=run(s);assert.notEqual(r.status,0,s+r.text);assert.deepEqual(r.stages,[]);assert(!r.text.includes('INSTALL_WRITTEN=1'));
 }
});
test('multiple devices are selected once and the same serial is passed to every stage',()=>{
 for(const [extra,input] of [[[],'2\n'],[['-Serial','CAR-TWO'],undefined]]) {
  const r=run('Multiple','Install',extra,input);assert.equal(r.status,0,r.text);assert.equal(r.stages.length,3);
  for(const c of r.calls.slice(1))assert(c.includes('CAR-TWO'),c);
 }
 const r=run('Multiple','Install',[],'0\n');assert.equal(r.status,2,r.text);assert.equal(r.calls.length,1);
});
test('stage failures and missing acknowledgements stop without announcing installation',()=>{
 for(const s of ['BridgeFailure','MissingInstallMarker','HelperFailure','WrongUid','WrongVersion','AutostartFailure']) {
  const r=run(s);assert.notEqual(r.status,0,s+r.text);assert(!r.text.includes('INSTALL_WRITTEN=1'));
  if(['BridgeFailure','MissingInstallMarker'].includes(s))assert.equal(r.stages.length,1);
  if(['HelperFailure','WrongUid','WrongVersion'].includes(s))assert.equal(r.stages.length,2);
 }
});
test('status requires the loaded bridge, current UID and boot-capable helper without installing anything',()=>{
 const r=run('Ready','Status');assert.equal(r.status,0,r.text);assert(r.text.includes('RECOVERY_READY=1'));
 assert.deepEqual(r.stages,['helper\tStatus\tCAR-ONE','bridge\tStatus\tCAR-ONE']);
 for(const s of ['BeforeReboot','StaleProbe','MissingLoadedMarker','WrongVersion','WrongUid','WritableMount','DisabledAutostart','NoClusterCapability','HelperFailure']) {
  const r=run(s,'Status');assert.notEqual(r.status,0,s+r.text);assert(!r.text.includes('RECOVERY_READY=1'));assert.equal(r.stages.length,2);
  assert(r.stages.every(c=>c.includes('\tStatus\t')));
 }
});
test('restoring the bridge never reinstalls helper and works with VoyahPlay removed',()=>{
 const r=run('NoApp','RestoreBridge');assert.equal(r.status,0,r.text);assert.deepEqual(r.stages,['bridge\tRestore\tCAR-ONE']);
 assert(!r.calls.some(c=>c.includes('\tpath\t')));
});
test('damaged files, traversal and duplicate manifest entries stop before ADB',()=>{
 const file=path.join(base,'README.txt'),old=fs.readFileSync(file);fs.appendFileSync(file,'damage');
 let r=run();assert.notEqual(r.status,0,r.text);assert.deepEqual(r.calls,[]);fs.writeFileSync(file,old);manifest();
 const mpath=path.join(base,'FILES-SHA256.json'),raw=fs.readFileSync(mpath),entries=JSON.parse(raw);
 for(const bad of [[...entries,{file:'../outside',sha256:'a'.repeat(64)}],[...entries,entries[0]]]) {
  fs.writeFileSync(mpath,JSON.stringify(bad));r=run();assert.notEqual(r.status,0,r.text);assert.deepEqual(r.calls,[]);
 }
 fs.writeFileSync(mpath,raw);
});
