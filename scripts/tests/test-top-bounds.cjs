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
        const config = {windowConfiguration:{value:win}, densityDpi:{value:options.density ?? 160},
            screenHeightDp:{value:675}, screenWidthDp:{value:1920}};
        const record = {packageName:{value:options.package ?? 'com.shilapi.xcertplay'}, getDisplayId:() => options.display ?? 0,
            launchedFromUid:{value:options.caller ?? 0}, getDisplayContent:() => ({getDisplayInfo:() => ({
                name:{value:options.displayName ?? 'Cluster-Media-Display'}, ownerUid:{value:options.owner ?? 1000},
                ownerPackageName:{value:options.ownerPackage ?? 'com.qinggan.cluster'}, flags:{value:options.flags ?? 4},
                logicalWidth:{value:options.width ?? 1920}, logicalHeight:{value:options.height ?? 720}})}),
            intent:{value:{getComponent:() => ({getClassName:() => options.component ?? 'com.shilapi.xcertplay.CarPlayHostActivity'}),
                getStringExtra:key => key === 'voyah_probe_token' ? (options.token === undefined ? 'c7d35226-abce-43fc-af7f-ddfe27a2c434' : options.token) : (options.probeMode ?? 'grid'),
                getLongExtra:() => options.deadline ?? 65000}}};
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
const probe = {component:'com.shilapi.xcertplay.VoyahClusterProbeActivity', display:5,
    left:0, top:0, right:0, bottom:0};
test('a root-launched live grid/map probe gets a full canvas only on the reviewed media display', () => {
    for (const probeMode of ['grid', 'map']) {
        const c = fixture().invoke({...probe, probeMode});
        assert.equal(c.result.right.value, 1920); assert.equal(c.result.bottom.value, 720);
        assert.equal(c.app, c.result); assert.equal(c.screenWidthDp.value, 1920); assert.equal(c.screenHeightDp.value, 720);
    }
});
test('probe correction does not change other screens, owners, callers, geometry, activities or nonempty bounds', () => {
    for (const options of [{display:0}, {display:1}, {displayName:'Cluster-Navi-Display'}, {owner:10001},
        {ownerPackage:'other.app'}, {flags:0}, {width:1280}, {height:600}, {caller:2000}, {mode:5},
        {package:'other.app'}, {component:'com.shilapi.xcertplay.CarPlayHostActivity'}, {right:1920}])
        assert.equal(fixture().invoke({...probe, ...options}).result, undefined, JSON.stringify(options));
});
test('invalid, expired or excessive probe intents never grant a bounds correction', () => {
    for (const options of [{token:null}, {token:'bad'}, {probeMode:'other'}, {deadline:10000}, {deadline:70001}])
        assert.equal(fixture().invoke({...probe, ...options}).result, undefined, JSON.stringify(options));
});
test('probe correction preserves the bridge boot/license/freshness gate but needs no driver width lease', () => {
    const off = fixture(); off.values.delete('voyahplay_top_bounds_lease'); off.baseChange({lease_active:false}); off.poll();
    assert.equal(off.invoke(probe).result.right.value, 1920);
    for (const change of [{subscription_valid:false}, {boot_ready:false}, {pid:777}, {now_ms:4999}]) {
        const f = fixture(); f.baseChange(change); f.poll(); assert.equal(f.invoke(probe).result, undefined);
    }
    const expired = fixture(); expired.clock(15001); assert.equal(expired.invoke(probe).result, undefined);
});
test('automatic bounds follow a matching renewed root lease beyond the original intent deadline', () => {
    const f = fixture(); f.clock(50000); f.baseChange({now_ms:50000});
    f.values.set('voyahplay_cluster_auto_lease', '1|c7d35226-abce-43fc-af7f-ddfe27a2c434|5|95000'); f.poll();
    assert.equal(f.invoke({...probe, probeMode:'auto', deadline:20000}).result.right.value, 1920);
});
test('a fresh root-launched automatic intent renders before the settings poll observes its lease', () => {
    const c = fixture().invoke({...probe, probeMode:'auto', deadline:55000});
    assert.equal(c.result.right.value, 1920);
    assert.equal(fixture().invoke({...probe, probeMode:'auto', deadline:55001}).result, undefined);
});
test('automatic bounds reject missing, expired, excessive, wrong-token and wrong-display renewals', () => {
    for (const raw of [null, '0', '1|c7d35226-abce-43fc-af7f-ddfe27a2c434|5|10000',
        '1|c7d35226-abce-43fc-af7f-ddfe27a2c434|5|55001',
        '1|c7d35226-abce-43fc-af7f-ddfe27a2c434|6|50000',
        '1|01234567-89ab-cdef-0123-456789abcdef|5|50000']) {
        const f = fixture(); f.values.set('voyahplay_cluster_auto_lease', raw); f.poll();
        assert.equal(f.invoke({...probe, probeMode:'auto', deadline:9999}).result, undefined, String(raw));
    }
});
process.stdout.write(`${cases} top-edge contract tests passed\n`);
