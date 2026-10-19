package com.shilapi.xcertplay.voyah

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.util.DisplayMetrics
import android.view.View
import android.widget.FrameLayout
import com.shilapi.xcertplay.AirPlayPersistence

/** Wraps the whole content, keeping video, touch mapping, controls and popups in one rectangle. */
class VoyahFullscreenContent private constructor(private val activity: Activity, content: View) : FrameLayout(activity) {
    private val preferences = activity.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
    private val preferenceChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "hide_bottom_bar") post { requestLayout() }
    }

    init {
        setBackgroundColor(Color.BLACK)
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        preferences.registerOnSharedPreferenceChangeListener(preferenceChanged)
        requestLayout()
    }

    override fun onDetachedFromWindow() {
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceChanged)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        updateGutter(MeasureSpec.getSize(widthMeasureSpec))
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Window origin can finish changing after measure when the OEM applies new bounds.
        updateGutter(right - left)
    }

    @Suppress("DEPRECATION")
    private fun updateGutter(contentWidth: Int) {
        val targetDisplay = display ?: activity.windowManager.defaultDisplay
        val metrics = DisplayMetrics()
        targetDisplay.getRealMetrics(metrics)
        val origin = IntArray(2)
        activity.window.decorView.getLocationOnScreen(origin)
        val gutter = VoyahScreenGeometry.leftGutterPx(targetDisplay.displayId, activity.isInMultiWindowMode,
            AirPlayPersistence.loadHideBottomBar(activity), metrics.widthPixels, origin[0], contentWidth, metrics.density)
        if (paddingLeft != gutter) setPadding(gutter, 0, 0, 0)
    }

    companion object {
        @JvmStatic fun wrap(activity: Activity, content: View): View =
            if (content is VoyahFullscreenContent) content else VoyahFullscreenContent(activity, content)
    }
}
