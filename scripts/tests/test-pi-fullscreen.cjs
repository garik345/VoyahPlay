// Run actual firmware guards and replacement logic against local, isolated files.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const {spawnSync} = require('node:child_process');
const {test} = require('node:test');
const project = path.resolve(__dirname, '../..');
const base = path.join(project, 'build', `pi-fullscreen-tests-${process.pid}`);
const bash = process.env.BASH_EXE || (process.platform === 'win32' ? 'C:/Program Files/Git/bin/bash.exe' : 'bash');
// Optional real Android-style shell: extract Debian's mksh locally, no system installation.
const wslShell = process.env.VOYAH_PI_TEST_WSL_SHELL;
function shellPath(value) {
    return wslShell ? value.replace(/^([A-Za-z]):/, (_,drive) => '/mnt/'+drive.toLowerCase()) : value;
}
function executeShell(args, options) {
    if (!wslShell) return spawnSync(bash, ['--noprofile','--norc',...args], options);
    const forwarded = ['FIXTURE','SERVICES','UI','INJECTOR','BRIDGE','BAD_BACKUP'].filter(key => options.env[key] !== undefined)
        .map(key => `${key}=${options.env[key]}`);
    return spawnSync('wsl.exe', ['-d','Debian','--exec','/usr/bin/env',...forwarded,wslShell,...args], options);
}
const source = fs.readFileSync(path.join(project, 'scripts/root-module/pi-tweaks-bridge.sh'), 'utf8');
const patch = fs.readFileSync(path.join(project, 'scripts/root-module/pi-bridge/ss11.bin'));
// Filesystem fixture: the SHA boundary models the verified original without needing a car dump.
const original = Buffer.from('opaque original Tweaks bridge fixture');
const sha = b => crypto.createHash('sha256').update(b).digest('hex');
assert.equal(JSON.parse(fs.readFileSync(path.join(project,'scripts/root-module/pi-bridge/verification.json'),'utf8')).original_ss11_sha256,
    '8a5e9b9c612dd3ad09ee3218383bbef6c352835f7238011271a92bb3317011e2');
assert.equal(sha(patch), '575735ed6f6074f1c33483c8df5c8984e51e6dc0fba328b1e4179ca10b9f9d7d');
let sequence = 0;
function fixture(oldHashAlias = false) {
    const dir = path.join(base, String(++sequence)).replaceAll('\\','/');
    const shellDir = shellPath(dir);
    const tmp = dir + '/data/local/tmp', home = dir + '/data/local/voyahplay-tweaks-bounds-test';
    fs.mkdirSync(tmp, {recursive:true});
    fs.writeFileSync(tmp+'/ss11.bin', original);
    fs.writeFileSync(tmp+'/frida-inject','fixture');
    fs.writeFileSync(tmp+'/voyah-bounds-test.bin', patch);
    fs.writeFileSync(dir+'/boot-id','fixture-boot-id');
    const env = {...process.env, MSYS_NO_PATHCONV:'1', FIXTURE:shellDir};
    const prefix = `
id() { echo 0; }
getprop() { echo 30; }
stat() { case "$3" in *ss11.bin.original) [ "\${BAD_BACKUP:-0}" != 1 ] || { echo 0:644; return; };; esac; if [ -d "$3" ]; then echo 0:700; else echo 0:600; fi; }
settings() { echo "$*" >> "$FIXTURE/settings.log"; [ "$1" != get ] || echo null; }
sha256sum() {
 case "$1" in
  */services.jar) echo "\${SERVICES:-483fedea7707833454272f84a64692078996127d64abac63f13863b76246a579}  $1";;
  */SystemUI-release-signed.apk) echo "\${UI:-5d5f7498d92ee68e927bc0b891daedf15978d079b85a254399112bad98d2dd6b}  $1";;
  */frida-inject) echo "\${INJECTOR:-0d99be0e44d4ca3449056fa0a0b868fca1cf66c8ad704508949945afc07706aa}  $1";;
  *) actual=$(command sha256sum "$1"); if [ "\${actual%% *}" = ${sha(original)} ]; then echo "8a5e9b9c612dd3ad09ee3218383bbef6c352835f7238011271a92bb3317011e2  $1"; else printf '%s\\n' "$actual"; fi;;
 esac
}
`;
    // Keep /proc read-only and isolate every writable absolute path in the production script.
    let script = prefix + source.replaceAll('/data/local', shellDir+'/data/local').replaceAll('/proc/sys/kernel/random/boot_id',shellDir+'/boot-id');
    if (oldHashAlias) script = script.replaceAll('voyah_file_sha256','hash');
    function run(mode, changes={}) {
        const result = executeShell(['-s','--',mode,shellPath(tmp)+'/voyah-bounds-test.bin'],
            {input:script, encoding:'utf8', env:{...env,...changes}, timeout:10000});
        assert(!result.error, result.error?.message);
        return result;
    }
    return {dir,tmp,home,run, current:()=>fs.readFileSync(tmp+'/ss11.bin'), backup:()=>fs.readFileSync(home+'/ss11.bin.original')};
}
test('PI check validates original bridge without changing it or creating a backup', () => {
    const f=fixture(), r=f.run('check'); assert.equal(r.status,0,r.stdout+r.stderr);
    assert(r.stdout.includes('INJECTOR_ACTUAL_SHA256=0d99be0e44d4ca3449056fa0a0b868fca1cf66c8ad704508949945afc07706aa'));
    assert.deepEqual(f.current(), original); assert(!fs.existsSync(f.home));
});
test('OTA7.2 pair installs the same reviewed extension and mixed PI pairs are rejected', () => {
    const services='262bfd515f5c181a556477cadbdee1e574ead0ef1acff158dc3fa25bd8b84c38';
    const ui='b676a3a426ba3b59fabb9d98355ff0e0ac7b07d7ad6545ac44d9cbf85f1700c1';
    const good=fixture(), accepted=good.run('install',{SERVICES:services,UI:ui});
    assert.equal(accepted.status,0,accepted.stdout+accepted.stderr);
    assert(accepted.stdout.includes('PI_FIRMWARE_PROFILE=OTA7.2-build35'));
    assert.deepEqual(good.current(),patch);assert.deepEqual(good.backup(),original);
    for(const changed of [{SERVICES:services},{UI:ui}]) {
        const bad=fixture(), rejected=bad.run('install',changed);
        assert.notEqual(rejected.status,0);assert.deepEqual(bad.current(),original);assert(!fs.existsSync(bad.home));
    }
});
test('mksh hash alias cannot capture our digest function or calls', () => {
    assert(!/^hash\s*\(\)/m.test(source));
    assert(!/\$\(hash\s/.test(source));
    if (!wslShell) return;
    const old=fixture(true), rejected=old.run('check');
    assert.notEqual(rejected.status,0);
    assert(rejected.stdout.includes('ERROR unreviewed-injector'),rejected.stdout+rejected.stderr);
    assert.deepEqual(old.current(),original);
    assert(!fs.existsSync(old.home));
    const fixed=fixture(), accepted=fixed.run('check');
    assert.equal(accepted.status,0,accepted.stdout+accepted.stderr);
});
test('unreviewed services, UI and injector fail before backup or replacement', () => {
    for(const changed of [{SERVICES:'other'}, {UI:'other'}, {INJECTOR:'other'}]) {
        const f=fixture(), r=f.run('install',changed); assert.notEqual(r.status,0);
        assert.deepEqual(f.current(), original); assert(!fs.existsSync(f.home));
    }
});
test('install preserves original, installs verified bridge and requests reboot', () => {
    const f=fixture(), r=f.run('install'); assert.equal(r.status,0,r.stdout+r.stderr);
    assert.deepEqual(f.backup(),original); assert.deepEqual(f.current(),patch);
    assert(r.stdout.includes('REBOOT_REQUIRED=1'));
    assert(!fs.existsSync(f.dir+'/data/local/voyahplay-tweaks-bounds-test.lock'));
});
test('repeated install preserves backup and restoration restores exact original bytes', () => {
    const f=fixture(); assert.equal(f.run('install').status,0);
    const again=f.run('install'); assert.equal(again.status,0,again.stdout+again.stderr);
    assert(again.stdout.includes('ALREADY_INSTALLED=1')); assert.deepEqual(f.backup(),original);
    const restore=f.run('restore'); assert.equal(restore.status,0,restore.stdout+restore.stderr);
    assert.deepEqual(f.current(),original); assert(restore.stdout.includes('REBOOT_REQUIRED=1'));
});
test('a newer bridge or corrupt backup is never overwritten during install or restore', () => {
    for(const operation of ['install','restore']) {
        const f=fixture(); assert.equal(f.run('install').status,0);
        fs.writeFileSync(f.tmp+'/ss11.bin','new Tweaks release');
        assert.notEqual(f.run(operation).status,0); assert.equal(f.current().toString(),'new Tweaks release');
        const g=fixture(); assert.equal(g.run('install').status,0);
        fs.writeFileSync(g.home+'/ss11.bin.original','bad backup');
        assert.notEqual(g.run(operation).status,0); assert.deepEqual(g.current(),patch);
    }
});
test('unverified upload and an existing staging file do not change current bytes', () => {
    const f=fixture(); fs.writeFileSync(f.tmp+'/voyah-bounds-test.bin','bad upload');
    assert.notEqual(f.run('install').status,0); assert.deepEqual(f.current(),original);
    const g=fixture(); fs.mkdirSync(g.home); fs.writeFileSync(g.home+'/owner','voyahplay-tweaks-bounds-test-v1');
    fs.writeFileSync(g.home+'/ss11.bin.original',original); fs.writeFileSync(g.home+'/ss11.bin.pending','occupied');
    assert.notEqual(g.run('install').status,0); assert.deepEqual(g.current(),original);
    assert.equal(fs.readFileSync(g.home+'/ss11.bin.pending','utf8'),'occupied');
});
const runtime = fs.readFileSync(path.join(project,'common/src/main/assets/voyah-helper/runtime.sh'),'utf8');
const guard = runtime.slice(runtime.indexOf('fullscreen_firmware() {'),runtime.indexOf('\nagent_alive()'));
test('runtime accepts only reviewed RU/PI pairs and the patched bridge', () => {
    const RU='0faed7ebf1094c62a4f92b4bc533bbb3c08480a2cd847567cb1cff91b2d1d045', PI='483fedea7707833454272f84a64692078996127d64abac63f13863b76246a579';
    const RUI='28f5d93eaec22b755b339193a733bd0d4160c496cf301bcd2fcfb88c36ade162', PUI='5d5f7498d92ee68e927bc0b891daedf15978d079b85a254399112bad98d2dd6b';
    const PI72='262bfd515f5c181a556477cadbdee1e574ead0ef1acff158dc3fa25bd8b84c38', PUI72='b676a3a426ba3b59fabb9d98355ff0e0ac7b07d7ad6545ac44d9cbf85f1700c1';
    for(const [services,ui,bridge,expected] of [[RU,RUI,sha(patch),0],[PI,PUI,sha(patch),0],[PI72,PUI72,sha(patch),0],[PI72,PUI,sha(patch),1],[PI,PUI72,sha(patch),1],[RU,PUI,sha(patch),1],[PI,RUI,sha(patch),1],[PI,PUI,'8a5e9b9c612dd3ad09ee3218383bbef6c352835f7238011271a92bb3317011e2',1],['unknown',PUI,sha(patch),1]]) {
        const input=`sha256sum() { case "$1" in */services.jar) echo "$SERVICES";; */ss11.bin) echo "$BRIDGE";; *) echo "$UI";; esac; }\n${guard}\nfullscreen_firmware\n`;
        const r=executeShell(['-s'],{input,encoding:'utf8',env:{...process.env,SERVICES:services,UI:ui,BRIDGE:bridge}});
        assert.equal(r.status,expected,r.stdout+r.stderr);
    }
});
