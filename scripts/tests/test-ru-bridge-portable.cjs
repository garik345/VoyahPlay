const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const crypto=require('node:crypto');
const {spawnSync}=require('node:child_process');
const {test}=require('node:test');
const project=path.resolve(__dirname,'../..');
const powershell='C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe';
const base=path.join(project,'build',`ru-bridge-portable-tests-${process.pid}`);
const sha=b=>crypto.createHash('sha256').update(b).digest('hex');
const patched='575735ed6f6074f1c33483c8df5c8984e51e6dc0fba328b1e4179ca10b9f9d7d';
let sequence=0;
function fixture(){
 const dir=path.join(base,String(++sequence));fs.mkdirSync(path.join(dir,'bridge'),{recursive:true});fs.mkdirSync(path.join(dir,'adb'));
 for(const [src,dest] of [['scripts/root-module/start-ru-bridge.ps1','start-ru-bridge.ps1'],
  ['scripts/root-module/pi-tweaks-bridge.sh','bridge-installer.sh'],['scripts/root-module/pi-bridge/ss11.bin','bridge/ss11.bin'],
  ['scripts/root-module/pi-bridge/verification.json','bridge/verification.json']])fs.copyFileSync(path.join(project,src),path.join(dir,dest));
 for(const file of ['INSTALL.cmd','STATUS.cmd','RESTORE.cmd','adb/adb.exe','adb/AdbWinApi.dll','adb/AdbWinUsbApi.dll','adb/NOTICE.txt','adb/source.properties'])fs.writeFileSync(path.join(dir,file),'fixture only');
 const fake=String.raw`
$all=@($args)
$record=[PSCustomObject]@{arguments=$all}
Add-Content -LiteralPath $env:RU_FAKE_LOG -Value (ConvertTo-Json -InputObject $record -Compress) -Encoding utf8
$scenario=$env:RU_SCENARIO
if($all[0] -eq 'devices'){
 Write-Output 'List of devices attached'
 if($scenario -eq 'NoDevices'){exit 0}
 if($scenario -eq 'Two'){Write-Output 'carA device model:RU';Write-Output 'carB device model:RU';exit 0}
 if($scenario -eq 'Offline'){Write-Output 'carA offline';exit 0}
 if($scenario -eq 'Unauthorized'){Write-Output 'carA unauthorized';exit 0}
 Write-Output 'carA device model:RU';exit 0
}
if($all[0] -ne '-s'){Write-Output 'Missing target';exit 4}
$all=@($all | Select-Object -Skip 2)
if($all[0] -eq 'push'){if($scenario -eq 'PushFailure'){exit 1};Write-Output 'pushed';exit 0}
if($all[0] -eq 'shell' -and $all[1] -eq 'getprop'){if($scenario -eq 'OldAndroid'){Write-Output '29'}else{Write-Output '30'};exit 0}
if($all[0] -eq 'shell' -and $all[1] -eq 'pidof'){Write-Output '999';exit 0}
if($all[0] -eq 'shell' -and $all[1] -eq 'cat'){Write-Output '100.00 150.00';exit 0}
if($all[0] -eq 'shell' -and $all[3] -eq 'id'){if($scenario -eq 'NotRoot'){Write-Output 'uid=2000(shell)'}else{Write-Output 'uid=0(root) gid=0(root)'};exit 0}
if($all[0] -eq 'shell' -and $all[3] -eq 'rm'){exit 0}
if($all[0] -ne 'shell' -or $all[3] -ne 'sh' -or $all[-1] -ne 'RU'){Write-Output 'Unexpected request';exit 4}
if($scenario -eq 'Unreviewed'){Write-Output 'ERROR unreviewed-RU-services';exit 1}
if($all[5] -eq 'check'){
 if($scenario -ne 'NoVerifiedMarker'){Write-Output 'RU_FIRMWARE_VERIFIED=1'}
 $original='8a5e9b9c612dd3ad09ee3218383bbef6c352835f7238011271a92bb3317011e2'
 $patched='575735ed6f6074f1c33483c8df5c8984e51e6dc0fba328b1e4179ca10b9f9d7d'
 $statusScenarios=@('Loaded','BeforeReboot','Stale','Future','WrongPid','StringBoolean')
 Write-Output ('CURRENT_BRIDGE_SHA256='+$(if($statusScenarios -contains $scenario){$patched}else{$original}))
 $probe=[ordered]@{version='1.1';pid=999;now_ms=99500;boot_ready=$true;subscription_valid=$true}
 if($scenario -eq 'BeforeReboot'){Write-Output 'BOUNDS_PROBE_JSON=null';exit 0}
 if($scenario -eq 'Stale'){$probe.now_ms=1}
 if($scenario -eq 'Future'){$probe.now_ms=100100}
 if($scenario -eq 'WrongPid'){$probe.pid=998}
 if($scenario -eq 'StringBoolean'){$probe.boot_ready='true'}
 Write-Output ('BOUNDS_PROBE_JSON='+(ConvertTo-Json -InputObject $probe -Compress));exit 0
}
if($all[5] -eq 'install'){
 if($scenario -eq 'InstallFailure'){Write-Output 'ERROR backup-checksum';exit 1}
 if($scenario -eq 'NoInstallMarker'){Write-Output 'incomplete';exit 0}
 Write-Output 'RU_BRIDGE_INSTALLED=1';Write-Output 'REBOOT_REQUIRED=1';exit 0
}
if($all[5] -eq 'restore'){Write-Output 'ORIGINAL_RESTORED=1';Write-Output 'REBOOT_REQUIRED=1';exit 0}
exit 4
`;
 fs.writeFileSync(path.join(dir,'fake-adb.ps1'),'\uFEFF'+fake);
 function manifest(){
  const files=['start-ru-bridge.ps1','bridge-installer.sh','bridge/ss11.bin','bridge/verification.json','INSTALL.cmd','STATUS.cmd','RESTORE.cmd','adb/adb.exe','adb/AdbWinApi.dll','adb/AdbWinUsbApi.dll','adb/NOTICE.txt','adb/source.properties'];
  fs.writeFileSync(path.join(dir,'FILES-SHA256.json'),JSON.stringify(files.map(file=>{const data=fs.readFileSync(path.join(dir,file));return {file,bytes:data.length,sha256:sha(data)};})));
 }
 manifest();
 function run(mode='Install',scenario='One',extra=[],input){
  const log=path.join(dir,'calls.jsonl');if(fs.existsSync(log))fs.unlinkSync(log);
  const r=spawnSync(powershell,['-NoProfile','-ExecutionPolicy','Bypass','-File',path.join(dir,'start-ru-bridge.ps1'),'-Mode',mode,'-Adb',path.join(dir,'fake-adb.ps1'),...extra],
   {encoding:'utf8',input,env:{...process.env,RU_SCENARIO:scenario,RU_FAKE_LOG:log},timeout:20000});
  assert(!r.error,r.error?.message);
  const calls=fs.existsSync(log)?fs.readFileSync(log,'utf8').replaceAll('\uFEFF','').split(/\r?\n/).filter(Boolean).map(JSON.parse).map(x=>x.arguments):[];
  return {...r,output:r.stdout+r.stderr,calls};
 }
 return {dir,run,manifest};
}
const operations=r=>r.calls.filter(a=>a.includes('sh')).map(a=>a[7]);
test('single RU device gets only the explicit RU bridge preparation and no APK/helper/reboot commands',()=>{
 const f=fixture(),r=f.run();assert.equal(r.status,0,r.output);assert.deepEqual(operations(r),['check','install']);
 for(const a of r.calls.slice(1))assert.equal(a[1],'carA');
 assert(r.output.includes('Добавка записана'));assert(!r.calls.some(a=>a.includes('reboot')||a[2]==='install'||a.includes('runtime.sh')));
 assert(r.calls.filter(a=>a[2]==='push').some(a=>a[3].endsWith('ss11.bin')));
});
test('multiple devices need an explicit serial or a numbered choice; cancel performs no device preparation',()=>{
 for(const [extra,input,serial] of [[['-Serial','carB'],undefined,'carB'],[[],'2\n','carB']]){
  const r=fixture().run('Install','Two',extra,input);assert.equal(r.status,0,r.output);for(const a of r.calls.slice(1))assert.equal(a[1],serial);
 }
 const r=fixture().run('Install','Two',[],'0\n');assert.equal(r.status,2,r.output);assert.equal(r.calls.length,1);
});
test('missing/offline/unauthorized device, old Android and absent root never upload anything',()=>{
 for(const scenario of ['NoDevices','Offline','Unauthorized','OldAndroid','NotRoot']){
  const r=fixture().run('Install',scenario);assert.notEqual(r.status,0,scenario+r.output);assert(!r.calls.some(a=>a[2]==='push'));assert.deepEqual(operations(r),[]);
 }
});
test('unknown firmware or missing verification marker prevents upload of the bridge and install',()=>{
 for(const scenario of ['Unreviewed','NoVerifiedMarker']){
  const r=fixture().run('Install',scenario);assert.notEqual(r.status,0,r.output);assert.deepEqual(operations(r),['check']);
  assert(!r.calls.some(a=>a[2]==='push'&&a[3].endsWith('ss11.bin')));
 }
});
test('failed upload or device installation never prints a success message',()=>{
 for(const scenario of ['PushFailure','InstallFailure','NoInstallMarker']){
  const r=fixture().run('Install',scenario);assert.notEqual(r.status,0,r.output);assert(!r.output.includes('Добавка записана. Перезагрузите'));
 }
});
test('damaged package, incomplete manifest and incorrect bridge hash are refused before ADB access',()=>{
 const broken=fixture();fs.appendFileSync(path.join(broken.dir,'bridge-installer.sh'),'changed');
 const missing=fixture();fs.writeFileSync(path.join(missing.dir,'FILES-SHA256.json'),'[]');
 const binary=fixture();fs.writeFileSync(path.join(binary.dir,'bridge/ss11.bin'),'wrong payload');binary.manifest();
 for(const f of [broken,missing,binary]){const r=f.run();assert.notEqual(r.status,0,r.output);assert.equal(r.calls.length,0);}
});
test('status distinguishes the original module, a reboot pending, stale probe and fresh loaded bridge',()=>{
 const good=fixture().run('Status','Loaded');assert.equal(good.status,0,good.output);assert(good.output.includes('BRIDGE_LOADED=1'));
 assert.deepEqual(operations(good),['check']);assert.equal(good.calls.filter(a=>a[2]==='push').length,1);
 for(const scenario of ['One','BeforeReboot','Stale','Future','WrongPid','StringBoolean']){
  const r=fixture().run('Status',scenario);assert.notEqual(r.status,0,scenario+r.output);assert(!r.output.includes('BRIDGE_LOADED=1'));assert.deepEqual(operations(r),['check']);
 }
});
test('restore calls only the reviewed restore operation and requests a manual reboot',()=>{
 const r=fixture().run('Restore');assert.equal(r.status,0,r.output);assert.deepEqual(operations(r),['check','restore']);
 assert.equal(r.calls.filter(a=>a[2]==='push').length,1);assert(!r.calls.some(a=>a.includes('reboot')));
 assert(r.output.includes('Исходный модуль восстановлен'));
});
