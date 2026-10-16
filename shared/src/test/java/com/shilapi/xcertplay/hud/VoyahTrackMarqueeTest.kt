package com.shilapi.xcertplay.hud

import org.junit.Assert.*
import org.junit.Test

class VoyahTrackMarqueeTest {
    @Test fun holdsThenScrollsAndClears() {
        val text = "ABCDEFGHIJKL"
        assertEquals("ABCDEFGHIJ", VoyahTrackMarquee.frame(text, 0))
        assertEquals("ABCDEFGHIJ", VoyahTrackMarquee.frame(text, 1800))
        assertEquals("BCDEFGHIJK", VoyahTrackMarquee.frame(text, 2450))
        assertEquals("CDEFGHIJKL", VoyahTrackMarquee.frame(text, 3100))
        assertEquals("CDEFGHIJKL", VoyahTrackMarquee.frame(text, 4899))
        assertNull(VoyahTrackMarquee.frame(text, 4900))
    }
    @Test fun shortTitlesAndEmptyTitlesExpire() {
        assertEquals("Трек", VoyahTrackMarquee.frame("  Трек\n", 0))
        assertNull(VoyahTrackMarquee.frame("Трек", 3600))
        assertNull(VoyahTrackMarquee.frame(" \n\t", 0))
        assertNull(VoyahTrackMarquee.frame("Трек", -1))
    }
    @Test fun emojiAreNotSplitAndFramesFitPhoneInfo() {
        val text = "🎵".repeat(20)
        val frame = requireNotNull(VoyahTrackMarquee.frame(text, 2450))
        assertEquals("🎵".repeat(10), frame)
        assertTrue(frame.toByteArray(Charsets.UTF_16LE).size <= 60)
    }
    @Test fun longTitlesNeverOccupyDisplayBeyondThirtySeconds() {
        val text = "А".repeat(200)
        assertNotNull(VoyahTrackMarquee.frame(text, 29999))
        assertNull(VoyahTrackMarquee.frame(text, 30000))
    }
    @Test fun customWidthAndIntervalApply() {
        assertEquals("ABCDEFGHIJKLMNOPQRST", VoyahTrackMarquee.frame("ABCDEFGHIJKLMNOPQRSTUVWXYZ", 0, 20, 200))
        assertEquals("BCDEFGHIJKLMNOPQRSTU", VoyahTrackMarquee.frame("ABCDEFGHIJKLMNOPQRSTUVWXYZ", 2000, 20, 200))
        assertEquals("ABCDEFGHIJKLMNOPQRST", VoyahTrackMarquee.frame("ABCDEFGHIJKLMNOPQRSTUVWXYZ", 2000, 20, 1500))
    }
    @Test fun wideEmojiWindowsStillFitParcelBudget() {
        val text = "🎵".repeat(40)
        for (time in 0L..20000L step 200) {
            VoyahTrackMarquee.frame(text, time, 30, 200)?.let {
                assertTrue(it.toByteArray(Charsets.UTF_16LE).size <= 60)
                assertEquals(0, it.length % 2)
            }
        }
    }
}
