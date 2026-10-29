'use strict';
// The reviewed PI Launcher: suppress a driver-dock show before its window is drawn.
Java.perform(function () {
    const Bar = Java.use('com.qinggan.mainlauncher.navigation.NavigationBar');
    const AppUtils = Java.use('com.qinggan.launcher.base.utils.AppUtils');
    const Clock = Java.use('android.os.SystemClock');
    const Settings = Java.use('android.provider.Settings$Global');
    const ActivityThread = Java.use('android.app.ActivityThread');
    const resolver = ActivityThread.currentApplication().getContentResolver();
    const method = Bar.show.overload();
    const key = 'voyahplay_tweaks_bounds_test';
    let allowedUntil = 0, calls = 0, blocked = 0, foreground = '', error = '', lastShowError = '';
    let installed = false, setupError = '';
    function lease(now) {
        const raw = Settings.getString(resolver, key);
        const match = raw === null ? null : /^1\|([0-9]{1,15})$/.exec(raw.toString());
        const end = match ? Number(match[1]) : 0;
        return end > now && end - now <= 30000 ? end : 0;
    }
    function poll() {
        const now = Number(Clock.elapsedRealtime());
        try {
            const base = JSON.parse(Settings.getString(resolver, 'voyahplay_tweaks_bounds_probe').toString());
            const end = lease(now);
            allowedUntil = end > now && base.version === '1.1' &&
                typeof base.pid === 'number' && base.pid > 0 && base.boot_ready === true &&
                base.subscription_valid === true && base.lease_active === true &&
                typeof base.now_ms === 'number' && base.now_ms >= 0 && base.now_ms <= now && now - base.now_ms <= 5000 &&
                typeof base.lease_until_ms === 'number' && base.lease_until_ms > now && base.lease_until_ms - now <= 30000 ?
                Math.min(end, base.lease_until_ms, base.now_ms + 5000) : 0;
            error = '';
        } catch (e) { allowedUntil = 0; error = String(e).slice(0,160); }
        Settings.putString(resolver, 'voyahplay_dock_probe', JSON.stringify({version:'1.0', pid:Process.id,
            now_ms:now, installed:installed, active:installed && allowedUntil > now,
            calls:calls, blocked:blocked, foreground:foreground,
            error:setupError || error, last_show_error:lastShowError}));
    }
    const guardedShow = function () {
        calls++;
        foreground = '';
        try {
            lastShowError = '';
            const now = Number(Clock.elapsedRealtime());
            if (this.mScreenId.value === 0 && this.mBarName.value !== null &&
                this.mBarName.value.toString() === 'MainScreenNavigationBar' && allowedUntil > now && lease(now) > now) {
                const context = this.mContext.value;
                if (context !== null && context.getPackageName().toString() === 'com.qinggan.app.launcher') {
                    const top = AppUtils.getTopAppInfo(context, 0, 4);
                    foreground = top === null ? '' : top.toString();
                    // QG overlays are separate windows. Require the actual main-display activity,
                    // rather than a remembered activity, a package prefix, or the passenger's app.
                    if (foreground === 'com.shilapi.xcertplay/com.shilapi.xcertplay.CarPlayHostActivity' ||
                        foreground === 'com.shilapi.xcertplay/com.shilapi.xcertplay.DiPlayActivity') {
                        blocked++;
                        return;
                    }
                }
            }
        } catch (e) { lastShowError = String(e).slice(0,160); }
        // HOME, cameras, permissions, other activities, errors and expired leases retain OEM behavior.
        return method.call(this);
    };
    try {
        if (method.implementation) throw new Error('DOCK_GUARD_CONFLICT: existing local show replacement');
        // Another Frida runtime is not visible through .implementation. ART exposes its
        // replacement as native, whereas the reviewed APK declares a normal Java method.
        const declared = Bar.class.getDeclaredMethods();
        let found = false;
        for (let i = 0; i < declared.length; i++) {
            const m = declared[i];
            if (m.getName().toString() === 'show' && m.getParameterTypes().length === 0) {
                found = true;
                if ((m.getModifiers() & 256) !== 0) throw new Error('DOCK_GUARD_CONFLICT: existing native show replacement');
            }
        }
        if (!found) throw new Error('DOCK_GUARD_METHOD: cannot verify the reviewed Java show method');
        method.implementation = guardedShow;
        installed = true;
    } catch (e) { setupError = String(e).slice(0,160); }
    poll();
    setInterval(function () { Java.perform(poll); }, 500);
    console.log('VOYAH_DOCK_GUARD_READY version=1.0 installed=' + installed);
});
