package com.shilapi.xcertplay

/** Only an empty picture is retried. A detailed but stationary map never triggers a restart. */
internal class VoyahClusterPictureRecovery(private val clock: () -> Long) {
    enum class Action { REFRESH, REFRESH_VIEW_AREA, REQUEST_MAP, REOPEN_MAP }
    private var active = false
    private var detailed = false
    private var emptySince = 0L
    private var hostVersion: Long? = null
    private var hostRefreshPending = false
    var attempts = 0
        private set

    fun start() {
        active = true; detailed = false; emptySince = clock(); attempts = 0
        hostVersion = null; hostRefreshPending = false
    }

    /** The main Activity can hide while the cluster's Activity and Surface stay unchanged. */
    fun hostVisibility(version: Long) {
        if (!active) return
        // A hide and return can both occur between two polls, leaving the same visible flag.
        if (hostVersion != null && hostVersion != version) hostRefreshPending = true
        hostVersion = version
    }

    fun picture(hasDetail: Boolean) {
        if (!active) return
        if (!hasDetail && detailed) { emptySince = clock(); attempts = 0 }
        detailed = hasDetail
    }

    fun due(): Action? {
        if (!active) return null
        val refresh = hostRefreshPending
        hostRefreshPending = false
        val delay = RETRIES.getOrNull(attempts)
        if (!detailed && delay != null && clock() - emptySince >= delay) {
            attempts++
            return when (attempts) {
                1 -> Action.REFRESH_VIEW_AREA
                RETRIES.size -> Action.REOPEN_MAP
                else -> Action.REQUEST_MAP
            }
        }
        // A host transition refreshes decoder output, not the phone's map scene or retry budget.
        return if (refresh) Action.REFRESH else null
    }

    fun stop() { active = false; hostRefreshPending = false; hostVersion = null }

    companion object {
        private val RETRIES = longArrayOf(2_000, 15_000, 30_000)
        /** H.264's idle frame can contain a second grey shade, without containing a map. */
        fun hasDetail(opaque: Int, nonBlack: Int, colors: Int): Boolean =
            opaque >= 16 && nonBlack >= 4 && colors >= 8
    }
}
