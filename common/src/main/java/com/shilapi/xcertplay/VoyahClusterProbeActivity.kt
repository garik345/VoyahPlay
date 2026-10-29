package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.*
import android.hardware.display.DisplayManager
import android.os.*
import android.view.*
import android.widget.FrameLayout
import com.shilapi.xcertplay.voyah.VoyahClusterProbeSpec as Spec
import com.shilapi.xcertplay.voyah.root.VoyahRootModule

/** A temporary task above the OEM media card. It never renders on the centre/passenger screen. */
@android.annotation.TargetApi(30)
class VoyahClusterProbeActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var token = ""
    private var mode = ""
    private var deadline = 0L
    private var closing = false
    private var output: Surface? = null
    private var frames = 0L
    private var mapRequestAccepted = false
    private var sampledPixels = 0
    private var opaquePixels = 0
    private var nonBlackPixels = 0
    private var distinctColors = 0
    private var expectedDisplay = -1
    private val mirrorKey get() = "voyah-cluster-probe:$token"
    private var displayManager: DisplayManager? = null
    private var listenerInstalled = false
    private var lastRecord = 0L
    private var mediaReady: VoyahClusterMediaReady? = null
    private val watch = object : Runnable {
        override fun run() {
            if (mode == "auto") {
                val live = VoyahClusterMap.lease(token, expectedDisplay)
                if (live == null) { closeProbe(); return }
                deadline = live.deadline
            }
            if (SystemClock.elapsedRealtime() >= deadline || !validDisplay()) { closeProbe(); return }
            mediaReady?.ensure()
            if (mode != "auto" || SystemClock.elapsedRealtime() - lastRecord >= 10000) record("running")
            if (!closing) main.postDelayed(this, 1000)
        }
    }
    private val displays = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = Unit
        override fun onDisplayChanged(id: Int) { if (id == expectedDisplay && !validDisplay()) closeProbe() }
        override fun onDisplayRemoved(id: Int) { if (id == expectedDisplay) closeProbe() }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (Build.VERSION.SDK_INT != 30) { finishAndRemoveTask(); return }
        token = intent.getStringExtra(Spec.TOKEN).orEmpty()
        mode = intent.getStringExtra(Spec.MODE).orEmpty()
        deadline = intent.getLongExtra(Spec.DEADLINE, 0)
        val id = probeDisplay()?.displayId ?: -1
        if (mode == "auto") VoyahClusterMap.lease(token, id)?.let { deadline = it.deadline }
        if (!validDisplay() || !Spec.validLease(token, mode, id, deadline, SystemClock.elapsedRealtime())) {
            closeProbe(); return
        }
        expectedDisplay = id
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        VoyahRootModule.clusterProbe(applicationContext, VoyahRootModule.ClusterProbeAction.STATUS) { ok, report ->
            fun field(key: String) = report.lineSequence().singleOrNull { it.startsWith("$key=") }?.substringAfter('=')
            if (closing || isFinishing || isDestroyed) return@clusterProbe
            val authorizedDeadline = field("CLUSTER_PROBE_DEADLINE")?.toLongOrNull() ?: 0
            if (!ok || field("CLUSTER_PROBE_TOKEN") != token || field("CLUSTER_PROBE_MODE") != mode ||
                field("CLUSTER_PROBE_DISPLAY")?.toIntOrNull() != id || (mode != "auto" && authorizedDeadline != deadline) ||
                !validDisplay() || !Spec.validLease(token, mode, id, authorizedDeadline, SystemClock.elapsedRealtime())) { closeProbe(); return@clusterProbe }
            deadline = authorizedDeadline
            showProbe()
        }
    }

    private fun probeDisplay(): Display? = if (Build.VERSION.SDK_INT != 30) null else runCatching { display }.getOrNull()
    private fun validDisplay(): Boolean = runCatching {
        val screen = probeDisplay() ?: return false
        val size = Point().also { screen.getRealSize(it) }
        screen.isValid && screen.displayId > 0 && (expectedDisplay < 0 || screen.displayId == expectedDisplay) && screen.name == Spec.DISPLAY &&
            screen.flags and Display.FLAG_PRIVATE != 0 && size.x == 1920 && size.y == 720
    }.getOrDefault(false)

    private fun showProbe() {
        mediaReady = VoyahClusterMediaReady.create(this, {
            !closing && !isDestroyed && frames > 0 && mapRequestAccepted && validDisplay() &&
                Spec.validLease(token, mode, expectedDisplay, deadline, SystemClock.elapsedRealtime()) &&
                (mode != "auto" || VoyahClusterMap.lease(token, expectedDisplay) != null)
        }) { text ->
            android.util.Log.i("VoyahClusterMap", "Media readiness: $text")
            applicationContext.getSharedPreferences(PREFS, 0).edit().putString("mediaReadyReport", text).apply()
            record("media-ready")
        }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        val card = FrameLayout.LayoutParams(Spec.WIDTH, Spec.HEIGHT, Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = Spec.LEFT; topMargin = Spec.TOP
        }
        if (mode == "grid") root.addView(VoyahClusterProbeGrid(this, deadline), card)
        else {
            val viewport = VoyahClusterMapView(this)
            val waiting = viewport.waiting
            val texture = viewport.texture
            texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    if (closing) return
                    surface.setDefaultBufferSize(width, height)
                    val next = Surface(surface)
                    output = next
                    if (mode == "auto") {
                        if (!VoyahClusterMap.setSurface(token, next)) { closeProbe(); return }
                    } else MapMirrors.set(mirrorKey, next)
                    // The initialURL offer alone may leave the phone sending a black idle frame.
                    // Ask for the map after the mirror exists, using the active session's command queue.
                    val controller = CarPlayBackgroundSession.snapshot()?.controller
                    val completed: (Boolean) -> Unit = { accepted ->
                        main.post {
                            if (!closing && !isDestroyed && CarPlayBackgroundSession.snapshot()?.controller === controller) {
                                mapRequestAccepted = accepted
                                mediaReady?.ensure()
                                record("map-requested")
                            }
                        }
                    }
                    if (mode == "auto") VoyahClusterMap.requestMap(token, completed)
                    else controller?.showDashboardContent(com.shilapi.xcertplay.airplay.CarPlayClusterDisplay.MAP_URL, completed)
                    record("surface-ready")
                }
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = viewport.updateTransform()
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                    frames++; waiting.visibility = View.GONE
                    // Two tiny readbacks distinguish decoded black frames from a cluster composition failure.
                    // No route image is stored and this does not run on every frame.
                    if (frames == 1L || frames == 8L) runCatching {
                        texture.getBitmap(16, 16)?.let { bitmap ->
                            try {
                                val pixels = IntArray(bitmap.width * bitmap.height)
                                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                                sampledPixels = pixels.size
                                opaquePixels = pixels.count { Color.alpha(it) > 0 }
                                nonBlackPixels = pixels.count { Color.alpha(it) > 0 &&
                                    maxOf(Color.red(it), Color.green(it), Color.blue(it)) > 24 }
                                distinctColors = pixels.map { it and 0x00ffffff }.toSet().size
                            } finally { bitmap.recycle() }
                        }
                    }
                    if (frames == 1L) {
                        mediaReady?.ensure()
                        record("first-frame")
                    }
                }
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    releaseSurface(); closeProbe(); return true
                }
            }
            root.addView(viewport, card)
        }
        setContentView(root)
        applicationContext.getSharedPreferences(PREFS, 0).edit().putString("token", token)
            .putString("mediaReadyReport", "waiting-first-frame").apply()
        record("verified-window")
        displayManager = getSystemService(DisplayManager::class.java)
        displayManager?.registerDisplayListener(displays, main); listenerInstalled = true
        main.post(watch)
    }

    private fun record(phase: String) {
        val prefs = applicationContext.getSharedPreferences(PREFS, 0)
        if (prefs.getString("token", "") != token) return
        lastRecord = SystemClock.elapsedRealtime()
        prefs.edit().putString("mode", mode).putString("phase", phase).putInt("display", probeDisplay()?.displayId ?: -1)
            .putInt("windowWidth", window.decorView.width).putInt("windowHeight", window.decorView.height)
            .putBoolean("mapRequestAccepted", mapRequestAccepted)
            .putBoolean("mediaReady", mediaReady?.ready == true)
            .putInt("sampledPixels", sampledPixels).putInt("opaquePixels", opaquePixels)
            .putInt("nonBlackPixels", nonBlackPixels).putInt("distinctColors", distinctColors)
            .putLong("frames", frames).putLong("sampledAt", SystemClock.elapsedRealtime()).apply()
    }
    private fun releaseSurface() {
        if (mode == "auto") VoyahClusterMap.setSurface(token, null) else MapMirrors.set(mirrorKey, null)
        output?.release(); output = null
    }
    private fun closeProbe(removeTask: Boolean = true) {
        if (closing) return
        closing = true; mediaReady?.close()
        main.removeCallbacksAndMessages(null); record("closed"); releaseSurface()
        if (listenerInstalled) displayManager?.unregisterDisplayListener(displays)
        listenerInstalled = false
        if (removeTask) finishAndRemoveTask()
    }
    override fun onDestroy() { closeProbe(false); super.onDestroy() }
    companion object { const val PREFS = "voyah_cluster_probe" }
}

/** Deliberately simple calibration pixels, confined to the measured media card. */
internal class VoyahClusterProbeGrid(context: android.content.Context, private val deadline: Long) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(10, 18, 26))
        val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        paint.style = Paint.Style.FILL
        colors.forEachIndexed { i, color ->
            paint.color = color
            val left = width * (i % 2) / 2f; val top = height * (i / 2) / 2f
            canvas.drawRect(left, top, left + width / 2f, top + height / 2f, paint)
        }
        paint.color = Color.WHITE; paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f
        canvas.drawRect(2f, 2f, width - 2f, height - 2f, paint)
        paint.style = Paint.Style.FILL; paint.color = Color.BLACK
        canvas.drawRect(0f, height / 2f - 44, width.toFloat(), height / 2f + 44, paint)
        paint.color = Color.WHITE; paint.textSize = 25f; paint.textAlign = Paint.Align.CENTER
        canvas.drawText("VOYAHPLAY 574 x 464", width / 2f, height / 2f - 6, paint)
        val seconds = ((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 999) / 1000
        canvas.drawText("66,200   $seconds s", width / 2f, height / 2f + 28, paint)
        if (seconds > 0) postInvalidateDelayed(1000)
    }
}
