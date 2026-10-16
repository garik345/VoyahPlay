package com.shilapi.xcertplay.voyah.root

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.shilapi.xcertplay.settings.SettingsTheme
import com.shilapi.xcertplay.settings.SettingsWidgets

/** Shared helper and fullscreen settings. The legacy module has no UI or startup path. */
object RootModuleSettings {
    @JvmStatic
    fun add(activity: Activity, parent: LinearLayout, actionsParent: LinearLayout, theme: SettingsTheme) {
        TweaksFullscreenSettings.add(activity, parent, actionsParent, theme)
        val description = TextView(activity).apply {
            textSize = 14f; maxLines = 8
            setTextColor(theme.textSecondary)
            text = "Первичная установка автозапуска: root-helper.ps1 -Mode Install. После неё компьютер при перезагрузках не требуется."
        }
        var attached = false
        var busy = false
        var painting = false
        var installed = false
        var enabled = false
        var dialog: AlertDialog? = null
        val buttons = mutableListOf<Button>()
        lateinit var row: SettingsWidgets.SwitchRowResult
        fun paint() {
            painting = true
            row.switch.isChecked = enabled
            row.switch.isEnabled = (installed || enabled) && !busy
            buttons.forEach { it.isEnabled = !busy }
            painting = false
        }
        fun completed(ok: Boolean, report: String) {
            busy = false
            if (!attached || activity.isFinishing || activity.isDestroyed) return
            installed = ok && report.lineSequence().any { it == "AUTOSTART_INSTALLED=1" }
            enabled = ok && report.lineSequence().any { it == "AUTOSTART_ENABLED=1" }
            description.text = report
            if (dialog?.isShowing == true) dialog?.setMessage(report)
            paint()
        }
        fun perform(title: String, show: Boolean, operation: (Context, VoyahRootModule.Callback) -> Unit) {
            if (busy) return
            busy = true; paint()
            if (show) dialog = AlertDialog.Builder(activity).setTitle(title)
                .setMessage("Ожидаем ответ root-помощника…").setPositiveButton("Закрыть", null).show()
            operation(activity, VoyahRootModule.Callback { ok, report -> completed(ok, report) })
        }
        row = SettingsWidgets.createSwitchRow(
            activity, "Автозапуск root-помощника",
            "Запускает помощник после загрузки мультимедиа. Полная ширина включается отдельным переключателем. При выключенном автозапуске после перезагрузки потребуется ручной запуск через ADB.",
            false, theme, enabled = false,
            onChanged = { value ->
                if (!painting && !busy) perform("Автозапуск root-помощника", true) { context, callback ->
                    VoyahRootModule.setAutoStart(context, value, callback)
                }
            },
        )
        parent.addView(row.rowView)
        parent.addView(description)
        row.rowView.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: android.view.View) {
                attached = true
                perform("Состояние root-помощника", false, VoyahRootModule::helperStatus)
            }
            override fun onViewDetachedFromWindow(view: android.view.View) { attached = false; dialog?.dismiss() }
        })
        actionsParent.addView(SettingsWidgets.createCategoryHeader(activity, "Root-помощник VoyahPlay", theme))
        fun action(title: String, callback: () -> Unit) {
            val button = Button(activity).apply {
                text = title; isAllCaps = false; textSize = 16f; minHeight = theme.dp(activity, 52)
                setTextColor(theme.textPrimary); backgroundTintList = ColorStateList.valueOf(theme.trackOff)
                setOnClickListener { callback() }
            }
            buttons.add(button)
            actionsParent.addView(button, LinearLayout.LayoutParams(-1, -2))
        }
        action("Проверить root из APK") { perform("Проверка root", true) { context, callback ->
            VoyahRootModule.checkRoot(context) { ok, result ->
                if (ok) VoyahRootModule.helperStatus(context) { ready, report -> callback.complete(ready, result + "\n" + report) }
                else callback.complete(false, result)
            }
        } }
        action("Состояние root-помощника и автозапуска") { perform("Root-помощник", true, VoyahRootModule::helperStatus) }
        action("Скопировать отчёт root-помощника") {
            (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("VoyahPlay root helper", VoyahRootModule.lastReport(activity)))
            Toast.makeText(activity, "Отчёт скопирован", Toast.LENGTH_SHORT).show()
        }
        paint()
    }
}
