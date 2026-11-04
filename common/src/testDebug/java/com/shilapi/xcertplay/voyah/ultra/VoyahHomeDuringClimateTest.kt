package com.shilapi.xcertplay.voyah.ultra

import android.content.Intent
import android.os.Looper
import com.shilapi.xcertplay.airplay.ultra.UltraVehicleControl
import com.shilapi.xcertplay.airplay.ultra.UltraClimateWriteResult
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class VoyahHomeDuringClimateTest {
    @Test fun homeOpensEvenWhenTheClimateWriteLockIsHeld() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("voyah_ultra_pilot", 0).edit().putBoolean("radioEnabled", false).commit()
        val source = VoyahUltraSettings.ClimateSource(app, false)
        val field = source.javaClass.getDeclaredField("writeBusy").apply { isAccessible = true }
        val busy = field.get(source) as AtomicBoolean
        busy.set(true)
        val result = AtomicReference<UltraClimateWriteResult>()
        val task = Thread { result.set(source.writeVehicleControl(UltraVehicleControl.CAR_HOME, 1)) }
        try {
            task.start()
            val until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
            while (task.isAlive && System.nanoTime() < until) { shadowOf(Looper.getMainLooper()).idle(); task.join(2) }
            assertNotNull(result.get())
            assertTrue(result.get().message, result.get().confirmed)
            val intent = shadowOf(app).nextStartedActivity
            assertEquals(Intent.ACTION_MAIN, intent.action)
            assertTrue(intent.categories.contains(Intent.CATEGORY_HOME))
            assertTrue("HOME must not steal the climate lock", busy.get())
        } finally { source.close(); shadowOf(Looper.getMainLooper()).idle(); task.join(500) }
    }
}
