package com.shilapi.xcertplay.airplay.ultra

import com.shilapi.xcertplay.airplay.AirPlayAppStates
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

enum class UltraMediaSource { CARPLAY, RADIO }

/** Serializes native radio selections and restores the last deliberate media source. */
internal class VoyahRadioAudioSync(
    private val takeAudio: () -> Boolean,
    private val releaseAudio: () -> Unit,
    private val trace: (String) -> Unit,
    private val preferred: () -> UltraMediaSource = { UltraMediaSource.RADIO },
    private val remember: (UltraMediaSource) -> Unit = {},
    private val pauseRadio: () -> Boolean = { true },
    private val selectionChanged: () -> Unit = {},
    private val speechDuckChanged: (Boolean) -> Unit = {},
) : Closeable {
    private enum class Action { TAKE, PAUSE, DOOR_PAUSE }
    private data class Ticket(val generation: Long, val action: Action)
    private val state = Any()
    private val operation = Any()
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "voyah-radio-audio").apply { isDaemon = true } }
    private var selected: UltraMediaSource? = null
    private var closed = false
    private var ready = false
    private var playing = false
    private var baseline = false
    private var station: String? = null
    private var generation = 0L
    private var pending: Ticket? = null
    private var scheduled = false
    private var commands = 0
    private var startingCommands = 0
    private var doorOpen: Boolean? = null
    private var doorSampledAt = 0L
    private var doorPausePending = false
    private val appStates = AirPlayAppStates()
    private var speechDucked = false

    fun modes(params: Map<String, Any?>) = synchronized(state) {
        if (!closed) { appStates.accept(params); updateSpeechDuck() }
    }

    fun resetModes() = synchronized(state) { appStates.clear(); updateSpeechDuck() }

    private fun updateSpeechDuck() {
        val wanted = !closed && ready && playing && selection() == UltraMediaSource.RADIO &&
            appStates.speech && !appStates.phoneCall
        if (wanted != speechDucked) {
            speechDucked = wanted
            speechDuckChanged(wanted)
        }
    }

    private fun selection() = selected ?: preferred().also { selected = it }
    fun radioSelected(): Boolean = synchronized(state) { selection() == UltraMediaSource.RADIO }
    private fun select(value: UltraMediaSource) {
        if (selection() == value) return
        selected = value
        runCatching { remember(value) }.onFailure { trace("MEDIA SOURCE persist error=${it.javaClass.simpleName}") }
        trace("MEDIA SOURCE selected=$value")
        selectionChanged()
        updateSpeechDuck()
    }

    /** OEM callbacks never wait for the phone or an OEM playback read-back. */
    fun observe(radio: UltraRadioSnapshot?) = synchronized(state) {
        if (closed) return@synchronized
        if (radio?.connected != true) {
            doorPausePending = false
            if (baseline || playing) { playing = false; baseline = false; station = null; generation++; pending = null }
            updateSpeechDuck()
            return@synchronized
        }
        val actual = radio.state?.playing ?: return@synchronized
        val first = !baseline
        val tuned = station != null && radio.currentId != null && station != radio.currentId
        if (!first && actual == playing && !tuned) return@synchronized
        baseline = true; station = radio.currentId; playing = actual; generation++
        if (!actual) doorPausePending = false
        pending = null
        if (actual && startingCommands == 0) {
            // A boot-time snapshot is not a new user press on Radio. Do not let it overwrite CarPlay.
            if ((first || !ready) && selection() == UltraMediaSource.CARPLAY) {
                pending = Ticket(generation, Action.PAUSE)
                trace("RADIO STARTUP ignored; rememberedSource=CARPLAY")
            } else {
                select(UltraMediaSource.RADIO)
                pending = Ticket(generation, Action.TAKE)
                trace("RADIO EXTERNAL playing=true station=${radio.currentId}")
            }
        }
        schedule()
        updateSpeechDuck()
    }

    /** One pause for a fresh closed-to-open edge, never for an initial/open or unknown seed. */
    fun driverDoor(open: Boolean?, sampledAt: Long, now: Long) = synchronized(state) {
        if (closed) return@synchronized
        // Audio/RDS emissions can repeat the previous CAN sample while a new read is in flight.
        // Ignore those stale repeats; only a new valid CAN sample may change the baseline.
        if (sampledAt <= doorSampledAt || sampledAt <= 0 || now - sampledAt !in 0..2_500) return@synchronized
        doorSampledAt = sampledAt
        if (open == null) {
            doorOpen = null
            return@synchronized
        }
        val opened = doorOpen == false && open
        doorOpen = open
        if (opened && playing) {
            // Keep this request separate: a CAF write may currently hold the radio worker.
            // Its completion or an RDS callback must not discard the door pause.
            doorPausePending = true
            generation++; pending = null
            trace("RADIO DRIVER DOOR opened; pause queued")
            schedule()
        }
    }

    fun attach() = synchronized(state) { if (!closed) { ready = true; selectionChanged(); schedule(); updateSpeechDuck() } }
    /** Called only for iAP2 music playback, never speech/call ownership. A Siri play-music request counts too. */
    fun iphonePlayback(playing: Boolean, initial: Boolean) = synchronized(state) {
        if (closed || !playing) return@synchronized
        if (initial && selection() == UltraMediaSource.RADIO) return@synchronized
        select(UltraMediaSource.CARPLAY)
        generation++
        pending = if (this.playing) Ticket(generation, Action.PAUSE) else null
        schedule()
    }

    fun <T> control(startsRadio: Boolean, confirmed: (T) -> Boolean = { true }, action: () -> T): T {
        synchronized(state) { commands++; if (startsRadio) startingCommands++; generation++; pending = null }
        try {
            return synchronized(operation) { action() }.also { result -> synchronized(state) {
                if (!closed && startsRadio && confirmed(result)) { select(UltraMediaSource.RADIO); generation++; pending = null }
            } }
        } finally {
            synchronized(state) { commands--; if (startsRadio) startingCommands--; schedule() }
        }
    }

    private fun schedule() {
        if (closed || !ready || commands != 0 || (pending == null && !doorPausePending) || scheduled) return
        scheduled = true
        try { worker.execute(::drain) } catch (_: RejectedExecutionException) { scheduled = false }
    }
    private fun drain() {
        while (true) {
            val ticket = synchronized(state) {
                if (closed || !ready || commands != 0 || (pending == null && !doorPausePending)) { scheduled = false; return }
                if (doorPausePending) Ticket(generation, Action.DOOR_PAUSE) else pending!!
            }
            synchronized(operation) {
                val current = synchronized(state) {
                    val queued = if (ticket.action == Action.DOOR_PAUSE) doorPausePending else pending == ticket
                    if (closed || commands != 0 || !playing || !queued || generation != ticket.generation) false
                    else { pending = null; if (ticket.action == Action.DOOR_PAUSE) doorPausePending = false; true }
                }
                if (current) {
                    val ok = runCatching { if (ticket.action == Action.TAKE) takeAudio() else pauseRadio() }
                        .getOrElse { trace("RADIO AUDIO error=${it.javaClass.simpleName}"); false }
                    val stillCurrent = synchronized(state) { !closed && playing && generation == ticket.generation }
                    if (ticket.action == Action.TAKE) {
                        if (ok && !stillCurrent) runCatching(releaseAudio)
                        trace("RADIO EXTERNAL AUDIO mainAudio accessoryGranted=$ok current=$stillCurrent")
                    } else if (ticket.action == Action.DOOR_PAUSE) trace("RADIO DRIVER DOOR paused=$ok current=$stillCurrent")
                    else trace("RADIO RESTORE CARPLAY paused=$ok current=$stillCurrent")
                }
            }
        }
    }
    override fun close() {
        synchronized(state) { closed = true; generation++; pending = null; doorPausePending = false; updateSpeechDuck() }
        worker.shutdownNow()
    }
}
