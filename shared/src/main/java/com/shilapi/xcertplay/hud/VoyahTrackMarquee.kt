package com.shilapi.xcertplay.hud

/** Unicode code-point windows fit within PhoneInfo's 60-byte limit even for surrogate pairs. */
internal object VoyahTrackMarquee {
    const val WIDTH = 10
    const val STEP_MS = 650L
    const val HOLD_MS = 1800L
    const val MAX_MS = 30000L

    fun frame(text: String, elapsedMs: Long, width: Int = WIDTH, stepMs: Long = STEP_MS): String? {
        val points = text.replace(Regex("[\\p{Cntrl}\\s]+"), " ").trim().codePoints().toArray()
        if (points.isEmpty() || elapsedMs < 0) return null
        val count = width.coerceIn(6, 30)
        val interval = stepMs.coerceIn(200, 1500)
        // The last window and each outgoing window must fit 30 UTF-16 code units.
        var tail = points.size
        var units = 0
        while (tail > 0 && points.size - tail < count && units + Character.charCount(points[tail - 1]) <= 30) {
            units += Character.charCount(points[--tail])
        }
        val steps = tail
        val duration = (HOLD_MS * 2 + steps * interval).coerceAtMost(MAX_MS)
        if (elapsedMs >= duration) return null
        val offset = ((elapsedMs - HOLD_MS).coerceAtLeast(0) / interval).toInt().coerceAtMost(steps)
        var end = offset
        units = 0
        while (end < points.size && end - offset < count && units + Character.charCount(points[end]) <= 30) {
            units += Character.charCount(points[end++])
        }
        return String(points, offset, end - offset)
    }
}
