'use strict';
// Driver-only extension of the verified Tweaks bridge. Its boot/license gate is preserved.
Java.perform(function () {
    const Task = Java.use('com.android.server.wm.Task');
    const Rect = Java.use('android.graphics.Rect');
    const SystemClock = Java.use('android.os.SystemClock');
    const Settings = Java.use('android.provider.Settings$Global');
    const ActivityThread = Java.use('android.app.ActivityThread');
    const method = Task.onRequestedOverrideConfigurationChanged.overload('android.content.res.Configuration');
    let allowedUntil = 0, calls = 0, changes = 0, error = '';
    const busy = {};
    const resolver = ActivityThread.currentApplication().getContentResolver();
    function poll() {
        try {
            const now = Number(SystemClock.elapsedRealtime());
            const raw = Settings.getString(resolver, 'voyahplay_top_bounds_lease');
            const match = raw === null ? null : /^1\|([0-9]{1,15})$/.exec(raw.toString());
            const base = JSON.parse(Settings.getString(resolver, 'voyahplay_tweaks_bounds_probe').toString());
            const end = match ? Number(match[1]) : 0;
            allowedUntil = end > now && end - now <= 30000 && base.version === '1.1' &&
                base.pid === Process.id && base.boot_ready === true && base.subscription_valid === true && base.lease_active === true &&
                typeof base.now_ms === 'number' && base.now_ms >= 0 && base.now_ms <= now && now - base.now_ms <= 5000 &&
                typeof base.lease_until_ms === 'number' && base.lease_until_ms > now && base.lease_until_ms - now <= 30000 ?
                Math.min(end, base.lease_until_ms) : 0;
            error = '';
            Settings.putString(resolver, 'voyahplay_top_bounds_probe', JSON.stringify({version:'1.0',
                pid:Process.id, now_ms:now, active:allowedUntil > now, calls:calls, changes:changes, error:error}));
        } catch (e) { allowedUntil = 0; error = String(e).slice(0, 160); }
    }
    poll();
    setInterval(function () { Java.perform(poll); }, 500);
    method.implementation = function (config) {
        calls++;
        const tid = Process.getCurrentThreadId();
        if (allowedUntil > Number(SystemClock.elapsedRealtime()) && !busy[tid]) {
            busy[tid] = true;
            try {
                const record = this.getTopMostActivity();
                if (record !== null && record.packageName.value.toString() === 'com.shilapi.xcertplay' && record.getDisplayId() === 0) {
                    const component = record.intent.value.getComponent().getClassName().toString();
                    const win = config.windowConfiguration.value;
                    const bounds = win.getBounds();
                    if ((component === 'com.shilapi.xcertplay.DiPlayActivity' || component === 'com.shilapi.xcertplay.CarPlayHostActivity') &&
                        win.getWindowingMode() === 1 && bounds.right.value === 1920 &&
                        (bounds.left.value === 0 || bounds.left.value === 145) && bounds.top.value === 45 &&
                        (bounds.bottom.value === 720 || bounds.bottom.value === 530)) {
                        const full = Rect.$new(bounds);
                        full.top.value = 0;
                        win.setBounds(full); win.setAppBounds(full);
                        const density = config.densityDpi.value || 160;
                        config.screenHeightDp.value = Math.floor(full.height() * 160 / density);
                        changes++;
                    }
                }
            } catch (e) { error = String(e).slice(0, 160); }
            finally { delete busy[tid]; }
        }
        return method.call(this, config);
    };
    console.log('VOYAH_TOP_HOOK_READY version=1.0');
});
