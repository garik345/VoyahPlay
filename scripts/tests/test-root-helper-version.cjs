// Exercise the production version gates without root, ADB or starting a helper.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {spawnSync} = require('node:child_process');
const {test} = require('node:test');
const root = path.resolve(__dirname, '../..');
const read = file => fs.readFileSync(path.join(root, file), 'utf8').replaceAll('\r\n', '\n');
const runtime = read('common/src/main/assets/voyah-helper/runtime.sh');
const version = runtime.match(/^VERSION=(\d+\.\d+\.\d+)$/m)[1];
const start = runtime.indexOf('code=0\nanswer=$(probe 2>&1) || code=$?');
const end = runtime.indexOf('[ "$code" = 2 ]', start);
assert(start >= 0 && end > start, 'Production already-running branch exists');
const branch = runtime.slice(start, end);
const bash = process.env.BASH_EXE || (process.platform === 'win32' ? 'C:/Program Files/Git/bin/bash.exe' : 'bash');
function probe(actual) {
    return spawnSync(bash, ['--noprofile', '--norc', '-s'], {
        encoding: 'utf8', env: {...process.env, RUNNING_HELPER_VERSION: actual},
        input: `set -u
VERSION=${version}
probe() { printf 'HELPER_VERSION=%s\\nHELPER_READY=1\\n' "$RUNNING_HELPER_VERSION"; }
record() { printf 'RECORD=%s\\n' "$1"; }
complete() { echo COMPLETE; }
fail() { echo "ERROR=$1"; exit 1; }
${branch}
exit 2
`,
    });
}
test('repeated runtime start accepts the current live server and completes', () => {
    for (let attempt = 0; attempt < 2; attempt++) {
        const result = probe(version);
        assert.equal(result.status, 0, result.stdout + result.stderr);
        assert(result.stdout.includes('RECORD=already-running'));
        assert(result.stdout.includes('COMPLETE'));
    }
});
test('runtime rejects old, future and partial version matches', () => {
    for (const actual of ['1.2.3', '1.2.5', '1.2.6', '1.2.7', '1.2.8', '1.2.9', '9.9.9', `${version}-extra`, '']) {
        const result = probe(actual);
        assert.equal(result.status, 1, result.stdout + result.stderr);
        assert(result.stdout.includes('ERROR=helper-version-differs'));
        assert(!result.stdout.includes('COMPLETE'));
    }
});
test('runtime, APK protocol and bootstrap advertise the same version', () => {
    const protocol = read('common/src/main/java/com/shilapi/xcertplay/voyah/root/RootHelperProtocol.java');
    const bootstrap = read('scripts/root-module/root-helper-bootstrap.sh');
    assert.equal(protocol.match(/String VERSION = "([^"]+)"/)[1], version);
    assert(bootstrap.includes(`echo EXPECTED_HELPER_VERSION=${version}`));
    assert(bootstrap.includes(`grep -Fxq 'HELPER_VERSION=${version}'`));
});
const installer = read('scripts/root-module/root-helper.ps1');
const gate = installer.match(/    if \(\$Mode -in @\('Install','Start','Status'\)[\s\S]*?\n    }\n/)[0];
const fixtureDir = path.join(root, 'build/root-helper-version-tests');
fs.mkdirSync(fixtureDir, {recursive: true});
const fixture = path.join(fixtureDir, 'installer-gate.ps1');
fs.writeFileSync(fixture, `$ErrorActionPreference='Stop'
$Mode=$env:HELPER_TEST_MODE
$result=$env:HELPER_TEST_RESULT
${gate}
Write-Output 'ACCEPTED'
`, 'utf8');
function installGate(mode, result) {
    return spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', fixture], {
        encoding: 'utf8', env: {...process.env, HELPER_TEST_MODE: mode, HELPER_TEST_RESULT: result},
    });
}
test('installer accepts the current version for install, start and status', () => {
    for (const mode of ['Install', 'Start', 'Status']) {
        const result = installGate(mode, `HELPER_VERSION=${version}\r\nCAPABILITIES=TWEAKS_BOUNDS_1_1\r\n`);
        assert.equal(result.status, 0, result.stdout + result.stderr);
        assert(result.stdout.includes('ACCEPTED'));
    }
});
test('installer rejects stale versions and a missing capability', () => {
    for (const text of ['HELPER_VERSION=1.2.5\nCAPABILITIES=TWEAKS_BOUNDS_1_1\n',
        'HELPER_VERSION=9.9.9\nCAPABILITIES=TWEAKS_BOUNDS_1_1\n', `HELPER_VERSION=${version}\n`]) {
        const result = installGate('Status', text);
        assert.notEqual(result.status, 0, result.stdout + result.stderr);
        assert(!result.stdout.includes('ACCEPTED'));
    }
});
