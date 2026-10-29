'use strict';
// UI only, for the APK whose checksum is checked by radio-runtime.sh before attaching.
function cleanStationName(value, frequency) {
    if (value === null || value === undefined) return '';
    const name = String(value).replace(/\0/g, '').replace(/[\r\n\t]+/g, ' ').trim().slice(0, 128);
    return name && name !== frequency ? name : '';
}
let waitingReported = false;
Java.perform(function installRadioLabels() {
    const App = Java.use('android.app.ActivityThread').currentApplication();
    if (App === null) {
        if (!waitingReported) {
            console.log('VOYAH_RADIO_LABELS_STATE pid=' + Process.id + ' enabled=0 modified=0 error=waiting-for-app');
            waitingReported = true;
        }
        setTimeout(function () { Java.perform(installRadioLabels); }, 500);
        return;
    }
    if (App.getPackageName().toString() !== 'com.pateo.rdsapp') {
        console.log('VOYAH_RADIO_LABELS_STATE pid=' + Process.id + ' enabled=0 modified=0 error=wrong-process');
        return;
    }
    const Settings = Java.use('android.provider.Settings$Global');
    const resolver = App.getContentResolver();
    const TextView = Java.use('android.widget.TextView');
    const Bundle = Java.use('android.os.Bundle');
    const WeakMap = Java.use('java.util.WeakHashMap');
    const MapEntry = Java.use('java.util.Map$Entry');
    const Paint = Java.use('android.text.TextPaint');
    const TextUtils = Java.use('android.text.TextUtils');
    const Truncate = Java.use('android.text.TextUtils$TruncateAt');
    const Spannable = Java.use('android.text.SpannableString');
    const Size = Java.use('android.text.style.AbsoluteSizeSpan');
    const Station = Java.use('com.adayo.proxy.dab.aidl.beans.RadioStation');
    const views = WeakMap.$new();
    let queued = false, error = '', lastReport = '', modified = 0, restorePending = false;
    let binds = 0, formats = 0, restores = 0, polls = 0;
    function enabled() {
        try {
            const value = Settings.getString(resolver, 'voyahplay_radio_labels_enabled');
            return value !== null && value.toString() === '1';
        } catch (e) { error = String(e); return false; }
    }
    function restore(view, state) {
        if (!state.getBoolean('modified')) return;
        view.setTextSize(0, state.getFloat('textSize')); // COMPLEX_UNIT_PX: restore the original paint metrics too.
        view.setMaxLines(state.getInt('maxLines'));
        const ellipsis = state.getString('ellipsis');
        view.setEllipsize(ellipsis === null ? null : Truncate.valueOf(ellipsis.toString()));
        view.setIncludeFontPadding(state.getBoolean('fontPadding'));
        view.setText(Java.use('java.lang.String').$new(state.getString('frequency').toString()));
        state.putBoolean('modified', false);
        state.remove('sourceKey'); state.remove('styled'); state.remove('displayText');
        modified = Math.max(0, modified - 1); restores++;
    }
    function paint(view, state) {
        const station = Java.cast(state.getParcelable('station'), Station);
        const frequency = state.getString('frequency').toString();
        const name = cleanStationName(station.getPSName(), frequency);
        if (!name) { restore(view, state); return; }
        const params = view.getLayoutParams();
        const originalSize = state.getFloat('textSize');
        const width = view.getWidth() || (params === null ? 0 : params.width.value);
        const height = view.getHeight() || (params === null ? 0 : params.height.value);
        const availableWidth = Math.max(1, width > 0 ? width - view.getPaddingLeft() - view.getPaddingRight() :
            view.getPaint().measureText(frequency));
        const availableHeight = height > 0 ? height - view.getPaddingTop() - view.getPaddingBottom() : originalSize * 2.5;
        const fontSize = Math.max(1, Math.floor(Math.min(originalSize * 0.72, availableHeight / 2.5)));
        const key = JSON.stringify([name, frequency, availableWidth, availableHeight, fontSize]);
        const previous = state.getString('sourceKey');
        const cached = state.getCharSequence('styled');
        if (previous !== null && previous.toString() === key && cached !== null) {
            if (view.getText().toString() !== state.getString('displayText').toString()) view.setText(cached);
            return;
        }
        formats++;
        const textPaint = Paint.$new(view.getPaint());
        textPaint.setTextSize(fontSize);
        const end = Truncate.END.value;
        const first = TextUtils.ellipsize(Java.use('java.lang.String').$new(name), textPaint, availableWidth, end).toString();
        const second = TextUtils.ellipsize(Java.use('java.lang.String').$new(frequency), textPaint, availableWidth, end).toString();
        const text = first + '\n' + second;
        const styled = Spannable.$new(Java.use('java.lang.String').$new(text));
        styled.setSpan(Size.$new(fontSize, false), 0, first.length, 33);
        styled.setSpan(Size.$new(fontSize, false), first.length + 1, text.length, 33);
        // Keep OEM bounds, click targets, frequency formatting, colours and selected-state handling.
        const wasModified = state.getBoolean('modified');
        state.putBoolean('modified', true); // A partial UI failure must still be eligible for rollback.
        if (!wasModified) modified++;
        if (!wasModified || view.getTextSize() !== fontSize) {
            view.setTextSize(0, fontSize); // Match the two-line layout metrics, not only the spans.
            view.setMaxLines(2); view.setEllipsize(null); view.setIncludeFontPadding(false);
        }
        view.setText(styled);
        state.putString('sourceKey', key); state.putString('displayText', text);
        state.putCharSequence('styled', styled);
    }
    let active = enabled(), requested = active;
    function remember(holder) {
        binds++;
        const station = holder.station.value, view = holder.tvFreq.value;
        if (station === null || view === null) return;
        const frequency = view.getText().toString(); // Original bindView has already formatted the band/frequency.
        let state = views.get(view);
        if (state === null) {
            state = Bundle.$new();
            state.putInt('maxLines', view.getMaxLines());
            state.putFloat('textSize', view.getTextSize());
            state.putBoolean('fontPadding', view.getIncludeFontPadding());
            const ellipsis = view.getEllipsize();
            if (ellipsis !== null) state.putString('ellipsis', ellipsis.name());
            views.put(view, state);
        } else state = Java.cast(state, Bundle);
        state.putString('frequency', frequency);
        state.putParcelable('station', station);
        if (active) paint(view, state); else restore(view, state);
    }
    function restoreAll() {
        error = '';
        try {
            const entries = views.entrySet().iterator();
            while (entries.hasNext()) {
                // Iterator.next() is declared Object; Frida does not expose Map.Entry methods until cast.
                const entry = Java.cast(entries.next(), MapEntry);
                const key = entry.getKey();
                if (key === null) continue;
                const view = Java.cast(key, TextView), state = Java.cast(entry.getValue(), Bundle);
                try { restore(view, state); }
                catch (e) { error = String(e); }
            }
        } catch (e) { error = String(e); }
        restorePending = !!error;
        if (!restorePending) { views.clear(); modified = 0; }
    }
    function reportState() {
        const report = 'VOYAH_RADIO_LABELS_STATE pid=' + Process.id + ' enabled=' + (active ? 1 : 0) +
            ' modified=' + modified + ' error=' + error.replace(/[\r\n]/g, ' ').slice(0, 160);
        if (report !== lastReport) { console.log(report); lastReport = report; }
    }
    function refreshVisibleAdapters() {
        ['com.pateo.rdsapp.main.widget.listInfo.RadioInfoAdapter',
            'com.pateo.rdsapp.main.widget.play.PlayViewAdapter'].forEach(name => {
            try {
                let found = 0;
                Java.choose(name, {
                    onMatch: function (instance) {
                        if (++found > 32) return 'stop';
                        const retained = Java.retain(instance);
                        Java.scheduleOnMainThread(function () {
                            try { retained.notifyDataSetChanged(); }
                            catch (e) { error = String(e); }
                            finally { retained.$dispose(); }
                        });
                    }, onComplete: function () {}
                });
            } catch (e) { error = String(e); }
        });
    }
    function controlPoll() {
        requested = enabled();
        // This checks only the switch on the agent thread. No per-tile/UI work while unchanged.
        if (!queued && (requested !== active || restorePending)) {
            queued = true;
            Java.scheduleOnMainThread(function () {
                try {
                    const wasActive = active;
                    active = requested;
                    if (!active) restoreAll();
                    else if (!wasActive) { error = ''; restorePending = false; refreshVisibleAdapters(); }
                    reportState();
                } finally { queued = false; }
            });
        }
        reportState();
        if (++polls % 10 === 0) console.log('VOYAH_RADIO_LABELS_PERF pid=' + Process.id +
            ' binds=' + binds + ' formats=' + formats + ' restores=' + restores);
    }
    const methods = [];
    try {
        const names = [
            'com.pateo.rdsapp.main.widget.listInfo.RadioInfoAdapter$MyViewHolder',
            'com.pateo.rdsapp.main.widget.play.PlayViewAdapter$MyViewHolder'
        ];
        names.forEach(name => methods.push(Java.use(name).bindView.overload('int')));
        methods.forEach(method => {
            method.implementation = function (position) {
                const result = method.call(this, position);
                try { remember(this); } catch (e) { error = String(e); }
                return result;
            };
        });
        console.log('VOYAH_RADIO_LABELS_READY version=1.1 mode=on-bind pid=' + Process.id);
        reportState();
        setInterval(function () { Java.perform(controlPoll); }, 1000);
        // A list can already be visible when the user enables the patch. Request its normal
        // rebind once; never launch an Activity, tune a station or replace its click handlers.
        if (active) refreshVisibleAdapters();
    } catch (e) {
        methods.forEach(method => { try { method.implementation = null; } catch (_) {} });
        console.log('VOYAH_RADIO_LABELS_ERROR ' + String(e).replace(/[\r\n]/g, ' ').slice(0, 160));
        console.log('VOYAH_RADIO_LABELS_STATE pid=' + Process.id + ' enabled=0 modified=0 error=setup-failed');
    }
});
