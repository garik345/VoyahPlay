package com.shilapi.xcertplay.voyah.ultra

import android.media.AudioManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VoyahMediaVolumeTest {
    private val audio = mock(AudioManager::class.java, withSettings().mockMaker("mock-maker-subclass"))
    private val saved get() = RuntimeEnvironment.getApplication().getSharedPreferences("volume-test", 0)
    private var index = 5
    private var nativeMute = false
    private lateinit var volume: VoyahMediaVolume

    @Before fun setup() {
        saved.edit().clear().commit()
        `when`(audio.getStreamMinVolume(AudioManager.STREAM_MUSIC)).thenReturn(0)
        `when`(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).thenReturn(30)
        `when`(audio.getStreamVolume(AudioManager.STREAM_MUSIC)).thenAnswer { index }
        `when`(audio.isStreamMute(AudioManager.STREAM_MUSIC)).thenAnswer { nativeMute }
        doAnswer { index = it.getArgument(1); null }.`when`(audio).setStreamVolume(anyInt(), anyInt(), anyInt())
        doAnswer { nativeMute = it.getArgument<Int>(1) == AudioManager.ADJUST_MUTE; null }
            .`when`(audio).adjustStreamVolume(anyInt(), anyInt(), anyInt())
        volume = VoyahMediaVolume(audio, saved)
    }

    @Test fun mutingActuallyZerosTheIndexEvenWhenTheRadioIgnoresStreamMute() {
        assertTrue(volume.write(null, true).confirmed)
        assertEquals(0, index)
        assertFalse(nativeMute)
        assertEquals(0L to true, volume.read())
        verify(audio, never()).adjustStreamVolume(anyInt(), eq(AudioManager.ADJUST_MUTE), anyInt())
    }

    @Test fun unmuteRestoresTheAudibleIndexAcrossACarPlayReconnect() {
        assertTrue(volume.write(null, true).confirmed)
        volume = VoyahMediaVolume(audio, saved)
        assertTrue(volume.write(null, false).confirmed)
        assertEquals(5, index)
        assertEquals(5L to false, volume.read())
    }

    @Test fun selectingANewLevelAfterMuteUnmutesAndKeepsThatLevel() {
        volume.write(null, true)
        assertTrue(volume.write(7, null).confirmed)
        assertEquals(7L to false, volume.read())
        volume.write(null, false)
        assertEquals(7, index)
    }

    @Test fun aNativeKnobChangeBecomesTheNextRestoredLevel() {
        volume.write(null, true)
        index = 8
        assertEquals(8L to false, volume.read())
        volume.write(null, true)
        volume.write(null, false)
        assertEquals(8, index)
    }

    @Test fun invalidOrConflictingWritesDoNotChangeSound() {
        for ((level, mute) in listOf(-1 to null, 31 to null, 5 to true, 0 to false))
            assertFalse(volume.write(level, mute).confirmed)
        assertEquals(5, index)
        verify(audio, never()).setStreamVolume(anyInt(), anyInt(), anyInt())
        verify(audio, never()).adjustStreamVolume(anyInt(), anyInt(), anyInt())
    }
}
