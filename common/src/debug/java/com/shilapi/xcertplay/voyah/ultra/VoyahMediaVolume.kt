package com.shilapi.xcertplay.voyah.ultra

import android.content.SharedPreferences
import android.media.AudioManager
import com.shilapi.xcertplay.airplay.ultra.UltraClimateWriteResult

/** The OEM radio follows the media index, but ignores Android's stream-mute flag. */
internal class VoyahMediaVolume(private val audio: AudioManager, private val saved: SharedPreferences) {
    val range: IntRange? = runCatching {
        val min = audio.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        (min..max).takeIf { min in 0..255 && max in min..255 }
    }.getOrNull()
    private var audible = saved.getInt("lastAudibleIndex", -1).takeIf { it in (range ?: IntRange.EMPTY) && it > (range?.first ?: 0) }

    private fun remember(index: Int) {
        if (audible != index) { audible = index; saved.edit().putInt("lastAudibleIndex", index).apply() }
    }

    @Synchronized fun read(): Pair<Long?, Boolean?> = runCatching {
        val bounds = range ?: return null to null
        val index = audio.getStreamVolume(AudioManager.STREAM_MUSIC).takeIf { it in bounds } ?: return null to null
        val muted = index == bounds.first || audio.isStreamMute(AudioManager.STREAM_MUSIC)
        if (!muted) remember(index)
        index.toLong() to muted
    }.getOrElse { null to null }

    @Synchronized fun write(level: Int?, mute: Boolean?): UltraClimateWriteResult {
        val bounds = range ?: return UltraClimateWriteResult(false, "Volume range unavailable")
        if (level != null && level !in bounds || level == null && mute == null ||
            level != null && mute != null && mute != (level == bounds.first))
            return UltraClimateWriteResult(false, "Invalid or conflicting volume/mute")
        return try {
            val stream = AudioManager.STREAM_MUSIC
            val before = audio.getStreamVolume(stream)
            if (before > bounds.first && (mute == true || level == bounds.first)) remember(before)
            val wanted = when {
                mute == true -> bounds.first
                level != null -> level
                before > bounds.first -> before
                else -> audible ?: (bounds.first + 1).coerceAtMost(bounds.last)
            }
            // A mute flag alone leaves FM audible on this head unit. Zero the same
            // index that the working volume buttons use, and restore it on unmute.
            audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
            audio.setStreamVolume(stream, wanted, 0)
            val (readback, muted) = read()
            UltraClimateWriteResult(readback == wanted.toLong() && (mute == null || muted == mute),
                "index=$readback range=$bounds wanted=$wanted muted=$muted restore=$audible")
        } catch (e: Exception) { UltraClimateWriteResult(false, "${e.javaClass.simpleName}: ${e.message}") }
    }
}
