'use strict';
// Extend the OEM fullscreen predicate before either status window is drawn.
Java.perform(function () {
    const Invisible = Java.use('com.qinggan.systemui.statusbar.utils.StatusBarInvisibleItem');
    const Clock = Java.use('android.os.SystemClock');
    const Settings = Java.use('android.provider.Settings$Global');
    const ActivityThread = Java.use('android.app.ActivityThread');
    const resolver = ActivityThread.currentApplication().getContentResolver();
    const method = Invisible.isHit.overload('java.lang.String', 'int');
    let allowedUntil = 0, calls = 0, hits = 0, error = '';
    function poll() {
        const now = Number(Clock.elapsedRealtime());
        try {
            const raw = Settings.getString(resolver, 'voyahplay_top_bounds_lease');
            const match = raw === null ? null : /^1\|([0-9]{1,15})$/.exec(raw.toString());
            const base = JSON.parse(Settings.getString(resolver, 'voyahplay_tweaks_bounds_probe').toString());
            const end = match ? Number(match[1]) : 0;
            allowedUntil = end > now && end - now <= 30000 && base.version === '1.1' &&
                typeof base.pid === 'number' && base.pid > 0 && base.boot_ready === true &&
                base.subscription_valid === true && base.lease_active === true &&
                typeof base.now_ms === 'number' && base.now_ms >= 0 && base.now_ms <= now && now - base.now_ms <= 5000 &&
                typeof base.lease_until_ms === 'number' && base.lease_until_ms > now && base.lease_until_ms - now <= 30000 ?
                Math.min(end, base.lease_until_ms, base.now_ms + 5000) : 0;
            error = '';
        } catch (e) { allowedUntil = 0; error = String(e).slice(0, 160); }
        Settings.putString(resolver, 'voyahplay_status_bar_probe', JSON.stringify({version:'1.0',
            pid:Process.id, now_ms:now, active:allowedUntil > now, calls:calls, hits:hits, error:error}));
    }
    poll();
    setInterval(function () { Java.perform(poll); }, 500);
    method.implementation = function (component, displayId) {
        const original = method.call(this, component, displayId);
        calls++;
        try {
            const now = Number(Clock.elapsedRealtime());
            if (displayId === 0 && allowedUntil > now && component !== null) {
                const name = component.toString();
                if (name === 'com.shilapi.xcertplay.CarPlayHostActivity' || name === 'com.shilapi.xcertplay.DiPlayActivity') {
                    // A cleared setting takes effect even before the next periodic sample.
                    const raw = Settings.getString(resolver, 'voyahplay_top_bounds_lease');
                    const lease = raw === null ? null : /^1\|([0-9]{1,15})$/.exec(raw.toString());
                    const end = lease ? Number(lease[1]) : 0;
                    if (end > now && end - now <= 30000) { hits++; return true; }
                }
            }
        } catch (e) { error = String(e).slice(0, 160); }
        return original;
    };
    console.log('VOYAH_STATUS_BAR_READY version=1.0');
});
