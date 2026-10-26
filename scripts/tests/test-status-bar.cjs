const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../common/src/main/assets/voyah-helper/status-bar.js'), 'utf8');
function fixture(original = false) {
    let now = 10000, poll, calls = 0, failRead = false;
    const values = new Map();
    const base = {version:'1.1', pid:989, now_ms:10000, lease_until_ms:25000,
        boot_ready:true, subscription_valid:true, lease_active:true};
    values.set('voyahplay_top_bounds_lease', '1|25000');
    values.set('voyahplay_tweaks_bounds_probe', JSON.stringify(base));
    const method = {call(self, name, display) { calls++; if (original instanceof Error) throw original; return original; }};
    const types = {
        'com.qinggan.systemui.statusbar.utils.StatusBarInvisibleItem':{isHit:{overload:() => method}},
        'android.os.SystemClock':{elapsedRealtime:() => now},
        'android.provider.Settings$Global':{getString:(_, key) => {if (failRead) throw Error('IPC failed'); return values.get(key) ?? null;},
            putString:(_, key, value) => values.set(key, value)},
        'android.app.ActivityThread':{currentApplication:() => ({getContentResolver:() => ({})})}
    };
    vm.runInNewContext(source, {Java:{perform:fn => fn(), use:name => {assert.ok(types[name]); return types[name];}},
        Process:{id:2421}, setInterval:fn => {poll = fn;}, console:{log() {}}});
    function invoke(component = 'com.shilapi.xcertplay.CarPlayHostActivity', display = 0) {
        const before = calls;
        try { return method.implementation.call({}, component, display); }
        finally { assert.equal(calls, before + 1, 'OEM predicate must execute exactly once'); }
    }
    return {invoke, values, clock:value => {now = value;}, poll:() => poll(), failRead:() => {failRead = true;},
        baseChange:change => values.set('voyahplay_tweaks_bounds_probe', JSON.stringify({...base, ...change}))};
}
let count = 0;
function test(name, fn) { fn(); count++; process.stdout.write(`PASS ${name}\n`); }
test('both owned activities are fullscreen only on the driver display', () => {
    for (const name of ['CarPlayHostActivity', 'DiPlayActivity']) {
        const f = fixture(); assert.equal(f.invoke(`com.shilapi.xcertplay.${name}`, 0), true);
        assert.equal(f.invoke(`com.shilapi.xcertplay.${name}`, 1), false);
    }
});
test('other components and OEM fullscreen results are preserved', () => {
    for (const name of [null, '', 'com.shilapi.xcertplay.OtherActivity', 'other.CarPlayHostActivity',
        'com.shilapi.xcertplay.CarPlayHostActivityExtra']) assert.equal(fixture().invoke(name), false);
    assert.equal(fixture(true).invoke('com.qinggan.app.gallery.PictureMainActivity'), true);
    assert.equal(fixture(true).invoke('passenger.Activity', 1), true);
});
test('all non-driver displays are excluded', () => {
    for (const display of [-1, 1, 2, 3, 4, 5]) assert.equal(fixture().invoke(undefined, display), false);
});
test('lease clearing is immediate without waiting for poll', () => {
    const f = fixture(); f.values.delete('voyahplay_top_bounds_lease'); assert.equal(f.invoke(), false);
});
test('invalid, future and expired own leases cannot hide the bar', () => {
    for (const raw of [null, '', '1|0', '1|10000', '1|40001', '1|9999999999999999', '0|25000', '1|25000|x']) {
        const f = fixture(); f.values.set('voyahplay_top_bounds_lease', raw); assert.equal(f.invoke(), false, String(raw));
        f.poll(); assert.equal(f.invoke(), false);
    }
});
test('boot, license and fresh width acknowledgement are mandatory', () => {
    for (const change of [{boot_ready:false}, {subscription_valid:false}, {lease_active:false}, {version:'old'},
        {pid:0}, {now_ms:4999}, {now_ms:10001}, {now_ms:'10000'}, {lease_until_ms:0}, {lease_until_ms:40001}]) {
        const f = fixture(); f.baseChange(change); f.poll(); assert.equal(f.invoke(), false, JSON.stringify(change));
    }
});
test('stale base fails closed even if polling stops', () => {
    const f = fixture(); f.clock(15001); assert.equal(f.invoke(), false);
});
test('missing reports and IPC failures preserve OEM result', () => {
    for (const raw of [null, 'null', '{}', 'broken']) {
        const f = fixture(); f.values.set('voyahplay_tweaks_bounds_probe', raw); f.poll(); assert.equal(f.invoke(), false);
    }
    const f = fixture(); f.failRead(); assert.equal(f.invoke(), false); f.poll(); assert.equal(f.invoke(), false);
});
test('OEM exceptions propagate unchanged', () => {
    const error = Error('OEM failure'); assert.throws(() => fixture(error).invoke(), e => e === error);
});
process.stdout.write(`${count} status bar contract tests passed\n`);
