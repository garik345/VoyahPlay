package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
@LooperMode(LooperMode.Mode.PAUSED)
class VoyahNavigationDiagnosticsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @After fun stop() { VoyahNavigationOutput.end() }

    @Test fun enabledPreferenceAndNoRouteAreReportedWithoutBindingToTheCluster() {
        context.getSharedPreferences("voyah_navigation", 0).edit().putBoolean("enabled", true).commit()
        VoyahNavigationOutput.start(context)
        val report = VoyahNavigationOutput.diagnosticReport(context)
        assertTrue(report.contains("voyahNavigationEnabled=true"))
        assertTrue(report.contains("voyahNavigationSessionActive=true"))
        assertTrue(report.contains("profile=not_verified"))
        assertTrue(report.contains("voyahNavigationRouteFrames=0 maneuverFrames=0 decodedChanges=0"))
        assertTrue(report.contains("voyahNavigationFrameAgeMs=none"))
        assertTrue(report.contains("voyahNavigationGuidanceAvailable=false sentTotal=0"))
    }
    @Test fun incomingPacketsAreDistinguishedFromDecodedGuidanceAndFromASuccessfulSend() {
        context.getSharedPreferences("voyah_navigation", 0).edit().putBoolean("enabled", true).commit()
        VoyahNavigationOutput.start(context)
        val privateText = "PRIVATE-ROAD-DO-NOT-EXPORT"
        VoyahNavigationOutput.onFrame(Iap2Frame(BydHudRouteState.ROUTE_GUIDANCE_UPDATE, privateText.toByteArray()))
        VoyahNavigationOutput.onFrame(Iap2Frame(BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE, byteArrayOf(1)))
        val report = VoyahNavigationOutput.diagnosticReport(context)
        assertTrue(report.contains("voyahNavigationRouteFrames=1 maneuverFrames=1 decodedChanges=0"))
        assertTrue(report.contains("voyahNavigationGuidanceAvailable=false sentTotal=0"))
        assertFalse(report.contains(privateText))
    }
    @Test fun disabledAndEndedSessionsCannotPresentPacketsAsLiveGuidance() {
        context.getSharedPreferences("voyah_navigation", 0).edit().putBoolean("enabled", false).commit()
        VoyahNavigationOutput.start(context)
        VoyahNavigationOutput.onFrame(Iap2Frame(BydHudRouteState.ROUTE_GUIDANCE_UPDATE, byteArrayOf(1)))
        var report = VoyahNavigationOutput.diagnosticReport(context)
        assertTrue(report.contains("voyahNavigationEnabled=false"))
        assertTrue(report.contains("voyahNavigationRouteFrames=0 maneuverFrames=0"))
        VoyahNavigationOutput.end()
        report = VoyahNavigationOutput.diagnosticReport(context)
        assertTrue(report.contains("voyahNavigationSessionActive=false"))
        assertTrue(report.contains("voyahNavigationGuidanceAvailable=false"))
    }
}
