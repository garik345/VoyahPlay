package com.shilapi.xcertplay.voyah.root

import android.app.Activity
import android.app.AlertDialog
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.settings.SettingsTheme
import com.shilapi.xcertplay.settings.SettingsWidgets

/** The helper owns the persistent choice; the app caches only its last acknowledged state. */
object RadioLabelsSettings {
    fun add(
        activity: Activity,
        parent: LinearLayout,
        theme: SettingsTheme,
        status: (VoyahRootModule.Callback) -> Unit = { VoyahRootModule.radioLabelsStatus(activity, it) },
        change: (Boolean, VoyahRootModule.Callback) -> Unit = { value, reply -> VoyahRootModule.setRadioLabels(activity, value, reply) },
    ) {
        val preferences = activity.getSharedPreferences("voyah_radio_labels", 0)
        var enabled = preferences.getBoolean("enabled", false)
        var busy = false
        var painting = false
        lateinit var row: SettingsWidgets.SwitchRowResult
        fun paint() {
            painting = true
            row.switch.isChecked = enabled
            row.switch.isEnabled = !busy
            painting = false
        }
        fun perform(showError: Boolean, operation: (VoyahRootModule.Callback) -> Unit) {
            if (busy) return
            busy = true
            paint()
            operation(VoyahRootModule.Callback { ok, report ->
                val state = report.lineSequence().firstOrNull { it.startsWith("RADIO_LABELS_ENABLED=") }
                    ?.substringAfter('=')
                if (state == "0" || state == "1") {
                    enabled = state == "1"
                    preferences.edit().putBoolean("enabled", enabled).apply()
                }
                busy = false
                if (!activity.isDestroyed && !activity.isFinishing) {
                    paint()
                    if (!ok && showError) AlertDialog.Builder(activity)
                        .setTitle(R.string.settings_radio_labels_title).setMessage(report)
                        .setPositiveButton(android.R.string.ok, null).show()
                }
            })
        }
        row = SettingsWidgets.createSwitchRow(
            activity, activity.getString(R.string.settings_radio_labels_title),
            activity.getString(R.string.settings_radio_labels_description), enabled, theme,
            onChanged = { value -> if (!painting && !busy) perform(true) { change(value, it) } },
        )
        parent.addView(row.rowView)
        row.rowView.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: android.view.View) { perform(false, status) }
            override fun onViewDetachedFromWindow(view: android.view.View) = Unit
        })
        paint()
    }
}
