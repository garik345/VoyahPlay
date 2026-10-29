package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class VoyahClusterSessionTaskTest {
    private fun service() = Robolectric.buildService(DiPlaySessionService::class.java).create().get()

    @Test fun closingAuxiliaryClusterTaskKeepsConnectionServiceAlive() {
        val service = service()
        service.onTaskRemoved(Intent().setComponent(ComponentName(service.packageName,
            com.shilapi.xcertplay.voyah.VoyahClusterProbeSpec.ACTIVITY)))
        assertFalse(Shadows.shadowOf(service).isStoppedBySelf)
    }

    @Test fun removingMainCarPlayTaskStillStopsConnection() {
        val service = service()
        service.onTaskRemoved(Intent(service, CarPlayHostActivity::class.java))
        assertTrue(Shadows.shadowOf(service).isStoppedBySelf)
    }

    @Test fun foreignComponentDoesNotBypassNormalTaskRemoval() {
        val service = service()
        service.onTaskRemoved(Intent().setComponent(ComponentName("another.package",
            com.shilapi.xcertplay.voyah.VoyahClusterProbeSpec.ACTIVITY)))
        assertTrue(Shadows.shadowOf(service).isStoppedBySelf)
    }

    @Test fun absentRootIntentRetainsNormalTaskRemovalBehavior() {
        val service = service()
        service.onTaskRemoved(null)
        assertTrue(Shadows.shadowOf(service).isStoppedBySelf)
    }
}
