package com.shilapi.xcertplay.voyah

import android.content.*
import android.content.pm.PackageManager
import android.os.*
import com.shilapi.xcertplay.airplay.ultra.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Wire facts from the supplied H97C CAN APK/QGVehicle, not SDK classes packaged in our APK. */
internal object VoyahHvacSchema {
    data class Field(val ordinal: Int, val id: Int, val cacheName: String)
    fun field(control: UltraHvacControl): Field = when (control) {
        UltraHvacControl.DRIVER_TARGET -> Field(2, 3, "airLeftTemperature")
        UltraHvacControl.PASSENGER_TARGET -> Field(3, 4, "airRightTemperature")
        UltraHvacControl.POWER -> Field(7, 8, "airSWStatus")
        UltraHvacControl.AC -> Field(4, 5, "airACStatus")
        UltraHvacControl.FAN -> Field(0, 1, "airWindSpeed")
        UltraHvacControl.AUTO -> Field(6, 7, "airMode")
        UltraHvacControl.RECIRCULATION -> Field(10, 11, "airCirculationMode")
        UltraHvacControl.FRONT_DEFROST -> Field(8, 9, "airFrontWindowDefogger")
        UltraHvacControl.REAR_DEFROST -> Field(9, 10, "airRearWindowDefogger")
    }
    fun decode(raw: Map<String, Number>): Map<UltraHvacControl, Any?> = UltraHvacControl.entries.associateWith { control ->
        val number = raw[field(control).cacheName]?.toDouble()
        if (control == UltraHvacControl.RECIRCULATION) VoyahRecirculation.decode(raw["airCirculationMode"])?.on
        else if (control.format == com.shilapi.xcertplay.airplay.rcs.caf.CafCharacteristicFormat.BOOL)
            when (number) { 0.0 -> false; 1.0 -> true; else -> null }
        else control.normalize(number)
    }
    /** TX30 cache names for values that are only shown to the phone. Unverified semantics stay null unless 0/1. */
    fun readingField(reading: UltraClimateReading): String = when (reading) {
        UltraClimateReading.CABIN_TEMPERATURE -> "airTempInCar"
        UltraClimateReading.MAX_AC -> "airACMaxSW"
        UltraClimateReading.MAX_DEFROST -> "airMaxFrontStatus"
        UltraClimateReading.ZONES_SYNCED -> "airSyncStatus"
        UltraClimateReading.RECIRCULATION_AUTO -> "airCirculationMode"
    }
    fun decodeReadings(raw: Map<String, Number>): Map<UltraClimateReading, Any?> =
        UltraClimateReading.entries.associateWith { reading ->
            if (reading == UltraClimateReading.RECIRCULATION_AUTO) VoyahRecirculation.decode(raw["airCirculationMode"])?.automatic
            else reading.normalize(raw[readingField(reading)]) ?: if (reading == UltraClimateReading.ZONES_SYNCED) {
                // H97C leaves airSyncStatus at -1; airDUALStatus 0 means one shared zone, 1 independent zones.
                when (raw["airDUALStatus"]?.toInt()) { 0 -> true; 1 -> false; else -> null }
            } else null
        }
    fun cycleAdvanced(before: Int?, after: Int?): Boolean = before in 0..2 && after in 0..2 && before != after
    fun wireValue(control: UltraHvacControl, value: Any): Int {
        val normalized = control.normalize(value) ?: throw IllegalArgumentException("Invalid $control=$value")
        // Exactly the multiplication performed by setAirConditionStateEx; no second scaling.
        // H97C power/AC/AUTO/front defrost/recirculation are button pulses, not absolute bits.
        if (control in setOf(UltraHvacControl.POWER, UltraHvacControl.AC, UltraHvacControl.AUTO,
                UltraHvacControl.RECIRCULATION, UltraHvacControl.FRONT_DEFROST)) return 1
        if (control == UltraHvacControl.FAN) return VoyahHvacFan.wireValue(normalized as Long)
        return when (normalized) {
            is Boolean -> if (normalized) 1 else 0
            is Double -> (normalized * 10).toInt()
            is Long -> normalized.toInt()
            else -> error("Unsupported climate value")
        }
    }
}

internal data class VoyahHvacResult(val status: UltraClimateWriteResult, val values: Map<UltraHvacControl, Any?>?, val sampledAt: Long,
    val readings: Map<UltraClimateReading, Any?>? = null)

/** Runs on the CAF worker. The UI thread performs binding/unbinding only. */
internal object VoyahHvac {
    private const val PKG = "com.qinggan.canbus.service"
    private const val API = "com.qinggan.canbus.ICanBusService"
    private val busy = AtomicBoolean()
    private val main = Handler(Looper.getMainLooper())
    fun write(context: Context, control: UltraHvacControl, value: Any, live: () -> Boolean): VoyahHvacResult {
        check(Looper.myLooper() != Looper.getMainLooper()) { "HVAC must run off the UI thread" }
        if (!busy.compareAndSet(false, true)) return failure("HVAC busy")
        val app = context.applicationContext
        val cancelled = AtomicBoolean()
        val bound = AtomicBoolean()
        val ready = CountDownLatch(1)
        val binder = AtomicReference<IBinder?>()
        val deadline = SystemClock.elapsedRealtime() + 6_000
        var actual: Map<UltraHvacControl, Any?>? = null
        var actualReadings: Map<UltraClimateReading, Any?>? = null
        var stamp = 0L
        fun checkLive() {
            if (cancelled.get() || !live() || Thread.currentThread().isInterrupted || SystemClock.elapsedRealtime() > deadline)
                throw IOException("Climate request cancelled or timed out; state unknown")
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { binder.set(service); ready.countDown() }
            override fun onServiceDisconnected(name: ComponentName) { binder.set(null); ready.countDown() }
            override fun onNullBinding(name: ComponentName) { ready.countDown() }
            override fun onBindingDied(name: ComponentName) { binder.set(null); ready.countDown() }
        }
        try {
            val wanted = control.normalize(value) ?: return failure("Invalid $control=$value")
            val snapshot = VoyahClient.latestSnapshot()
            if (snapshot == null || SystemClock.elapsedRealtime() - snapshot.receivedAt !in 0..6_000)
                return failure("A fresh verified H97C snapshot is required")
            check(app.checkSelfPermission("com.qinggan.permission.WRITE_CANBUS") == PackageManager.PERMISSION_GRANTED) { "WRITE_CANBUS denied" }
            val info = app.packageManager.getApplicationInfo(PKG, 0)
            check(info.splitSourceDirs.isNullOrEmpty()) { "Unreviewed split CAN build" }
            val apk = File(info.sourceDir)
            check(apk.isFile && apk.length() in 1..(128L * 1024 * 1024)) { "Invalid CAN APK" }
            val digest = MessageDigest.getInstance("SHA-256")
            apk.inputStream().use { input ->
                val buffer = ByteArray(65_536)
                while (true) { checkLive(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            check(VoyahOemBuilds.supportsCanApk(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })) { "Unreviewed CAN APK" }
            main.post {
                if (cancelled.get() || !live()) { ready.countDown(); return@post }
                try {
                    bound.set(app.bindService(Intent().setComponent(ComponentName(PKG, "$PKG.CanBusService")), connection, Context.BIND_AUTO_CREATE))
                    if (!bound.get()) ready.countDown()
                } catch (_: RuntimeException) { ready.countDown() }
            }
            check(ready.await(3, TimeUnit.SECONDS)) { "CAN bind timeout" }
            checkLive()
            val can = binder.get() ?: throw IOException("CAN service unavailable")
            check(can.interfaceDescriptor == API) { "Unknown CAN Binder" }
            var raw = read(can)
            actual = VoyahHvacSchema.decode(raw); actualReadings = VoyahHvacSchema.decodeReadings(raw); stamp = SystemClock.elapsedRealtime()
            fun reached(): Boolean = if (control == UltraHvacControl.RECIRCULATION)
                VoyahRecirculation.matchesManual(raw["airCirculationMode"], wanted as Boolean)
                else actual?.get(control) == wanted
            val before = actual?.get(control)
            val initialCycle = raw["airCirculationMode"]?.toInt()
            if (before == null && !(control == UltraHvacControl.RECIRCULATION && initialCycle in 0..2))
                throw IOException("Current $control state is unknown")
            var steps = 0
            var lastPulseAt = 0L
            while (!reached() && steps < if (control == UltraHvacControl.RECIRCULATION) 2 else 1) {
                if (control == UltraHvacControl.RECIRCULATION && steps > 0) {
                    // The H97C ignores a second button pulse sent as soon as its cache changes.
                    // Leave a full second between pulses, then re-read before another side effect.
                    val previousMode = raw["airCirculationMode"]?.toInt()
                    val remaining = 1_000 - (SystemClock.elapsedRealtime() - lastPulseAt)
                    if (remaining > 0) Thread.sleep(remaining)
                    checkLive()
                    raw = read(can); actual = VoyahHvacSchema.decode(raw); actualReadings = VoyahHvacSchema.decodeReadings(raw)
                    stamp = SystemClock.elapsedRealtime()
                    if (reached() || raw["airCirculationMode"]?.toInt() != previousMode) break
                }
                val previousCycle = raw["airCirculationMode"]?.toInt()
                steps++
                // AIDL transaction 31 is AirConditionState; VehicleState TX58 is a different ABI.
                checkLive()
                check(app.checkSelfPermission("com.qinggan.permission.WRITE_CANBUS") == PackageManager.PERMISSION_GRANTED)
                val field = VoyahHvacSchema.field(control)
                val data = Parcel.obtain(); val reply = Parcel.obtain()
                actual = null; actualReadings = null; stamp = 0 // Never publish the pre-command snapshot as a new readback.
                try {
                    data.writeInterfaceToken(API); data.writeInt(1); data.writeInt(field.ordinal); data.writeInt(field.id)
                    data.writeInt(VoyahHvacSchema.wireValue(control, wanted))
                    lastPulseAt = SystemClock.elapsedRealtime()
                    check(can.transact(31, data, reply, 0)) { "TX31 unsupported" }
                    reply.readException(); check(reply.dataAvail() == 0) { "Unexpected TX31 response" }
                } finally { data.recycle(); reply.recycle() }
                for (attempt in 0..7) {
                    checkLive(); Thread.sleep(250); checkLive()
                    raw = read(can); actual = VoyahHvacSchema.decode(raw); actualReadings = VoyahHvacSchema.decodeReadings(raw)
                    stamp = SystemClock.elapsedRealtime()
                    if (reached()) break
                    if (control == UltraHvacControl.RECIRCULATION &&
                        VoyahHvacSchema.cycleAdvanced(previousCycle, raw["airCirculationMode"]?.toInt())) break
                }
                // A second circulation pulse is permitted only after a known, different mode arrived.
                if (control == UltraHvacControl.RECIRCULATION &&
                    !VoyahHvacSchema.cycleAdvanced(previousCycle, raw["airCirculationMode"]?.toInt())) break
            }
            val confirmed = reached()
            return VoyahHvacResult(UltraClimateWriteResult(confirmed,
                "$control=$wanted readback=${actual?.get(control)}" +
                    (if (control == UltraHvacControl.RECIRCULATION) " rawMode=${raw["airCirculationMode"]}" else "") +
                    " steps=$steps ${if (confirmed) "confirmed" else "not confirmed; stopped"}"), actual, stamp, actualReadings)
        } catch (error: Exception) {
            return VoyahHvacResult(UltraClimateWriteResult(false, "${error.javaClass.simpleName}: ${error.message}"), actual, stamp, actualReadings)
        } finally {
            cancelled.set(true)
            main.post { if (bound.getAndSet(false)) runCatching { app.unbindService(connection) } }
            busy.set(false)
        }
    }
    private fun failure(message: String) = VoyahHvacResult(UltraClimateWriteResult(false, message), null, 0)
    private fun read(can: IBinder): Map<String, Number> {
        val spec = CanSchema.byCode(30)
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(API); check(can.transact(30, data, reply, 0)) { "TX30 unsupported" }
            reply.readException()
            check(reply.dataAvail() >= 4 && reply.readInt() == 1 && reply.dataAvail() == spec.fields.size * 4) { "Unknown AirCondition layout" }
            val raw = linkedMapOf<String, Number>()
            spec.fields.indices.forEach { index ->
                raw[spec.fields[index]] = if (spec.types[index] == 'f') reply.readFloat() else reply.readInt()
            }
            return raw
        } finally { data.recycle(); reply.recycle() }
    }
}
