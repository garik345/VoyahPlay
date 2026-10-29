'use strict';
// Scoped driver bounds and root-launched cluster probes. The verified bridge boot/license gate is preserved.
Java.perform(function () {
    const Task = Java.use('com.android.server.wm.Task');
    const Rect = Java.use('android.graphics.Rect');
    const SystemClock = Java.use('android.os.SystemClock');
    const Settings = Java.use('android.provider.Settings$Global');
    const ActivityThread = Java.use('android.app.ActivityThread');
    const method = Task.onRequestedOverrideConfigurationChanged.overload('android.content.res.Configuration');
    let allowedUntil = 0, bridgeUntil = 0, calls = 0, changes = 0, probeChanges = 0, error = '', probeError = '';
    let autoLease = null;
    const busy = {};
    const resolver = ActivityThread.currentApplication().getContentResolver();
    function poll() {
        try {
            const now = Number(SystemClock.elapsedRealtime());
            const autoRaw = Settings.getString(resolver, 'voyahplay_cluster_auto_lease');
            const autoMatch = autoRaw === null ? null : /^1\|([0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})\|([1-9][0-9]{0,5})\|([0-9]{1,15})$/.exec(autoRaw.toString());
            autoLease = autoMatch && Number(autoMatch[3]) > now && Number(autoMatch[3]) - now <= 45000 ?
                {token:autoMatch[1], display:Number(autoMatch[2]), end:Number(autoMatch[3])} : null;
            const raw = Settings.getString(resolver, 'voyahplay_top_bounds_lease');
            const match = raw === null ? null : /^1\|([0-9]{1,15})$/.exec(raw.toString());
            const base = JSON.parse(Settings.getString(resolver, 'voyahplay_tweaks_bounds_probe').toString());
            const end = match ? Number(match[1]) : 0;
            const ready = base.version === '1.1' && base.pid === Process.id && base.boot_ready === true &&
                base.subscription_valid === true && typeof base.now_ms === 'number' &&
                base.now_ms >= 0 && base.now_ms <= now && now - base.now_ms <= 5000;
            bridgeUntil = ready ? base.now_ms + 5000 : 0;
            allowedUntil = ready && end > now && end - now <= 30000 && base.lease_active === true &&
                typeof base.lease_until_ms === 'number' && base.lease_until_ms > now && base.lease_until_ms - now <= 30000 ?
                Math.min(end, base.lease_until_ms) : 0;
            error = '';
            Settings.putString(resolver, 'voyahplay_top_bounds_probe', JSON.stringify({version:'1.0',
                pid:Process.id, now_ms:now, active:allowedUntil > now, calls:calls, changes:changes, error:error,
                probe_changes:probeChanges, probe_error:probeError}));
        } catch (e) { allowedUntil = 0; bridgeUntil = 0; autoLease = null; error = String(e).slice(0, 160); }
    }
    function restoreProbe(record, win, bounds, config) {
        try {
            const now = Number(SystemClock.elapsedRealtime());
            if (bridgeUntil <= now || win.getWindowingMode() !== 1 || bounds.left.value !== 0 ||
                bounds.top.value !== 0 || bounds.right.value !== 0 || bounds.bottom.value !== 0 ||
                record.getDisplayId() <= 1 || record.launchedFromUid.value !== 0) return false;
            const intent = record.intent.value;
            if (intent.getComponent().getClassName().toString() !== 'com.shilapi.xcertplay.VoyahClusterProbeActivity') return false;
            const token = intent.getStringExtra('voyah_probe_token');
            const mode = intent.getStringExtra('voyah_probe_mode');
            let end = Number(intent.getLongExtra('voyah_probe_deadline', 0));
            if (mode !== null && mode.toString() === 'auto') {
                if (autoLease !== null && token !== null && autoLease.token === token.toString() && autoLease.display === record.getDisplayId()) end = autoLease.end;
                // The first Task configuration can arrive before our next 500 ms settings poll.
                // Its bounded root-launched Intent is enough initially; later configurations require renewal.
                else if (end <= now || end - now > 45000) return false;
            }
            if (token === null || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(token.toString()) ||
                mode === null || !['grid', 'map', 'auto'].includes(mode.toString()) || end <= now || end - now > 60000) return false;
            const info = record.getDisplayContent().getDisplayInfo();
            if (info.name.value.toString() !== 'Cluster-Media-Display' || info.ownerUid.value !== 1000 ||
                info.ownerPackageName.value.toString() !== 'com.qinggan.cluster' ||
                info.logicalWidth.value !== 1920 || info.logicalHeight.value !== 720 || (info.flags.value & 4) === 0) return false;
            // The verified Tweaks bridge assigns its shared empty Rect to display 5. Copy, never mutate that shared Rect.
            const full = Rect.$new(bounds);
            full.right.value = 1920; full.bottom.value = 720;
            win.setBounds(full); win.setAppBounds(full);
            const density = config.densityDpi.value || 160;
            config.screenWidthDp.value = Math.floor(1920 * 160 / density);
            config.screenHeightDp.value = Math.floor(720 * 160 / density);
            probeChanges++; probeError = '';
            return true;
        } catch (e) { probeError = String(e).slice(0, 160); return false; }
    }
    poll();
    setInterval(function () { Java.perform(poll); }, 500);
    method.implementation = function (config) {
        calls++;
        const tid = Process.getCurrentThreadId();
        const now = Number(SystemClock.elapsedRealtime());
        if ((allowedUntil > now || bridgeUntil > now) && !busy[tid]) {
            busy[tid] = true;
            try {
                const record = this.getTopMostActivity();
                if (record !== null && record.packageName.value.toString() === 'com.shilapi.xcertplay') {
                    const component = record.intent.value.getComponent().getClassName().toString();
                    const win = config.windowConfiguration.value;
                    const bounds = win.getBounds();
                    if (component === 'com.shilapi.xcertplay.VoyahClusterProbeActivity') restoreProbe(record, win, bounds, config);
                    else if (allowedUntil > now && record.getDisplayId() === 0 &&
                        (component === 'com.shilapi.xcertplay.DiPlayActivity' || component === 'com.shilapi.xcertplay.CarPlayHostActivity') &&
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
