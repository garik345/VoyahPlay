package com.shilapi.xcertplay.voyah.ultra

import android.content.SharedPreferences
import android.media.AudioManager
import com.shilapi.xcertplay.airplay.ultra.UltraClimateWriteResult

/** The head unit also has a Qinggan master/media mute independent of Android's media index. */
internal interface VoyahNativeMute {
    fun read(): Boolean?
    /** null: OEM service unavailable; false: a known OEM mute could not be cleared. */
    fun clear(): Boolean?
}

/** The OEM radio follows the media index, but ignores Android's stream-mute flag. */
internal class VoyahMediaVolume(private val audio: AudioManager, private val saved: SharedPreferences,
    private val nativeMute: VoyahNativeMute? = null) {
    private data class Duck(val original: Int, val reduced: Int, val owner: VoyahMediaVolume? = null)

    companion object {
        private val volumeLock = Any()
        private val activeDucks = java.util.WeakHashMap<SharedPreferences, Duck>()
        private const val DUCK_RESTORE = "siriRadioVolumeRestore"
        private const val STREAM = AudioManager.STREAM_MUSIC
    }
    val range: IntRange? = runCatching {
        val min = audio.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        (min..max).takeIf { min in 0..255 && max in min..255 }
    }.getOrNull()
    private var audible = saved.getInt("lastAudibleIndex", -1).takeIf { it in (range ?: IntRange.EMPTY) && it > (range?.first ?: 0) }

    private fun remember(index: Int) {
        if (audible != index) { audible = index; saved.edit().putInt("lastAudibleIndex", index).apply() }
    }

    private fun muted() = audio.isStreamMute(STREAM) || nativeMute?.read() == true

    private fun clearDuck() {
        activeDucks.remove(saved)
        saved.edit().remove(DUCK_RESTORE).apply()
    }

    private fun savedDuck(): Duck? {
        val values = saved.getString(DUCK_RESTORE, null)?.split(':') ?: return null
        val original = values.getOrNull(0)?.toIntOrNull() ?: return null
        val reduced = values.getOrNull(1)?.toIntOrNull() ?: return null
        val bounds = range ?: return null
        return Duck(original, reduced).takeIf { original in bounds && reduced in bounds && original > reduced }
    }

    /** Restore only the level this app applied. A wheel/Tweaks change takes precedence. */
    private fun restoreDuck(duck: Duck): Boolean {
        if (audio.getStreamVolume(STREAM) == duck.reduced) {
            if (muted()) return false
            audio.setStreamVolume(STREAM, duck.original, 0)
            if (audio.getStreamVolume(STREAM) == duck.reduced) return false
        }
        clearDuck()
        return true
    }

    fun setSpeechDuck(enabled: Boolean): String = synchronized(volumeLock) {
        runCatching {
            val bounds = range ?: return@synchronized "volume unavailable"
            val active = activeDucks[saved]
            if (!enabled) {
                if (active == null || active.owner !== this) return@synchronized "no owned reduction"
                activeDucks.remove(saved)
                return@synchronized "restore=${restoreDuck(active)}"
            }
            if (active != null) return@synchronized "reduction already active"
            savedDuck()?.let { if (!restoreDuck(it)) return@synchronized "previous reduction awaiting restore" }
            val original = audio.getStreamVolume(STREAM)
            if (original !in bounds || original == bounds.first || muted()) return@synchronized "already quiet"
            val reduced = bounds.first + (original - bounds.first) / 3
            val duck = Duck(original, reduced, this)
            if (!saved.edit().putString(DUCK_RESTORE, "$original:$reduced").commit()) return@synchronized "restore record unavailable"
            audio.setStreamVolume(STREAM, reduced, 0)
            if (audio.getStreamVolume(STREAM) != reduced) {
                clearDuck()
                return@synchronized "reduction not confirmed"
            }
            activeDucks[saved] = duck
            "ducked=$original->$reduced"
        }.getOrElse { "volume error=${it.javaClass.simpleName}" }
    }

    fun read(): Pair<Long?, Boolean?> = synchronized(volumeLock) { runCatching {
        val bounds = range ?: return null to null
        val active = activeDucks[saved]
        if (active == null) savedDuck()?.let(::restoreDuck)
        val actual = audio.getStreamVolume(STREAM).takeIf { it in bounds } ?: return null to null
        if (active != null && actual != active.reduced) clearDuck()
        val index = active?.takeIf { actual == it.reduced }?.original ?: actual
        val muted = index == bounds.first || muted()
        if (!muted) remember(index)
        index.toLong() to muted
    }.getOrElse { null to null } }

    fun write(level: Int?, mute: Boolean?): UltraClimateWriteResult = synchronized(volumeLock) {
        val bounds = range ?: return UltraClimateWriteResult(false, "Volume range unavailable")
        if (level != null && level !in bounds || level == null && mute == null ||
            level != null && mute != null && mute != (level == bounds.first))
            return UltraClimateWriteResult(false, "Invalid or conflicting volume/mute")
        return try {
            val stream = AudioManager.STREAM_MUSIC
            val actual = audio.getStreamVolume(stream)
            val duck = activeDucks[saved] ?: savedDuck()
            val before = duck?.takeIf { it.reduced == actual }?.original ?: actual
            if (before > bounds.first && (mute == true || level == bounds.first)) remember(before)
            val wanted = when {
                mute == true -> bounds.first
                level != null -> level
                before > bounds.first -> before
                else -> audible ?: (bounds.first + 1).coerceAtMost(bounds.last)
            }
            // A positive Android index does not release the head unit's master mute.
            // Only an explicit unmute/positive-volume command may clear that mute.
            if (wanted > bounds.first && nativeMute?.clear() == false)
                return UltraClimateWriteResult(false, "OEM mute could not be cleared")
            // A mute flag alone leaves FM audible on this head unit. Zero the same
            // index that the working volume buttons use, and restore it on unmute.
            audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
            audio.setStreamVolume(stream, wanted, 0)
            if (duck != null && audio.getStreamVolume(stream) == wanted) clearDuck()
            val (readback, muted) = read()
            UltraClimateWriteResult(readback == wanted.toLong() && (mute == null || muted == mute),
                "index=$readback range=$bounds wanted=$wanted muted=$muted restore=$audible")
        } catch (e: Exception) { UltraClimateWriteResult(false, "${e.javaClass.simpleName}: ${e.message}") }
    }
}
