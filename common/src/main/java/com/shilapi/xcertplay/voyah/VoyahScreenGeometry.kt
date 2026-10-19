package com.shilapi.xcertplay.voyah

import kotlin.math.roundToInt

/** Physical left edge of the driver's expanded window, independent of reading direction. */
object VoyahScreenGeometry {
    const val SIDE_ICON_SCALE = 0.8f
    const val SHADE_HANDLE_GUTTER_DP = 16f

    fun leftGutterPx(displayId: Int, multiWindow: Boolean, hideDock: Boolean,
        displayWidth: Int, windowLeft: Int, contentWidth: Int, displayDensity: Float): Int {
        if (displayId != 0 || multiWindow || !hideDock || displayWidth <= 0 ||
            windowLeft != 0 || contentWidth < displayWidth) return 0
        val density = displayDensity.takeIf { it.isFinite() && it > 0f } ?: 1f
        return (SHADE_HANDLE_GUTTER_DP * density).roundToInt().coerceIn(0, contentWidth / 4)
    }
}
