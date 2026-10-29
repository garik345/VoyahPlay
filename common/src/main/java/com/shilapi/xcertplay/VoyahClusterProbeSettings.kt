package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.voyah.root.VoyahRootModule
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Probe actions never enable the stream, reconnect CarPlay or launch at boot. */
internal object VoyahClusterProbeSettings {
    private val busy = AtomicBoolean()
    private val zoomSequence = AtomicLong()
    fun mapReady(context: Context) = AirPlayPersistence.loadClusterMapEnabled(context) &&
        MapMirrors.sink != null && MapMirrors.streamActive

    fun localReport(context: Context, rootReport: String = ""): String {
        val prefs = context.getSharedPreferences(VoyahClusterProbeActivity.PREFS, 0)
        val expected = rootReport.lineSequence().singleOrNull { it.startsWith("CLUSTER_PROBE_TOKEN=") }?.substringAfter('=')
        val matches = expected != null && expected == prefs.getString("token", "")
        return "\nPROBE_MAP_ENABLED=${AirPlayPersistence.loadClusterMapEnabled(context)}" +
            "\nPROBE_SINK_ATTACHED=${MapMirrors.sink != null}\nPROBE_STREAM_111_ACTIVE=${MapMirrors.streamActive}" +
            "\nPROBE_ACTIVITY_MODE=${prefs.getString("mode", "none")}\nPROBE_ACTIVITY_PHASE=${prefs.getString("phase", "none")}" +
            "\nPROBE_ACTIVITY_DISPLAY=${prefs.getInt("display", -1)}\nPROBE_ACTIVITY_MATCHES_LEASE=$matches" +
            "\nPROBE_MAP_FRAMES=${if (matches) prefs.getLong("frames", 0) else 0}\nPROBE_LAST_FRAMES=${prefs.getLong("frames", 0)}" +
            "\nPROBE_WINDOW_WIDTH=${prefs.getInt("windowWidth", 0)}\nPROBE_WINDOW_HEIGHT=${prefs.getInt("windowHeight", 0)}" +
            "\nPROBE_LAST_MAP_REQUEST_ACCEPTED=${prefs.getBoolean("mapRequestAccepted", false)}" +
            "\nPROBE_MEDIA_READY=${prefs.getBoolean("mediaReady", false)}" +
            "\nPROBE_MEDIA_READY_REPORT=${prefs.getString("mediaReadyReport", "none")}" +
            "\nPROBE_LAST_SAMPLED_PIXELS=${prefs.getInt("sampledPixels", 0)}\nPROBE_LAST_OPAQUE_PIXELS=${prefs.getInt("opaquePixels", 0)}" +
            "\nPROBE_LAST_NONBLACK_PIXELS=${prefs.getInt("nonBlackPixels", 0)}\nPROBE_LAST_DISTINCT_COLORS=${prefs.getInt("distinctColors", 0)}" +
            "\nPROBE_SAMPLE_AGE_MS=${(SystemClock.elapsedRealtime() - prefs.getLong("sampledAt", 0)).coerceAtLeast(0)}\n" +
            "PROBE_ZOOM_DIRECTION=${prefs.getString("zoomDirection", "none")}" +
            "\nPROBE_ZOOM_SENT=${prefs.getString("zoomSent", "none")}" +
            "\nPROBE_ZOOM_REASON=${prefs.getString("zoomReason", "none")}" +
            "\nPROBE_ZOOM_AGE_MS=${prefs.getLong("zoomAt", 0).takeIf { it > 0 }?.let { (System.currentTimeMillis() - it).takeIf { age -> age >= 0 } } ?: -1}\n" +
            VoyahClusterMap.report() + prefs.getString("autoReport", "").orEmpty().lineSequence()
                .joinToString("\n", postfix = "\n") { "AUTO_LAST_ROOT_$it" }
    }

    fun run(activity: Activity, action: VoyahRootModule.ClusterProbeAction) {
        if (action == VoyahRootModule.ClusterProbeAction.MAP && !mapReady(activity)) {
            AlertDialog.Builder(activity).setTitle(R.string.settings_cluster_probe_title)
                .setMessage(activity.getString(R.string.settings_cluster_probe_map_help,
                    activity.getString(R.string.carplay_map_on_instrument_cluster_experimental)))
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        if (!busy.compareAndSet(false, true) && action != VoyahRootModule.ClusterProbeAction.STOP) return
        if (action != VoyahRootModule.ClusterProbeAction.STATUS) VoyahClusterMap.pauseForTest(activity)
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.settings_cluster_probe_title)
            .setMessage(R.string.settings_cluster_probe_working).setPositiveButton(android.R.string.ok, null).show()
        val app = activity.applicationContext
        VoyahRootModule.clusterProbe(app, action) { _, rootReport ->
            busy.set(false)
            val report = rootReport + localReport(app, rootReport)
            app.getSharedPreferences(VoyahClusterProbeActivity.PREFS, 0).edit().putString("report", rootReport).apply()
            if (!activity.isFinishing && !activity.isDestroyed && dialog.isShowing) dialog.setMessage(report)
        }
    }

    fun copyReport(activity: Activity) {
        val root = activity.getSharedPreferences(VoyahClusterProbeActivity.PREFS, 0).getString("report", "").orEmpty()
        val report = root + localReport(activity, root)
        (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("VoyahPlay cluster probe", report))
        Toast.makeText(activity, R.string.settings_cluster_probe_copied, Toast.LENGTH_SHORT).show()
    }

    fun zoom(activity: Activity, zoomIn: Boolean) {
        val attempt = zoomSequence.incrementAndGet()
        val prefs = activity.applicationContext.getSharedPreferences(VoyahClusterProbeActivity.PREFS, 0)
        fun record(sent: String, reason: String) {
            if (zoomSequence.get() != attempt) return
            prefs.edit().putString("zoomDirection", if (zoomIn) "in" else "out")
                .putString("zoomSent", sent).putString("zoomReason", reason)
                .putLong("zoomAt", System.currentTimeMillis()).apply()
        }
        val snapshot = CarPlayBackgroundSession.snapshot()
        val owner = snapshot?.controller?.activeAirPlaySessionToken()
        fun unavailable() {
            if (!activity.isFinishing && !activity.isDestroyed)
                Toast.makeText(activity, R.string.settings_cluster_map_zoom_unavailable, Toast.LENGTH_SHORT).show()
        }
        if (snapshot == null || owner == null) { record("false", "no-active-session"); unavailable(); return }
        if (!snapshot.sink.isScreenStreamActive(111)) { record("false", "stream-not-active"); unavailable(); return }
        if (!(VoyahClusterMap.showsMap() || MapMirrors.voyahProbeShowsMap)) {
            record("false", "map-not-visible"); unavailable(); return
        }
        record("pending", "queued")
        snapshot.controller.zoomMirroredDashboardMapWithStatus(zoomIn, owner) { sent, reason ->
            activity.runOnUiThread {
                if (zoomSequence.get() == attempt) {
                    record(sent.toString(), reason)
                    if (!sent) unavailable()
                }
            }
        }
    }
}
