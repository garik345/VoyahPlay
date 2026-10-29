// Native fake ADB: exercise Windows PowerShell 5.1 byte transport and command scope.
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const crypto=require('node:crypto');
const {spawnSync}=require('node:child_process');
const {test}=require('node:test');
const root=path.resolve(__dirname,'../..');
const fixture=path.join(root,'build',`compatibility-tests-${process.pid}`,'Проверка с пробелами');
fs.mkdirSync(path.join(fixture,'adb'),{recursive:true});
for(const name of ['collect-voyah-compatibility.ps1','start-voyah-compatibility.ps1'])
 fs.copyFileSync(path.join(root,'scripts/diagnostics',name),path.join(fixture,name));
for(const name of ['AdbWinApi.dll','AdbWinUsbApi.dll']) fs.writeFileSync(path.join(fixture,'adb',name),'fixture');
const data=Buffer.from([0,255,254,13,10,0,65,128,1,2,13,10,0,90]);
const remoteFiles=new Map();
for(const name of ['QGAPI.jar','QGVehicle.jar','services.jar','framework.jar','QGMOD.jar','ActivityExt.jar','car-frameworks-service.jar']) remoteFiles.set('/system/framework/'+name,data);
for(const name of ['ss11.bin','ss9.bin','load.bin','frida-inject']) remoteFiles.set('/data/local/tmp/'+name,data);
remoteFiles.set('/system/etc/init.logcat.sh',Buffer.from('#!/system/bin/sh\nlogcat\n'));
const packages={
 'com.qinggan.canbus.service':['/system/priv-app/CanBus/base.apk','/system/priv-app/CanBus/split_config.apk'],
 'com.qinggan.carsignal.service':['/system/app/Signal/base.apk'],
 'com.qinggan.app.launcher':['/system/priv-app/Launcher/base.apk'],
 'com.qinggan.systemui':['/system/priv-app/SystemUI/base.apk'],
 'com.qinggan.QGBus':['/system/app/QGBus/base.apk'],
 'com.qinggan.cluster':['/system/app/Cluster/base.apk'],
 'com.qinggan.app.vehicle':['/system/priv-app/Vehicle/base.apk'],
 'com.pateo.rdsapp':['/vendor/app/rdsapp/base.apk']
};
for(const paths of Object.values(packages)) for(const p of paths) remoteFiles.set(p,data);
const sha=b=>crypto.createHash('sha256').update(b).digest('hex');
fs.writeFileSync(path.join(fixture,'remote-files.txt'),[...remoteFiles].map(([n,b])=>n+'\t'+b.toString('base64')+'\t'+sha(b)).join('\n'));
fs.writeFileSync(path.join(fixture,'packages.txt'),Object.entries(packages).map(([n,paths])=>n+'\t'+paths.join('\t')).join('\n'));
const cs=String.raw`
using System;
using System.IO;
using System.Collections.Generic;
class FakeAdb {
 static int Main(string[] args) {
  File.AppendAllText(Environment.GetEnvironmentVariable("VOYAH_TEST_LOG"),String.Join("\t",args)+"\n");
  string scenario=Environment.GetEnvironmentVariable("VOYAH_TEST_SCENARIO");
  if(String.Join(" ",args)=="devices -l") {
   Console.WriteLine("List of devices attached");
   if(scenario=="NoDevices") return 0;
   if(scenario=="Multiple") {Console.WriteLine("PI-ONE device model:Voyah");Console.WriteLine("PI-TWO device model:Voyah");}
   else Console.WriteLine("PI-ONE "+(scenario=="Unauthorized"?"unauthorized":"device")+" model:Voyah");
   return 0;
  }
  if(args.Length<3 || args[0]!="-s" || (args[1]!="PI-ONE" && args[1]!="PI-TWO")) return 80;
  string folder=Environment.GetEnvironmentVariable("VOYAH_TEST_FILES");
  var files=new Dictionary<string,string[]>();
  foreach(string line in File.ReadAllLines(Path.Combine(folder,"remote-files.txt"))) {var row=line.Split('\t'); files.Add(row[0],row);}
  if(scenario=="VendorSdk") {
   foreach(string name in new string[]{"QGVehicle.jar","QGAPI.jar"}) {
    string oldPath="/system/framework/"+name;
    string newPath=(name=="QGAPI.jar"?"/vendor":"/system_ext")+"/framework/"+name;
    string[] row=(string[])files[oldPath].Clone();row[0]=newPath;files.Remove(oldPath);files.Add(newPath,row);
   }
  }
  var packages=new Dictionary<string,string[]>();
  foreach(string line in File.ReadAllLines(Path.Combine(folder,"packages.txt"))) {var row=line.Split('\t'); packages.Add(row[0],row);}
  if(args[2]=="exec-out") {
   int p=3; if(args[p]=="su") {if(scenario=="NoRoot") return 1; p+=2;}
   if(p+2!=args.Length || args[p]!="cat") return 81;
   string file=args[p+1]; if(!files.ContainsKey(file)) return 1;
   byte[] bytes=Convert.FromBase64String(files[file][1]);
   if(scenario=="Corrupt" && file.EndsWith("QGAPI.jar")) bytes[1]^=1;
   Stream output=Console.OpenStandardOutput();output.Write(bytes,0,bytes.Length);
   if(scenario=="Growing" && file.EndsWith("QGAPI.jar")) output.WriteByte(100);
   return 0;
  }
  if(args[2]!="shell") return 82;
  int at=3;
  if(args[at]=="su") {if(scenario=="NoRoot" || scenario=="NoSuRoot") return 1;at+=2;}
  string command=String.Join(" ",args,at,args.Length-at);
  if(command=="id") {Console.WriteLine(scenario=="NoRoot"?"uid=2000(shell)":"uid=0(root)");return 0;}
  if(args[at]=="getprop") {Console.WriteLine(args[at+1]=="ro.build.version.sdk"?(scenario=="Android9"?"28":"30"):"fixture-firmware");return 0;}
  if(command=="wm size") {Console.WriteLine("Physical size: 1920x720");return 0;}
  if(command=="wm density") {Console.WriteLine("Physical density: 160");return 0;}
  if(command=="pm list packages") {
   foreach(string name in packages.Keys) Console.WriteLine("package:"+name);
   Console.WriteLine("package:private.bank.app");return 0;
  }
  if(command.StartsWith("pm path ")) {
   string name=args[at+2]; if(!packages.ContainsKey(name)) return 1;
   if(scenario=="UnsafePath" && name=="com.qinggan.app.launcher") {Console.WriteLine("package:/system/app/../../data/private.apk");Console.WriteLine("package:/data/app/evil;id.apk");return 0;}
   string[] row=packages[name];for(int i=1;i<row.Length;i++) Console.WriteLine("package:"+row[i]);return 0;
  }
  if(command.StartsWith("dumpsys package ")) {Console.WriteLine(" versionCode=1208 minSdk=28\n versionName=fixture\n android.permission.WRITE_CANBUS: granted=true\n imei=123456");return 0;}
  if(command=="dumpsys window windows") {Console.WriteLine(" mCurrentFocus=Window{abc u0 com.qinggan.app.vehicle/Climate}\n mFrame=[0,0][1920,720]\n ssid=PRIVATE_NETWORK");return 0;}
  if(args[at]=="logcat") {Console.WriteLine("VoyahDockVisibility: visible=false 192.168.1.11 11:22:33:44:55:66\nssid=PRIVATE_NETWORK");return 0;}
  if(args[at]=="settings") {Console.WriteLine("null");return 0;}
  if(args[at]=="run-as" || args[at]=="tail") {Console.Error.WriteLine("not installed");return 1;}
  string remote=args[args.Length-1];
  if(!files.ContainsKey(remote)) return 1;
  if(args[at]=="sha256sum") {
   if(scenario=="Missing" && remote.EndsWith("QGVehicle.jar")) return 1;
   string hash=files[remote][2];
   if(remote.EndsWith("frida-inject") && scenario!="NewInjector") hash="0d99be0e44d4ca3449056fa0a0b868fca1cf66c8ad704508949945afc07706aa";
   Console.WriteLine(hash+"  "+remote);return 0;
  }
  if(args[at]=="stat") {Console.WriteLine(scenario=="Oversized" && remote.EndsWith("QGAPI.jar")?"9999999999":Convert.FromBase64String(files[remote][1]).Length.ToString());return 0;}
  if(args[at]=="ls") {Console.WriteLine("lrwxrwxrwx root root "+remote+" -> /reviewed/target");return 0;}
  if(args[at]=="readlink") {Console.WriteLine(remote);return 0;}
  Console.Error.WriteLine("Unexpected command "+command);return 83;
 }
}
`;
fs.writeFileSync(path.join(fixture,'FakeAdb.cs'),cs);
fs.writeFileSync(path.join(fixture,'compile.ps1'),"$ErrorActionPreference='Stop'\nAdd-Type -Path (Join-Path $PSScriptRoot 'FakeAdb.cs') -OutputAssembly (Join-Path $PSScriptRoot 'adb/adb.exe') -OutputType ConsoleApplication\n");
const compiled=spawnSync('powershell.exe',['-NoProfile','-ExecutionPolicy','Bypass','-File',path.join(fixture,'compile.ps1')],{encoding:'utf8',timeout:30000});
assert.equal(compiled.status,0,compiled.stdout+compiled.stderr);
let sequence=0;
function run(scenario, options={}) {
 const out=path.join(fixture,String(++sequence)),log=out+'.log';fs.mkdirSync(out);
 const args=['-NoProfile','-ExecutionPolicy','Bypass','-File',path.join(fixture,options.launcher?'start-voyah-compatibility.ps1':'collect-voyah-compatibility.ps1'),'-OutputDirectory',out,'-TraceSeconds',String(options.trace||0)];
 if(!options.launcher) args.push('-Adb',path.join(fixture,'adb/adb.exe'));
 if(options.serial) args.push('-Serial',options.serial);
 const r=spawnSync('powershell.exe',args,{encoding:'utf8',input:options.input||'',timeout:90000,env:{...process.env,VOYAH_TEST_LOG:log,VOYAH_TEST_SCENARIO:scenario,VOYAH_TEST_FILES:fixture}});
 assert(!r.error,r.error?.message);
 const dirs=fs.readdirSync(out).filter(n=>n.startsWith('Voyah-compatibility-') && !n.endsWith('.zip'));
 const capture=dirs.length?path.join(out,dirs[0]):null;
 const summary=capture && fs.existsSync(path.join(capture,'summary.json'))?JSON.parse(fs.readFileSync(path.join(capture,'summary.json'),'utf8')):null;
 const calls=fs.existsSync(log)?fs.readFileSync(log,'utf8').trim().split('\n').map(s=>s.split('\t')):[];
 return {...r,output:r.stdout+r.stderr,out,capture,summary,calls};
}
function good(r) {assert.equal(r.status,0,r.output);assert(r.capture);assert(fs.existsSync(r.capture+'.zip'));}
test('collects binary OEM SDK, split APKs, Launcher and window trace without an installed VoyahPlay',()=>{
 const r=run('Ready',{trace:1});good(r);assert(r.summary.root);
 assert.deepEqual(fs.readFileSync(path.join(r.capture,'framework/QGAPI.jar')),data);
 assert.deepEqual(fs.readFileSync(path.join(r.capture,'apks/com.qinggan.canbus.service-1.apk')),data);
 assert.deepEqual(fs.readFileSync(path.join(r.capture,'apks/com.qinggan.app.launcher-0.apk')),data);
 assert(fs.existsSync(path.join(r.capture,'windows-trace.txt')));
 const text=fs.readFileSync(path.join(r.capture,'report.txt'),'utf8');
 assert(!text.includes('private.bank.app'));assert(!text.includes('PRIVATE_NETWORK'));
 assert(!text.includes('11:22:33:44:55:66'));assert(!text.includes('192.168.1.11'));
 const injector=r.summary.files.find(f=>f.remote.endsWith('frida-inject'));assert(!injector.captured);assert(injector.reason.includes('reviewed'));
 assert(!r.calls.some(a=>a.includes('exec-out') && a.at(-1).endsWith('frida-inject')));
});
test('continues without root, on Android 9 and with missing optional files',()=>{
 for(const scenario of ['NoRoot','NoSuRoot','Android9','Missing']) {
  const r=run(scenario);good(r);
  assert(fs.existsSync(path.join(r.capture,'framework/services.jar')));
  if(scenario==='NoRoot') {assert(!r.summary.root);assert(!r.calls.some(a=>a[2]==='exec-out' && a[3]==='su'));}
  if(scenario==='NoSuRoot') {assert(r.summary.root);assert(!r.summary.useSu);assert(!r.calls.some(a=>a[2]==='exec-out' && a[3]==='su'));}
  if(scenario==='Missing') assert(r.summary.unavailable.some(s=>s.includes('QGVehicle.jar')));
 }
});
test('corrupt, growing and oversized files are rejected without keeping partial bytes',()=>{
 for(const scenario of ['Corrupt','Growing','Oversized']) {
  const r=run(scenario);good(r);
  assert(!fs.existsSync(path.join(r.capture,'framework/QGAPI.jar')));
  assert(!fs.existsSync(path.join(r.capture,'framework/QGAPI.jar.partial')));
  assert(r.summary.unavailable.some(s=>s.includes('QGAPI.jar')));
 }
});
test('unknown injector is captured and invalid package paths never reach a read command',()=>{
 const changed=run('NewInjector');good(changed);assert.deepEqual(fs.readFileSync(path.join(changed.capture,'tweaks/frida-inject')),data);
 const unsafe=run('UnsafePath');good(unsafe);
 assert(!unsafe.calls.some(a=>a.some(v=>v.includes('../') || v.includes(';'))));
 assert(!fs.existsSync(path.join(unsafe.capture,'apks/com.qinggan.app.launcher-0.apk')));
});
test('SDK files on system_ext and vendor are captured under their actual partition names',()=>{
 const r=run('VendorSdk');good(r);
 assert.deepEqual(fs.readFileSync(path.join(r.capture,'framework/system_ext-QGVehicle.jar')),data);
 assert.deepEqual(fs.readFileSync(path.join(r.capture,'framework/vendor-QGAPI.jar')),data);
});
test('the launcher selects one device and all device operations are read-only',()=>{
 const r=run('Multiple',{launcher:true,input:'2\r\n'});good(r);
 assert(r.calls.filter(a=>a[0]==='-s').every(a=>a[1]==='PI-TWO'));
 for(const a of r.calls) {
  if(a[0]==='devices') continue;
  assert(a[0]==='-s');let cmd=a.slice(2);
  if(cmd[0]==='exec-out') {assert(['cat','su'].includes(cmd[1]));assert.equal(cmd.at(-2),'cat');continue;}
  assert.equal(cmd.shift(),'shell');if(cmd[0]==='su') cmd=cmd.slice(2);
  assert(['id','getprop','wm','dumpsys','pm','sha256sum','ls','readlink','stat','settings','run-as','logcat','tail'].includes(cmd[0]),cmd.join(' '));
  if(cmd[0]==='pm') assert(['path','list'].includes(cmd[1]));
  if(cmd[0]==='settings') assert.equal(cmd[1],'get');
  if(cmd[0]==='wm') assert.equal(cmd.length,2);
  if(cmd[0]==='run-as') assert.equal(cmd[2],'cat');
 }
});
test('no device, unauthorized and cancelled choice perform no vehicle reads',()=>{
 for(const [scenario,input] of [['NoDevices',''],['Unauthorized',''],['Multiple','0\r\n']]) {
  const r=run(scenario,{launcher:true,input});assert.notEqual(r.status,0);assert(!r.capture);
  assert(r.calls.every(a=>a[0]==='devices'));
 }
});
