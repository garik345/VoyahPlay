package com.shilapi.xcertplay.hud

/** tbtIconId values observed on the user's Voyah Free H97 RU. Not Qinggan enum ordinals. */
internal object VoyahManeuverCodes {
    fun icon(type: Int, drivingSide: Int): Int = when (type) {
        1, 20 -> 7
        2, 21 -> 3
        13, 22, 49, 52 -> 8
        14, 23, 50, 53 -> 2
        47 -> 6
        48 -> 4
        4, 18, 19, 26 -> if (drivingSide == 1) 0 else 5
        3, 5, 8, 9, 11, 51 -> 1
        6, 7, in 28..46 -> if (drivingSide == 1) 0 else 9
        // Arrival, unknown types and unverified right U-turns have no invented icon.
        else -> 0
    }
}
