package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Matrix
import android.graphics.Color
import android.view.Gravity
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.TextView
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.voyah.VoyahClusterProbeSpec as Spec
import kotlin.math.roundToInt

/** Stream density is independent of the centre display and of the phone's geographic zoom. */
internal object VoyahClusterMapLayout {
    val elementSizes = listOf(75, 100, 125, 150)
    fun elementSize(value: Int) = value.takeIf { it in elementSizes } ?: 100

    fun stream(value: Int) = CarPlayClusterDisplay.config(
        widthPixels = Spec.WIDTH,
        heightPixels = Spec.HEIGHT,
        scalePercent = (10000.0 / elementSize(value)).roundToInt(),
        content = CarPlayClusterDisplay.Content.MAP,
        baseSafeArea = CarPlayClusterDisplay.VIRTUAL_SAFE_AREA_PERCENT,
    )

    fun cover(width: Int, height: Int, aspect: Double): Matrix {
        val matrix = Matrix()
        if (width <= 0 || height <= 0 || !aspect.isFinite() || aspect <= 0) return matrix
        val ratio = aspect / (width.toDouble() / height)
        matrix.setScale(maxOf(1.0, ratio).toFloat(), maxOf(1.0, 1.0 / ratio).toFloat(), width / 2f, height / 2f)
        return matrix
    }
}

/** One clipped card: texture and waiting label always occupy its complete measured height. */
internal class VoyahClusterMapView(context: Context) : FrameLayout(context) {
    val texture = TextureView(context)
    val waiting = TextView(context).apply {
        text = context.getString(R.string.settings_cluster_probe_waiting)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
    }
    private val aspectChanged: () -> Unit = { updateTransform() }

    init {
        setBackgroundColor(Color.BLACK)
        clipChildren = true
        clipToPadding = true
        addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(waiting, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun updateTransform() = texture.setTransform(VoyahClusterMapLayout.cover(width, height, MapMirrors.STREAM_ASPECT))
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateTransform()
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        MapMirrors.addAspectListener(aspectChanged)
        updateTransform()
    }
    override fun onDetachedFromWindow() {
        MapMirrors.removeAspectListener(aspectChanged)
        super.onDetachedFromWindow()
    }
}
