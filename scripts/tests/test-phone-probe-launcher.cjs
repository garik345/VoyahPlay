const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {spawnSync}=require('node:child_process');
const root=path.resolve(__dirname,'../..');
const dir=path.join(root,'build/phone-probe100-20261102',`launcher-tests-${process.pid}`,'Проверка с пробелами');
fs.mkdirSync(path.join(dir,'adb'),{recursive:true});
const starter=fs.readFileSync(path.join(root,'scripts/diagnostics/start-voyah-phone-probe.ps1'),'utf8').replace(/^\uFEFF/,'');
fs.writeFileSync(path.join(dir,'start.ps1'),'\uFEFF'+starter);
for(const name of ['AdbWinApi.dll','AdbWinUsbApi.dll']) fs.writeFileSync(path.join(dir,'adb',name),'fixture');
fs.writeFileSync(path.join(dir,'VoyahPlay-opack-100-phone-probe-receiver-debug.apk'),'fixture');
const cs=String.raw`
using System;using System.IO;using System.Text.RegularExpressions;
class FakeAdb {
 static string Extra(string[] args,string key) {
  for(int i=0;i+2<args.Length;i++)if(args[i]=="--es" && args[i+1]==key)return args[i+2];
  return null;
 }
 static string Event(string id,string name,string detail) {
  return "{\"requestId\":\""+id+"\",\"event\":\""+name+"\",\"detail\":\""+detail+"\"}";
 }
 static int Main(string[] a) {
  string log=Environment.GetEnvironmentVariable("PHONE_TEST_LOG"), scenario=Environment.GetEnvironmentVariable("PHONE_TEST_CASE");
  File.AppendAllText(log,String.Join("\t",a)+"\n");
  if(String.Join(" ",a)=="devices -l") {
   Console.WriteLine("List of devices attached");
   if(scenario=="NoDevices") return 0;
   Console.WriteLine("ONE device model:Voyah");if(scenario=="Multiple")Console.WriteLine("TWO device model:Voyah");return 0;
  }
  if(a.Length<3 || a[0]!="-s" || (a[1]!="ONE" && a[1]!="TWO"))return 90;
  if(a[2]=="install") {if(a.Length!=5 || a[3]!="-r" || !File.Exists(a[4]))return 91; Console.WriteLine("Success");return 0;}
  string command=String.Join(" ",a,2,a.Length-2);
  if(command.StartsWith("shell am broadcast ")) {
   if(Array.IndexOf(a,"--receiver-foreground")<0 || Array.IndexOf(a,"com.shilapi.xcertplay/com.shilapi.xcertplay.VoyahPhoneKeyProbeReceiver")<0)return 93;
   string id=Extra(a,"request_id"),mode=Extra(a,"mode");
   if(id==null || !Regex.IsMatch(id,"^[a-f0-9]{32}$") || (mode!="open-recents" && mode!="phone-key-30s" && mode!="stop"))return 94;
   if(scenario=="LaunchError") {Console.WriteLine("Error: Permission Denial: diagnostic receiver");return 0;}
   Console.WriteLine("Broadcasting: Intent { cmp=com.shilapi.xcertplay/.VoyahPhoneKeyProbeReceiver (has extras) }");
   if(scenario=="NoAck") {Console.WriteLine("Broadcast completed: result=200");return 0;}
   string acknowledged=scenario=="WrongAck"?"00000000000000000000000000000000":id;
   int result=scenario=="WrongResult"?0:(scenario=="RejectedWithError"?409:200);
   string state=scenario=="RejectedWithError"?"rejected":"accepted";
   Console.WriteLine("Broadcast completed: result="+result+", data=\"VP_PHONE_PROBE:"+acknowledged+":"+state+"\"");return 0;
  }
  if(a[2]=="exec-out" && a.Length==7 && a[3]=="run-as" && a[4]=="com.shilapi.xcertplay" && a[5]=="cat") {
   Match match=Regex.Match(a[6],"^files/phone-probe/([a-f0-9]{32})\\.jsonl$");if(!match.Success)return 95;
   string id=match.Groups[1].Value,mode=null;
   foreach(string line in File.ReadAllLines(log)) {
    string[] previous=line.Split('\t');
    if(Array.IndexOf(previous,"broadcast")>=0 && Extra(previous,"request_id")==id)mode=Extra(previous,"mode");
   }
   if(mode==null)return 96;
   if(scenario=="NoJournal") {Console.Error.WriteLine("cat: journal not created");return 1;}
   string owner=(scenario=="OldReady" && mode=="phone-key-30s") || scenario=="OldDelivered"?"11111111111111111111111111111111":id;
   Console.WriteLine(Event(owner,"ENTRY",mode));
   if(mode=="stop")Console.WriteLine(Event(id,"STOPPED","gate-disabled-unregister-queued"));
   else if(mode=="phone-key-30s")Console.WriteLine(Event(owner,"READY","duration=30s"));
   else if(scenario=="NoDelivered")Console.WriteLine(Event(id,"QUEUED","main-screen"));
   else if(scenario=="EntryError" || scenario=="RejectedWithError")Console.WriteLine(Event(id,"ERROR","no-idle-carplay-session"));
   else Console.WriteLine(Event(id,"RECENTS_DELIVERED",scenario=="FalseDelivered"?"false":"true"));
   return 0;
  }
  return 92;
 }
}`;
fs.writeFileSync(path.join(dir,'FakeAdb.cs'),cs);
fs.writeFileSync(path.join(dir,'compile.ps1'),"$ErrorActionPreference='Stop'\nAdd-Type -Path (Join-Path $PSScriptRoot 'FakeAdb.cs') -OutputAssembly (Join-Path $PSScriptRoot 'adb/adb.exe') -OutputType ConsoleApplication\n");
const compiled=spawnSync('powershell.exe',['-NoProfile','-ExecutionPolicy','Bypass','-File',path.join(dir,'compile.ps1')],{encoding:'utf8',timeout:30000});
assert.equal(compiled.status,0,compiled.stdout+compiled.stderr);
let i=0;
function logFiles() {
 const folder=path.join(dir,'logs');
 return fs.existsSync(folder)?fs.readdirSync(folder,{recursive:true}).map(n=>path.join(folder,n)).filter(n=>fs.statSync(n).isFile()):[];
}
function run(scenario,mode='Stop',input='') {
 const log=path.join(dir,`calls-${++i}.txt`);
 const before=new Set(logFiles());
 const r=spawnSync('powershell.exe',['-NoProfile','-ExecutionPolicy','Bypass','-File',path.join(dir,'start.ps1'),'-Mode',mode],
  {encoding:'utf8',input,timeout:30000,env:{...process.env,PHONE_TEST_LOG:log,PHONE_TEST_CASE:scenario}});
 assert(!r.error,r.error?.message);
 const calls=fs.readFileSync(log,'utf8').trim().split('\n').map(s=>s.split('\t'));
 const saved=logFiles().filter(p=>!before.has(p));
 const evidence=saved.map(p=>fs.readFileSync(p,'utf8')).join('\n');
 return {...r,output:r.stdout+r.stderr,calls,saved,evidence};
}
function broadcasts(r) {return r.calls.filter(a=>a.includes('broadcast'));}
function extra(args,name) {const i=args.findIndex((a,i)=>a==='--es' && args[i+1]===name);return i<0?null:args[i+2];}
function savedResult(r) {assert(r.saved.length>0,'Each attempted diagnostic must preserve evidence');assert(r.evidence.includes('Broadcast completed: result='),r.evidence);}
test('no connected vehicle performs no install or phone action',()=>{
 const r=run('NoDevices');assert.notEqual(r.status,0);assert.deepEqual(r.calls,[['devices','-l']]);
});
test('installation preserves a spaced APK path as one argument',()=>{
 const r=run('Ready','Install');assert.equal(r.status,0,r.output);
 const install=r.calls.find(a=>a[2]==='install');assert(install);assert.equal(install.length,5);assert(fs.existsSync(install[4]));
 assert(!r.calls.some(a=>a.includes('shell')));
});
test('multiple devices are selected explicitly and stop addresses only the protected receiver',()=>{
 const r=run('Multiple','Stop','2\r\n');assert.equal(r.status,0,r.output);
 assert(r.calls.filter(a=>a[0]==='-s').every(a=>a[1]==='TWO'));
 const am=broadcasts(r);assert.equal(am.length,1);assert.equal(extra(am[0],'mode'),'stop');
 assert.match(extra(am[0],'request_id'),/^[a-f0-9]{32}$/);
 assert(!r.calls.some(a=>a.includes('input') || a.includes('force-stop') || a.includes('call') || a.includes('start') || a.includes('logcat')));
 savedResult(r);
});
test('am errors are rejected even when the OEM shell returns exit code zero',()=>{
 const r=run('LaunchError');assert.notEqual(r.status,0);assert(r.output.includes('Permission Denial'));
 assert(r.saved.length>0);assert(r.evidence.includes('Permission Denial'));
});
test('OpenRecents requires an addressed ACK and a true delivery event from its private journal',()=>{
 const r=run('Ready','OpenRecents');assert.equal(r.status,0,r.output);savedResult(r);
 const am=broadcasts(r);assert.equal(am.length,1);assert.equal(extra(am[0],'mode'),'open-recents');
 const id=extra(am[0],'request_id');assert.match(id,/^[a-f0-9]{32}$/);
 assert(r.calls.some(a=>a[2]==='exec-out' && a.at(-1)===`files/phone-probe/${id}.jsonl`));
 assert(!r.calls.some(a=>a.includes('start') || a.includes('logcat')));
 assert.match(r.evidence,/"event"\s*:\s*"RECENTS_DELIVERED"/);
});
test('missing or mismatched ACK and a wrong broadcast result can never pass',()=>{
 for(const scenario of ['NoAck','WrongAck','WrongResult']) {
  const r=run(scenario,'OpenRecents');assert.notEqual(r.status,0,scenario+' '+r.output);savedResult(r);
  assert.equal(broadcasts(r).length,1);
 }
});
test('a private journal that is missing or lacks successful delivery cannot pass',()=>{
 for(const scenario of ['NoJournal','NoDelivered','OldDelivered','FalseDelivered','EntryError']) {
  const r=run(scenario,'OpenRecents');assert.notEqual(r.status,0,scenario+' '+r.output);savedResult(r);
  assert.equal(broadcasts(r).length,1);
  if(scenario==='NoDelivered')assert.match(r.evidence,/"event"\s*:\s*"QUEUED"/);
  if(scenario==='FalseDelivered')assert.match(r.evidence,/"detail"\s*:\s*"false"/);
 }
});
test('READY from another request cannot start the test; cleanup uses a fresh addressed stop',()=>{
 const r=run('OldReady','WheelTest');assert.notEqual(r.status,0,r.output);
 const am=broadcasts(r);assert.equal(am.length,2);assert.equal(extra(am[0],'mode'),'phone-key-30s');assert.equal(extra(am[1],'mode'),'stop');
 const id=extra(am[0],'request_id'),stopId=extra(am[1],'request_id');assert.match(stopId,/^[a-f0-9]{32}$/);assert.notEqual(stopId,id);
 savedResult(r);assert(r.evidence.includes('11111111111111111111111111111111'));
});
test('a rejected current request preserves its private error without becoming successful',()=>{
 const r=run('RejectedWithError','OpenRecents');assert.notEqual(r.status,0,r.output);savedResult(r);
 assert.equal(broadcasts(r).length,1);
 assert(r.evidence.includes('Broadcast completed: result=409'));
 assert.match(r.evidence,/"event"\s*:\s*"ENTRY"/);
 assert.match(r.evidence,/"event"\s*:\s*"ERROR"/);
 assert(r.evidence.includes('no-idle-carplay-session'));
});
