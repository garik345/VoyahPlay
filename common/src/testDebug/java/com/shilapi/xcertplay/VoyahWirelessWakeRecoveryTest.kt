package com.shilapi.xcertplay

import android.net.wifi.WifiManager
import android.os.Handler
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.voyah.VoyahWirelessStartupRecovery
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "ru", manifest = Config.NONE,
    shadows = [VoyahWirelessWakeRecoveryTest.ApState::class])
@LooperMode(LooperMode.Mode.PAUSED)
class VoyahWirelessWakeRecoveryTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var retry: Button
    private lateinit var stage: TextView
    private fun set(name: String, value: Any?) = ReflectionHelpers.setField(activity, name, value)
    private fun generation() = ReflectionHelpers.getField<Int>(activity, "restartGeneration")
    private fun budget() = ReflectionHelpers.getField<WirelessStartupRetryBudget>(activity, "startupRetryBudget")
    private fun recovery() = ReflectionHelpers.getField<VoyahWirelessStartupRecovery>(activity, "startupRecovery")

    @Before fun setup() {
        CarPlayBackgroundSession.clear()
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", activity)
        CarPlayBackgroundSession.active = false
        set("isActivityStarted", true)
        set("wirelessEnabled", true)
        set("wirelessPermissionsReady", true)
        val size = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
            .getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(1920, 720)
        set("activeDisplaySize", size)
        retry = Button(activity).apply { visibility = View.GONE }
        stage = TextView(activity)
        set("startupRetryButton", retry)
        set("stageStatusView", stage)
        ApState.enabled = false
    }

    @After fun cleanup() {
        ReflectionHelpers.getField<AtomicBoolean>(activity, "shuttingDown").set(true)
        ReflectionHelpers.getField<ExecutorService>(activity, "teardownExecutor").apply {
            shutdownNow(); awaitTermination(1, TimeUnit.SECONDS)
        }
        ReflectionHelpers.getField<Handler>(activity, "mainHandler").removeCallbacksAndMessages(null)
        CarPlayBackgroundSession.clear()
    }

    private fun report(status: CarPlayStatus, ownerGeneration: Int = generation()) {
        ReflectionHelpers.callInstanceMethod<(CarPlayStatus) -> Unit>(activity, "createStatusReporter",
            from(Int::class.javaPrimitiveType, ownerGeneration))(status)
    }
    private fun checkRecovery() = ReflectionHelpers.callInstanceMethod<Unit>(activity, "resumeWirelessStartupIfReady")
    private val off get() = CarPlayStatus.Failed("The car hotspot is off", startupFailure = WirelessStartupFailure.HOTSPOT_OFF)

    @Test fun coldHotspotRecoversOnceWithoutPressingRetryAndKeepsTheCauseVisible() {
        report(off)
        assertEquals(activity.getString(R.string.voyah_waiting_for_car_hotspot), stage.text.toString())
        assertEquals(View.VISIBLE, retry.visibility)
        report(CarPlayStatus.ControlEnded)
        report(CarPlayStatus.Failed("Bluetooth socket closed"))
        assertEquals(activity.getString(R.string.voyah_waiting_for_car_hotspot), stage.text.toString())
        checkRecovery()
        assertEquals(0, generation())
        ApState.enabled = true
        checkRecovery()
        assertEquals(1, generation())
        assertEquals(0, budget().retries)
        assertFalse(recovery().pending)
        assertFalse(ReflectionHelpers.getField(activity, "startupRetryStopped"))
        assertEquals(View.GONE, retry.visibility)
        checkRecovery()
        assertEquals(1, generation())
    }

    @Test fun anExhaustedAttemptResumesAfterSleepWithoutResettingTheFastRetryBudget() {
        repeat(6) { next ->
            set("restartGeneration", next)
            set("reconnectScheduled", false)
            report(CarPlayStatus.Failed("timeout", startupFailure = WirelessStartupFailure.FIRST_TCP_TIMEOUT))
        }
        assertEquals(5, budget().retries)
        assertTrue(stage.text.toString().contains(activity.getString(R.string.first_tcp_timeout)))
        assertTrue(stage.text.toString().contains(activity.getString(R.string.voyah_wireless_retry_later)))
        checkRecovery()
        assertEquals(5, generation())
        ShadowSystemClock.advanceBy(Duration.ofHours(8))
        checkRecovery()
        assertEquals(6, generation())
        assertEquals(5, budget().retries)
    }

    @Test fun automaticRecoveryWaitsWhileHiddenInSettingsOrWhileAnotherAttemptOrVideoIsActive() {
        report(off)
        ApState.enabled = true
        for ((name, blocked, allowed) in listOf(
            Triple("isActivityStarted", false, true), Triple("menuOpen", true, false),
            Triple("wirelessEnabled", false, true), Triple("wirelessPermissionsReady", false, true),
            Triple("handshakeResetInProgress", true, false), Triple("reconnectScheduled", true, false),
        )) {
            set(name, blocked); checkRecovery(); assertEquals(name, 0, generation()); set(name, allowed)
        }
        CarPlayBackgroundSession.active = true
        checkRecovery(); assertEquals(0, generation())
        CarPlayBackgroundSession.active = false
        val streams = ReflectionHelpers.getField<MutableSet<Int>>(activity, "activeScreenStreamTypes")
        streams.add(110); checkRecovery(); assertEquals(0, generation()); streams.clear()
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", null)
        checkRecovery(); assertEquals(0, generation())
        assertTrue(recovery().pending)
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", activity)
        ReflectionHelpers.getField<AtomicBoolean>(activity, "shuttingDown").set(true)
        checkRecovery(); assertEquals(0, generation())
    }

    @Test fun invalidConfigurationAndUnsupportedBluetoothDoNotScheduleAutomaticWakeRecovery() {
        for (reason in listOf(WirelessStartupFailure.HOTSPOT_CONFIGURATION, WirelessStartupFailure.BLUETOOTH_UNUSABLE)) {
            set("startupRetryStopped", false)
            set("startupFailureGeneration", -1)
            report(CarPlayStatus.Failed("Configuration requires attention", startupFailure = reason))
            assertFalse(recovery().pending)
            assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
            assertEquals(0, budget().retries)
            ShadowSystemClock.advanceBy(Duration.ofHours(8))
            ApState.enabled = true
            checkRecovery()
            assertEquals(0, generation())
        }
    }

    @Implements(WifiManager::class)
    class ApState {
        @Implementation fun getWifiApState(): Int = if (enabled) 13 else 11
        @Implementation fun isWifiApEnabled(): Boolean = enabled
        companion object { var enabled = false }
    }
}
