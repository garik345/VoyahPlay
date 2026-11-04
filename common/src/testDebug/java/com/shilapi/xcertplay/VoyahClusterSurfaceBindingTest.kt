package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class VoyahClusterSurfaceBindingTest {
    private data class Write(val sink: Any, val key: String, val output: Any?)
    private class Fixture {
        val writes = mutableListOf<Write>()
        val binding = VoyahClusterSurfaceBinding<Any, Any> { sink, key, output -> writes += Write(sink, key, output) }
        val sink = Any()
        fun bind(token: String = "lease") = binding.bind(token, sink)
    }

    @Test fun recreatedSurfaceUnderTheSameLeaseGetsANewDecoderBinding() {
        val f = Fixture(); val window = Any(); val first = Any(); val replacement = Any()
        f.binding.set("lease", window, first); f.bind()
        repeat(10) { f.bind() }
        assertEquals(1, f.writes.size)
        f.binding.set("lease", window, replacement); f.bind()
        assertEquals(listOf(first, null, replacement), f.writes.map { it.output })
        assertTrue(f.binding.active)
    }

    @Test fun destroyingTheOldWindowCannotDetachItsAlreadyVisibleReplacement() {
        val f = Fixture(); val oldWindow = Any(); val newWindow = Any(); val old = Any(); val next = Any()
        f.binding.set("lease", oldWindow, old); f.bind()
        f.binding.set("lease", newWindow, next); f.bind()
        f.binding.set("lease", oldWindow, null); f.bind()
        assertSame(next, f.binding.output)
        assertEquals(listOf(old, null, next), f.writes.map { it.output })
        f.binding.set("lease", newWindow, null); f.bind()
        assertFalse(f.binding.active)
        assertNull(f.writes.last().output)
    }

    @Test fun expiredLeaseOrAnotherSessionCannotDisplayThePreviousSurface() {
        val f = Fixture(); val window = Any(); val old = Any()
        f.binding.set("lease", window, old); f.bind()
        f.binding.bind(null, null)
        assertFalse(f.binding.active)
        f.bind("new-lease")
        assertFalse(f.binding.active)
        assertEquals(listOf(old, null), f.writes.map { it.output })
        val next = Any(); f.binding.set("new-lease", Any(), next); f.bind("new-lease")
        f.binding.set("lease", window, null); f.bind("new-lease")
        assertSame(next, f.binding.output)
        assertSame(next, f.writes.last().output)
    }

    @Test fun changingTheSinkDetachesTheRetiredSessionBeforeUsingItsReplacement() {
        val f = Fixture(); val output = Any(); val nextSink = Any()
        f.binding.set("lease", Any(), output); f.bind()
        f.binding.bind("lease", nextSink)
        assertEquals(3, f.writes.size)
        assertNull(f.writes[1].output)
        assertSame(f.sink, f.writes[1].sink)
        assertSame(nextSink, f.writes[2].sink)
        assertSame(output, f.writes[2].output)
    }
}
