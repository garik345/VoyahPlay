// Execute only the production shutdown/retry functions, without Android, root or ADB.
const fs = require('node:fs'), path = require('node:path');
const assert = require('node:assert/strict');
const {spawnSync} = require('node:child_process');
const {test} = require('node:test');
const root = path.resolve(__dirname,'../..');
const source = fs.readFileSync(path.join(root,'scripts/root-module/root-helper-bootstrap.sh'),'utf8');
const begin = source.indexOf('radio_lock_diagnostics() {');
const functions = source.slice(begin,source.indexOf('[ "$(id -u)" = 0 ]',begin));
assert(functions.includes('quiesce_owned_runtime()'));
assert(!functions.includes('kill ')); assert(!functions.includes('rm '));
const bash=process.env.BASH_EXE || (process.platform==='win32'?'C:/Program Files/Git/bin/bash.exe':'bash');
const base=path.join(root,'build/helper-install97-20261102/quiesce-tests');
fs.mkdirSync(base,{recursive:true});
const quote=s=>"'"+s.replaceAll("'","'\\''")+"'";
function run(scenario,setup='') {
    const dir=fs.mkdtempSync(path.join(base,'case-')).replaceAll('\\','/');
    fs.writeFileSync(path.join(dir,'runtime.sh'),`#!/bin/sh
n=$(cat "$ROOT/calls" 2>/dev/null || echo 0); n=$((n+1)); echo "$n" > "$ROOT/calls"
case "$SCENARIO" in
  ready) echo QUIESCED; exit 0;;
  transient) if [ "$n" -gt 2 ]; then echo QUIESCED; exit 0; fi;;
  restore) echo RADIO_LABELS_RESTORE_PENDING=1; echo 'ERROR radio-agent-stop'; exit 1;;
  identity) echo RADIO_LABELS_BUSY=1; echo 'ERROR supervisor-stop'; exit 1;;
  transport) echo RADIO_LABELS_BUSY=1; echo 'ERROR radio-agent-stop'; exit 2;;
esac
echo RADIO_LABELS_BUSY=1; echo 'ERROR radio-agent-stop'; exit 1
`);
    const script=`set -eu
ROOT=${quote(dir)}; export ROOT
SCENARIO=${quote(scenario)}; export SCENARIO
boot_id=1234-abcd
number() { case "$1" in ''|*[!0-9]*) return 1;; *) return 0;; esac; }
identity() { echo 222; }
process_args() { printf '/system/bin/sh\\n%s/radio-runtime.sh\\ntick\\n' "$ROOT"; }
stat() { if [ "$2" = '%u:%a' ]; then if [ -d "$3" ]; then printf '0:700'; else printf '0:600'; fi; else command stat "$@"; fi; }
sleep() { printf 'WAIT\\n' >> "$ROOT/waits"; }
${setup}
${functions}
quiesce_owned_runtime
`;
    const p=spawnSync(bash,['--noprofile','--norc','-s'],{input:script,encoding:'utf8',timeout:15000,env:{...process.env,MSYS_NO_PATHCONV:'1'}});
    return {...p,dir,text:p.stdout+p.stderr,calls:Number(fs.readFileSync(path.join(dir,'calls'),'utf8')),waits:fs.existsSync(path.join(dir,'waits'))?fs.readFileSync(path.join(dir,'waits'),'utf8').trim().split('\n').length:0};
}
test('normal shutdown is performed once',()=>{const r=run('ready');assert.equal(r.status,0,r.text);assert.equal(r.calls,1);assert.equal(r.waits,0);});
test('the logged radio busy race is retried before UID or snapshot replacement',()=>{
    const r=run('transient',`echo 10092 > "$ROOT/allowed.uid"; echo old > "$ROOT/app.apk"`);
    assert.equal(r.status,0,r.text);assert.equal(r.calls,3);assert.equal(r.waits,2);
    assert(r.text.includes('HELPER_QUIESCE_WAIT=radio-busy'));assert(!r.text.includes('RADIO_LOCK_DIAGNOSTICS=1'));
    assert.equal(fs.readFileSync(path.join(r.dir,'allowed.uid'),'utf8').trim(),'10092');
    assert.equal(fs.readFileSync(path.join(r.dir,'app.apk'),'utf8').trim(),'old');
});
test('permanent busy is bounded and preserves the incomplete lock',()=>{
    const r=run('busy',`mkdir "$ROOT/radio-runtime.lock"`);
    assert.equal(r.status,1,r.text);assert.equal(r.calls,8);assert.equal(r.waits,7);
    assert(r.text.includes('RADIO_LOCK_DIAGNOSTICS=1'));assert(r.text.includes('HELPER_QUIESCE_REPAIR='));
    assert(fs.existsSync(path.join(r.dir,'radio-runtime.lock')));
});
test('a live recorded owner is diagnosed and never killed or removed',()=>{
    const r=run('busy',`mkdir "$ROOT/radio-runtime.lock"; chmod 700 "$ROOT/radio-runtime.lock"
printf '1234-abcd 111 222\\n' > "$ROOT/radio-runtime.lock/owner-1234-abcd-111-222"
chmod 600 "$ROOT/radio-runtime.lock/owner-1234-abcd-111-222"`);
    assert.equal(r.status,1,r.text);assert(r.text.includes('RADIO_LOCK_OWNER_ALIVE=1'),r.text);
    assert(fs.existsSync(path.join(r.dir,'radio-runtime.lock/owner-1234-abcd-111-222')));
});
test('restoration, supervisor identity and unexpected exit errors are not treated as a busy race',()=>{
    for(const kind of ['restore','identity','transport']) {
        const r=run(kind);assert.equal(r.status,kind==='transport'?2:1,r.text);assert.equal(r.calls,1);assert.equal(r.waits,0);
        assert(!r.text.includes('HELPER_QUIESCE_WAIT='));
    }
});
