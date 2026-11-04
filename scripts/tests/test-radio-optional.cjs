const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const {spawnSync}=require('node:child_process'),{test}=require('node:test');
const assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../..');
const source=fs.readFileSync(path.join(root,'common/src/main/assets/voyah-helper/runtime.sh'),'utf8');
const block=source.slice(source.indexOf('radio() {'),source.indexOf('fail() {'));
const bash=process.env.BASH_EXE || 'C:/Program Files/Git/bin/bash.exe';
const base=path.join(root,'build/pi-no-radio98-20261102/optional-radio-tests');fs.mkdirSync(base,{recursive:true});
const quote=s=>"'"+s.replaceAll("'","'\\''")+"'";
function run(mode,{enabled=0,agent=false,packages='',pmError=false,noProcess=false}={}) {
 const dir=fs.mkdtempSync(path.join(base,'case-')).replaceAll('\\','/');
 fs.writeFileSync(path.join(dir,'radio-labels.conf'),String(enabled));
 fs.writeFileSync(path.join(dir,'radio-runtime.sh'),'fixture');fs.writeFileSync(path.join(dir,'radio-runtime.sha256'),'fixture');
 fs.mkdirSync(path.join(dir,'radio-runtime.lock'));fs.writeFileSync(path.join(dir,'radio-runtime.lock/owner-busy'),'keep');
 if(agent)fs.writeFileSync(path.join(dir,'radio-agent.pid'),'keep-agent-record');
 const script=`set -u
ROOT=${quote(dir)}
private_file() { [ -f "$1" ]; }
pidof() { ${noProcess?'return 1':'echo 123; return 0'}; }
cmd() { echo PM >> "$ROOT/calls"; printf '%s' ${quote(packages)}; return ${pmError?1:0}; }
sha256sum() { echo HASH >> "$ROOT/calls"; return 0; }
sh() { printf 'AGENT_%s\\n' "$2" >> "$ROOT/calls"; return 0; }
${block.replace('/system/bin/sh','sh')}
radio ${quote(mode)}
`;
 const p=spawnSync(bash,['--noprofile','--norc','-s'],{input:script,encoding:'utf8',timeout:15000});
 return {...p,dir,text:p.stdout+p.stderr,calls:fs.existsSync(path.join(dir,'calls'))?fs.readFileSync(path.join(dir,'calls'),'utf8'):''};
}
test('disabled unused labels do not query a radio, take its lock or start a script',()=>{
 const r=run('tick');assert.equal(r.status,0,r.text);assert.equal(r.calls,'');
 assert.equal(fs.readFileSync(path.join(r.dir,'radio-runtime.lock/owner-busy'),'utf8'),'keep');
});
test('PI without a radio and no owned agent skips stop despite an old radio lock',()=>{
 const r=run('stop');assert.equal(r.status,0,r.text);assert(r.text.includes('RADIO_LABELS_SKIPPED=not-installed'));
 assert.equal(r.calls,'PM\n');assert(fs.existsSync(path.join(r.dir,'radio-runtime.lock/owner-busy')));
});
test('a saved opt-in cannot create a radio on a machine without the package',()=>{
 const r=run('tick',{enabled:1});assert.equal(r.status,0,r.text);assert(!r.calls.includes('AGENT'));
});
test('disabled radio with no process or agent skips shutdown even with an OEM stub package',()=>{
 const r=run('stop',{packages:'package:com.pateo.rdsapp',noProcess:true});assert.equal(r.status,0,r.text);
 assert(r.text.includes('RADIO_LABELS_SKIPPED=disabled-no-process'));assert.equal(r.calls,'');
});
test('existing agent state is still restored even when the radio package disappeared',()=>{
 const r=run('stop',{agent:true});assert.equal(r.status,0,r.text);assert(r.calls.includes('AGENT_stop'));assert(!r.calls.includes('PM'));
});
test('a present radio is stopped through the verified script',()=>{
 const r=run('stop',{packages:'package:com.pateo.rdsapp'});assert.equal(r.status,0,r.text);assert(r.calls.includes('HASH'));assert(r.calls.includes('AGENT_stop'));
});
test('package manager errors or ambiguous replies never count as absence',()=>{
 for(const opts of [{pmError:true},{packages:'package:com.pateo.rdsapp.fake'},{packages:'unexpected'}]) {
  const r=run('stop',opts);assert.equal(r.status,1,r.text);assert(!r.calls.includes('AGENT'));
 }
});
const bootstrap=fs.readFileSync(path.join(root,'scripts/root-module/root-helper-bootstrap.sh'),'utf8');
const from=bootstrap.indexOf('if [ -n "$QUIESCE_UPLOAD" ]; then');
const stage=bootstrap.slice(from,bootstrap.indexOf('if [ "$MODE" = install ]; then',from));
function stageRun({badHash=false,badPath=false,mode='install'}={}) {
 const dir=fs.mkdtempSync(path.join(base,'stage-')).replaceAll('\\','/');
 const upload=dir+(badPath?'/other.sh':'/voyahplay-helper-test-quiesce.sh');fs.writeFileSync(upload,'echo STAGED\n');
 const digest=crypto.createHash('sha256').update(fs.readFileSync(upload)).digest('hex');
 const script=`set -eu
ROOT=${quote(dir)};QUIESCE_UPLOAD=${quote(upload)};QUIESCE_SHA=${quote(badHash?'0'.repeat(64):digest)};QUIESCE_STAGED=;MODE=${quote(mode)}
fail() { echo "ERROR $*"; exit 1; }
${stage.replaceAll('/data/local/tmp/voyahplay-helper-',dir+'/voyahplay-helper-')}
printf 'STAGED=%s\\n' "$QUIESCE_STAGED"
`;
 return spawnSync(bash,['--noprofile','--norc','-s'],{input:script,encoding:'utf8',timeout:15000});
}
test('installer shutdown payload is privately staged and checksum verified',()=>{
 const r=stageRun();assert.equal(r.status,0,r.stdout+r.stderr);assert(r.stdout.includes('HELPER_QUIESCE_SOURCE=installer'));
});
test('invalid payload hash, upload path and non-shutdown mode stop before execution',()=>{
 for(const opts of [{badHash:true},{badPath:true},{mode:'status'}]) {
  const r=stageRun(opts);assert.equal(r.status,1,r.stdout+r.stderr);assert(!r.stdout.includes('HELPER_QUIESCE_SOURCE=installer'));
 }
});
