package com.shilapi.xcertplay.voyah.ultra

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VoyahOemMuteTest {
    private class Service : VoyahOemMute.Service {
        var master = false
        var media = false
        var live = true
        var denied = false
        val cleared = arrayListOf<String>()
        override fun alive() = live
        override fun masterMuted() = master
        override fun mediaMuted() = media
        override fun clearMaster() { if (denied) throw SecurityException(); cleared += "master"; master = false }
        override fun clearMedia() { if (denied) throw SecurityException(); cleared += "media"; media = false }
    }
    private fun mute(service: Service) = VoyahOemMute(RuntimeEnvironment.getApplication(), {}) { service }

    @Test fun masterAndMediaAreReadIndependentlyWithoutWriting() {
        val service = Service()
        val mute = mute(service)
        assertEquals(false, mute.read())
        service.master = true; assertEquals(true, mute.read())
        service.master = false; service.media = true; assertEquals(true, mute.read())
        assertTrue(service.cleared.isEmpty())
    }

    @Test fun explicitUnmuteClearsOnlyActiveMasterAndMediaMutesAndReadsBack() {
        val service = Service().apply { master = true; media = true }
        val mute = mute(service)
        assertEquals(true, mute.clear())
        assertEquals(listOf("master", "media"), service.cleared)
        assertEquals(false, mute.read())
        assertEquals(true, mute.clear())
        assertEquals(2, service.cleared.size)
    }

    @Test fun rejectedClearIsReportedAsFailure() {
        val service = Service().apply { master = true; denied = true }
        assertEquals(false, mute(service).clear())
        assertTrue(service.master)
    }

    @Test fun absentSdkIsUnknownAndConnectionRetriesAreBounded() {
        var attempts = 0
        val mute = VoyahOemMute(RuntimeEnvironment.getApplication(), {}) { attempts++; throw ClassNotFoundException() }
        assertNull(mute.read())
        repeat(10) { assertNull(mute.read()) }
        assertEquals(1, attempts)
    }
}
