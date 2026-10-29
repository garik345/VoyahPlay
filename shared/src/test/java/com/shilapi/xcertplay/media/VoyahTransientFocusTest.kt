package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE,
    shadows = [AudioFocusAutoYieldTest.VolumeTrackingAudioTrack::class])
class VoyahTransientFocusTest {
    @Test fun assistantUsesItsPlaybackTrackButPublishesTheVendorFocusAndRestoresMedia() {
        val context: Context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val published = mutableListOf<Int>()
        val coordinator = AudioFocusCoordinator(context, enabled = true, navigationFocusEnabled = true,
            onFocusStreamChanged = published::add)
        fun track(stream: Int) = if (stream == 10) AudioTrack.Builder()
            .setAudioAttributes(VoyahAudioRouting.assistantPlaybackAttributes())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(4096).build()
        else AudioTrack(stream, 48000, AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT, 4096, AudioTrack.MODE_STREAM)
        val media = track(3)
        val siri = track(10)
        // The real AudioTrack JNI writes the selected stream back into these fields.
        // Robolectric's native_setup stub leaves them at -1 for attributes-based tracks.
        org.robolectric.util.ReflectionHelpers.setField(media, "mStreamType", 3)
        org.robolectric.util.ReflectionHelpers.setField(siri, "mStreamType", 10)
        try {
            coordinator.acquire(media, AudioChannel.MEDIA, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            val attributes = VoyahAudioRouting.assistantPlaybackAttributes()
            assertEquals(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY, attributes.usage)
            assertEquals(10, siri.streamType)
            coordinator.acquire(siri, AudioChannel.ASSISTANT, attributes)
            coordinator.release(siri)
            coordinator.close()
            assertEquals(listOf(3, 22, 3, -1), published)
        } finally {
            coordinator.close(); media.release(); siri.release()
        }
    }

    @Test fun defaultCoordinatorDoesNotMuteMediaAcrossCallInterruption() {
        val context: Context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val volumes = AudioFocusAutoYieldTest.VolumeTrackingAudioTrack.volumes
        volumes.clear()
        val diagnostics = mutableListOf<String>()
        val coordinator = AudioFocusCoordinator(context, enabled = true, report = diagnostics::add)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        fun track() = AudioTrack.Builder().setAudioAttributes(attributes)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(44100)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(4096).build()
        val media = track()
        val phone = track()
        val resumed = track()
        try {
            coordinator.acquire(media, AudioChannel.MEDIA, attributes)
            coordinator.onExternalFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            coordinator.acquire(phone, AudioChannel.PHONE, attributes)
            coordinator.release(phone)
            coordinator.acquire(resumed, AudioChannel.MEDIA, attributes)
            coordinator.onExternalFocusChange(AudioManager.AUDIOFOCUS_GAIN)
            assertTrue(volumes[media].isNullOrEmpty())
            assertTrue(volumes[phone].isNullOrEmpty())
            assertTrue(volumes[resumed].isNullOrEmpty())
            assertTrue(diagnostics.any { it.contains("muteOnTransientLoss=false") })
        } finally {
            coordinator.close()
            listOf(media, phone, resumed).forEach(AudioTrack::release)
            volumes.clear()
        }
    }
}
