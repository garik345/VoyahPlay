package com.shilapi.xcertplay.media

/** On-demand read-only diagnostics, including tracks retained by the background CarPlay session. */
object VoyahPlaybackRoutes {
    internal class Owner { internal var closed = false }
    private val routes = LinkedHashMap<Owner, () -> String>()
    internal fun open() = Owner()
    @Synchronized internal fun register(owner: Owner, describe: () -> String) { if (!owner.closed) routes[owner] = describe }
    @Synchronized internal fun remove(owner: Owner) { owner.closed = true; routes.remove(owner) }
    fun describe(): String = synchronized(this) { routes.values.take(8) }.map { read ->
        runCatching(read).getOrElse { "route unavailable: ${it.javaClass.simpleName}" }.take(350)
    }.sorted().joinToString("; ").ifEmpty { "no playback tracks" }
}
