package com.shilapi.xcertplay.voyah.ultra

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Binder
import android.os.Looper
import android.os.Parcel
import android.os.ResultReceiver
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaSessionCompat
import com.shilapi.xcertplay.airplay.ultra.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VoyahRadioClientTest {
    private val browser = mock(MediaBrowser::class.java, withSettings().mockMaker("mock-maker-subclass"))
    private val controller = mock(MediaController::class.java, withSettings().mockMaker("mock-maker-subclass"))
    private val calls = AtomicInteger()
    private lateinit var client: VoyahRadioClient
    private lateinit var connection: MediaBrowser.ConnectionCallback
    private var observer: MediaController.Callback? = null
    private var metadata = metadata(10170)
    private var playback = playback(PlaybackState.STATE_PAUSED)
    private var queue = listOf(10170, 10200)
    private var onPlay: () -> Unit = {}
    private var onTune: (Int) -> Unit = {}
    private var tunedIndex: Int? = null
    private val main get() = shadowOf(Looper.getMainLooper())

    @Before fun connect() {
        val pm = mock(PackageManager::class.java, withSettings().mockMaker("mock-maker-subclass"))
        val info = ResolveInfo().apply { serviceInfo = ServiceInfo().apply {
            enabled = true; exported = true; applicationInfo = ApplicationInfo().apply { enabled = true }
        } }
        `when`(pm.resolveService(any(android.content.Intent::class.java), eq(0))).thenReturn(info)
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getPackageManager(): PackageManager = pm
        }
        val tokenParcel = Parcel.obtain()
        tokenParcel.writeStrongBinder(Binder()); tokenParcel.setDataPosition(0)
        val token = MediaSession.Token.CREATOR.createFromParcel(tokenParcel)
        tokenParcel.recycle()
        `when`(browser.sessionToken).thenReturn(token)
        `when`(controller.metadata).thenAnswer { metadata }
        `when`(controller.playbackState).thenAnswer { playback }
        doAnswer { observer = it.getArgument(0); null }.`when`(controller)
            .registerCallback(any(MediaController.Callback::class.java), any(android.os.Handler::class.java))
        doAnswer { connection.onConnected(); null }.`when`(browser).connect()
        doAnswer { call ->
            val result = call.getArgument<ResultReceiver>(2)
            val items = ArrayList(queue.mapIndexed { index, frequency -> MediaSessionCompat.QueueItem(
                MediaDescriptionCompat.Builder().setMediaId(frequency.toString())
                    .setTitle("FM ${frequency / 100.0} MHz").setSubtitle("Station $frequency").build(), index.toLong()) })
            val encoded = Parcel.obtain()
            Bundle().apply { putParcelableArrayList("RESULT_DATA", items) }.writeToParcel(encoded, 0)
            encoded.setDataPosition(0)
            val wire = Bundle.CREATOR.createFromParcel(encoded)
            encoded.recycle()
            result.send(1, wire)
            null
        }.`when`(controller).sendCommand(eq("MEDIA_ACTION_GET_CURRENT_RDS_PLAY_LIST"), isNull(), any(ResultReceiver::class.java))
        client = VoyahRadioClient(context, {}, {},
            { _, _, cb -> connection = cb; browser }, { _, _ -> controller }, { _, command, index ->
                calls.incrementAndGet()
                when (command) {
                    VoyahRadioOem.Command.PLAY -> onPlay()
                    VoyahRadioOem.Command.TUNE -> { tunedIndex = index; onTune(requireNotNull(index)) }
                    else -> Unit
                }
            })
        client.start(); main.idle()
        assertTrue(client.snapshot.connected)
    }

    @After fun stop() { if (::client.isInitialized) { client.close(); main.idle() } }

    @Test fun connectionReadsTheStandardCompatPlaylistWithoutStartingPlayback() {
        assertEquals(listOf("FM:10170", "FM:10200"), client.snapshot.stations.map { it.id })
        assertEquals(listOf(0, 1), client.snapshot.stations.map { it.oemIndex })
        assertEquals("FM:10170", client.snapshot.currentId)
        assertEquals(0, calls.get())
    }

    @Test fun successfulApiReturnAloneIsNotAnOemConfirmation() {
        val job = launch(UltraRadioRequest(VoyahRadioOem.Command.PLAY))
        drainUntil { calls.get() == 1 }
        assertFalse(job.isDone)
        main.idleFor(Duration.ofSeconds(6))
        assertFalse(job.get(1, TimeUnit.SECONDS).confirmed)
    }

    @Test fun playbackCallbackConfirmsPlayAndDisconnectInvalidatesState() {
        onPlay = {
            playback = playback(PlaybackState.STATE_PLAYING)
            observer!!.onPlaybackStateChanged(playback)
        }
        val job = launch(UltraRadioRequest(VoyahRadioOem.Command.PLAY))
        drainUntil { job.isDone }
        assertTrue(job.get().confirmed)
        observer!!.onSessionDestroyed()
        assertFalse(client.snapshot.connected)
        assertNull(client.snapshot.state)
        assertTrue(client.snapshot.stations.isEmpty())
    }

    @Test fun tuningRefreshesTheListBeforeUsingItsOemIndexAndConfirmsTheStation() {
        queue = listOf(10200, 10170) // The OEM list changed after the phone saw the first list.
        onTune = { index ->
            metadata = metadata(queue[index])
            playback = playback(PlaybackState.STATE_PLAYING)
            observer!!.onMetadataChanged(metadata)
        }
        val job = launch(UltraRadioRequest(VoyahRadioOem.Command.TUNE, "FM:10200"))
        drainUntil { job.isDone }
        assertTrue(job.get().confirmed)
        assertEquals(0, tunedIndex)
        assertEquals("FM:10200", client.snapshot.currentId)
    }

    @Test fun aStationRemovedFromTheOemListIsNotDispatchedByAnOldIndex() {
        queue = listOf(10170)
        val job = launch(UltraRadioRequest(VoyahRadioOem.Command.TUNE, "FM:10200"))
        drainUntil { job.isDone }
        assertFalse(job.get().confirmed)
        assertEquals(0, calls.get())
        assertNull(tunedIndex)
    }

    @Test fun closingCancelsAQueuedCommandWithoutSendingPlay() {
        val job = launch(UltraRadioRequest(VoyahRadioOem.Command.PLAY))
        client.close()
        drainUntil { job.isDone }
        assertFalse(job.get().confirmed)
        assertEquals(0, calls.get())
    }

    private fun launch(request: UltraRadioRequest): FutureTask<UltraClimateWriteResult> =
        FutureTask { client.write(request) }.also { Thread(it, "radio-test-write").apply { isDaemon = true; start() } }

    private fun drainUntil(done: () -> Boolean) {
        repeat(2_000) { main.idle(); if (done()) return; Thread.sleep(1) }
        fail("Radio callback did not complete")
    }

    private fun metadata(frequency: Int) = MediaMetadata.Builder().putString(VoyahRadioOem.BAND, "FM")
        .putLong(VoyahRadioOem.FREQUENCY, frequency.toLong()).putString(VoyahRadioOem.TITLE, "FM ${frequency / 100.0} MHz").build()
    private fun playback(state: Int) = PlaybackState.Builder().setState(state, 0, 1f).build()
}
