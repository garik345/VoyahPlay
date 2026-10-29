package com.shilapi.xcertplay.voyah.ultra

import com.shilapi.xcertplay.airplay.ultra.UltraAudioChannel
import org.junit.Assert.*
import org.junit.Test

class VoyahOemAudioChannelsTest {
    private class Api(override val linkedPhoneAndRingtone: Boolean = false) : VoyahOemAudioChannels.Service {
        val levels = mutableMapOf(1 to 4, 6 to 7, 22 to 9, 2 to 5)
        val maxima = mutableMapOf(1 to 15, 6 to 20, 22 to 30, 2 to 12)
        val writes = mutableListOf<Pair<Int, Int>>()
        override var verifiesPlayback = false
        val nativeLevels = levels.toMutableMap()
        var applyNative = true
        var nativeAvailable = true
        var deferredReads = 0
        private var pending: Pair<Int, Int>? = null
        var confirm = true
        var alive = true
        override fun alive() = alive
        override fun maximum(stream: Int) = maxima.getValue(stream)
        override fun volume(stream: Int) = levels.getValue(stream)
        override fun set(stream: Int, value: Int) {
            writes += stream to value
            if (confirm) levels[stream] = value
            if (applyNative) pending = stream to value
        }
        override fun playbackVolume(stream: Int): Int? {
            if (!nativeAvailable) return null
            pending?.let { if (deferredReads-- <= 0) { nativeLevels[it.first] = it.second; pending = null } }
            return nativeLevels[stream]
        }
    }
    @Test fun vendorCacheAloneCannotConfirmOrPublishAChangeTheAndroidMixerRejected() {
        val api = Api().apply { verifiesPlayback = true; applyNative = false }
        var waits = 0
        val audio = VoyahOemAudioChannels({ api }, { 10_000 }, {}, { waits++ })
        audio.ranges()
        val result = audio.write(UltraAudioChannel.SIRI, 12)
        assertFalse(result.confirmed)
        assertEquals(12, api.levels[22])
        assertEquals(9, audio.values[UltraAudioChannel.SIRI])
        assertEquals(8, waits)
        assertEquals(listOf(22 to 12), api.writes)
    }
    @Test fun asynchronousMixerConfirmationIsBoundedAndNeverRepeatsTheWrite() {
        val api = Api().apply { verifiesPlayback = true; deferredReads = 2 }
        var waits = 0
        val audio = VoyahOemAudioChannels({ api }, { 10_000 }, {}, { waits++ })
        assertTrue(audio.write(UltraAudioChannel.NAVIGATION, 7).confirmed)
        assertEquals(2, waits)
        assertEquals(7, audio.values[UltraAudioChannel.NAVIGATION])
        assertEquals(listOf(1 to 7), api.writes)
        api.nativeAvailable = false
        assertFalse(audio.write(UltraAudioChannel.NAVIGATION, 8).confirmed)
        assertFalse(audio.values.containsKey(UltraAudioChannel.NAVIGATION))
    }
    @Test fun verifiedLinkedPhoneAndRingtoneAdvertiseOneVolumeGroup() {
        val api = Api(linkedPhoneAndRingtone = true)
        val audio = VoyahOemAudioChannels({ api }, { 10_000 }, {})
        assertTrue(audio.ranges().containsKey(UltraAudioChannel.PHONE))
        assertFalse(audio.ranges().containsKey(UltraAudioChannel.RINGTONE))
        assertFalse(audio.write(UltraAudioChannel.RINGTONE, 6).confirmed)
        assertTrue(api.writes.isEmpty())
        assertTrue(audio.write(UltraAudioChannel.PHONE, 8).confirmed)
        assertEquals(listOf(6 to 8), api.writes)
    }
    @Test fun eachChannelUsesItsNativeRangeAndDoesNotWriteMediaOrOtherChannels() {
        val api = Api()
        val audio = VoyahOemAudioChannels({ api }, { 10_000 }, {})
        val before = api.levels.toMap()
        assertEquals(0..15, audio.ranges()[UltraAudioChannel.NAVIGATION])
        assertEquals(0..12, audio.ranges()[UltraAudioChannel.RINGTONE])
        assertTrue(api.writes.isEmpty())
        assertTrue(audio.write(UltraAudioChannel.PHONE, 8).confirmed)
        assertEquals(listOf(6 to 8), api.writes)
        assertEquals(before - 6, api.levels - 6)
        assertTrue(audio.write(UltraAudioChannel.RINGTONE, 6).confirmed)
        assertEquals(2 to 6, api.writes.last())
    }
    @Test fun invalidLevelsAndChangedRangesAreRejectedBeforeWriting() {
        val api = Api()
        val audio = VoyahOemAudioChannels({ api }, { 10_000 }, {})
        audio.ranges()
        assertFalse(audio.write(UltraAudioChannel.NAVIGATION, 16).confirmed)
        assertFalse(audio.write(UltraAudioChannel.NAVIGATION, -1).confirmed)
        api.maxima[1] = 30
        assertFalse(audio.write(UltraAudioChannel.NAVIGATION, 8).confirmed)
        assertFalse(audio.read().containsKey(UltraAudioChannel.NAVIGATION))
        assertEquals(0..15, audio.ranges()[UltraAudioChannel.NAVIGATION])
        assertTrue(api.writes.isEmpty())
    }
    @Test fun failedReadbackNeverEchoesTheRequestedValue() {
        val api = Api().apply { confirm = false }
        val audio = VoyahOemAudioChannels({ api }, { 10_000 }, {})
        assertFalse(audio.write(UltraAudioChannel.SIRI, 20).confirmed)
        assertEquals(9, audio.values[UltraAudioChannel.SIRI])
    }
    @Test fun unavailableServiceIsBoundedAndCanRecoverWithoutInventingValues() {
        var now = 10_000L
        var attempts = 0
        val api = Api()
        val audio = VoyahOemAudioChannels({ attempts++; if (attempts == 1) error("offline") else api }, { now }, {})
        assertTrue(audio.ranges().isEmpty())
        repeat(10) { assertTrue(audio.read().isEmpty()) }
        assertEquals(1, attempts)
        now += 5_000
        assertEquals(4, audio.read().size)
        assertEquals(2, attempts)
    }
}
