package com.shilapi.xcertplay

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.ArrayDeque
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class VoyahPhoneWheelNativeTest {
    private class Jobs : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    private class App(base: Context) : ContextWrapper(base) {
        val connections = mutableListOf<ServiceConnection>()
        val unbound = mutableListOf<ServiceConnection>()
        var accepts = true
        override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
            assertEquals(ComponentName(VoyahPhoneWheelOem.PACKAGE, "${VoyahPhoneWheelOem.PACKAGE}.KeyManagerService"), service.component)
            assertEquals(Context.BIND_AUTO_CREATE, flags)
            connections.add(conn)
            return accepts
        }
        override fun unbindService(conn: ServiceConnection) { unbound.add(conn) }
    }

    private class KeyManager : Binder() {
        val operations = mutableListOf<String>()
        var listener: IBinder? = null
        var duringRegister: (() -> Unit)? = null
        var serial = 0
        init { attachInterface(null, VoyahPhoneWheelOem.SERVICE) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(VoyahPhoneWheelOem.SERVICE)
            when (code) {
                3 -> {
                    assertNotNull(data.readStrongBinder())
                    listener = data.readStrongBinder()
                    assertArrayEquals(intArrayOf(128), data.createIntArray())
                    assertEquals(VoyahPhoneWheelOem.OWNER, data.readString())
                    assertEquals(2, data.readInt())
                    assertEquals(50, data.readInt())
                    operations.add("register-${++serial}")
                    duringRegister?.also { duringRegister = null }?.invoke()
                }
                4 -> {
                    assertNotNull(data.readStrongBinder())
                    assertEquals(VoyahPhoneWheelOem.OWNER, data.readString())
                    operations.add("unregister")
                    listener = null // The reviewed OEM service also removes by owner, regardless of callback.
                }
                else -> return false
            }
            reply!!.writeNoException(); reply.writeInt(0)
            return true
        }

        fun key(key: Int = 128, action: Int = 8192, owner: String? = null, type: Int = 1): Boolean {
            val target = checkNotNull(listener)
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(VoyahPhoneWheelOem.CALLBACK)
                data.writeInt(1)
                KeyEvent(0, 0, action, key, 0).writeToParcel(data, 0)
                if (owner != null) { data.writeString(owner); data.writeInt(type) }
                assertTrue(target.transact(if (owner == null) 1 else 2, data, reply, 0))
                reply.readException()
                return reply.readInt() == 1
            } finally { data.recycle(); reply.recycle() }
        }
    }

    private class Fixture {
        val app = App(RuntimeEnvironment.getApplication())
        val worker = Jobs()
        val remote = KeyManager()
        val component = ComponentName(VoyahPhoneWheelOem.PACKAGE, "${VoyahPhoneWheelOem.PACKAGE}.KeyManagerService")
        val backend = VoyahPhoneWheelNativeBackend(app, Handler(Looper.getMainLooper()), worker) { KeyEvent.CREATOR }
        var consumed = 0
        var lost = 0
        fun bind(): VoyahPhoneWheelBackend.Connection = backend.bind({ _, _ -> consumed++; true }, { lost++ })
        fun connect(index: Int = app.connections.lastIndex) { app.connections[index].onServiceConnected(component, remote) }
        fun main() = shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun unverifiedBackendCannotBindEvenIfThePreferenceWasEnabled() {
        val f = Fixture()
        try { f.bind(); fail("Unverified backend bound") }
        catch (_: IllegalStateException) { }
        assertTrue(f.app.connections.isEmpty())
    }

    @Test fun registrationUsesReviewedAbiAndReadinessOnlyAfterRegisterReturns() {
        val f = Fixture()
        f.backend.verify()
        val binding = f.bind()
        f.remote.duringRegister = { assertFalse("No callback while registration is incomplete", f.remote.key()) }
        f.connect()
        f.worker.drain(); f.main()
        assertEquals(listOf("register-1"), f.remote.operations)
        assertTrue(f.remote.key())
        assertEquals(1, f.consumed)
        assertFalse(f.remote.key(owner = "com.shilapi.xcertplay.wheelprobe"))
        assertFalse(f.remote.key(owner = VoyahPhoneWheelOem.OWNER, type = 0))
        assertTrue(f.remote.key(owner = VoyahPhoneWheelOem.OWNER))
        binding.close()
        assertFalse("Close disables before the worker unregisters", f.remote.key())
        f.worker.drain(); f.main()
        assertEquals(listOf("register-1", "unregister"), f.remote.operations)
    }

    @Test fun delayedOldConnectionCannotRegisterOrRemoveTheNewOwner() {
        val f = Fixture()
        f.backend.verify()
        val old = f.bind()
        old.close()
        val fresh = f.bind()
        f.connect(1)
        f.connect(0) // Old bind completed only after the new one.
        f.worker.drain(); f.main()
        assertEquals(listOf("register-1"), f.remote.operations)
        assertTrue(f.remote.key())
        f.app.connections[0].onBindingDied(f.component)
        f.main(); f.worker.drain()
        assertEquals(listOf("register-1"), f.remote.operations)
        assertTrue(f.remote.key())
        fresh.close(); f.worker.drain()
    }

    @Test fun inFlightOldRegisterMustBeUnregisteredBeforeReplacementRegister() {
        val f = Fixture()
        f.backend.verify()
        val old = f.bind()
        var fresh: VoyahPhoneWheelBackend.Connection? = null
        f.remote.duringRegister = {
            old.close() // Simulate disable/replacement while the remote register call is still returning.
            fresh = f.bind()
            f.connect(1)
        }
        f.connect(0)
        f.worker.drain(); f.main()
        assertEquals(listOf("register-1", "unregister", "register-2"), f.remote.operations)
        assertTrue(f.remote.key())
        old.close() // A second old cleanup cannot unregister the new owner.
        f.worker.drain()
        assertEquals(listOf("register-1", "unregister", "register-2"), f.remote.operations)
        assertTrue(f.remote.key())
        fresh!!.close(); f.worker.drain()
    }

    @Test fun descriptorMismatchAndBindRejectionFailOpenWithoutARegister() {
        val f = Fixture()
        f.backend.verify()
        f.bind()
        f.app.connections.single().onServiceConnected(f.component, Binder())
        f.worker.drain(); f.main()
        assertTrue(f.remote.operations.isEmpty())
        assertEquals(1, f.lost)
        assertEquals(1, f.app.unbound.size)
        f.app.accepts = false
        f.bind()
        f.worker.drain(); f.main()
        assertEquals(2, f.lost)
        assertTrue(f.remote.operations.isEmpty())
    }
}
