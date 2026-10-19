package com.shilapi.xcertplay.voyah
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class VoyahMediaSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
    @Before fun reset() { prefs.edit().clear().commit() }
    @Test fun cancelRestoresBothBuffersAndOnlyTheEditedMediaPreferences() {
        prefs.edit().putInt("media_buffer_ms", 500).putInt("wired_media_buffer_ms", 100)
            .putString("identity_private", "owner-key").commit()
        val before = VoyahMediaSettings.capture(context)
        prefs.edit().putInt("media_buffer_ms", 1000).putInt("wired_media_buffer_ms", 200)
            .putBoolean("smooth_video", true).putString("pairing_id", "paired-phone").commit()
        VoyahMediaSettings.restore(context, before)
        assertEquals(500, prefs.getInt("media_buffer_ms", 0))
        assertEquals(100, prefs.getInt("wired_media_buffer_ms", 0))
        assertFalse(prefs.contains("smooth_video"))
        assertEquals("owner-key", prefs.getString("identity_private", null))
        assertEquals("paired-phone", prefs.getString("pairing_id", null))
    }
    @Test fun sessionStampDetectsWiredBufferAndVideoChangesWithoutIncludingPairing() {
        val first = VoyahMediaSettings.sessionKey(context)
        prefs.edit().putString("pairing_id", "paired-phone").commit()
        assertEquals(first, VoyahMediaSettings.sessionKey(context))
        prefs.edit().putInt("wired_media_buffer_ms", 50).commit()
        assertNotEquals(first, VoyahMediaSettings.sessionKey(context))
        val second = VoyahMediaSettings.sessionKey(context)
        VoyahMediaSettings.setEnabled(context, VoyahMediaSettings.Option.LOW_LATENCY, true)
        assertNotEquals(second, VoyahMediaSettings.sessionKey(context))
    }
    @Test fun onlyVideoOutputOptionsRequireReplacingTheVideoView() {
        assertEquals(0, VoyahMediaSettings.surfaceKey(context))
        VoyahMediaSettings.setEnabled(context, VoyahMediaSettings.Option.CALL_ECHO, true)
        assertEquals(0, VoyahMediaSettings.surfaceKey(context))
        VoyahMediaSettings.setEnabled(context, VoyahMediaSettings.Option.SMOOTH_VIDEO, true)
        assertEquals(1, VoyahMediaSettings.surfaceKey(context))
        VoyahMediaSettings.setEnabled(context, VoyahMediaSettings.Option.DIRECT_VIDEO, true)
        assertEquals(3, VoyahMediaSettings.surfaceKey(context))
    }
}
