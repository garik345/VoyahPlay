package com.shilapi.xcertplay

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], qualifiers = "en", manifest = Config.NONE)
class AudioFocusSettingsTest {
    @Test fun onlyTheAudioFocusSwitchIsShownAndRemovedOptionsDefaultOff() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        activity.getSharedPreferences("xcertplay_airplay",0).edit().clear().commit()
        val page = LinearLayout(activity)
        DiPlayActivity::class.java.getDeclaredMethod("audioFocusControls",LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(activity,page)
        activity.setContentView(page)
        val switches = views(page).filterIsInstance<Switch>().toList()
        assertEquals(1,switches.size)
        assertEquals(activity.getString(R.string.contrib_audio_home_toggle_audio_focus),switches.single().contentDescription)
        assertFalse(AirPlayPersistence.loadDisableMediaKeyAudioFocus(activity))
        assertFalse(AirPlayPersistence.loadAudioFocusAutoYield(activity))
        switches.single().isChecked = false
        assertFalse(AirPlayPersistence.loadAudioFocusEnabled(activity))
    }
    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
