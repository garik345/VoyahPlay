const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../common/src/main/assets/voyah-helper/radio-labels.js'), 'utf8');
const names = ['com.pateo.rdsapp.main.widget.listInfo.RadioInfoAdapter$MyViewHolder',
    'com.pateo.rdsapp.main.widget.play.PlayViewAdapter$MyViewHolder'];
function fixture({packageName = 'com.pateo.rdsapp', missingClass = '', applicationReady = true} = {}) {
    let flag = '1', poll, calls = 0, measurements = 0, visits = 0;
    const logs = [], scheduled = [], retries = [], methods = new Map(), seeded = [];
    const enumValue = name => ({name:() => name});
    const end = enumValue('END');
    const textPaint = size => ({size, setTextSize(value) {this.size = value;}, measureText(text) {return String(text).length * this.size * .6;}});
    class JavaString {constructor(value) {this.value = String(value);} toString() {return this.value;}}
    class Bundle {
        constructor() {this.values = new Map();}
        putInt(k,v) {this.values.set(k,v);} getInt(k) {return this.values.get(k) || 0;}
        putFloat(k,v) {this.values.set(k,v);} getFloat(k) {return this.values.get(k) || 0;}
        putString(k,v) {this.values.set(k,String(v));} getString(k) {return this.values.get(k) ?? null;}
        putBoolean(k,v) {this.values.set(k,v);} getBoolean(k) {return this.values.get(k) || false;}
        putParcelable(k,v) {this.values.set(k,v);} getParcelable(k) {return this.values.get(k);}
        putCharSequence(k,v) {this.values.set(k,v);} getCharSequence(k) {return this.values.get(k) ?? null;}
        remove(k) {this.values.delete(k);}
    }
    class WeakMap {
        constructor() {this.map = new Map();}
        get(k) {return this.map.get(k) ?? null;} put(k,v) {this.map.set(k,v);} clear() {this.map.clear();}
        entrySet() {visits++; const entries = [...this.map]; let i = 0;
            return {iterator:() => ({hasNext:() => i < entries.length, next:() => {
                const [key, value] = entries[i++];
                return {opaqueEntry:{getKey:() => key, getValue:() => value}};
            }})};}
    }
    const types = {
        'android.app.ActivityThread': {currentApplication:() => applicationReady ? ({getPackageName:() => packageName, getContentResolver:() => ({})}) : null},
        'android.provider.Settings$Global': {getString:() => flag},
        'android.widget.TextView': {}, 'android.os.Bundle': {$new:() => new Bundle()},
        'java.util.WeakHashMap': {$new:() => new WeakMap()},
        'java.util.Map$Entry': {},
        'android.text.TextPaint': {$new:paint => textPaint(paint.size)},
        'android.text.TextUtils': {ellipsize:(value, paint, width) => {
            measurements++;
            const size = Math.max(1, Math.floor(width / (paint.size * .6)));
            return String(value).length <= size ? String(value) : String(value).slice(0, size - 1) + '…';
        }},
        'android.text.TextUtils$TruncateAt': {END:{value:end}, valueOf:enumValue},
        'android.text.SpannableString': {$new:text => {
            assert(text instanceof JavaString, 'SpannableString requires a Java CharSequence, not a JS string');
            return {text:String(text), spans:[], setSpan(span, from, to) {this.spans.push({span,from,to});},
                toString() {return this.text;}};
        }},
        'android.text.style.AbsoluteSizeSpan': {$new:size => ({size})},
        'com.adayo.proxy.dab.aidl.beans.RadioStation': {},
        'java.lang.String': {$new:value => new JavaString(value)},
    };
    for (const name of names) {
        const method = {implementation:null, call(holder, position) {
            calls++; holder.position = position;
            if (holder.failOriginal) throw holder.failOriginal;
            holder.tvFreq.value.setText(holder.frequency);
            return 'OEM';
        }};
        methods.set(name, method);
        types[name] = {bindView:{overload:type => {assert.equal(type, 'int'); return method;}}};
    }
    const java = {perform:fn => fn(), scheduleOnMainThread:fn => scheduled.push(fn), cast:(value, type) => {
        if (value?.opaqueEntry) {assert.equal(type, types['java.util.Map$Entry']); return value.opaqueEntry;}
        return value;
    },
        retain:value => value, choose:(name, callbacks) => {
            callbacks.onMatch({notifyDataSetChanged:() => seeded.push(name), $dispose() {}});
            callbacks.onComplete();
        },
        use:name => {if (name === missingClass) throw Error('class missing'); assert.ok(types[name], name); return types[name];}};
    const sandbox = {Java:java, Process:{id:4321}, setInterval:fn => {poll = fn;}, setTimeout:fn => retries.push(fn), console:{log:value => logs.push(value)}};
    vm.runInNewContext(source, sandbox);
    while (scheduled.length) scheduled.shift()();
    function holder(name = 'EUROPA', frequency = '101.7 MHz') {
        const view = {text:frequency, maxLines:1_000_000, ellipsis:end, fontPadding:true, size:42,
            width:160, height:60, writes:0, failStyled:false,
            getText() {return {toString:() => this.text};}, setText(value) {
                if (this.failStyled && value.spans) throw Error('UI write failed');
                this.text = String(value); this.styled = value.spans ? value : null; this.writes++;
            },
            getMaxLines() {return this.maxLines;}, setMaxLines(value) {this.maxLines = value;},
            getTextSize() {return this.size;}, setTextSize(unit, value) {assert.equal(unit, 0); this.size = value;},
            getEllipsize() {return this.ellipsis;}, setEllipsize(value) {this.ellipsis = value;},
            getIncludeFontPadding() {return this.fontPadding;}, setIncludeFontPadding(value) {this.fontPadding = value;},
            getWidth() {return this.width;}, getHeight() {return this.height;},
            getPaddingLeft:() => 0, getPaddingRight:() => 0, getPaddingTop:() => 0, getPaddingBottom:() => 0,
            getPaint() {return textPaint(this.size);}, getLayoutParams() {return {width:{value:this.width},height:{value:this.height}};}
        };
        const station = {name, getPSName() {return this.name;}};
        return {station:{value:station}, tvFreq:{value:view}, frequency};
    }
    function bind(h, index = 0, type = names[0]) {
        const method = methods.get(type); return method.implementation.call(h, index);
    }
    function drain() {while (scheduled.length) scheduled.shift()();}
    return {holder, bind, logs, methods, seeded, calls:() => calls, measurements:() => measurements, visits:() => visits,
        flag:value => {flag = value;},
        ready:() => {applicationReady = true; while (retries.length) retries.shift()(); drain();},
        poll:() => {poll(); drain();}, queuePoll:() => poll(), drain, queued:() => scheduled.length,
        clean:sandbox.cleanStationName};
}
let count = 0;
function test(name, fn) {fn(); count++; process.stdout.write(`PASS ${name}\n`);}
test('both OEM list binders run once with the original position and result', () => {
    const f = fixture();
    for (const type of names) {
        const h = f.holder(); assert.equal(f.bind(h, 4, type), 'OEM');
        assert.equal(h.position, 4); assert.equal(h.tvFreq.value.text, 'EUROPA\n101.7 MHz');
        assert.equal(h.tvFreq.value.styled.spans.length, 2);
    }
    assert.equal(f.calls(), 2);
});
test('absent RDS preserves OEM formatting and font behaviour', () => {
    for (const name of [null, undefined, '', '  ', '\0\0', '101.7 MHz']) {
        const f = fixture(), h = f.holder(); h.station.value.name = name; f.bind(h);
        assert.equal(h.tvFreq.value.text, '101.7 MHz'); assert.equal(h.tvFreq.value.maxLines, 1_000_000);
        assert.equal(h.tvFreq.value.size, 42); assert.equal(h.tvFreq.value.fontPadding, true);
    }
});
test('RDS padding and control characters never add extra rows', () => {
    const f = fixture(), h = f.holder('  EUROPA\0\r\nFM  '); f.bind(h);
    assert.equal(h.tvFreq.value.text, 'EUROPA FM\n101.7 MHz');
    assert.equal(f.clean('x'.repeat(300), '' ).length, 128);
});
test('disabling restores original text, max lines, padding and ellipsis', () => {
    const f = fixture(), h = f.holder(); const originalEllipsis = h.tvFreq.value.ellipsis;
    f.bind(h); f.flag('0'); f.poll();
    assert.equal(h.tvFreq.value.text, '101.7 MHz'); assert.equal(h.tvFreq.value.maxLines, 1_000_000);
    assert.equal(h.tvFreq.value.fontPadding, true); assert.equal(h.tvFreq.value.ellipsis.name(), originalEllipsis.name());
    assert.match(f.logs.at(-1), /pid=4321 enabled=0 modified=0 error=/);
    const writes = h.tvFreq.value.writes; f.poll(); assert.equal(h.tvFreq.value.writes, writes);
});
test('a recycled row cannot show the previous station name or frequency', () => {
    const f = fixture(), h = f.holder(); f.bind(h);
    h.station.value = {getPSName:() => null}; h.frequency = '98.0 MHz'; f.bind(h, 6);
    assert.equal(h.tvFreq.value.text, '98.0 MHz'); f.poll(); assert.equal(h.tvFreq.value.text, '98.0 MHz');
});
test('later RDS data is applied at the next OEM bind, without polling the tiles', () => {
    const f = fixture(), h = f.holder(null); f.bind(h);
    h.station.value.name = 'CHOCOLAD'; f.poll();
    assert.equal(h.tvFreq.value.text, '101.7 MHz'); f.bind(h);
    assert.equal(h.tvFreq.value.text, 'CHOCOLAD\n101.7 MHz'); assert.equal(f.calls(), 2);
    const writes = h.tvFreq.value.writes; f.poll(); assert.equal(h.tvFreq.value.writes, writes);
});
test('long names are clipped on their own line and leave frequency visible', () => {
    const f = fixture(), h = f.holder('An extremely long radio station name'); f.bind(h);
    const lines = h.tvFreq.value.text.split('\n');
    assert.match(lines[0], /…$/); assert.equal(lines[1], '101.7 MHz'); assert.equal(h.tvFreq.value.maxLines, 2);
});
test('OEM exceptions propagate once without touching the row', () => {
    const f = fixture(), h = f.holder(); const error = Error('OEM failed'); h.failOriginal = error;
    assert.throws(() => f.bind(h), value => value === error); assert.equal(f.calls(), 1);
    assert.equal(h.tvFreq.value.writes, 0);
});
test('partial decoration failure is isolated and remains reversible', () => {
    const f = fixture(), h = f.holder(); h.tvFreq.value.failStyled = true;
    assert.equal(f.bind(h), 'OEM'); f.flag('0'); f.poll();
    assert.equal(h.tvFreq.value.text, '101.7 MHz'); assert.equal(h.tvFreq.value.maxLines, 1_000_000);
    assert.match(f.logs.at(-1), /enabled=0 modified=0/);
});
test('a blocked UI has at most one queued poll and rollback wins when it resumes', () => {
    const f = fixture(), h = f.holder(); f.bind(h);
    f.flag('0');
    for (let i = 0; i < 20; i++) f.queuePoll();
    assert.equal(f.queued(), 1); f.drain();
    assert.equal(h.tvFreq.value.text, '101.7 MHz'); assert.match(f.logs.at(-1), /enabled=0 modified=0/);
});
test('other processes and missing OEM classes never retain partial hooks', () => {
    const wrong = fixture({packageName:'other.app'});
    assert.ok([...wrong.methods.values()].every(method => method.implementation === null));
    const missing = fixture({missingClass:names[1]});
    assert.ok([...missing.methods.values()].every(method => method.implementation === null));
    assert.match(missing.logs.at(-1), /enabled=0 modified=0 error=setup-failed/);
});
test('a cold radio process waits for its application before installing hooks', () => {
    const f = fixture({applicationReady:false});
    assert.ok([...f.methods.values()].every(method => method.implementation === null));
    assert.match(f.logs[0], /modified=0 error=waiting-for-app/);
    f.ready(); const h = f.holder(); f.bind(h); assert.equal(h.tvFreq.value.text, 'EUROPA\n101.7 MHz');
});
test('lists already on screen receive exactly one OEM redraw each', () => {
    const f = fixture();
    assert.deepEqual(f.seeded, ['com.pateo.rdsapp.main.widget.listInfo.RadioInfoAdapter',
        'com.pateo.rdsapp.main.widget.play.PlayViewAdapter']);
    f.poll(); assert.equal(f.seeded.length, 2);
});
test('opaque Map.Entry objects are cast before refreshing and restoring visible rows', () => {
    const f = fixture(), h = f.holder('BEFORE'); f.bind(h);
    h.station.value.name = 'AFTER'; f.bind(h); f.poll();
    assert.equal(h.tvFreq.value.text, 'AFTER\n101.7 MHz');
    assert.match(f.logs.at(-1), /enabled=1 modified=1 error=$/);
    f.flag('0'); f.poll();
    assert.equal(h.tvFreq.value.text, '101.7 MHz');
    assert.match(f.logs.at(-1), /enabled=0 modified=0 error=$/);
});
test('two-line labels reduce the base paint metrics and rollback restores the OEM text size', () => {
    const f = fixture(), h = f.holder(); f.bind(h);
    assert(h.tvFreq.value.size <= h.tvFreq.value.height / 2.5);
    assert.equal(h.tvFreq.value.size, h.tvFreq.value.styled.spans[0].span.size);
    f.flag('0'); f.poll();
    assert.equal(h.tvFreq.value.size, 42);
});
test('idle control checks do not measure, enumerate, rewrite or queue UI work for any tile', () => {
    const f = fixture(), holders = Array.from({length:30}, () => f.holder()); holders.forEach(h => f.bind(h));
    const measurements = f.measurements(), writes = holders.reduce((n,h) => n + h.tvFreq.value.writes, 0);
    for (let i = 0; i < 30; i++) f.queuePoll();
    assert.equal(f.queued(), 0); assert.equal(f.visits(), 0);
    assert.equal(f.measurements(), measurements);
    assert.equal(holders.reduce((n,h) => n + h.tvFreq.value.writes, 0), writes);
});
test('repeated OEM binds reuse the measured label until the station or bounds change', () => {
    const f = fixture(), h = f.holder(); f.bind(h); const measured = f.measurements();
    for (let i = 0; i < 20; i++) f.bind(h);
    assert.equal(f.measurements(), measured); assert.equal(h.tvFreq.value.text, 'EUROPA\n101.7 MHz');
    h.frequency = '105.0 MHz'; f.bind(h); assert(f.measurements() > measured);
    assert.equal(h.tvFreq.value.text, 'EUROPA\n105.0 MHz');
});
process.stdout.write(`${count} OEM radio label tests passed\n`);
