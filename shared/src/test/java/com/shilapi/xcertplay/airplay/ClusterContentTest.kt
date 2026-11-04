package com.shilapi.xcertplay.airplay

import java.net.Socket
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.robolectric.RuntimeEnvironment
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Live dashboard selections must not claim delivery before their commands reach the event channel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ClusterContentTest {
    private fun controller(session: AirPlaySession, initialClusterUrl: String? = null): CarPlayController = CarPlayController(
        RuntimeEnvironment.getApplication(), CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL,
            identification = Iap2IdentificationConfig(name = "test", modelIdentifier = "test", manufacturer = "test",
                serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3)),
        AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480),
            cluster = initialClusterUrl?.let { AirPlayDisplayConfig(760, 616, initialUrl = it) }),
        AirPlayIdentity.generate(), PairingStore(), object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, {},
    ).also { it.javaClass.getDeclaredField("activeSession").apply { isAccessible = true }.set(it, session) }

    private fun mainSetup(session: AirPlaySession) {
        session.javaClass.getDeclaredMethod("handleStreams", List::class.java).apply { isAccessible = true }
            .invoke(session, listOf(mapOf("type" to 110L)))
    }
    private fun delivered(action: ((Boolean) -> Unit) -> Unit): Boolean {
        val done = CountDownLatch(1)
        var result: Boolean? = null
        action { result = it; done.countDown() }
        assertTrue("Command callback completed", done.await(5, TimeUnit.SECONDS))
        return result!!
    }

    private fun deferredSession() = session(CarPlayClusterDisplay.MAP_URL, clusterDisplay =
        AirPlayDisplayConfig(760, 616, initialUrl = CarPlayClusterDisplay.MAP_URL, deferInitialUi = true))

    @Test fun phoneRecentsRequestUsesOnlyThePrimaryScreenAndSendsNoCallControl() {
        val session = deferredSession(); val controller = controller(session)
        try {
            mainSetup(session); setup(session)
            val event = eventReady(session)
            val token = controller.activeAirPlaySessionToken()!!
            assertTrue(delivered { controller.requestPhoneRecentsForSession(token, it) })
            val commands = commands(event)
            assertEquals(1, commands.size)
            assertEquals("requestUI", commands.single()["type"])
            val params = commands.single()["params"] as Map<*, *>
            assertFalse("Main application launch has no alternate-display UUID", params.containsKey("uuid"))
            assertEquals("mobilephone-recents://", params["url"])
            assertNull(session.clusterUrl())
        } finally { controller.close(); session.close() }
    }

    @Test fun aQueuedPhoneRequestCannotReachAReplacementPhoneOrPrimaryStream() {
        for (replacePhone in listOf(true, false)) {
            val session = deferredSession(); val next = deferredSession(); val controller = controller(session)
            val release = CountDownLatch(1)
            try {
                mainSetup(session); mainSetup(next)
                val firstEvent = eventReady(session); val nextEvent = eventReady(next)
                val entered = CountDownLatch(1)
                executor(controller).execute { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val done = CountDownLatch(1); var sent = true
                assertTrue(controller.requestPhoneRecentsForSession(controller.activeAirPlaySessionToken()!!) { sent = it; done.countDown() })
                if (replacePhone) controller.javaClass.getDeclaredField("activeSession").apply { isAccessible = true }.set(controller, next)
                else mainSetup(session)
                release.countDown(); assertTrue(done.await(5, TimeUnit.SECONDS))
                assertFalse(sent); assertEquals(0, firstEvent.writes); assertEquals(0, nextEvent.writes)
            } finally { release.countDown(); controller.close(); session.close(); next.close() }
        }
    }

    @Test fun aQueuedPhoneRequestIsCancelledWhenItsKeyOwnerOrIdleCallStateChanges() {
        val session = deferredSession(); val controller = controller(session)
        val release = CountDownLatch(1)
        try {
            mainSetup(session); val event = eventReady(session)
            val entered = CountDownLatch(1)
            executor(controller).execute { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val permitted = java.util.concurrent.atomic.AtomicBoolean(true)
            val done = CountDownLatch(1); var sent = true
            assertTrue(controller.requestPhoneRecentsForSession(controller.activeAirPlaySessionToken()!!,
                permitted::get) { sent = it; done.countDown() })
            permitted.set(false)
            release.countDown(); assertTrue(done.await(5, TimeUnit.SECONDS))
            assertFalse(sent); assertEquals(0, event.writes)
        } finally { release.countDown(); controller.close(); session.close() }
    }

    @Test fun phoneRequestsRejectAnAbsentStreamAnIncorrectTokenAndAClosedController() {
        val session = deferredSession(); val controller = controller(session)
        try {
            assertFalse(delivered { controller.requestPhoneRecentsForSession(Any(), it) })
            mainSetup(session); val event = eventReady(session)
            assertFalse(delivered { controller.requestPhoneRecentsForSession(Any(), it) })
            val token = controller.activeAirPlaySessionToken()!!
            controller.close()
            assertFalse(delivered { controller.requestPhoneRecentsForSession(token, it) })
            assertEquals(0, event.writes)
        } finally { controller.close(); session.close() }
    }

    @Test fun phoneUiObservationRecordsOnlyTheSchemeOfTheOriginalStreamForEightSeconds() {
        val session = deferredSession(); val next = deferredSession(); val controller = controller(session)
        try {
            mainSetup(session); mainSetup(next)
            val events = mutableListOf<String>()
            val listener = controller.javaClass.getDeclaredField("sessionListener").apply { isAccessible = true }
                .get(controller) as AirPlaySessionListener
            assertFalse(controller.observePhoneUiRequestsForSession(Any(), events::add))
            assertTrue(controller.observePhoneUiRequestsForSession(controller.activeAirPlaySessionToken()!!, events::add))
            listener.onCommand(next, "requestUI", mapOf("url" to "tel:+79991234567"))
            listener.onCommand(session, "modesChanged", mapOf("url" to "tel:+79991234567"))
            assertTrue(events.isEmpty())
            listener.onCommand(session, "requestUI", mapOf("url" to "mobilephone-recents://private?name=private"))
            assertEquals(listOf("scheme=mobilephone-recents:"), events)
            mainSetup(session)
            listener.onCommand(session, "requestUI", mapOf("url" to "tel:+79991234567"))
            assertEquals(1, events.size)
            assertTrue(controller.observePhoneUiRequestsForSession(controller.activeAirPlaySessionToken()!!, events::add))
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(9))
            listener.onCommand(session, "requestUI", mapOf("url" to "tel:+79991234567"))
            assertEquals(1, events.size)
        } finally { controller.close(); session.close(); next.close() }
    }

    @Test fun deferredSceneWaitsForAReceiverRequestDespiteSetupRestoreAndPhoneSuggestions() {
        val session = deferredSession()
        try {
            mainSetup(session); setup(session)
            val event = eventReady(session)
            readyAfterSetup(session)
            assertTrue(session.clusterScreenReady())
            assertTrue(session.clusterUiNeedsActivation())
            assertNull(session.clusterUrl())
            assertTrue(session.restoreClusterUrl(CarPlayClusterDisplay.MAP_URL, send = true))
            assertFalse(session.activateSuggestedClusterUrl(CarPlayClusterDisplay.MAP_URL))
            readyAfterSetup(session)
            assertEquals(0, event.bytes.size())
            assertFalse(session.reopenClusterUrl(CarPlayClusterDisplay.MAP_URL) { true })
        } finally { session.close() }
    }

    @Test fun preparedScreenDoesNotDependOnVideoFramesAndRejectsStaleOrUnansweredStreams() {
        val session = deferredSession()
        val controller = controller(session, CarPlayClusterDisplay.MAP_URL)
        try {
            assertFalse(controller.hasPreparedDashboardScreenForSession(Any()))
            mainSetup(session)
            val token = controller.activeAirPlaySessionToken()!!
            assertFalse(controller.hasPreparedDashboardScreenForSession(token))
            setup(session); eventReady(session)
            assertFalse(controller.hasPreparedDashboardScreenForSession(token))
            readyAfterSetup(session)
            assertTrue("SETUP readiness must allow attaching the first output before video", controller.hasPreparedDashboardScreenForSession(token))
            assertFalse(controller.hasPreparedDashboardScreenForSession(Any()))
            teardown(session)
            assertFalse(controller.hasPreparedDashboardScreenForSession(token))
            setup(session)
            assertFalse(controller.hasPreparedDashboardScreenForSession(token))
            readyAfterSetup(session)
            assertTrue(controller.hasPreparedDashboardScreenForSession(token))
            mainSetup(session)
            assertFalse(controller.hasPreparedDashboardScreenForSession(token))
        } finally { controller.close(); session.close() }
    }

    @Test fun firstDeferredMapUsesThePreparedAreaBeforeCreatingItsSceneAndReSetupCanRestoreIt() {
        val session = deferredSession()
        val controller = controller(session, CarPlayClusterDisplay.MAP_URL)
        try {
            mainSetup(session); setup(session)
            val event = eventReady(session)
            readyAfterSetup(session)
            val token = controller.activeAirPlaySessionToken()!!
            val done = CountDownLatch(1)
            var sent = false
            controller.showDashboardContentForSession(CarPlayClusterDisplay.MAP_URL, token) { sent = it; done.countDown() }
            drain(controller)
            assertEquals(listOf("updateViewArea"), commands(event).map { it["type"] })
            assertNull(session.clusterUrl())
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
            assertTrue(done.await(5, TimeUnit.SECONDS)); assertTrue(sent)
            assertEquals(listOf("updateViewArea", "showUI", "forceKeyFrame"), commands(event).map { it["type"] })
            assertFalse(session.clusterUiNeedsActivation())
            assertEquals(CarPlayClusterDisplay.MAP_URL, session.clusterUrl())
            teardown(session); setup(session)
            assertNull(session.clusterUrl())
            readyAfterSetup(session)
            assertEquals(listOf("showUI", "forceKeyFrame"), commands(event).takeLast(2).map { it["type"] })
            assertEquals(CarPlayClusterDisplay.MAP_URL, session.clusterUrl())
        } finally { controller.close(); session.close() }
    }

    @Test fun firstDeferredMapReusesTheIdleAreaWithoutSendingAnotherUpdate() {
        val session = deferredSession()
        val controller = controller(session, CarPlayClusterDisplay.MAP_URL)
        try {
            mainSetup(session); setup(session)
            val event = eventReady(session)
            readyAfterSetup(session)
            val token = controller.activeAirPlaySessionToken()!!
            controller.prepareIdleDashboardMap(token)
            drain(controller)
            controller.prepareIdleDashboardMap(token)
            drain(controller)
            assertEquals(listOf("updateViewArea"), commands(event).map { it["type"] })
            assertNull(session.clusterUrl())

            val done = CountDownLatch(1)
            var sent = false
            controller.showDashboardContentForSession(CarPlayClusterDisplay.MAP_URL, token) { sent = it; done.countDown() }
            drain(controller)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
            assertTrue(done.await(5, TimeUnit.SECONDS)); assertTrue(sent)
            assertEquals(listOf("updateViewArea", "showUI", "forceKeyFrame"), commands(event).map { it["type"] })
        } finally { controller.close(); session.close() }
    }

    @Test fun deferredPauseResumeDoesNotCreateASceneOrLoseTheRetainedSelection() {
        val session = deferredSession()
        val controller = controller(session, CarPlayClusterDisplay.MAP_URL)
        try {
            mainSetup(session); setup(session)
            val event = eventReady(session)
            readyAfterSetup(session)
            assertTrue(session.setClusterUiShown(false))
            assertTrue(session.restoreClusterUrl(CarPlayClusterDisplay.MAP_URL, send = true))
            assertFalse(session.activateSuggestedClusterUrl(CarPlayClusterDisplay.MAP_URL))
            assertTrue(session.setClusterUiShown(true))
            readyAfterSetup(session)
            assertTrue(session.clusterUiNeedsActivation())
            assertEquals(0, event.bytes.size())
        } finally { controller.close(); session.close() }
    }

    @Test fun anUnscopedFirstMapSelectionUsesTheSamePreparedGeometryTransaction() {
        val session = deferredSession()
        val controller = controller(session, CarPlayClusterDisplay.MAP_URL)
        try {
            mainSetup(session); setup(session)
            val event = eventReady(session)
            readyAfterSetup(session)
            val done = CountDownLatch(1)
            var sent = false
            controller.showDashboardContent(CarPlayClusterDisplay.MAP_URL) { sent = it; done.countDown() }
            drain(controller)
            assertEquals(listOf("updateViewArea"), commands(event).map { it["type"] })
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
            assertTrue(done.await(5, TimeUnit.SECONDS)); assertTrue(sent)
            assertEquals(listOf("updateViewArea", "showUI", "forceKeyFrame"), commands(event).map { it["type"] })
        } finally { controller.close(); session.close() }
    }

    @Test fun controllerAcceptsItsPublicMainScreenTokenForMirroredShowAndZoom() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        val controller = controller(session)
        try {
            mainSetup(session); setup(session)
            val event = eventReady(session)
            val token = controller.activeAirPlaySessionToken()!!
            assertNotSame("The public token is not the AirPlaySession object", session, token)
            assertTrue(delivered { controller.showDashboardContentForSession(CarPlayClusterDisplay.MAP_URL, token, it) })
            assertTrue(delivered { controller.zoomMirroredDashboardMap(true, token, it) })
            var zoomReason: String? = null
            assertTrue(delivered { done -> controller.zoomMirroredDashboardMapWithStatus(false, token) { sent, reason ->
                zoomReason = reason; done(sent)
            } })
            assertEquals("sent", zoomReason)
            assertEquals(listOf("showUI", "forceKeyFrame", "changeMapZoomLevel", "changeMapZoomLevel"),
                commands(event).map { it["type"] })
        } finally { controller.close(); session.close() }
    }

    @Test fun recoveryReopensOnlyTheClusterUuidAndKeepsThePrimarySessionToken() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        val controller = controller(session)
        try {
            mainSetup(session); setup(session)
            val event = eventReady(session)
            val token = controller.activeAirPlaySessionToken()!!
            assertTrue(delivered { controller.recoverDashboardMapForSession(token, it) })
            val sent = commands(event)
            assertEquals(listOf("stopUI", "showUI", "forceKeyFrame"), sent.map { it["type"] })
            assertTrue(sent.all { (it["params"] as Map<*, *>)["uuid"] == session.displayIdentifier(111) })
            assertSame(token, controller.activeAirPlaySessionToken())
            assertEquals(CarPlayClusterDisplay.MAP_URL, session.clusterUrl())
        } finally { controller.close(); session.close() }
    }

    @Test fun emptyMapGeometryRefreshTargetsOnlyTheClusterAndPreservesThePhoneSession() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        val controller = controller(session, CarPlayClusterDisplay.MAP_URL)
        try {
            mainSetup(session); setup(session)
            val event = eventReady(session)
            val token = controller.activeAirPlaySessionToken()!!
            val completed = CountDownLatch(1)
            var delivered = false
            controller.refreshDashboardMapViewAreaForSession(token) { delivered = it; completed.countDown() }
            drain(controller)
            assertEquals(listOf("updateViewArea"), commands(event).map { it["type"] })
            assertEquals(1L, completed.count)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
            drain(controller)
            assertEquals("showUI must wait for layout, while the phone command queue stays usable", 1, commands(event).size)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertTrue(delivered)
            val sent = commands(event)
            assertEquals(listOf("updateViewArea", "showUI", "forceKeyFrame"), sent.map { it["type"] })
            val area = sent.first()["params"] as Map<*, *>
            assertEquals(0L, area["viewAreaIndex"])
            assertEquals(300L, area["animationDurationMillis"])
            assertEquals(emptyList<Any>(), area["adjacentViewAreas"])
            assertTrue(sent.all { (it["params"] as Map<*, *>)["uuid"] == session.displayIdentifier(111) })
            assertSame(token, controller.activeAirPlaySessionToken())
        } finally { controller.close(); session.close() }
    }

    @Test fun mapGeometryRefreshRejectsOldTokensPausedOutputAndADriversTurnCard() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        val controller = controller(session, CarPlayClusterDisplay.MAP_URL)
        try {
            mainSetup(session); setup(session)
            val event = eventReady(session)
            val token = controller.activeAirPlaySessionToken()!!
            assertFalse(delivered { controller.refreshDashboardMapViewAreaForSession(Any(), it) })
            assertEquals(0, event.bytes.size())
            assertTrue(session.setClusterUiShown(false))
            val pausedBytes = event.bytes.size()
            assertFalse(delivered { controller.refreshDashboardMapViewAreaForSession(token, it) })
            assertEquals(pausedBytes, event.bytes.size())
            assertTrue(session.setClusterUiShown(true))
            assertTrue(delivered { controller.showDashboardContent(CarPlayClusterDisplay.Content.TURN_CARD.url, it) })
            val turnBytes = event.bytes.size()
            assertFalse(delivered { controller.refreshDashboardMapViewAreaForSession(token, it) })
            assertEquals(turnBytes, event.bytes.size())
        } finally { controller.close(); session.close() }
    }

    @Test fun mapGeometryRefreshRechecksBothStreamGenerationsAfterItsFirstWrite() {
        for (replaceMain in listOf(false, true)) {
            val session = session(CarPlayClusterDisplay.MAP_URL)
            val controller = controller(session, CarPlayClusterDisplay.MAP_URL)
            try {
                mainSetup(session); setup(session)
                val token = controller.activeAirPlaySessionToken()!!
                val event = eventReady(session, EventSocket { if (replaceMain) mainSetup(session) else setup(session) })
                assertFalse(delivered { controller.refreshDashboardMapViewAreaForSession(token, it) })
                assertEquals(listOf("updateViewArea"), commands(event).map { it["type"] })
            } finally { controller.close(); session.close() }
        }
    }

    @Test fun mapGeometryRefreshDoesNotResetTheSelectionOfAMultiAreaDisplay() {
        val session = session(CarPlayClusterDisplay.MAP_URL, clusterDisplay = AirPlayDisplayConfig(1600, 600,
            initialUrl = CarPlayClusterDisplay.MAP_URL, initialViewArea = 1,
            viewAreas = listOf(AirPlayViewArea(1600, 600), AirPlayViewArea(1200, 600))))
        try {
            mainSetup(session); setup(session)
            val event = eventReady(session)
            assertNull(session.prepareClusterMapViewAreaRefresh { true })
            assertEquals(0, event.bytes.size())
        } finally { session.close() }
    }

    @Test fun aDelayedGeometryResumeCannotReplaceANewerDriverSelection() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        val controller = controller(session, CarPlayClusterDisplay.MAP_URL)
        try {
            mainSetup(session); setup(session)
            val event = eventReady(session)
            val completed = CountDownLatch(1)
            var result = true
            controller.refreshDashboardMapViewAreaForSession(controller.activeAirPlaySessionToken()) {
                result = it; completed.countDown()
            }
            drain(controller)
            assertEquals(listOf("updateViewArea"), commands(event).map { it["type"] })
            val turn = CarPlayClusterDisplay.Content.TURN_CARD.url
            assertTrue(delivered { controller.showDashboardContent(turn, it) })
            val before = event.bytes.size()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertFalse(result)
            assertEquals(before, event.bytes.size())
            assertEquals(turn, session.clusterUrl())
        } finally { controller.close(); session.close() }
    }

    @Test fun aDelayedGeometryResumeCompletesAsRejectedAfterPhoneOrStreamReplacementOrClose() {
        for (replacement in listOf("main", "cluster", "phone", "close")) {
            val session = session(CarPlayClusterDisplay.MAP_URL)
            val next = session(CarPlayClusterDisplay.MAP_URL)
            val controller = controller(session, CarPlayClusterDisplay.MAP_URL)
            try {
                mainSetup(session); setup(session)
                val event = eventReady(session)
                val completed = CountDownLatch(1)
                var result = true
                controller.refreshDashboardMapViewAreaForSession(controller.activeAirPlaySessionToken()) {
                    result = it; completed.countDown()
                }
                drain(controller)
                when (replacement) {
                    "main" -> mainSetup(session)
                    "cluster" -> setup(session)
                    "phone" -> controller.javaClass.getDeclaredField("activeSession").apply { isAccessible = true }.set(controller, next)
                    else -> controller.close()
                }
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
                assertTrue(completed.await(5, TimeUnit.SECONDS))
                assertFalse(result)
                assertEquals(listOf("updateViewArea"), commands(event).map { it["type"] })
            } finally { controller.close(); session.close(); next.close() }
        }
    }

    @Test fun recoveryRejectsAnOldPhoneTokenAndCannotResumeADeliberatelyPausedCluster() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        val controller = controller(session)
        try {
            mainSetup(session); setup(session)
            val event = eventReady(session)
            val token = controller.activeAirPlaySessionToken()!!
            assertFalse(delivered { controller.recoverDashboardMapForSession(Any(), it) })
            assertEquals(0, event.bytes.size())
            assertTrue(session.setClusterUiShown(false))
            assertFalse(delivered { controller.recoverDashboardMapForSession(token, it) })
            assertEquals(listOf("stopUI"), commands(event).map { it["type"] })
        } finally { controller.close(); session.close() }
    }

    @Test fun recoveryCannotReopenAStreamReplacedWhileStopUiWasBeingSent() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        try {
            setup(session)
            val event = eventReady(session, EventSocket { setup(session) })
            assertFalse(session.reopenClusterUrl(CarPlayClusterDisplay.MAP_URL) { true })
            assertEquals(listOf("stopUI"), commands(event).map { it["type"] })
            assertEquals(2, session.clusterStream)
        } finally { session.close() }
    }

    @Test fun recoveryRechecksThePrimaryTokenBetweenStoppingAndReopeningTheMap() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        val controller = controller(session)
        try {
            mainSetup(session); setup(session)
            val event = eventReady(session, EventSocket { mainSetup(session) })
            val token = controller.activeAirPlaySessionToken()!!
            assertFalse(delivered { controller.recoverDashboardMapForSession(token, it) })
            assertEquals(listOf("stopUI"), commands(event).map { it["type"] })
            assertNotSame(token, controller.activeAirPlaySessionToken())
        } finally { controller.close(); session.close() }
    }

    @Test fun failedStopDeliveryDoesNotClaimSuccessfulRecovery() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        val controller = controller(session)
        try {
            mainSetup(session); setup(session)
            assertFalse(delivered { controller.recoverDashboardMapForSession(controller.activeAirPlaySessionToken(), it) })
            assertEquals(CarPlayClusterDisplay.MAP_URL, session.clusterUrl())
        } finally { controller.close(); session.close() }
    }

    @Test fun oldPublicTokenIsRejectedAfterPrimaryStreamReSetupOrPhoneReplacement() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        val controller = controller(session)
        val next = session(CarPlayClusterDisplay.MAP_URL)
        try {
            mainSetup(session); setup(session)
            val event = eventReady(session)
            val token = controller.activeAirPlaySessionToken()!!
            mainSetup(session)
            assertFalse(delivered { controller.showDashboardContentForSession(CarPlayClusterDisplay.MAP_URL, token, it) })
            var zoomReason: String? = null
            assertFalse(delivered { done -> controller.zoomMirroredDashboardMapWithStatus(true, token) { sent, reason ->
                zoomReason = reason; done(sent)
            } })
            assertEquals("session-changed", zoomReason)
            assertEquals(0, event.bytes.size())
            mainSetup(next); setup(next)
            val nextEvent = eventReady(next)
            controller.javaClass.getDeclaredField("activeSession").apply { isAccessible = true }.set(controller, next)
            assertFalse(delivered { controller.showDashboardContentForSession(CarPlayClusterDisplay.MAP_URL, token, it) })
            assertFalse(delivered { controller.zoomMirroredDashboardMap(true, token, it) })
            assertEquals(0, nextEvent.bytes.size())
        } finally { controller.close(); session.close(); next.close() }
    }

    @Test fun queuedMirroredCommandsRecheckThePublicTokenBeforeWriting() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        val controller = controller(session)
        val release = CountDownLatch(1)
        try {
            mainSetup(session); setup(session)
            val event = eventReady(session)
            val token = controller.activeAirPlaySessionToken()!!
            val executor = controller.javaClass.getDeclaredField("touchExecutor").apply { isAccessible = true }
                .get(controller) as ExecutorService
            val started = CountDownLatch(1)
            executor.execute { started.countDown(); release.await(5, TimeUnit.SECONDS) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val completed = CountDownLatch(4)
            val results = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
            val callback: (Boolean) -> Unit = { results.add(it); completed.countDown() }
            controller.showDashboardContentForSession(CarPlayClusterDisplay.MAP_URL, token, callback)
            controller.zoomMirroredDashboardMap(true, token, callback)
            controller.recoverDashboardMapForSession(token, callback)
            controller.refreshDashboardMapViewAreaForSession(token, callback)
            mainSetup(session)
            release.countDown()
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(false, false, false, false), results.toList())
            assertEquals(0, event.bytes.size())
        } finally { release.countDown(); controller.close(); session.close() }
    }

    @Test fun geographicZoomUsesTheCurrentClusterUuidAndStopsAfterTeardown() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        try {
            setup(session)
            val event = eventReady(session)
            assertTrue(session.setClusterUrl(CarPlayClusterDisplay.MAP_URL, send = true))
            assertTrue(session.changeMapZoomLevel(true))
            assertTrue(session.changeMapZoomLevel(false))
            val sent = commands(event)
            val mapUuid = (sent.first()["params"] as Map<*, *>)["uuid"]
            val zoom = sent.takeLast(2)
            assertEquals(listOf("changeMapZoomLevel", "changeMapZoomLevel"), zoom.map { it["type"] })
            assertEquals(listOf(0L, 1L), zoom.map { (it["params"] as Map<*, *>)["zoomDirection"] })
            assertTrue(zoom.all { (it["params"] as Map<*, *>)["uuid"] == mapUuid })
            val bytesBefore = event.bytes.size()
            teardown(session)
            assertFalse(session.changeMapZoomLevel(true))
            assertEquals(bytesBefore, event.bytes.size())
        } finally { session.close() }
    }
    private fun session(initialUrl: String, listener: AirPlaySessionListener = object : AirPlaySessionListener {},
        clusterDisplay: AirPlayDisplayConfig? = null,
        onTeardown: (AirPlaySession) -> Unit = {}): AirPlaySession = AirPlaySession(
        socket = Socket(),
        config = AirPlayConfig(deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
            sourceVersion = "1", main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            cluster = clusterDisplay ?: AirPlayDisplayConfig(widthPixels = 1600, heightPixels = 600, initialUrl = initialUrl)),
        identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
        listener = listener, media = object : AirPlayMediaHandler {
            override fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Int = 1
            override fun onTeardown(session: AirPlaySession, type: Int) { onTeardown(session) }
        },
    )

    private fun setup(session: AirPlaySession) {
        session.javaClass.getDeclaredMethod("handleStreams", List::class.java).apply { isAccessible = true }
            .invoke(session, listOf(mapOf("type" to 111L)))
    }

    private fun teardown(session: AirPlaySession) {
        val request = RtspMessage.Request("TEARDOWN", "*", "RTSP/1.0", emptyMap(),
            BplistCodec.encode(mapOf("streams" to listOf(mapOf("type" to 111L)))))
        session.javaClass.getDeclaredMethod("handleTeardown", RtspMessage.Request::class.java).apply { isAccessible = true }
            .invoke(session, request)
    }

    @Test fun aFailedShowUiMustNotClaimThatATurnCardHasBecomeAMap() {
        val initial = CarPlayClusterDisplay.Content.TURN_CARD.url
        val session = session(initial)
        try {
            setup(session)
            assertFalse(session.setClusterUrl(CarPlayClusterDisplay.MAP_URL, send = true)) // No event connection.
            assertEquals("No command was delivered, so map zoom must not become eligible", initial, session.clusterUrl())
        } finally { session.close() }
    }

    @Test fun aRecreatedStreamMustNotReportThePreviousStreamsUrlAsAlreadyShown() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        try {
            setup(session)
            assertTrue(session.setClusterUrl(CarPlayClusterDisplay.Content.TURN_CARD.url, send = false))
            assertEquals(CarPlayClusterDisplay.MAP_URL, session.clusterUrl())
            teardown(session)
            assertEquals(0, session.clusterStream)
            setup(session)
            assertEquals(2, session.clusterStream)
            assertEquals("A new cluster stream starts at its configured initialURL", CarPlayClusterDisplay.MAP_URL,
                session.clusterUrl())
        } finally { session.close() }
    }

    private class EventSocket(private val incoming: ByteArray = byteArrayOf(), private val onWrite: (() -> Unit)? = null) : Socket() {
        val bytes = ByteArrayOutputStream()
        var writes = 0
        override fun getInputStream() = java.io.ByteArrayInputStream(incoming)
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(value: Int) { bytes.write(value) }
            override fun write(data: ByteArray, offset: Int, length: Int) {
                bytes.write(data, offset, length)
                writes++
                if (writes == 1) onWrite?.invoke()
            }
        }
    }

    private fun eventReady(session: AirPlaySession, event: EventSocket = EventSocket()): EventSocket {
        for ((name, value) in listOf("eventSocket" to event, "eventCipher" to ControlCipher(ByteArray(32), ByteArray(32)))) {
            session.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
        }
        return event
    }

    private fun readyAfterSetup(session: AirPlaySession) {
        session.javaClass.getDeclaredMethod("notifySetupResponseSent").apply { isAccessible = true }.invoke(session)
    }

    private fun commands(event: EventSocket): List<Map<String, Any?>> {
        val plaintext = ControlCipher(ByteArray(32), ByteArray(32)).decrypt(event.bytes.toByteArray()).data
        return RtspMessage.parseMessages(plaintext).messages.map { BplistCodec.decode(it.body) as Map<String, Any?> }
    }

    @Test fun aSuccessfulSwitchPublishesTheDeliveredUrlAndInvalidatesTheOldRoute() {
        val session = session(CarPlayClusterDisplay.Content.TURN_CARD.url)
        try {
            setup(session)
            val before = session.clusterContentRoute()!!.third
            val event = eventReady(session)
            assertTrue(session.setClusterUrl(CarPlayClusterDisplay.MAP_URL, send = true))
            assertEquals(CarPlayClusterDisplay.MAP_URL, session.clusterUrl())
            assertNotSame(before, session.clusterContentRoute()!!.third)
            val commands = commands(event)
            assertEquals(listOf("showUI", "forceKeyFrame"), commands.map { it["type"] })
            assertEquals(CarPlayClusterDisplay.MAP_URL, (commands.first()["params"] as Map<*, *>)["url"])
        } finally { session.close() }
    }

    @Test fun aPausedSelectionStaysPendingUntilResume() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        try {
            setup(session)
            val event = eventReady(session)
            assertTrue(session.setClusterUiShown(false))
            assertTrue(session.setClusterUrl(CarPlayClusterDisplay.Content.TURN_CARD.url, send = false))
            readyAfterSetup(session) // A ready-channel notification must not undo a pause.
            assertEquals(CarPlayClusterDisplay.MAP_URL, session.clusterUrl())
            assertEquals(1, event.writes)
            assertTrue(session.setClusterUiShown(true))
            assertEquals(CarPlayClusterDisplay.Content.TURN_CARD.url, session.clusterUrl())
            assertEquals(listOf("stopUI", "showUI", "forceKeyFrame"), commands(event).map { it["type"] })
        } finally { session.close() }
    }

    @Test fun anUnavailableEventChannelKeepsTheOldUrlUntilRetryActuallyDelivers() {
        val session = session(CarPlayClusterDisplay.Content.TURN_CARD.url)
        try {
            mainSetup(session)
            setup(session)
            assertFalse(session.setClusterUrl(CarPlayClusterDisplay.MAP_URL, send = true))
            assertEquals(CarPlayClusterDisplay.Content.TURN_CARD.url, session.clusterUrl())
            eventReady(session)
            readyAfterSetup(session)
            assertEquals(CarPlayClusterDisplay.MAP_URL, session.clusterUrl())
        } finally { session.close() }
    }

    @Test fun aRecreatedStreamReappliesTheSelectionAfterSetupWithoutClaimingItEarly() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        try {
            mainSetup(session)
            setup(session)
            eventReady(session)
            assertTrue(session.setClusterUrl(CarPlayClusterDisplay.Content.TURN_CARD.url, send = true))
            teardown(session)
            assertNull(session.clusterContentRoute())
            setup(session)
            assertEquals(CarPlayClusterDisplay.MAP_URL, session.clusterUrl())
            readyAfterSetup(session)
            assertEquals(CarPlayClusterDisplay.Content.TURN_CARD.url, session.clusterUrl())
        } finally { session.close() }
    }

    @Test fun aStreamReplacementDuringDeliveryCannotPublishTheOldResultForTheNewStream() {
        val session = session(CarPlayClusterDisplay.Content.TURN_CARD.url)
        try {
            mainSetup(session)
            setup(session)
            eventReady(session, EventSocket { setup(session) })
            assertFalse(session.setClusterUrl(CarPlayClusterDisplay.MAP_URL, send = true))
            assertEquals(2, session.clusterStream)
            assertNull(session.clusterUrl()) // In-flight showUI may have reached either generation.
            readyAfterSetup(session)
            assertEquals(CarPlayClusterDisplay.MAP_URL, session.clusterUrl())
        } finally { session.close() }
    }

    @Test fun aClosedSessionHasNoContentRouteAndCannotAcceptSelections() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        setup(session)
        val event = eventReady(session)
        session.close()
        assertEquals(0, session.clusterStream)
        assertNull(session.clusterContentRoute())
        assertFalse(session.setClusterUrl(CarPlayClusterDisplay.Content.TURN_CARD.url, send = false))
        assertEquals(0, event.writes)
    }

    @Test fun aStopUiThatOutlastsStreamReplacementInvalidatesTheNewRoute() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        try {
            setup(session)
            val event = eventReady(session, EventSocket { setup(session) })
            assertFalse(session.setClusterUiShown(false))
            assertEquals(2, session.clusterStream)
            assertNull(session.clusterUrl())
            readyAfterSetup(session)
            assertEquals(1, event.writes) // An uncertain stop cannot be silently undone by a ready event.
            assertTrue(session.setClusterUiShown(true))
            assertEquals(CarPlayClusterDisplay.MAP_URL, session.clusterUrl())
        } finally { session.close() }
    }

    @Test fun teardownInvalidatesTheRouteBeforeAnUnsuccessfulMediaCallback() {
        var checked = false
        val session = session(CarPlayClusterDisplay.MAP_URL) {
            assertEquals(0, it.clusterStream)
            assertNull(it.clusterContentRoute())
            checked = true
            throw java.io.IOException("media teardown failed")
        }
        try {
            setup(session)
            val failure = assertThrows(java.lang.reflect.InvocationTargetException::class.java) { teardown(session) }
            assertTrue(failure.cause is java.io.IOException)
            assertTrue(checked)
            assertNull(session.clusterContentRoute())
        } finally { session.close() }
    }

    @Test fun firstConnectionExplicitlyActivatesItsInitialUrlOnceAfterBothSetupReplies() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        try {
            val event = eventReady(session)
            setup(session)
            readyAfterSetup(session)
            assertEquals("The primary screen is not ready yet", 0, event.writes)
            mainSetup(session)
            session.javaClass.getDeclaredMethod("reapplyClusterContent").apply { isAccessible = true }.invoke(session)
            assertEquals("A token alone does not mean SETUP was answered", 0, event.writes)
            readyAfterSetup(session)
            assertEquals(listOf("showUI", "forceKeyFrame"), commands(event).map { it["type"] })
            assertTrue(commands(event).all { (it["params"] as Map<*, *>)["uuid"] == session.displayIdentifier(111) })
            readyAfterSetup(session)
            assertEquals("Later RTSP replies must not reactivate an already-started map", 2, event.writes)
        } finally { session.close() }
    }

    @Test fun lateEventReadinessAndAlternateReSetupActivateEachGenerationExactlyOnce() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        try {
            mainSetup(session); setup(session); readyAfterSetup(session)
            val event = eventReady(session)
            val ready = session.javaClass.getDeclaredMethod("reapplyClusterContent").apply { isAccessible = true }
            ready.invoke(session)
            assertEquals(2, event.writes)
            teardown(session); setup(session)
            ready.invoke(session)
            assertEquals("Re-SETUP has not been answered", 2, event.writes)
            readyAfterSetup(session)
            assertEquals(listOf("showUI", "forceKeyFrame", "showUI", "forceKeyFrame"), commands(event).map { it["type"] })
            ready.invoke(session)
            assertEquals(4, event.writes)
        } finally { session.close() }
    }

    @Test fun noAlternateStreamCannotActivateASecondDisplay() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        try {
            mainSetup(session)
            val event = eventReady(session)
            readyAfterSetup(session)
            assertEquals(0, event.writes)
        } finally { session.close() }
    }

    private fun suggest(controller: CarPlayController, session: AirPlaySession, urls: Any?) {
        val listener = controller.javaClass.getDeclaredField("sessionListener").apply { isAccessible = true }
            .get(controller) as AirPlaySessionListener
        listener.onCommand(session, "suggestUI", mapOf("urls" to urls))
    }

    private fun executor(controller: CarPlayController) = controller.javaClass.getDeclaredField("touchExecutor")
        .apply { isAccessible = true }.get(controller) as ExecutorService
    private fun drain(controller: CarPlayController) { executor(controller).submit {}.get(5, TimeUnit.SECONDS) }

    @Test fun aSuggestionBeforeSetupWasAnsweredIsRetainedForTheFirstReadyNotification() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        val controller = controller(session, CarPlayClusterDisplay.MAP_URL)
        try {
            mainSetup(session); setup(session)
            val event = eventReady(session)
            suggest(controller, session, listOf(CarPlayClusterDisplay.MAP_URL)); drain(controller)
            assertEquals("Do not send showUI ahead of the stream SETUP reply", 0, event.writes)
            readyAfterSetup(session)
            assertEquals(listOf("showUI", "forceKeyFrame"), commands(event).map { it["type"] })
            readyAfterSetup(session)
            assertEquals(2, event.writes)
        } finally { controller.close(); session.close() }
    }

    @Test fun initialPhoneSuggestionActivatesTheSelectedMapAndRepeatedOffersDoNotLoop() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        val controller = controller(session, CarPlayClusterDisplay.MAP_URL)
        try {
            mainSetup(session); setup(session); readyAfterSetup(session)
            val event = eventReady(session)
            suggest(controller, session, listOf(CarPlayClusterDisplay.MAP_URL)); drain(controller)
            assertEquals(listOf("showUI", "forceKeyFrame"), commands(event).map { it["type"] })
            repeat(1000) { suggest(controller, session, listOf(CarPlayClusterDisplay.MAP_URL)) }
            drain(controller)
            assertEquals(2, event.writes)
            suggest(controller, session, emptyList<String>()); drain(controller)
            suggest(controller, session, listOf(CarPlayClusterDisplay.MAP_URL)); drain(controller)
            assertEquals("Reoffering a scene after route release must activate it again", 4, event.writes)
        } finally { controller.close(); session.close() }
    }

    @Test fun phoneOffersKeepTheDriversSelectionAndNeverOpenAnUnrequestedUrl() {
        val turn = CarPlayClusterDisplay.Content.TURN_CARD.url
        val session = session(turn)
        val controller = controller(session, turn)
        try {
            mainSetup(session); setup(session); readyAfterSetup(session)
            val event = eventReady(session)
            for (bad in listOf<Any?>(null, "bad", listOf(1, turn), List(17) { turn }, listOf("file:///bad"), listOf(CarPlayClusterDisplay.MAP_URL))) {
                suggest(controller, session, bad); drain(controller)
            }
            assertEquals(0, event.writes)
            suggest(controller, session, listOf(CarPlayClusterDisplay.MAP_URL, turn)); drain(controller)
            assertEquals(turn, (commands(event).first()["params"] as Map<*, *>)["url"])
        } finally { controller.close(); session.close() }
    }

    @Test fun aSuggestionCannotResumeTheDriversPausedCluster() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        val controller = controller(session, CarPlayClusterDisplay.MAP_URL)
        try {
            mainSetup(session); setup(session)
            val event = eventReady(session)
            controller.javaClass.getDeclaredMethod("applyClusterUi", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(controller, false)
            suggest(controller, session, listOf(CarPlayClusterDisplay.MAP_URL)); drain(controller)
            assertEquals(listOf("stopUI"), commands(event).map { it["type"] })
        } finally { controller.close(); session.close() }
    }

    @Test fun aFailedSuggestionDeliveryCanRetryWhenTheEventChannelBecomesReady() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        val controller = controller(session, CarPlayClusterDisplay.MAP_URL)
        try {
            mainSetup(session); setup(session); readyAfterSetup(session)
            suggest(controller, session, listOf(CarPlayClusterDisplay.MAP_URL)); drain(controller)
            val event = eventReady(session)
            suggest(controller, session, listOf(CarPlayClusterDisplay.MAP_URL)); drain(controller)
            assertEquals(2, event.writes)
            setup(session)
            readyAfterSetup(session)
            val before = event.writes
            suggest(controller, session, listOf(CarPlayClusterDisplay.MAP_URL)); drain(controller)
            assertEquals("The same offer belongs to a new alternate stream", before + 2, event.writes)
        } finally { controller.close(); session.close() }
    }

    @Test fun coalescingPreservesTheEmptyToMapTransitionEvenWhenTheFinalUrlIsUnchanged() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        val controller = controller(session, CarPlayClusterDisplay.MAP_URL)
        val release = CountDownLatch(1)
        try {
            mainSetup(session); setup(session); readyAfterSetup(session)
            val event = eventReady(session)
            suggest(controller, session, listOf(CarPlayClusterDisplay.MAP_URL)); drain(controller)
            val entered = CountDownLatch(1)
            executor(controller).execute { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(1000) {
                suggest(controller, session, emptyList<String>())
                suggest(controller, session, listOf(CarPlayClusterDisplay.MAP_URL))
            }
            release.countDown(); drain(controller)
            assertEquals("Only the latest meaningful offer is applied", 4, event.writes)
        } finally { release.countDown(); controller.close(); session.close() }
    }

    @Test fun queuedSuggestionsCannotReachAReplacementPhone() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        val next = session(CarPlayClusterDisplay.MAP_URL)
        val controller = controller(session, CarPlayClusterDisplay.MAP_URL)
        val release = CountDownLatch(1)
        try {
            mainSetup(session); setup(session); readyAfterSetup(session)
            mainSetup(next); setup(next); readyAfterSetup(next)
            val firstEvent = eventReady(session); val nextEvent = eventReady(next)
            val entered = CountDownLatch(1)
            executor(controller).execute { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            suggest(controller, session, listOf(CarPlayClusterDisplay.MAP_URL))
            controller.javaClass.getDeclaredField("activeSession").apply { isAccessible = true }.set(controller, next)
            release.countDown(); drain(controller)
            assertEquals(0, firstEvent.writes); assertEquals(0, nextEvent.writes)
            suggest(controller, next, listOf(CarPlayClusterDisplay.MAP_URL)); drain(controller)
            assertEquals(2, nextEvent.writes)
        } finally { release.countDown(); controller.close(); session.close(); next.close() }
    }

    @Test fun eventChannelSuggestionIsAcknowledgedBeforeItReachesTheController() {
        lateinit var event: EventSocket
        var received = false
        val session = session(CarPlayClusterDisplay.MAP_URL, listener = object : AirPlaySessionListener {
            override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {
                val ack = ControlCipher(ByteArray(32), ByteArray(32)).decrypt(event.bytes.toByteArray()).data
                assertTrue(String(ack, Charsets.US_ASCII).startsWith("RTSP/1.0 200"))
                assertEquals("suggestUI", type)
                assertEquals(listOf(CarPlayClusterDisplay.MAP_URL), params["urls"])
                received = true
            }
        })
        try {
            val body = BplistCodec.encode(mapOf("type" to "suggestUI", "params" to mapOf("urls" to listOf(CarPlayClusterDisplay.MAP_URL))))
            val head = "POST /command RTSP/1.0\r\nCSeq: 7\r\nContent-Type: application/x-apple-binary-plist\r\nContent-Length: ${body.size}\r\n\r\n"
            val wire = ControlCipher(ByteArray(32), ByteArray(32)).encrypt(head.toByteArray(Charsets.US_ASCII) + body)
            event = eventReady(session, EventSocket(incoming = wire))
            session.javaClass.getDeclaredMethod("runEventRead", Socket::class.java).apply { isAccessible = true }.invoke(session, event)
            assertTrue(received)
        } finally { session.close() }
    }
}
