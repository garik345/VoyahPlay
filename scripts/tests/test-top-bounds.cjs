// Contract tests for the actual injected asset, with a minimal Android/Frida boundary.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../common/src/main/assets/voyah-helper/top-bounds.js'), 'utf8');
function fixture() {
    let now = 10000, poll, originalCalls = 0;
    const values = new Map();
    const base = {version:'1.1', pid:989, now_ms:now, lease_until_ms:25000,
        boot_ready:true, subscription_valid:true, lease_active:true};
    values.set('voyahplay_top_bounds_lease', '1|25000');
    values.set('voyahplay_tweaks_bounds_probe', JSON.stringify(base));
    const method = {call(self, config) { originalCalls++; return config; }};
    function rect(left = 0, top = 45, right = 1920, bottom = 720) {
        return {left:{value:left}, top:{value:top}, right:{value:right}, bottom:{value:bottom},
            height() { return this.bottom.value - this.top.value; }};
    }
    const types = {
        'com.android.server.wm.Task':{onRequestedOverrideConfigurationChanged:{overload:() => method}},
        'android.graphics.Rect':{$new:r => rect(r.left.value, r.top.value, r.right.value, r.bottom.value)},
        'android.os.SystemClock':{elapsedRealtime:() => now},
        'android.provider.Settings$Global':{getString:(_, key) => values.get(key) ?? null,
            putString:(_, key, value) => values.set(key, value)},
        'android.app.ActivityThread':{currentApplication:() => ({getContentResolver:() => ({})})}
    };
    vm.runInNewContext(source, {Java:{perform:fn => fn(), use:name => {assert.ok(types[name], name); return types[name];}},
        Process:{id:989, getCurrentThreadId:() => 1}, setInterval:fn => {poll = fn;}, console:{log() {}}});
    function invoke(options = {}) {
        const bounds = rect(options.left ?? 0, options.top ?? 45, options.right ?? 1920, options.bottom ?? 720);
        const win = {getBounds:() => bounds, getWindowingMode:() => options.mode ?? 1,
            setBounds:r => { config.result = r; }, setAppBounds:r => { config.app = r; }};
        const config = {windowConfiguration:{value:win}, densityDpi:{value:options.density ?? 160}, screenHeightDp:{value:675}};
        const record = {packageName:{value:options.package ?? 'com.shilapi.xcertplay'}, getDisplayId:() => options.display ?? 0,
            intent:{value:{getComponent:() => ({getClassName:() => options.component ?? 'com.shilapi.xcertplay.CarPlayHostActivity'})}}};
        const task = {getTopMostActivity:() => { if (options.error) throw Error('record unavailable'); return options.noRecord ? null : record; }};
        const before = originalCalls;
        assert.equal(method.implementation.call(task, config), config);
        assert.equal(originalCalls, before + 1, 'OEM original must run exactly once and preserve its return');
        return config;
    }
    return {invoke, poll:() => poll(), clock:value => { now = value; }, values, base,
        baseChange:change => values.set('voyahplay_tweaks_bounds_probe', JSON.stringify({...base, ...change}))};
}
let cases = 0;
function test(name, fn) { fn(); cases++; process.stdout.write(`PASS ${name}\n`); }
test('both driver activities expand top and update app bounds/dp', () => {
    for (const component of ['CarPlayHostActivity', 'DiPlayActivity']) {
        const c = fixture().invoke({component:`com.shilapi.xcertplay.${component}`, density:240, left:145});
        assert.equal(c.result.top.value, 0); assert.equal(c.result.left.value, 145);
        assert.equal(c.app, c.result); assert.equal(c.screenHeightDp.value, 480);
    }
});
test('bottom dock boundary is preserved', () => {
    const c = fixture().invoke({bottom:530}); assert.equal(c.result.bottom.value, 530);
});
test('other apps, displays, activities and window modes remain untouched', () => {
    for (const options of [{package:'other'}, {display:1}, {mode:5}, {noRecord:true}, {component:'com.shilapi.xcertplay.OtherActivity'}])
        assert.equal(fixture().invoke(options).result, undefined);
});
test('unknown and already expanded bounds remain untouched', () => {
    for (const options of [{left:200}, {top:0}, {top:46}, {right:1900}, {bottom:700}])
        assert.equal(fixture().invoke(options).result, undefined);
});
test('expired lease stops changes between polls', () => {
    const f = fixture(); f.clock(25001); assert.equal(f.invoke().result, undefined);
});
test('malformed, expired and excessive own leases fail closed', () => {
    for (const lease of [null, '', '1|0', '1|10000', '1|40001', '1|9999999999999999', '0|25000', '1|25000|x']) {
        const f = fixture(); f.values.set('voyahplay_top_bounds_lease', lease); f.poll();
        assert.equal(f.invoke().result, undefined, String(lease));
    }
});
test('verified bridge boot/license/freshness/process gates are mandatory', () => {
    for (const change of [{boot_ready:false}, {subscription_valid:false}, {lease_active:false}, {pid:990}, {version:'old'},
        {now_ms:4999}, {now_ms:10001}, {now_ms:'10000'}, {lease_until_ms:0}, {lease_until_ms:40001}]) {
        const f = fixture(); f.baseChange(change); f.poll(); assert.equal(f.invoke().result, undefined, JSON.stringify(change));
    }
});
test('missing or malformed bridge reports fail closed', () => {
    for (const raw of [null, 'null', '{}', 'broken']) {
        const f = fixture(); f.values.set('voyahplay_tweaks_bounds_probe', raw); f.poll(); assert.equal(f.invoke().result, undefined);
    }
});
test('our inspection error does not suppress original OEM behavior', () => {
    const f = fixture(); assert.equal(f.invoke({error:true}).result, undefined); assert.equal(f.invoke().result.top.value, 0);
});
process.stdout.write(`${cases} top-edge contract tests passed\n`);
