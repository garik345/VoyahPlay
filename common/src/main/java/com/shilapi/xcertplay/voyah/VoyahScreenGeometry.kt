package com.shilapi.xcertplay.voyah

import kotlin.math.roundToInt

/** Physical left edge of the driver's expanded window, independent of reading direction. */
object VoyahScreenGeometry {
    const val SIDE_ICON_SCALE = 0.8f
    // Reserve the visible handle (ends at 16 dp), followed by a small clear gap.
    const val SHADE_HANDLE_END_DP = 16f
    const val SHADE_HANDLE_CLEARANCE_DP = 3f
    const val SHADE_HANDLE_GUTTER_DP = SHADE_HANDLE_END_DP + SHADE_HANDLE_CLEARANCE_DP
    const val TOP_SHADE_HANDLE_GUTTER_DP = SHADE_HANDLE_GUTTER_DP

    fun leftGutterPx(displayId: Int, multiWindow: Boolean, hideDock: Boolean,
        displayWidth: Int, windowLeft: Int, contentWidth: Int, displayDensity: Float): Int {
        if (displayId != 0 || multiWindow || !hideDock || displayWidth <= 0 ||
            windowLeft != 0 || contentWidth < displayWidth) return 0
        val density = displayDensity.takeIf { it.isFinite() && it > 0f } ?: 1f
        return (SHADE_HANDLE_GUTTER_DP * density).roundToInt().coerceIn(0, contentWidth / 4)
    }

    fun topGutterPx(displayId: Int, multiWindow: Boolean, hideStatus: Boolean,
        windowTop: Int, contentHeight: Int, displayDensity: Float): Int {
        if (displayId != 0 || multiWindow || !hideStatus || windowTop != 0 || contentHeight <= 0) return 0
        val density = displayDensity.takeIf { it.isFinite() && it > 0f } ?: 1f
        return (TOP_SHADE_HANDLE_GUTTER_DP * density).roundToInt().coerceIn(0, contentHeight / 4)
    }
}
