// Exercise the production lock block with real concurrent shells, without root or ADB.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {spawn} = require('node:child_process');
const {test} = require('node:test');
const bash = process.env.BASH_EXE || (process.platform === 'win32' ? 'C:/Program Files/Git/bin/bash.exe' : 'bash');
const source = fs.readFileSync(path.join(__dirname, '../../common/src/main/assets/voyah-helper/radio-runtime.sh'), 'utf8');
const block = source.slice(source.indexOf('lock=$ROOT/radio-runtime.lock'), source.indexOf('record=$ROOT/radio-agent.pid'));
assert(block.includes('lock_owner='));
const base = path.resolve(__dirname, '../../build/radio-labels-20261026/runtime-lock-tests');
fs.mkdirSync(base, {recursive: true});
function fixture(setup = '') {
    const root = fs.mkdtempSync(path.join(base, 'case-')).replaceAll('\\', '/');
    const script = path.join(root, 'run.sh');
    fs.writeFileSync(script, `#!/bin/bash
set -u
umask 077
ROOT=${JSON.stringify(root)}
boot=1234-abcd
number() { case "$1" in ''|*[!0-9]*) return 1;; *) return 0;; esac; }
private_file() { [ ! -L "$1" ] && [ "$(stat -c '%a' "$1" 2>/dev/null)" = 600 ]; }
identity() { [ -r "/proc/$1/stat" ] && awk '{sub(/^.*\\) /, ""); if ($1 != "Z" && $1 != "X") print $20}' "/proc/$1/stat"; }
# Test only: map this user's private directory to the root-owned directory contract.
stat() { if [ "$2" = '%u:%a' ]; then printf '0:'; command stat -c '%a' "$3"; else command stat "$@"; fi; }
${setup}
${block}
echo ACQUIRED
printf '1\\n' >> "$ROOT/acquisitions"
sleep 1
`);
    return {root, script};
}
function run(f) {
    return new Promise((resolve, reject) => {
        const child = spawn(bash, [f.script], {env: {...process.env, MSYS_NO_PATHCONV: '1'}});
        let output = '';
        child.stdout.on('data', chunk => output += chunk);
        child.stderr.on('data', chunk => output += chunk);
        child.on('error', reject);
        child.on('close', code => resolve({code, output}));
    });
}
test('supervisor and settings request acquire at most one radio runtime', async () => {
    const f = fixture();
    const outcomes = await Promise.all([run(f), run(f)]);
    assert.equal(outcomes.filter(x => x.code === 0).length, 1, JSON.stringify(outcomes));
    assert.equal(outcomes.filter(x => x.output.includes('RADIO_LABELS_BUSY=1')).length, 1);
    assert.equal(fs.readFileSync(path.join(f.root, 'acquisitions'), 'utf8'), '1\n');
    assert(!fs.existsSync(path.join(f.root, 'radio-runtime.lock')));
});
test('a verified dead owner is recovered', async () => {
    const f = fixture(`mkdir "$ROOT/radio-runtime.lock"
printf '1234-abcd 999999999 1\\n' > "$ROOT/radio-runtime.lock/owner-1234-abcd-999999999-1"`);
    const result = await run(f);
    assert.equal(result.code, 0, result.output);
    assert(result.output.includes('ACQUIRED'));
    assert(!fs.existsSync(path.join(f.root, 'radio-runtime.lock')));
});
test('a live lock owner is left alone', async () => {
    const f = fixture(`mkdir "$ROOT/radio-runtime.lock"
started=$(identity $$)
printf '%s %s %s\\n' "$boot" "$$" "$started" > "$ROOT/radio-runtime.lock/owner-$boot-$$-$started"`);
    const result = await run(f);
    assert.equal(result.code, 1, result.output);
    assert(result.output.includes('RADIO_LABELS_BUSY=1'));
    assert(!fs.existsSync(path.join(f.root, 'acquisitions')));
    assert(fs.existsSync(path.join(f.root, 'radio-runtime.lock')));
});
test('an incomplete lock is not stolen from an initializing owner', async () => {
    const f = fixture('mkdir "$ROOT/radio-runtime.lock"');
    const result = await run(f);
    assert.equal(result.code, 1, result.output);
    assert(result.output.includes('RADIO_LABELS_BUSY=1'));
    assert(!fs.existsSync(path.join(f.root, 'acquisitions')));
});
