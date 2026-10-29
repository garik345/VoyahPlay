// Execute the production startup function; only process/system boundaries are local stubs.
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {spawnSync}=require('node:child_process');
const {test}=require('node:test');
const root=path.resolve(__dirname,'../..');
const runtime=fs.readFileSync(path.join(root,'common/src/main/assets/voyah-helper/runtime.sh'),'utf8');
function block(name,next) {const start=runtime.indexOf(name+'() {'),end=runtime.indexOf('\n'+next+'() {',start);assert(start>=0&&end>start);return runtime.slice(start,end);}
const code=block('agent_paths','fullscreen_firmware')+'\n'+block('start_agent','start_watch');
const bash=process.env.BASH_EXE||(process.platform==='win32'?'C:/Program Files/Git/bin/bash.exe':'bash');
let sequence=0;
function run(changes={}) {
 const dir=path.join(root,'build',`dock-runtime-tests-${process.pid}`,String(++sequence)).replaceAll('\\','/');
 fs.mkdirSync(dir,{recursive:true});
 const input=`set -u
ROOT="$FIXTURE"
boot_id=fixture-boot
private_file() { return 0; }
number() { case "$1" in ''|*[!0-9]*) return 1;; *) return 0;; esac; }
stat() { echo "$APK_SIZE"; }
sha256sum() {
 if [ "$1" = -c ]; then [ "$BAD_ASSET" = 0 ]; return $?; fi
 case "$1" in
  *Launcher-release-signed.apk) echo SHA_LAUNCHER >> "$ROOT/calls"; echo "$APK_HASH $1";;
  *frida-inject) echo "$INJECTOR_HASH $1";;
  *) return 1;;
 esac
}
pidof() { echo "PIDOF:$1" >> "$ROOT/calls"; printf '%s\\n' "$TARGET_PID"; }
process_args() { printf '%s\\n' "$TARGET_NAME"; }
awk() { if [ "$1" = '/^Uid:/ { print $2 }' ]; then echo "$TARGET_UID"; else command awk "$@"; fi; }
agent_alive() { agent_system=$RECORDED_TARGET; [ "$ALIVE" = 1 ]; }
stop_agent() { echo STOP_OWNED >> "$ROOT/calls"; }
fullscreen_firmware() { [ "$FIRMWARE" = valid ]; }
identity() { echo 12345; }
nohup() { echo "INJECT:$*" >> "$ROOT/calls"; }
${code}
result=0
start_agent dock || result=$?
wait
exit "$result"
`;
 const env={...process.env,FIXTURE:dir,MSYS_NO_PATHCONV:'1',APK_SIZE:'126586649',
  APK_HASH:'0430e677f8eabafd59876f56bc5835a06e626c2aa484b6f306c0a7b31b04d63d',
  INJECTOR_HASH:'0d99be0e44d4ca3449056fa0a0b868fca1cf66c8ad704508949945afc07706aa',
  TARGET_PID:'3456',TARGET_NAME:'com.qinggan.app.launcher',TARGET_UID:'1000',
  RECORDED_TARGET:'3456',FIRMWARE:'valid',ALIVE:'0',BAD_ASSET:'0',...changes};
 const r=spawnSync(bash,['--noprofile','--norc','-s'],{input,encoding:'utf8',env,timeout:10000});
 assert(!r.error,r.error?.message);
 const calls=fs.existsSync(dir+'/calls')?fs.readFileSync(dir+'/calls','utf8'):'';
 return {...r,calls,dir};
}
test('reviewed Launcher starts only the owned script in the verified Launcher process',()=>{
 const r=run();assert.equal(r.status,0,r.stdout+r.stderr);
 assert(r.calls.includes('PIDOF:com.qinggan.app.launcher'));
 assert(r.calls.includes('-p 3456 -s '+r.dir+'/dock-guard.js'));
 assert(!r.calls.includes('system_server'));
 assert(fs.existsSync(r.dir+'/dock-agent.pid'));
});
test('other Launcher sizes are optional and do not trigger hashing or injection',()=>{
 const r=run({APK_SIZE:'51888996'});assert.equal(r.status,0);assert.equal(r.calls,'');
});
test('reviewed OTA7.2 Launcher is accepted while the same-size unknown build is refused',()=>{
 const r=run({APK_SIZE:'126653880',APK_HASH:'b3287150827e7fa51072bcbcba257539101cda8433c5f9d3a95f4ca905abd567'});
 assert.equal(r.status,0,r.stdout+r.stderr);assert(r.calls.includes('INJECT:'));
 const bad=run({APK_SIZE:'126653880',APK_HASH:'unknown'});
 assert.notEqual(bad.status,0);assert(!bad.calls.includes('INJECT:'));
});
test('a changed APK, injector, firmware, target identity or private asset never gets injected',()=>{
 for(const change of [{APK_HASH:'unreviewed'},{INJECTOR_HASH:'unreviewed'},{FIRMWARE:'unknown'},
  {TARGET_UID:'10001'},{TARGET_NAME:'other.process'},{TARGET_PID:'12 34'},{BAD_ASSET:'1'}]) {
  const r=run(change);assert.notEqual(r.status,0,JSON.stringify(change));assert(!r.calls.includes('INJECT:'),r.calls);
 }
});
test('a live owned agent in the same process is reused without hashing the large APK again',()=>{
 const r=run({ALIVE:'1'});assert.equal(r.status,0);assert(!r.calls.includes('SHA_LAUNCHER'));assert(!r.calls.includes('INJECT:'));
});
