package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.voyah.root.VoyahRootModule

/** Automatic Voyah media-card map. Uses the retained session, even while the main UI is away. */
internal object VoyahClusterMap {
    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var session: CarPlayBackgroundSession.Snapshot? = null
    private var owner: Any? = null
    private var suppressed: Any? = null
    private val binding = VoyahClusterSurfaceBinding<AndroidMediaSink, Surface> { sink, key, output ->
        sink.setMirrorSurface(111, key, output)
    }
    private val loop = VoyahClusterMapLoop(SystemClock::elapsedRealtime, { keepAlive, complete ->
        VoyahRootModule.clusterAuto(requireNotNull(app), keepAlive) { ok, text ->
            complete(ok, text)
            bind()
        }
    }, { text ->
        app?.getSharedPreferences(VoyahClusterProbeActivity.PREFS, 0)?.edit()
            ?.putString("autoReport", text)?.apply()
    })
    private val poll = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, 1000)
        }
    }

    fun attach(context: Context) {
        if (app != null) return
        app = context.applicationContext
        main.post(poll)
    }

    private fun refresh() {
        val context = app ?: return
        val current = CarPlayBackgroundSession.snapshot()
        val token = current?.controller?.activeAirPlaySessionToken()
        val glance = CarPlayGlance.snapshot()
        if (!glance.connected || !glance.navigationActive || token !== owner) suppressed = null
        owner = token
        if (token != null && glance.connected && !glance.navigationActive && AirPlayPersistence.loadClusterMapEnabled(context)) {
            current.controller.prepareIdleDashboardMap(token)
        }
        val wanted = token != null && token !== suppressed && glance.connected && glance.navigationActive &&
            AirPlayPersistence.loadClusterMapEnabled(context) && current?.controller?.hasPreparedDashboardScreenForSession(token) == true
        session = if (wanted) current else null
        loop.update(if (wanted) token else null)
        bind()
    }

    /** Calibration/Stop stays in control until this route ends or the phone reconnects. */
    fun pauseForTest(context: Context) {
        attach(context)
        suppressed = CarPlayBackgroundSession.snapshot()?.controller?.activeAirPlaySessionToken()
        owner = suppressed
        session = null
        loop.update(null)
        bind()
    }

    fun lease(token: String, display: Int): VoyahClusterMapLoop.Lease? = loop.lease?.takeIf {
        ownsLiveSession() && it.token == token && it.display == display && it.deadline > SystemClock.elapsedRealtime()
    }

    fun report(): String = "AUTO_NAVIGATION_ACTIVE=${CarPlayGlance.snapshot().navigationActive}" +
        "\nAUTO_ROUTE_SUPPRESSED=${owner != null && owner === suppressed}" +
        "\nAUTO_LEASE_REMAINING_MS=${loop.lease?.let { (it.deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0) } ?: 0}\n"

    fun setSurface(token: String, window: Any, next: Surface?): Boolean {
        if (next != null && (loop.lease?.token != token || !ownsLiveSession())) return false
        binding.set(token, window, next)
        bind()
        return true
    }

    private fun ownsLiveSession(): Boolean {
        com.shilapi.xcertplay.voyah.root.VoyahFeatureAccess.refresh(null)
        val expected = session ?: return false
        val current = CarPlayBackgroundSession.snapshot() ?: return false
        val glance = CarPlayGlance.snapshot()
        return current.controller === expected.controller && current.sink === expected.sink &&
            current.controller.activeAirPlaySessionToken() === owner && owner != null &&
            glance.connected && glance.navigationActive && current.controller.hasPreparedDashboardScreenForSession(owner) &&
            app?.let(AirPlayPersistence::loadClusterMapEnabled) == true
    }

    fun requestMap(token: String, reopen: Boolean = false, complete: (Boolean) -> Unit,
        refreshViewArea: Boolean = false) {
        if (loop.lease?.token != token || !ownsLiveSession()) { complete(false); return }
        if (refreshViewArea) session!!.controller.refreshDashboardMapViewAreaForSession(owner, complete)
        else if (reopen) session!!.controller.recoverDashboardMapForSession(owner, complete)
        else session!!.controller.showDashboardContentForSession(
            com.shilapi.xcertplay.airplay.CarPlayClusterDisplay.MAP_URL, owner, complete)
    }

    fun showsMap(): Boolean = binding.active && binding.output?.isValid == true &&
        loop.lease?.deadline?.let { it > SystemClock.elapsedRealtime() } == true && ownsLiveSession()

    private fun bind() {
        val valid = loop.lease?.takeIf { it.token == binding.token && it.deadline > SystemClock.elapsedRealtime() }
        val sink = if (valid != null && binding.output?.isValid == true && ownsLiveSession()) session?.sink else null
        binding.bind(valid?.token, sink)
    }
}
