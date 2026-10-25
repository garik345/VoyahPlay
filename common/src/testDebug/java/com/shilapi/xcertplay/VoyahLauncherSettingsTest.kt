package com.shilapi.xcertplay

import android.content.Intent
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class VoyahLauncherSettingsTest {
    private var stops = 0
    @Before fun start() {
        CarPlayBackgroundSession.clear()
        CarPlayBackgroundSession.projectionVisible = false
        val stop: ((() -> Unit) -> Unit) = { done -> stops++; done() }
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", stop)
    }
    @After fun end() {
        CarPlayBackgroundSession.clear()
        CarPlayBackgroundSession.projectionVisible = false
    }
    private fun launcher() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    @Test fun iconOverVisibleProjectionOpensSettingsAndBackReturnsWithoutDisconnect() {
        CarPlayBackgroundSession.projectionVisible = true
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java, launcher()).create()
        try {
            val activity = controller.get()
            assertFalse(activity.isFinishing)
            assertEquals("settings", ReflectionHelpers.getField<String>(activity, "page"))
            assertTrue(activity.intent.getBooleanExtra("from_carplay_settings", false))
            assertNull(shadowOf(activity).nextStartedActivity)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "navigateBack")
            assertEquals(CarPlayHostActivity::class.java.name,
                shadowOf(activity).nextStartedActivity.component?.className)
            assertEquals(0, stops)
        } finally { controller.destroy() }
    }

    @Test fun launcherDeliveryToExistingTaskAlsoOpensSettings() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent().putExtra("page", "home")).create()
        try {
            CarPlayBackgroundSession.projectionVisible = true
            val activity = controller.get()
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "onNewIntent",
                ClassParameter.from(Intent::class.java, launcher()))
            assertEquals("settings", ReflectionHelpers.getField<String>(activity, "page"))
            assertNull(shadowOf(activity).nextStartedActivity)
            assertEquals(0, stops)
        } finally { controller.destroy() }
    }

    @Test fun iconFromAnotherAppReturnsToExistingProjection() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java, launcher()).create()
        try {
            val activity = controller.get()
            assertTrue(activity.isFinishing)
            assertEquals(CarPlayHostActivity::class.java.name,
                shadowOf(activity).nextStartedActivity.component?.className)
            assertEquals(0, stops)
        } finally { controller.destroy() }
    }
}
