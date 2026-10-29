package com.shilapi.xcertplay.media

/** Android playback streams. These are not the IDs accepted by Qinggan's volume Binder. */
object VoyahAudioRouting {
    const val MEDIA_STREAM = 3
    const val NAVIGATION_STREAM = 1

    /** Android forbids STREAM_ACCESSIBILITY in the legacy AudioTrack constructor. */
    internal fun assistantPlaybackAttributes(): android.media.AudioAttributes = android.media.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build()

    internal fun stream(channel: AudioChannel): Int = when (channel) {
        AudioChannel.MEDIA -> MEDIA_STREAM
        AudioChannel.NAVIGATION -> NAVIGATION_STREAM
        AudioChannel.PHONE -> 6
        // OSAudioPolicyService's Ivoka group (OEM 22) writes Android ACCESSIBILITY (10).
        // AudioTrack rejects vendor 22 and would fall back to USAGE_ASSISTANT on another bus.
        AudioChannel.ASSISTANT -> 10
    }

    /** The adapter takes Qinggan policy IDs, never Android's playback ID 10 for Siri. */
    internal fun focusStream(channel: AudioChannel, actualPlaybackStream: Int): Int =
        if (actualPlaybackStream != stream(channel)) -1 else when (channel) {
            AudioChannel.ASSISTANT -> 22
            else -> stream(channel)
        }

    // Some frameworks accept a vendor ID but silently translate it into STREAM_MUSIC.
    fun accepted(requested: Int, actual: Int, initialized: Boolean): Boolean =
        initialized && requested == actual
}
