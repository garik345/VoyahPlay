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
    private fun controller(session: AirPlaySession): CarPlayController = CarPlayController(
        RuntimeEnvironment.getApplication(), CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL,
            identification = Iap2IdentificationConfig(name = "test", modelIdentifier = "test", manufacturer = "test",
                serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3)),
        AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
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
            val completed = CountDownLatch(2)
            val results = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
            val callback: (Boolean) -> Unit = { results.add(it); completed.countDown() }
            controller.showDashboardContentForSession(CarPlayClusterDisplay.MAP_URL, token, callback)
            controller.zoomMirroredDashboardMap(true, token, callback)
            mainSetup(session)
            release.countDown()
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(false, false), results.toList())
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
    private fun session(initialUrl: String, onTeardown: (AirPlaySession) -> Unit = {}): AirPlaySession = AirPlaySession(
        socket = Socket(),
        config = AirPlayConfig(deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
            sourceVersion = "1", main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            cluster = AirPlayDisplayConfig(widthPixels = 1600, heightPixels = 600, initialUrl = initialUrl)),
        identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
        listener = object : AirPlaySessionListener {}, media = object : AirPlayMediaHandler {
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

    private class EventSocket(private val onWrite: (() -> Unit)? = null) : Socket() {
        val bytes = ByteArrayOutputStream()
        var writes = 0
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
}
