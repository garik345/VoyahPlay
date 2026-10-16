package com.shilapi.xcertplay.voyah.root

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.shilapi.xcertplay.settings.SettingsTheme
import com.shilapi.xcertplay.settings.SettingsWidgets

/** Same switch in home settings and the CarPlay settings window. */
object TweaksFullscreenSettings {
    @JvmStatic
    fun add(activity: Activity, parent: LinearLayout, actions: LinearLayout, theme: SettingsTheme) {
        val handler = Handler(Looper.getMainLooper())
        var updating = false
        var busy = false
        var attached = false
        var details: AlertDialog? = null
        val buttons = mutableListOf<Button>()
        val description = TextView(activity).apply {
            textSize = 14f
            maxLines = 5
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(theme.textSecondary)
            setPadding(0, theme.dp(activity, 8), 0, theme.dp(activity, 8))
        }
        lateinit var row: SettingsWidgets.SwitchRowResult
        fun paint() {
            updating = true
            row.switch.isChecked = VoyahTweaksFullscreen.enabled(activity)
            row.switch.isEnabled = !busy
            buttons.forEach { it.isEnabled = !busy }
            updating = false
            description.text = if (busy) "Ожидаем ответ root-помощника…" else VoyahTweaksFullscreen.lastReport(activity)
        }
        fun result(success: Boolean, text: String) {
            busy = false
            if (activity.isFinishing || activity.isDestroyed || !attached) return
            paint()
            if (!success) details = AlertDialog.Builder(activity)
                .setTitle("Полная ширина через Tweaks")
                .setMessage(text).setPositiveButton("Закрыть", null).show()
        }
        row = SettingsWidgets.createSwitchRow(
            activity, "Полная ширина через Tweaks",
            "Расширяет окно до левого края при включённом «Скрыть панель навигации». Верхняя строка сохраняется. Требуются добавка Tweaks 1.1 и root-помощник 1.1.2.",
            VoyahTweaksFullscreen.enabled(activity), theme,
            onChanged = { value ->
                if (!updating && !busy) {
                    busy = true
                    paint()
                    VoyahTweaksFullscreen.setEnabled(activity, value, ::result)
                }
            },
        )
        parent.addView(row.rowView)
        parent.addView(TextView(activity).apply {
            text = "После установки обновлённого APK один раз выполните root-helper.ps1 -Mode Install. После первичной установки помощник запускается при загрузке мультимедиа, если включён его автозапуск. При смене режима окно VoyahPlay обновляется."
            textSize = 14f
            setTextColor(theme.textSecondary)
        })
        parent.addView(description)
        val repaint = object : Runnable {
            override fun run() {
                if (!attached) return
                paint()
                handler.postDelayed(this, 1000)
            }
        }
        row.rowView.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: android.view.View) {
                attached = true
                handler.post(repaint)
            }
            override fun onViewDetachedFromWindow(view: android.view.View) {
                attached = false
                handler.removeCallbacks(repaint)
                details?.dismiss()
            }
        })
        actions.addView(SettingsWidgets.createCategoryHeader(activity, "Полная ширина через Tweaks", theme))
        fun action(title: String, callback: () -> Unit) {
            val button = Button(activity).apply {
                text = title; isAllCaps = false; textSize = 16f; minHeight = theme.dp(activity, 52)
                setTextColor(theme.textPrimary); backgroundTintList = ColorStateList.valueOf(theme.trackOff)
                setOnClickListener { callback() }
            }
            buttons.add(button)
            actions.addView(button, LinearLayout.LayoutParams(-1, -2))
        }
        action("Проверить полную ширину через Tweaks") {
            if (!busy) {
                busy = true
                paint()
                val dialog = AlertDialog.Builder(activity).setTitle("Полная ширина через Tweaks")
                    .setMessage("Проверяем root-помощник и добавку Tweaks…")
                    .setPositiveButton("Закрыть", null).show()
                details = dialog
                VoyahTweaksFullscreen.status(activity) { success, text ->
                    busy = false
                    if (attached && !activity.isFinishing && !activity.isDestroyed) {
                        paint()
                        if (dialog.isShowing) dialog.setMessage(text)
                    }
                }
            }
        }
        action("Скопировать отчёт полной ширины") {
            (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("VoyahPlay fullscreen", VoyahTweaksFullscreen.lastReport(activity)))
            Toast.makeText(activity, "Отчёт полной ширины скопирован", Toast.LENGTH_SHORT).show()
        }
        paint()
    }
}
