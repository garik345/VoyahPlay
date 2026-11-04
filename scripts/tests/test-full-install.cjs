// Real Windows launcher and helper installer, isolated from all real ADB devices.
const fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto');
const assert = require('node:assert/strict');
const {spawnSync} = require('node:child_process');
const {test} = require('node:test');
const root = path.resolve(__dirname, '../..');
const fixture = path.join(root, 'build', `full-install-tests-${process.pid}`, 'Проверка с пробелами');
fs.mkdirSync(path.join(fixture, 'adb'), {recursive:true});
const scriptSource = fs.readFileSync(path.join(root, 'scripts/root-module/build-full-install.ps1'),'utf8');
const profiles = [
    {name:'RU',bridgeProfile:'RU',services:'0faed7ebf1094c62a4f92b4bc533bbb3c08480a2cd847567cb1cff91b2d1d045',systemui:'28f5d93eaec22b755b339193a733bd0d4160c496cf301bcd2fcfb88c36ade162'},
    {name:'PI',bridgeProfile:'PI',services:'483fedea7707833454272f84a64692078996127d64abac63f13863b76246a579',systemui:'5d5f7498d92ee68e927bc0b891daedf15978d079b85a254399112bad98d2dd6b'},
    {name:'PI72',bridgeProfile:'PI',services:'262bfd515f5c181a556477cadbdee1e574ead0ef1acff158dc3fa25bd8b84c38',systemui:'b676a3a426ba3b59fabb9d98355ff0e0ac7b07d7ad6545ac44d9cbf85f1700c1'},
];
for (const p of profiles) { assert(scriptSource.includes(p.services)); assert(scriptSource.includes(p.systemui)); }
const version = fs.readFileSync(path.join(root,'common/src/main/assets/voyah-helper/runtime.sh'),'utf8').match(/^VERSION=(.+)$/m)[1].trim();
const cs = String.raw`
using System; using System.IO;
class FakeAdb {
static int Main(string[] args) {
File.AppendAllText(Environment.GetEnvironmentVariable("VOYAH_FULL_LOG"),String.Join("\t",args)+"\n");
string s=Environment.GetEnvironmentVariable("VOYAH_FULL_SCENARIO"), c=String.Join(" ",args);
if(c=="devices -l") { Console.WriteLine("List of devices attached"); if(s=="Missing") return 0;
Console.WriteLine("CAR-ONE "+(s=="Offline"?"offline":s=="Unauthorized"?"unauthorized":"device")+" model:Voyah");
if(s=="Multiple") Console.WriteLine("CAR-TWO device model:Voyah"); return 0; }
if(args.Length<3 || args[0]!="-s" || (args[1]!="CAR-ONE" && args[1]!="CAR-TWO")) return 88;
c=String.Join(" ",args,2,args.Length-2);
if(c=="shell su 0 id") {Console.WriteLine(s=="NoRoot"?"uid=2000(shell)":"uid=0(root)");return 0;}
if(c=="shell getprop ro.build.version.sdk") {Console.WriteLine(s=="OldAndroid"?"29":"30");return 0;}
if(c.StartsWith("shell su 0 sha256sum ")) {
bool ui=c.EndsWith(".apk"); string value=ui?"${profiles[0].systemui}":"${profiles[0].services}";
if(s=="PI") value=ui?"${profiles[1].systemui}":"${profiles[1].services}";
if(s=="PI72") value=ui?"${profiles[2].systemui}":"${profiles[2].services}";
if(s=="Unknown") value="unknown"; if(s=="WrongPair" && ui) value="${profiles[1].systemui}";
Console.WriteLine(value+"  file");return 0; }
if(c=="shell pm list packages ru.kachalin.voyahtweaks") {if(s!="NoTweaks") Console.WriteLine("package:ru.kachalin.voyahtweaks");return 0;}
if(args[2]=="push" || c.StartsWith("shell su 0 rm -f ")) return 0;
if(args[2]=="install") {if(s=="ApkFailure") return 1;Console.WriteLine("Success");return 0;}
if(c.StartsWith("shell monkey ")) return 0;
if(args[2]=="get-state") {Console.WriteLine("device");return 0;}
if(args[2]=="shell" && args[3]=="su" && args[4]=="0" && args[5]=="sh") {
if(args[6].StartsWith("/data/local/tmp/voyah-bounds-")) {
if(s=="BridgeCheckFailure" && args[7]=="check") return 1;
if(s=="BridgeInstallFailure" && args[7]=="install") return 1;
Console.WriteLine("FIRMWARE_VERIFIED=1");return 0;}
if(args[6].StartsWith("/data/local/tmp/voyahplay-helper-")) {
if(s=="HelperFailure" && args[7]=="install") return 1;
if(s=="AutostartFailure" && args[7]=="enableautostart") return 1;
if(args[7]=="enableautostart") {Console.WriteLine("AUTOSTART_ENABLED=1");return 0;}
Console.WriteLine("HELPER_VERSION=${version}\nHELPER_READY=1\nCAPABILITIES=TWEAKS_BOUNDS_1_1\nBOOT_MOUNT_READ_ONLY=1");return 0;}}
return 89; }}
`;
const compile = path.join(fixture,'compile.ps1');
fs.writeFileSync(compile, `$src=@'\n${cs}\n'@\nAdd-Type -TypeDefinition $src -OutputAssembly (Join-Path $PSScriptRoot 'adb/adb.exe') -OutputType ConsoleApplication\n`);
let r=spawnSync('powershell.exe',['-NoProfile','-ExecutionPolicy','Bypass','-File',compile],{encoding:'utf8',timeout:30000});
assert.equal(r.status,0,r.stdout+r.stderr);
for(const f of ['start-full-install.ps1','root-helper.ps1','root-helper-bootstrap.sh']) fs.copyFileSync(path.join(root,'scripts/root-module',f),path.join(fixture,f));
const required=['VoyahPlay.apk','bridge-installer.sh','bridge/ss11.bin','prerequisites/VoyahTweaks.apk','prerequisites/frida-inject','prerequisites/QGWindowPolicy.apk','adb/AdbWinApi.dll','adb/AdbWinUsbApi.dll','INSTALL.cmd','STATUS.cmd','RESTORE_BRIDGE.cmd','COLLECT.cmd'];
required.push('helper-quiesce.sh');
for(const f of required) {fs.mkdirSync(path.dirname(path.join(fixture,f)),{recursive:true});fs.writeFileSync(path.join(fixture,f),'fixture');}
fs.writeFileSync(path.join(fixture,'profiles.json'),JSON.stringify(profiles));
const files=[...required,'profiles.json','start-full-install.ps1','root-helper.ps1','root-helper-bootstrap.sh','adb/adb.exe'];
function manifest() {fs.writeFileSync(path.join(fixture,'FILES-SHA256.json'),JSON.stringify(files.map(file=>({file,sha256:crypto.createHash('sha256').update(fs.readFileSync(path.join(fixture,file))).digest('hex')}))));}
manifest(); let number=0;
function run(scenario,opts={}) {
    const log=path.join(fixture,`commands-${++number}.txt`),args=['-NoProfile','-ExecutionPolicy','Bypass','-File',path.join(fixture,'start-full-install.ps1'),'-Mode',opts.mode||'Install'];
    if(opts.serial) args.push('-Serial',opts.serial);
    const p=spawnSync('powershell.exe',args,{encoding:'utf8',input:opts.input,timeout:30000,env:{...process.env,VOYAH_FULL_LOG:log,VOYAH_FULL_SCENARIO:scenario}});
    return {...p,text:p.stdout+p.stderr,commands:fs.existsSync(log)?fs.readFileSync(log,'utf8'):''};
}
test('RU and both PI pairs install APK, matching bridge then current helper, without reboot',()=>{
    for(const scenario of ['RU','PI','PI72']) {
        const r=run(scenario);assert.equal(r.status,0,r.text);
        const c=r.commands;
        assert(c.indexOf('\tcheck\t')<c.indexOf('\tinstall\t-r\t'));
        assert(c.indexOf('\tinstall\t-r\t')<c.indexOf('\tinstall\t/data/local/tmp/voyah-bounds-'));
        assert(c.includes(`\t${scenario==='RU'?'RU':'PI'}\n`));
        assert(!c.includes('reboot'));assert(!c.includes('remount'));assert(!c.includes('uninstall'));
        assert(c.includes('\tenableautostart\n'));
        assert(r.text.includes('Установка завершена'));
    }
});
test('missing device, no root, old Android and unknown pairs never install or upload',()=>{
    for(const scenario of ['Missing','Offline','Unauthorized','NoRoot','OldAndroid','Unknown','WrongPair']) {
        const r=run(scenario);assert.notEqual(r.status,0,r.text);assert(!r.commands.includes('\tpush\t'));assert(!r.commands.includes('\tinstall\t'));
    }
});
test('several devices require a choice; explicit serial and cancel are respected',()=>{
    const chosen=run('Multiple',{input:'2\n'});assert.equal(chosen.status,0,chosen.text);assert(!chosen.commands.includes('CAR-ONE\t'));
    const explicit=run('Multiple',{serial:'CAR-TWO'});assert.equal(explicit.status,0,explicit.text);assert(!explicit.commands.includes('CAR-ONE\t'));
    const cancel=run('Multiple',{input:'0\n'});assert.equal(cancel.status,2,cancel.text);assert(!cancel.commands.includes('\tpush\t'));
});
test('fresh Tweaks setup is a distinct pending step without VoyahPlay or helper installation',()=>{
    const r=run('NoTweaks');assert.equal(r.status,2,r.text);assert(r.commands.includes('VoyahTweaks.apk'));assert(!r.commands.includes('\tpush\t'));
    assert(!r.commands.includes('VoyahPlay.apk'));assert(!r.text.includes('Установка завершена'));
});
test('failed preflight, APK, bridge or helper never announces completion or proceeds to later stages',()=>{
    for(const scenario of ['BridgeCheckFailure','ApkFailure','BridgeInstallFailure','HelperFailure','AutostartFailure']) {
        const r=run(scenario);assert.notEqual(r.status,0,r.text);assert(!r.text.includes('Установка завершена'));
        if(scenario==='BridgeCheckFailure') assert(!r.commands.includes('\tinstall\t-r\t'));
        if(scenario==='ApkFailure') assert(!r.commands.includes('\tinstall\t/data/local/tmp/voyah-bounds-'));
        if(scenario==='BridgeInstallFailure') assert(!r.commands.includes('/data/local/tmp/voyahplay-helper-'));
    }
});
test('status and bridge restore never update APK or helper',()=>{
    for(const mode of ['Status','RestoreBridge']) {const r=run('RU',{mode});assert.equal(r.status,0,r.text);assert(!r.commands.includes('\tinstall\t'));assert(!r.commands.includes('reboot'));}
});
test('a damaged shipment stops before invoking ADB',()=>{
    fs.appendFileSync(path.join(fixture,'VoyahPlay.apk'),'damage');
    const r=run('RU');assert.notEqual(r.status,0);assert.equal(r.commands,'');
});
