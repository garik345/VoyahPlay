// Execute the production path guard in a shell: /vendor is used by the connected RU car.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {spawnSync} = require('node:child_process');
const {test} = require('node:test');
const bash = process.env.BASH_EXE || (process.platform === 'win32' ? 'C:/Program Files/Git/bin/bash.exe' : 'bash');
const source = fs.readFileSync(path.join(__dirname, '../../common/src/main/assets/voyah-helper/radio-runtime.sh'), 'utf8');
const start = source.indexOf('radio_apk_path() {');
assert(start >= 0);
const block = source.slice(start, source.indexOf('\n}', start) + 2);
const directory = path.resolve(__dirname, '../../build/radio-live-20261026/path-tests');
fs.mkdirSync(directory, {recursive:true});
const script = path.join(directory, 'guard.sh');
fs.writeFileSync(script, block + '\nIFS= read -r -d "" value || true\nradio_apk_path "$value"\n');
function accepted(value) {
    const result = spawnSync(bash, [script], {input:value, encoding:'utf8', env:{...process.env, MSYS_NO_PATHCONV:'1'}});
    if (result.error) throw result.error;
    assert(result.status === 0 || result.status === 1, result.stderr);
    return result.status === 0;
}
test('the installed vendor radio and the existing system/product/data layouts reach the checksum check', () => {
    for (const value of ['/vendor/app/rdsapp-release-signed/rdsapp-release-signed.apk',
        '/vendor/priv-app/RdsApp/base.apk', '/system/app/RdsApp/base.apk', '/system/priv-app/RdsApp/base.apk',
        '/product/app/RdsApp/base.apk', '/product/priv-app/RdsApp/base.apk',
        '/data/app/~~abc==/com.pateo.rdsapp-abc==/base.apk']) assert(accepted(value), value);
});
test('a vendor path does not permit traversal, extra lines or unreviewed roots', () => {
    for (const value of ['', 'relative.apk', '/data/local/tmp/radio.apk', '/vendor/app/../../radio.apk',
        '/vendor/app/./radio.apk', '/vendor/app/RdsApp/base.apk\nwarning', '/vendor/app/Rds App/base.apk',
        '/vendor/app/.apk', '/vendor/app/radio.apk;id']) assert(!accepted(value), JSON.stringify(value));
});
