package com.shilapi.xcertplay

/** A window owns its surface; renewing the root lease does not renew that window's pixels. */
internal class VoyahClusterSurfaceBinding<Sink : Any, Output : Any>(
    private val write: (Sink, String, Output?) -> Unit,
) {
    var token: String? = null
        private set
    var output: Output? = null
        private set
    private var window: Any? = null
    private var boundSink: Sink? = null
    private var boundKey: String? = null
    private var boundOutput: Output? = null
    val active: Boolean get() = boundSink != null && boundOutput != null

    fun set(token: String, owner: Any, next: Output?) {
        // Android may destroy the old Activity after its replacement already attached.
        if (next == null && (this.token != token || window !== owner)) return
        this.token = if (next == null) null else token
        window = if (next == null) null else owner
        output = next
    }

    fun bind(authorizedToken: String?, sink: Sink?) {
        val next = output.takeIf { authorizedToken != null && token == authorizedToken && sink != null }
        val nextSink = sink.takeIf { next != null }
        val key = if (next != null) "voyah-cluster-auto:$authorizedToken" else null
        if (boundSink === nextSink && boundKey == key && boundOutput === next) return
        boundKey?.let { boundSink?.let { old -> write(old, it, null) } }
        boundSink = nextSink; boundKey = key; boundOutput = next
        if (next != null && nextSink != null && key != null) write(nextSink, key, next)
    }
}
