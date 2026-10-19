package com.shilapi.xcertplay

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.graphics.Bitmap
import android.graphics.Canvas
import com.shilapi.xcertplay.host.R
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "en", manifest = Config.NONE)
class BydCallPopupSettingsTest {
    @Test fun bydPopupControlIsNotOfferedInTheVoyahProfile() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        ReflectionHelpers.setField(activity,"settingsSectionFilter",setOf(SettingsSection.ADVANCED_MEDIA))
        val content = LinearLayout(activity)
        ReflectionHelpers.callInstanceMethod<Unit>(activity,"allSettingsSections",
            ReflectionHelpers.ClassParameter.from(LinearLayout::class.java,content))
        assertTrue(switches(content).none {
            it.contentDescription == activity.getString(R.string.settings_hide_byd_call_popup)
        })
    }
    private fun switches(view: View): List<Switch> = when (view) {
        is Switch -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { switches(view.getChildAt(it)) }
        else -> emptyList()
    }
}
