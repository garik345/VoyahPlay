package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.provider.Settings
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSettings
import org.robolectric.util.ReflectionHelpers

/** Exercises the live Vehicle page; firmware verification is the only simulated capability. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "ru-w600dp-h720dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class VoyahPhoneWheelUiTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var activity: DiPlayActivity? = null
    private var previousRuntime: VoyahPhoneWheelRuntime? = null
    private var previousApp: Context? = null
    private var previousController: CarPlayController? = null
    private var previousApplicationFlags = 0
    private var previousOverlayPermission = false

    @Before fun setUp() {
        // The automotive app opts into RTL; the common library's test manifest has no application flags.
        previousApplicationFlags = context.applicationInfo.flags
        context.applicationInfo.flags = previousApplicationFlags or ApplicationInfo.FLAG_SUPPORTS_RTL
        previousOverlayPermission = Settings.canDrawOverlays(context)
        ShadowSettings.setCanDrawOverlays(false)
        previousRuntime = ReflectionHelpers.getStaticField(VoyahPhoneWheel::class.java, "runtime")
        previousApp = ReflectionHelpers.getStaticField(VoyahPhoneWheel::class.java, "app")
        previousController = ReflectionHelpers.getStaticField(VoyahPhoneWheel::class.java, "controller")
        context.getSharedPreferences("voyah_phone_wheel", 0).edit().clear().commit()
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
        PendingReconnect.clear()
    }

    @After fun tearDown() {
        activity?.finish()
        context.applicationInfo.flags = previousApplicationFlags
        ShadowSettings.setCanDrawOverlays(previousOverlayPermission)
        CarPlayBackgroundSession.clear()
        VoyahPhoneWheel.setController(null)
        ReflectionHelpers.setStaticField(VoyahPhoneWheel::class.java, "runtime", previousRuntime)
        ReflectionHelpers.setStaticField(VoyahPhoneWheel::class.java, "app", previousApp)
        ReflectionHelpers.setStaticField(VoyahPhoneWheel::class.java, "controller", previousController)
        PendingReconnect.clear()
        RuntimeEnvironment.setFontScale(1f)
        AppAppearanceRuntime.resetForTest()
        context.getSharedPreferences("voyah_phone_wheel", 0).edit().clear().commit()
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    @Test fun reviewedFirmwareShowsTheOptInOnlyInVehicle() {
        installFirmwareCapability(true)
        val screen = openSettings()
        for (category in SettingsCategory.entries) {
            selectCategory(screen, category)
            val matching = texts(screen).count { it.text.toString() == title(screen) }
            assertEquals("Phone-button setting in $category", if (category == SettingsCategory.VEHICLE) 1 else 0, matching)
        }
    }

    @Test fun unreviewedFirmwareDoesNotOfferTheNativeKeyOverride() {
        installFirmwareCapability(false)
        val screen = openSettings()
        selectCategory(screen, SettingsCategory.VEHICLE)
        assertFalse(VoyahPhoneWheel.supported())
        assertFalse(VoyahPhoneWheelSettings.enabled(context))
        assertFalse(texts(screen).any { it.text.toString() == title(screen) })
        assertFalse(descendants(screen.window.decorView).filterIsInstance<Switch>().any {
            it.contentDescription == title(screen)
        })
    }

    @Test fun changingTheOptInPersistsImmediatelyWithoutReconnecting() {
        installFirmwareCapability(true)
        val screen = openSettings()
        val controller = mock(CarPlayController::class.java)
        var disconnects = 0
        CarPlayBackgroundSession.store(controller, mock(AndroidMediaSink::class.java), 800, 480, Any(),
            CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) { disconnects++ }
        CarPlayBackgroundSession.active = true
        selectCategory(screen, SettingsCategory.VEHICLE)
        val toggle = phoneSwitch(screen)
        assertFalse(toggle.isChecked)
        (toggle.parent as View).performClick()
        assertTrue(toggle.isChecked)
        assertTrue(VoyahPhoneWheelSettings.enabled(context))
        assertFalse(PendingReconnect.isPending(controller))
        assertEquals(0, disconnects)
        assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)

        selectCategory(screen, SettingsCategory.VEHICLE)
        assertTrue(phoneSwitch(screen).isChecked)
        (phoneSwitch(screen).parent as View).performClick()
        assertFalse(VoyahPhoneWheelSettings.enabled(context))
        assertFalse(PendingReconnect.isPending(controller))
        assertEquals(0, disconnects)
        assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
    }

    @Test @Config(qualifiers = "ru-w320dp-h720dp-mdpi")
    fun compactRussianLargeFontScreenshot() = capture("compact-ru", AppLocale.RUSSIAN)

    @Test fun backgroundPermissionActionFollowsTheOptInAndOpensThisAppsSystemPermission() {
        installFirmwareCapability(true)
        val screen = openSettings()
        selectCategory(screen, SettingsCategory.VEHICLE)
        assertFalse(hasPermissionAction(screen))

        (phoneSwitch(screen).parent as View).performClick()
        assertTrue(VoyahPhoneWheelSettings.enabled(context))
        assertTrue(hasPermissionAction(screen))
        assertTrue(texts(screen).any { it.text.toString() == screen.getString(R.string.settings_voyah_phone_wheel_permission_description) })
        permissionAction(screen).performClick()
        val intent = checkNotNull(shadowOf(screen).nextStartedActivity) { "Permission action must open Android settings" }
        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, intent.action)
        assertEquals("package:${screen.packageName}", intent.data.toString())

        ShadowSettings.setCanDrawOverlays(true)
        selectCategory(screen, SettingsCategory.VEHICLE)
        assertTrue(phoneSwitch(screen).isChecked)
        assertFalse(hasPermissionAction(screen))

        ShadowSettings.setCanDrawOverlays(false)
        selectCategory(screen, SettingsCategory.VEHICLE)
        assertTrue(hasPermissionAction(screen))
        (phoneSwitch(screen).parent as View).performClick()
        assertFalse(VoyahPhoneWheelSettings.enabled(context))
        assertFalse(hasPermissionAction(screen))
    }

    @Test fun unsupportedFirmwareDoesNotOfferTheBackgroundPermissionEvenWithASavedOptIn() {
        installFirmwareCapability(false)
        VoyahPhoneWheelSettings.setEnabled(context, true)
        val screen = openSettings()
        selectCategory(screen, SettingsCategory.VEHICLE)
        assertFalse(hasPermissionAction(screen))
        assertFalse(texts(screen).any { it.text.toString() == title(screen) })
    }

    @Test @Config(qualifiers = "ru-w320dp-h720dp-mdpi")
    fun compactRussianBackgroundPermissionLargeFontScreenshot() =
        capture("compact-ru-permission", AppLocale.RUSSIAN, enabled = true)

    @Test @Config(qualifiers = "ru-w1280dp-h720dp-xhdpi")
    fun expandedHighDensityRussianLargeFontScreenshot() = capture("expanded-ru-xhdpi", AppLocale.RUSSIAN)

    @Test @Config(qualifiers = "ar-ldrtl-w320dp-h720dp-mdpi")
    fun compactArabicRtlLargeFontScreenshot() = capture("compact-ar-rtl", AppLocale.ARABIC)

    private fun capture(name: String, language: String, enabled: Boolean = false) {
        RuntimeEnvironment.setFontScale(1.5f)
        AppLocale.save(context, language)
        installFirmwareCapability(true)
        VoyahPhoneWheelSettings.setEnabled(context, enabled)
        val screen = openSettings()
        selectCategory(screen, SettingsCategory.VEHICLE)
        layoutRoot(screen)
        val toggle = phoneSwitch(screen)
        val row = toggle.parent as ViewGroup
        val target = if (enabled) permissionAction(screen) else row
        target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height), true)
        layoutRoot(screen)
        val visible = Rect()
        assertTrue("Phone-button row must be visible in $name", row.getGlobalVisibleRect(visible))
        if (!enabled) assertTrue("Phone-button row must fit the viewport in $name", visible.height() >= row.height)
        assertTrue("Native toggle must fit its row in $name", toggle.height <= row.height)
        if (enabled) {
            val permissionVisible = Rect()
            assertTrue("Background permission action must be visible in $name", target.getGlobalVisibleRect(permissionVisible))
            assertTrue("Background permission action must fit in $name", permissionVisible.height() >= target.height)
            val caption = target as TextView
            val textLayout = checkNotNull(caption.layout)
            val contentHeight = caption.height - caption.compoundPaddingTop - caption.compoundPaddingBottom
            assertTrue("Permission caption must fit inside its button in $name: ${caption.text}",
                textLayout.height <= contentHeight)
            for (line in 0 until textLayout.lineCount) assertEquals("Clipped permission caption in $name", 0,
                textLayout.getEllipsisCount(line))
        }
        if (language == AppLocale.ARABIC) assertEquals(View.LAYOUT_DIRECTION_RTL, row.layoutDirection)
        for (label in descendants(row).filterIsInstance<TextView>()) {
            val layout = label.layout ?: continue
            for (line in 0 until layout.lineCount) assertEquals("Clipped $name: ${label.text}", 0, layout.getEllipsisCount(line))
        }
        assertTrue(descendants(row).filterIsInstance<TextView>().any { it.text.toString() == title(screen) })
        val root = screen.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            val output = File("build/reports/voyah-phone-wheel-ui/$name.png").apply { parentFile?.mkdirs() }
            output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun installFirmwareCapability(supported: Boolean) {
        val runtime = VoyahPhoneWheelRuntime(
            post = { it() }, later = { _, _ -> }, work = { it() },
            backend = object : VoyahPhoneWheelBackend {
                override fun verify() { check(supported) { "Unreviewed test firmware" } }
                override fun bind(onKey: (Int, Int) -> Boolean, lost: () -> Unit) =
                    VoyahPhoneWheelBackend.Connection { }
            },
            calling = { false }, visible = { true }, openHost = { fail("Settings must not open the projection") },
        )
        runtime.refresh()
        ReflectionHelpers.setStaticField(VoyahPhoneWheel::class.java, "runtime", runtime)
        ReflectionHelpers.setStaticField(VoyahPhoneWheel::class.java, "app", context)
        ReflectionHelpers.setStaticField(VoyahPhoneWheel::class.java, "controller", null)
    }

    private fun openSettings(): DiPlayActivity = Robolectric.buildActivity(DiPlayActivity::class.java,
        Intent(context, DiPlayActivity::class.java).putExtra("page", "settings")).setup().get().also { activity = it }

    private fun selectCategory(screen: DiPlayActivity, category: SettingsCategory) {
        ReflectionHelpers.setField(screen, "settingsCategory", category)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun layoutRoot(screen: DiPlayActivity) {
        val root = screen.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val metrics = screen.resources.displayMetrics
        root.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun title(screen: DiPlayActivity) = screen.getString(R.string.settings_voyah_phone_wheel)
    private fun hasPermissionAction(screen: DiPlayActivity) = texts(screen).any {
        it.text.toString() == screen.getString(R.string.settings_voyah_phone_wheel_permission)
    }
    private fun permissionAction(screen: DiPlayActivity) = texts(screen).single {
        it.text.toString() == screen.getString(R.string.settings_voyah_phone_wheel_permission)
    }
    private fun phoneSwitch(screen: DiPlayActivity) = descendants(screen.window.decorView).filterIsInstance<Switch>()
        .single { it.contentDescription == title(screen) }
    private fun texts(screen: DiPlayActivity) = descendants(screen.window.decorView).filterIsInstance<TextView>()
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
