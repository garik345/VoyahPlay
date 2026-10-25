package com.shilapi.xcertplay.voyah

import android.Manifest
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import com.shilapi.xcertplay.CarPlayHostActivity
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.media.AndroidMediaSink
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioRecord
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Exercise the host's real sink factory: it previously dropped the otherwise tested Voyah flag. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class VoyahHostMicrophoneTest {
    @Test fun hostActuallyConnectsTheVoyahCallMicrophoneWithFocusDisabled() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.MODIFY_AUDIO_SETTINGS)
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val sink = ReflectionHelpers.callInstanceMethod<AndroidMediaSink>(activity, "createMediaSink",
            ClassParameter.from(Int::class.javaPrimitiveType, 1280), ClassParameter.from(Int::class.javaPrimitiveType, 720),
            ClassParameter.from(Int::class.javaPrimitiveType, 0))
        val record = AtomicReference<AudioRecord>()
        val started = CountDownLatch(1)
        ShadowAudioRecord.setSourceProvider { recorder ->
            record.set(recorder); started.countDown()
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInByteArray(buffer: ByteArray, offset: Int, size: Int, blocking: Boolean): Int {
                    Thread.sleep(5)
                    return 0
                }
            }
        }
        try {
            sink.onMicrophoneStarted(AudioStreamId(100, "telephony"), MicrophoneConfig(audioType = "telephony",
                sampleRate = 16_000, channels = 1, payloadType = 100, frameMillis = 20,
                host = InetAddress.getLoopbackAddress(), port = 9, key = ByteArray(32)))
            assertTrue("Host did not start call capture", started.await(5, TimeUnit.SECONDS))
            assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION, record.get().audioSource)
            assertEquals(AudioManager.MODE_NORMAL, context.getSystemService(AudioManager::class.java).mode)
        } finally { sink.close(); ShadowAudioRecord.clearSource() }
    }
}
