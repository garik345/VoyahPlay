// BEGIN VOYAHPLAY BOUNDS TEST 1.1
// Same Tweaks Java.perform scope; no additional method replacement or injector.
// That scope aliases String to a Java class and has NO shared contentResolver.
var voyahPlayBoundsDeadline = 0;
var voyahPlayBoundsClock = null;
var voyahPlayBoundsResolver = null;
var voyahPlayBoundsSettings = null;
var voyahPlayBoundsThread = null;
var voyahPlayBoundsRaised = null;
var voyahPlayBoundsLowered = null;
var voyahPlayBoundsProbe = {
    version: '1.1', pid: Process.id, now_ms: 0, lease_until_ms: 0, lease_active: false,
    hook_calls: 0, selection_calls: 0, selected_calls: 0,
    boot_ready: null, subscription_valid: null, last_decision: 'loaded',
    last_component: '', display_id: null, windowing_mode: null, last_error: ''
};
function voyahPlayBoundsError(phase, error) {
    voyahPlayBoundsProbe.last_error = phase + ': ' +
        (error === null || error === undefined ? 'unknown' : error.toString()).slice(0, 512);
}
function voyahPlayRefreshBoundsLease() {
    try {
        if (voyahPlayBoundsResolver === null) {
            var activityThread = voyahPlayBoundsThread.currentActivityThread();
            if (activityThread === null) throw new Error('ActivityThread not ready');
            var systemContext = activityThread.getSystemContext();
            if (systemContext === null) throw new Error('System context not ready');
            voyahPlayBoundsResolver = Java.retain(systemContext.getContentResolver());
        }
        var raw = voyahPlayBoundsSettings.getString(voyahPlayBoundsResolver,
            'voyahplay_tweaks_bounds_test');
        raw = raw === null ? '' : raw.toString();
        var match = /^1\|([0-9]{1,15})$/.exec(raw);
        var now = Number(voyahPlayBoundsClock.elapsedRealtime());
        var deadline = match ? Number(match[1]) : 0;
        voyahPlayBoundsDeadline = deadline > now && deadline - now <= 30000 ? deadline : 0;
        voyahPlayBoundsProbe.now_ms = now;
        voyahPlayBoundsProbe.lease_until_ms = voyahPlayBoundsDeadline;
        voyahPlayBoundsProbe.lease_active = voyahPlayBoundsDeadline > now;
    } catch (error) {
        voyahPlayBoundsDeadline = 0;
        voyahPlayBoundsProbe.lease_until_ms = 0;
        voyahPlayBoundsProbe.lease_active = false;
        voyahPlayBoundsError('lease-read', error);
    }
    // Settings writes ONLY from this timer, never from a WM method callback.
    if (voyahPlayBoundsResolver !== null) {
        try {
            voyahPlayBoundsSettings.putString(voyahPlayBoundsResolver,
                'voyahplay_tweaks_bounds_probe', JSON.stringify(voyahPlayBoundsProbe));
        } catch (error) { voyahPlayBoundsError('probe-write', error); }
    }
}
try {
    voyahPlayBoundsClock = Java.use('android.os.SystemClock');
    voyahPlayBoundsThread = Java.use('android.app.ActivityThread');
    voyahPlayBoundsSettings = Java.use('android.provider.Settings$Global');
    voyahPlayBoundsRaised = Rect.$new(0, 45, 1920, 720);
    voyahPlayBoundsLowered = Rect.$new(0, 45, 1920, 530);
    voyahPlayRefreshBoundsLease();
    setInterval(function () { Java.perform(voyahPlayRefreshBoundsLease); }, 500);
} catch (error) {
    voyahPlayBoundsDeadline = 0;
    voyahPlayBoundsError('initialization', error);
}
function voyahPlayObserveBoundsHook(activity, ready, subscribed) {
    try {
        var packageName = activity.packageName.value;
        if (packageName === null || packageName.toString() !== 'com.shilapi.xcertplay') return;
        voyahPlayBoundsProbe.hook_calls++;
        voyahPlayBoundsProbe.boot_ready = ready === true;
        voyahPlayBoundsProbe.subscription_valid = subscribed === true;
        if (!ready || !subscribed) voyahPlayBoundsProbe.last_decision = 'existing-tweaks-gate';
    } catch (error) { voyahPlayBoundsError('hook-observation', error); }
}
function voyahPlayTestBounds(activity, packageName, displayId, windowingMode, original) {
    if (packageName === null || packageName.toString() !== 'com.shilapi.xcertplay') return original;
    voyahPlayBoundsProbe.selection_calls++;
    voyahPlayBoundsProbe.display_id = displayId;
    voyahPlayBoundsProbe.windowing_mode = windowingMode;
    if (displayId !== 0 || windowingMode !== 1) {
        voyahPlayBoundsProbe.last_decision = 'different-display-or-mode'; return original;
    }
    try {
        var now = Number(voyahPlayBoundsClock.elapsedRealtime());
        if (voyahPlayBoundsDeadline === 0 || now >= voyahPlayBoundsDeadline ||
            voyahPlayBoundsDeadline - now > 30000) {
            voyahPlayBoundsProbe.last_decision = 'no-active-lease'; return original;
        }
        var component = activity.mActivityComponent.value;
        if (component === null) {
            voyahPlayBoundsProbe.last_decision = 'no-component'; return original;
        }
        var name = component.getClassName().toString();
        voyahPlayBoundsProbe.last_component = name;
        if (name !== 'com.shilapi.xcertplay.DiPlayActivity' &&
            name !== 'com.shilapi.xcertplay.CarPlayHostActivity') {
            voyahPlayBoundsProbe.last_decision = 'different-activity'; return original;
        }
        if (original.left.value !== 145 || original.top.value !== 45 ||
            original.right.value !== 1920 ||
            (original.bottom.value !== 720 && original.bottom.value !== 530)) {
            voyahPlayBoundsProbe.last_decision = 'unexpected-original-bounds'; return original;
        }
        voyahPlayBoundsProbe.selected_calls++;
        voyahPlayBoundsProbe.last_decision = 'selected-full-width';
        return original.bottom.value === 720 ? voyahPlayBoundsRaised : voyahPlayBoundsLowered;
    } catch (error) {
        voyahPlayBoundsProbe.last_decision = 'selection-error';
        voyahPlayBoundsError('bounds-selection', error);
    }
    return original;
}
// END VOYAHPLAY BOUNDS TEST 1.1
