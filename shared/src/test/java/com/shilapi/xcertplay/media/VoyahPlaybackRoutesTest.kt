package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class VoyahPlaybackRoutesTest {
    @Test fun diagnosticsFollowLiveTracksAndClosedRendererCannotReturnAfterACloseRace() {
        val first = VoyahPlaybackRoutes.open()
        val second = VoyahPlaybackRoutes.open()
        var playing = false
        try {
            VoyahPlaybackRoutes.register(first) { "siri playing=$playing" }
            VoyahPlaybackRoutes.register(second) { "navigation stream=1" }
            assertTrue(VoyahPlaybackRoutes.describe().contains("playing=false"))
            playing = true
            assertTrue(VoyahPlaybackRoutes.describe().contains("playing=true"))
            VoyahPlaybackRoutes.remove(first)
            VoyahPlaybackRoutes.register(first) { error("closed renderer resurrected") }
            assertEquals("navigation stream=1", VoyahPlaybackRoutes.describe())
            VoyahPlaybackRoutes.register(second) { error("track released during snapshot") }
            assertTrue(VoyahPlaybackRoutes.describe().startsWith("route unavailable:"))
        } finally {
            VoyahPlaybackRoutes.remove(first); VoyahPlaybackRoutes.remove(second)
        }
        assertEquals("no playback tracks", VoyahPlaybackRoutes.describe())
    }
}
