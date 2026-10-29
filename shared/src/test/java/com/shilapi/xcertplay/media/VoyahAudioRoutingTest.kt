package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class VoyahAudioRoutingTest {
    @Test fun simultaneousCarPlayRolesHaveSeparateOemVolumeRoutes() {
        val inputs = listOf("media", "default", "telephony", "speechRecognition")
        val routes = inputs.map {
            VoyahAudioRouting.stream(AudioChannelMapper.map(it, 100, AudioChannelMappingMode.MOBILE_COMPATIBLE).channel)
        }
        assertEquals(listOf(3, 1, 6, 10), routes)
        assertEquals(4, routes.toSet().size)
    }

    @Test fun silentlyRemappedAssistantTrackIsReleasedBeforeUsageFallback() {
        val events = mutableListOf<String>()
        val result = LegacyAudioFallback.build(
            createLegacy = { 3 },
            isInitialized = { VoyahAudioRouting.accepted(10, it, true) },
            release = { events += "release:$it" },
            createFallback = { events += "fallback"; 16 },
        )
        assertEquals(16, result)
        assertEquals(listOf("release:3", "fallback"), events)
    }

    @Test fun acceptedRouteRequiresBothCorrectStreamAndInitializedTrack() {
        assertTrue(VoyahAudioRouting.accepted(10, 10, true))
        assertFalse(VoyahAudioRouting.accepted(10, 10, false))
        assertFalse(VoyahAudioRouting.accepted(6, 3, true))
    }

    @Test fun assistantPlaybackIdNeverBecomesAQingganFocusId() {
        assertEquals(10, VoyahAudioRouting.stream(AudioChannel.ASSISTANT))
        assertEquals(22, VoyahAudioRouting.focusStream(AudioChannel.ASSISTANT, 10))
        assertEquals(-1, VoyahAudioRouting.focusStream(AudioChannel.ASSISTANT, 3))
        assertEquals(-1, VoyahAudioRouting.focusStream(AudioChannel.ASSISTANT, 22))
        assertEquals(1, VoyahAudioRouting.focusStream(AudioChannel.NAVIGATION, 1))
        assertEquals(-1, VoyahAudioRouting.focusStream(AudioChannel.NAVIGATION, 3))
        assertEquals(6, VoyahAudioRouting.focusStream(AudioChannel.PHONE, 6))
    }
}
