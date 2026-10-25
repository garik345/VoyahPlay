package com.shilapi.xcertplay.voyah.ultra

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.DiagnosticExportStore
import com.shilapi.xcertplay.airplay.AirPlayProtocolExtension
import com.shilapi.xcertplay.airplay.ultra.*
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.voyah.VoyahClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Debug source set only: OEM writes require the existing WRITE_CANBUS grant; no root helper changes. */
object VoyahUltraSettings {
    const val available = true
    private const val PREFS = "voyah_ultra_pilot"
    private val reportLock = Any()
    private val io = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(256),
        { task -> Thread(task, "voyah-ultra-report").apply { isDaemon = true } })
    private var dropped = java.util.concurrent.atomic.AtomicLong()

    fun isEnabled(context: Context): Boolean = context.getSharedPreferences(PREFS, 0).getBoolean("enabled", false)
    fun saveEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("enabled", enabled).apply()
        record(context, "SETTING enabled=$enabled; applies on next receiver start")
    }

    fun isAssetProbeEnabled(context: Context): Boolean = context.getSharedPreferences(PREFS, 0).getBoolean("assetProbe", false)
    fun saveAssetProbe(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("assetProbe", enabled).apply()
        record(context, "SETTING assetProbe=$enabled; applies on next receiver start")
    }

    fun isMutualAuthEnabled(context: Context): Boolean = context.getSharedPreferences(PREFS, 0).getBoolean("mutualAuth", false)
    /** Local diagnostic only. This is LIVI's catalog example, not a Voyah production theme. */
    fun carPlayTheme(context: Context): com.shilapi.xcertplay.transport.Iap2CarPlayTheme? =
        if (isEnabled(context) && context.getSharedPreferences(PREFS, 0).getBoolean("themeProbe", false))
            com.shilapi.xcertplay.transport.Iap2CarPlayTheme(
                com.shilapi.xcertplay.iap2.message.Iap2ClusterAsset("44C9FA31EC", 985), "19.0")
        else null
    /** The catalog package has a fixed 1920x720 center canvas at 157 dpi. */
    fun themeDisplay(context: Context, fps: Int): com.shilapi.xcertplay.airplay.AirPlayDisplayConfig? =
        if (carPlayTheme(context) == null) null else com.shilapi.xcertplay.airplay.AirPlayDisplayConfig(
            1920, 720, widthPhysicalMm = 311, heightPhysicalMm = 116, fps = fps)
    fun saveMutualAuth(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("mutualAuth", enabled).apply()
        record(context, "SETTING mutualAuth=$enabled; applies on next connection")
    }

    /** Voyah Free RU is an EREV: a petrol range extender and electric drive. Declared with the Ultra pilot. */
    fun vehicleInformation(context: Context): com.shilapi.xcertplay.transport.Iap2VehicleInformation? =
        if (!isEnabled(context)) null else com.shilapi.xcertplay.transport.Iap2VehicleInformation("Voyah Free",
            listOf(com.shilapi.xcertplay.transport.Iap2VehicleInformation.GASOLINE,
                com.shilapi.xcertplay.transport.Iap2VehicleInformation.ELECTRIC))

    fun createExtension(context: Context): AirPlayProtocolExtension? {
        if (!isEnabled(context)) return null
        val app = context.applicationContext
        val probe = isAssetProbeEnabled(app)
        val panelProbe = app.getSharedPreferences(PREFS, 0).getBoolean("panelProbe", false)
        val themeProbe = carPlayTheme(app) != null
        record(app, "THEME_PROBE=$themeProbe asset=${carPlayTheme(app)}")
        record(app, "RECEIVER CONFIG pilot=1 version=ultra-climate-0.1.25 WIRE_REV=${com.shilapi.xcertplay.airplay.ultra.VoyahUltraProtocol.WIRE_REV} UI_SYNC=available ASSET_PROBE=$probe MUTUAL_AUTH=${isMutualAuthEnabled(app)} VEHICLE_INFO=Voyah Free/gas+electric")
        return VoyahUltraProtocol({ ClimateSource(app) }, SystemClock::elapsedRealtime, { record(app, it) },
            assetProbe = probe, dump = { name, bytes -> saveWireBody(app, name, bytes) }, panelProbe = panelProbe,
            themeProbe = themeProbe, vehicleDataText = UltraVehicleDataText(
                section = app.getString(R.string.carplay_vehicle_data),
                frontLeft = app.getString(R.string.carplay_tire_front_left), frontRight = app.getString(R.string.carplay_tire_front_right),
                rearLeft = app.getString(R.string.carplay_tire_rear_left), rearRight = app.getString(R.string.carplay_tire_rear_right),
                range = app.getString(R.string.carplay_vehicle_range), odometer = app.getString(R.string.carplay_vehicle_odometer),
                fuel = app.getString(R.string.carplay_vehicle_fuel), unavailable = app.getString(R.string.carplay_vehicle_unavailable),
                fuelRange = app.getString(R.string.carplay_vehicle_fuel_range),
                battery = app.getString(R.string.carplay_vehicle_battery), electricRange = app.getString(R.string.carplay_vehicle_electric_range),
                driveMode = app.getString(R.string.carplay_vehicle_drive_mode),
                driveModes = listOf(R.string.carplay_drive_eco, R.string.carplay_drive_comfort, R.string.carplay_drive_sport,
                    R.string.carplay_drive_offroad, R.string.carplay_drive_individual, R.string.carplay_drive_snow).map(app::getString),
                bar = app.getString(R.string.carplay_vehicle_bar), kilometers = app.getString(R.string.carplay_vehicle_km),
                localeTag = app.resources.configuration.locales[0].toLanguageTag()),
            vehicleControlsText = UltraVehicleControlsText(
                section = app.getString(R.string.carplay_vehicle_controls),
                driverMassage = app.getString(R.string.carplay_driver_massage), passengerMassage = app.getString(R.string.carplay_passenger_massage),
                driverProgram = app.getString(R.string.carplay_driver_massage_program), passengerProgram = app.getString(R.string.carplay_passenger_massage_program),
                lights = app.getString(R.string.carplay_vehicle_lights), carHome = app.getString(R.string.carplay_vehicle_menu),
                off = app.getString(R.string.carplay_control_off), wave = app.getString(R.string.carplay_massage_wave),
                rollers = app.getString(R.string.carplay_massage_rollers),
                lightModes = listOf(R.string.carplay_control_off, R.string.carplay_light_position,
                    R.string.carplay_light_low_beam, R.string.carplay_light_auto).map(app::getString),
                unavailable = app.getString(R.string.carplay_vehicle_unavailable)))
    }

    /** First receiver /info and incoming fileTransfer bodies; together capped at 4 MB per session. */
    private fun saveWireBody(context: Context, name: String, bytes: ByteArray) {
        val copy = bytes.copyOf()
        try {
            io.execute {
                synchronized(reportLock) {
                    runCatching {
                        val directory = File(File(context.filesDir, "ultra-pilot"), "probe").apply { mkdirs() }
                        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date())
                        File(directory, "$stamp-${name.replace(Regex("[^A-Za-z0-9_-]"), "_")}.bin").writeBytes(copy)
                    }.onFailure { Log.w("VoyahUltra", "Wire body write failed", it) }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { dropped.incrementAndGet() }
    }

    private fun wireBodies(context: Context): String {
        val files = File(File(context.filesDir, "ultra-pilot"), "probe").listFiles()?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) return ""
        return buildString {
            appendLine("ULTRA WIRE BODIES files=${files.size} bytes=${files.sumOf { it.length() }} (base64)")
            appendLine("receiver-info = outgoing /info bplist; CarPlayUpdateData = incoming fileTransfer body; older probe files may remain.")
            files.forEach { appendLine("--- ${it.name} ${it.length()}"); appendLine(android.util.Base64.encodeToString(it.readBytes(), android.util.Base64.NO_WRAP)) }
        }
    }

    private fun record(context: Context, text: String) {
        val line = "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.ROOT).format(Date())} ${text.take(3000)}\n"
        Log.i("VoyahUltra", text.take(3000))
        try {
            io.execute {
                synchronized(reportLock) {
                    runCatching {
                        val directory = File(context.filesDir, "ultra-pilot").apply { mkdirs() }
                        val file = File(directory, "capture.txt")
                        if (file.length() > 2 * 1024 * 1024) {
                            val previous = File(directory, "capture.previous.txt")
                            previous.delete()
                            check(file.renameTo(previous)) { "Cannot rotate Ultra journal" }
                        }
                        file.appendText(line, Charsets.UTF_8)
                    }.onFailure { Log.w("VoyahUltra", "Journal write failed", it) }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { dropped.incrementAndGet() }
    }

    private fun report(context: Context): String {
        // Called from a worker, so waiting for the bounded journal queue never blocks the UI.
        runCatching { io.submit {}.get(5, TimeUnit.SECONDS) }
        return synchronized(reportLock) {
            val dir = File(context.filesDir, "ultra-pilot")
            buildString {
                appendLine("VoyahPlay Ultra climate pilot 0.1.25")
                appendLine("DEBUG_ONLY=1 WIRE_REV=${com.shilapi.xcertplay.airplay.ultra.VoyahUltraProtocol.WIRE_REV} enabled=${isEnabled(context)} assetProbe=${isAssetProbeEnabled(context)} dropped=${dropped.get()}")
                appendLine("H97C HVAC: driver/passenger target, power, AC, fan, AUTO, recirculation, front/rear defrost; comfort controls.")
                appendLine("UI Sync: available handler; NEGOTIATED, RCS SETUP/LISTEN and STATE ready are separate stages.")
                appendLine("First /info is captured even with ASSET_PROBE off. Export also saves a voyahplay-ultra-wire-* file with base64 wire bodies.")
                appendLine("CAF transport and climate metadata checked against f-io/LIVI 3fb5210f; OEM commands checked against the supplied CAN APK SHA256 96ac5182.")
                appendLine("Climate UI is not confirmed. Writes are accepted only from explicit iPhone requests and require OEM readback.")
                appendLine("OEM radio: com.pateo.rdsapp MediaBrowser; CAF Media + NowPlaying use LIVI's wire schema. Play/Pause/Next/Previous/TuneToIdentifier require OEM readback. FM/AM semantic enum and iPhone rendering remain unverified.")
                for (name in listOf("capture.previous.txt", "capture.txt")) {
                    val file = File(dir, name)
                    if (file.exists()) appendLine(file.readText(Charsets.UTF_8))
                }
            }
        }
    }

    fun showStatus(activity: Activity) {
        background(activity) { report(activity.applicationContext).takeLast(45_000) }
    }

    fun exportReport(activity: Activity) {
        val context = activity.applicationContext
        background(activity) {
            val text = report(context)
            val name = "voyahplay-ultra-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())}.txt"
            val saved = DiagnosticExportStore.saveWithoutPicker(context, name, text)
            val bodies = synchronized(reportLock) { wireBodies(context) }
            val wire = if (bodies.isEmpty()) "" else DiagnosticExportStore.saveWithoutPicker(context,
                name.replace("voyahplay-ultra-", "voyahplay-ultra-wire-"), bodies).let { "\n" + (it.savedPath ?: it.uri.toString()) }
            (saved.savedPath ?: saved.uri.toString()) + wire
        }
    }

    fun probeClimate(activity: Activity) {
        val client = VoyahClient(activity.applicationContext)
        record(activity, "CAN PROBE requested")
        client.read { values, error ->
            val target = values?.left
            val result = if (error == null && target != null) "driverTargetC=$target sampledAt=${values.receivedAt}"
                else "CAN ERROR ${error ?: "airLeftTemperature unavailable"}"
            record(activity, result + " hvac=" + values?.climateValues())
            if (!activity.isFinishing && !activity.isDestroyed) dialog(activity, result)
        }
    }

    private fun background(activity: Activity, action: () -> String) {
        android.widget.Toast.makeText(activity, R.string.voyah_ultra_working, android.widget.Toast.LENGTH_SHORT).show()
        Thread({
            val result = runCatching(action).getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
            activity.runOnUiThread { if (!activity.isFinishing && !activity.isDestroyed) dialog(activity, result) }
        }, "voyah-ultra-export").apply { isDaemon = true; start() }
    }

    private fun dialog(activity: Activity, text: String) {
        val view = android.widget.TextView(activity).apply {
            this.text = text; setTextIsSelectable(true)
            val pad = (16 * resources.displayMetrics.density).toInt(); setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(activity).setTitle(R.string.voyah_ultra_title)
            .setView(android.widget.ScrollView(activity).apply { addView(view) })
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private class ClimateSource(context: Context) : UltraClimateSource {
        private val handler = Handler(Looper.getMainLooper())
        private val client = VoyahClient(context)
        private val app = context.applicationContext
        private val radio = VoyahRadioClient(app, { record(app, it) }, { emit() })
        @Volatile private var lightMode: Int? = null
        private val lights = com.shilapi.xcertplay.voyah.VoyahLightStatus(app) { mode ->
            if (!stopped) {
                lightMode = mode.takeIf { it in 0..3 }
                record(app, "LIGHT STATUS mode=$lightMode")
                synchronized(lock) {
                    val old = latest ?: UltraClimateSample(Double.NaN, 0)
                    latest = old.copy(vehicleControlValues = withLights(old.vehicleControlValues), lightingSampledAtMillis = SystemClock.elapsedRealtime())
                }
                emit()
            }
        }
        private val lock = Any()
        private val writeBusy = java.util.concurrent.atomic.AtomicBoolean()
        @Volatile private var stopped = false
        @Volatile private var latest: UltraClimateSample? = null
        @Volatile private var pending: ComfortRequest? = null
        private var callback: ((UltraClimateSample?) -> Unit)? = null
        private val audio = app.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        private val mediaVolume = VoyahMediaVolume(audio, app.getSharedPreferences("voyah_ultra_audio", 0))
        private val mediaVolumeRange get() = mediaVolume.range
        private var lastRawVehicle: String? = null
        private var lastRecirculationRaw: String? = null
        private var lastRawVehicleAt = 0L
        private fun permitted() = app.checkSelfPermission("com.qinggan.permission.WRITE_CANBUS") == android.content.pm.PackageManager.PERMISSION_GRANTED
        override val supportedControls = if (permitted()) UltraClimateControl.entries.toSet() else emptySet()
        override val supportedHvac = if (permitted()) UltraHvacControl.entries.toSet() else emptySet()
        override val supportedVehicleControls = if (permitted()) UltraVehicleControl.entries.toSet() else setOf(UltraVehicleControl.CAR_HOME)
        private fun withLights(values: Map<UltraVehicleControl, Int>) =
            (values - UltraVehicleControl.LIGHT_MODE) + (lightMode?.let { mapOf(UltraVehicleControl.LIGHT_MODE to it) } ?: emptyMap())
        private val poll = object : Runnable {
            override fun run() {
                if (stopped) return
                emit()
                val cached = VoyahClient.latestSnapshot()
                if (cached != null && SystemClock.elapsedRealtime() - cached.receivedAt in 0..2_500) update(cached)
                else client.read { values, error ->
                    if (!stopped) {
                        if (error == null && values != null) update(values)
                        else record(app, "CAN ERROR $error")
                    }
                }
                handler.postDelayed(this, 2_000)
            }
        }
        private fun update(values: com.shilapi.xcertplay.voyah.VoyahValues) {
            val comfort = values.comfortValues()
            val levels = linkedMapOf<UltraClimateControl, Int>()
            val selectedLevels = linkedMapOf<UltraClimateControl, Int>()
            for (control in supportedControls) {
                val level = if (control.isWheel) comfort?.wheelHeatingLevel()
                    else comfort?.seatLevel(if (control.isPassenger) 1 else 0, control.isVent)
                if (level != null) levels[control] = level
                if (!control.isWheel) comfort?.selectedSeatLevel(if (control.isPassenger) 1 else 0, control.isVent)
                    ?.let { selectedLevels[control] = it }
            }
            val (volumeLevel, muted) = volume()
            val vehicle = com.shilapi.xcertplay.voyah.VoyahVehicleSchema.readings(values, volumeLevel, muted)
            val raw = "odometer=${values.odometerValues()} tpms=${values.tireValues()} fuel=${values.fuelValues()}"
            if (raw != lastRawVehicle && SystemClock.elapsedRealtime() - lastRawVehicleAt >= 10_000) {
                lastRawVehicle = raw; lastRawVehicleAt = SystemClock.elapsedRealtime()
                record(app, "VEHICLE RAW $raw -> $vehicle")
            }
            val climate = values.climateValues()
            val circulation = "mode=${climate["airCirculationMode"]} status=${climate["airCirculationStatus"]}"
            if (circulation != lastRecirculationRaw) {
                lastRecirculationRaw = circulation
                record(app, "RECIRCULATION RAW $circulation")
            }
            val hvac = com.shilapi.xcertplay.voyah.VoyahHvacSchema.decode(climate)
            val readings = com.shilapi.xcertplay.voyah.VoyahHvacSchema.decodeReadings(climate)
            synchronized(lock) {
                val old = latest
                val useOldHvac = old != null && old.hvacSampledAtMillis > values.receivedAt
                val target = if (useOldHvac) old!!.hvacValues else hvac
                val stamp = if (useOldHvac) old!!.hvacSampledAtMillis else values.receivedAt
                latest = UltraClimateSample((target[UltraHvacControl.DRIVER_TARGET] as? Number)?.toDouble() ?: Double.NaN, stamp,
                    if (old != null && old.comfortSampledAtMillis > values.receivedAt) old.comfortLevels else levels,
                    maxOf(old?.comfortSampledAtMillis ?: 0, values.receivedAt), target, stamp,
                    if (useOldHvac) old!!.readings else readings,
                    vehicle = vehicle, vehicleSampledAtMillis = values.receivedAt,
                    vehicleControlValues = if (old != null && old.vehicleControlsSampledAtMillis > values.receivedAt)
                        withLights(old.vehicleControlValues) else withLights(com.shilapi.xcertplay.voyah.VoyahVehicleControlSchema.readings(comfort)),
                    vehicleControlsSampledAtMillis = maxOf(old?.vehicleControlsSampledAtMillis ?: 0, values.receivedAt),
                    lightingSampledAtMillis = SystemClock.elapsedRealtime(),
                    comfortSelectedLevels = if (old != null && old.comfortSampledAtMillis > values.receivedAt)
                        old.comfortSelectedLevels else selectedLevels)
            }
            emit()
        }
        override fun start(onSample: (UltraClimateSample?) -> Unit) {
            handler.post { if (!stopped && callback == null) { callback = onSample; radio.start(); lights.start(); handler.post(poll) } }
        }
        private fun emit() {
            if (stopped) return
            val value = synchronized(lock) {
                val old = latest ?: if (radio.snapshot.connected) UltraClimateSample(Double.NaN, 0) else null
                old?.copy(radio = radio.snapshot, radioSampledAtMillis = SystemClock.elapsedRealtime()).also { latest = it }
            }
            callback?.invoke(value)
        }
        override val supportsRadio get() = radio.supported
        override fun writeRadio(request: com.shilapi.xcertplay.airplay.ultra.UltraRadioRequest): UltraClimateWriteResult {
            if (stopped) return UltraClimateWriteResult(false, "Radio source closed")
            return radio.write(request)
        }
        /** Native stream index and mute; no lossy percent conversion between CarPlay and the car. */
        private fun volume(): Pair<Long?, Boolean?> = mediaVolume.read()
        override val supportsVolume get() = mediaVolumeRange != null
        override val volumeRange get() = mediaVolumeRange ?: 0..100
        override fun writeVolume(level: Int?, mute: Boolean?): UltraClimateWriteResult {
            if (stopped) return UltraClimateWriteResult(false, "Volume closed")
            val result = mediaVolume.write(level, mute)
            val (readback, muted) = volume()
            synchronized(lock) {
                latest = latest?.let { it.copy(vehicle = (it.vehicle ?: UltraVehicleReadings()).copy(volumeLevel = readback, muted = muted)) }
            }
            handler.post { emit() }
            return result
        }
        override fun writeHvac(control: UltraHvacControl, value: Any): UltraClimateWriteResult {
            check(Looper.myLooper() != Looper.getMainLooper())
            if (stopped || !permitted() || control !in supportedHvac || !writeBusy.compareAndSet(false, true))
                return UltraClimateWriteResult(false, "Climate closed, busy or permission denied")
            try {
                val result = com.shilapi.xcertplay.voyah.VoyahHvac.write(app, control, value) { !stopped }
                if (!stopped) synchronized(lock) {
                    val old = latest ?: UltraClimateSample(Double.NaN, 0)
                    val hvac = result.values ?: (old.hvacValues + (control to null))
                    val stamp = if (result.values != null) result.sampledAt else old.hvacSampledAtMillis
                    latest = old.copy(driverTargetCelsius = (hvac[UltraHvacControl.DRIVER_TARGET] as? Number)?.toDouble() ?: Double.NaN,
                        sampledAtMillis = stamp, hvacValues = hvac, hvacSampledAtMillis = stamp,
                        readings = result.readings ?: old.readings)
                }
                handler.post { emit() }
                return result.status
            } finally { writeBusy.set(false) }
        }
        private class ComfortRequest {
            val ready = java.util.concurrent.CountDownLatch(1)
            @Volatile var ticket: com.shilapi.xcertplay.voyah.VoyahControls.Ticket? = null
            @Volatile var result = UltraClimateWriteResult(false, "Comfort request unavailable")
        }
        override fun writeVehicleControl(control: UltraVehicleControl, value: Int): UltraClimateWriteResult {
            check(Looper.myLooper() != Looper.getMainLooper())
            if (stopped || control !in supportedVehicleControls || control.normalize(value) == null ||
                (control != UltraVehicleControl.CAR_HOME && !permitted()) || !writeBusy.compareAndSet(false, true))
                return UltraClimateWriteResult(false, "Vehicle control closed, busy, invalid or permission denied")
            val request = ComfortRequest(); pending = request
            try {
                if (control == UltraVehicleControl.LIGHT_MODE) {
                    val observed = lightMode ?: return UltraClimateWriteResult(false, "LightStatus is unavailable")
                    if (observed == value) return UltraClimateWriteResult(true, "LightStatus already mode=$value")
                }
                handler.post {
                    if (stopped || pending !== request) { request.ready.countDown(); return@post }
                    if (control == UltraVehicleControl.CAR_HOME) {
                        request.result = runCatching {
                            app.startActivity(android.content.Intent(android.content.Intent.ACTION_MAIN)
                                .addCategory(android.content.Intent.CATEGORY_HOME).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            UltraClimateWriteResult(true, "Native vehicle menu opened")
                        }.getOrElse { UltraClimateWriteResult(false, "Vehicle menu: ${it.message}") }
                        request.ready.countDown()
                        return@post
                    }
                    val command = com.shilapi.xcertplay.voyah.VoyahVehicleControlSchema.command(control, value)
                    if (command == null) { request.ready.countDown(); return@post }
                    request.ticket = com.shilapi.xcertplay.voyah.VoyahControls.request(app,
                        command.name, command.side, command.function, command.value) { states, message ->
                        if (!stopped && pending === request) {
                            if (control != UltraVehicleControl.LIGHT_MODE) {
                                val readings = withLights(com.shilapi.xcertplay.voyah.VoyahVehicleControlSchema.readback(states))
                                synchronized(lock) {
                                    latest = (latest ?: UltraClimateSample(Double.NaN, 0)).copy(
                                        vehicleControlValues = readings, vehicleControlsSampledAtMillis = SystemClock.elapsedRealtime())
                                }
                                request.result = UltraClimateWriteResult(readings[control] == value, "$message readback=${readings[control]}")
                                emit()
                            } else request.result = UltraClimateWriteResult(false, "$message; awaiting LightStatus")
                        }
                        request.ready.countDown()
                    }
                }
                if (!request.ready.await(7, TimeUnit.SECONDS)) return UltraClimateWriteResult(false, "Vehicle control timed out")
                if (control == UltraVehicleControl.LIGHT_MODE) {
                    // Command bits are pulses. Only the independent LightStatus subscription proves the resulting mode.
                    val deadline = SystemClock.elapsedRealtime() + 2_000
                    while (!stopped && lightMode != value && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
                    return UltraClimateWriteResult(!stopped && lightMode == value, "LightStatus mode=$lightMode wanted=$value; ${request.result.message}")
                }
                return request.result
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return UltraClimateWriteResult(false, "Vehicle control interrupted")
            } finally {
                if (pending === request) pending = null
                handler.post { request.ticket?.cancel() }
                writeBusy.set(false)
            }
        }
        override fun write(control: UltraClimateControl, level: Int): UltraClimateWriteResult {
            check(Looper.myLooper() != Looper.getMainLooper())
            if (stopped || !permitted() || control !in supportedControls || level !in 0..control.maxLevel || !writeBusy.compareAndSet(false, true))
                return UltraClimateWriteResult(false, "Comfort closed, busy, invalid or permission denied")
            val request = ComfortRequest(); pending = request
            try {
                handler.post {
                    if (stopped || pending !== request) { request.ready.countDown(); return@post }
                    request.ticket = com.shilapi.xcertplay.voyah.VoyahControls.request(app,
                        if (control.isWheel) "wheel-set" else "seat-level", if (control.isPassenger) 1 else 0,
                        if (control.isVent) 1 else 0, if (control.isWheel) level + 1 else level) { states, message ->
                        if (!stopped && pending === request) {
                            val levels = linkedMapOf<UltraClimateControl, Int>()
                            val selectedLevels = linkedMapOf<UltraClimateControl, Int>()
                            for (c in supportedControls) {
                                val state = if (c.isWheel) states["WHEEL"] else states[(if (c.isVent) "VENT" else "HEAT") + if (c.isPassenger) "_R" else "_L"]
                                val actual = if (c.isWheel) when (state) { 1 -> 0; 2 -> 1; else -> null }
                                    else com.shilapi.xcertplay.voyah.VoyahComfortValues.effectiveLevel(state ?: -1,
                                        states[(if (c.isVent) "VENT_LEVEL" else "HEAT_LEVEL") + if (c.isPassenger) "_R" else "_L"] ?: -1)
                                if (actual != null) levels[c] = actual
                                if (!c.isWheel) states[(if (c.isVent) "VENT_LEVEL" else "HEAT_LEVEL") + if (c.isPassenger) "_R" else "_L"]
                                    ?.takeIf { it in 1..c.maxLevel }?.let { selectedLevels[c] = it }
                            }
                            synchronized(lock) {
                                latest = (latest ?: UltraClimateSample(Double.NaN, 0)).copy(comfortLevels = levels,
                                    comfortSampledAtMillis = SystemClock.elapsedRealtime(), comfortSelectedLevels = selectedLevels)
                            }
                            emit()
                            request.result = UltraClimateWriteResult(levels[control] == level, "$message readback=${levels[control]}")
                        }
                        request.ready.countDown()
                    }
                }
                return if (request.ready.await(7, TimeUnit.SECONDS)) request.result
                    else UltraClimateWriteResult(false, "Comfort timed out; state unknown")
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return UltraClimateWriteResult(false, "Comfort interrupted; state unknown")
            } finally {
                if (pending === request) pending = null
                handler.post { request.ticket?.cancel() }
                writeBusy.set(false)
            }
        }
        override fun close() {
            stopped = true
            radio.close()
            val request = pending; pending = null; request?.ready?.countDown()
            handler.post { handler.removeCallbacksAndMessages(null); request?.ticket?.cancel(); lights.stop(); client.cancel(); callback = null; latest = null }
        }
    }
}
