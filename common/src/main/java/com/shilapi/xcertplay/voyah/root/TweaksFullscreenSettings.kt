package com.shilapi.xcertplay.voyah.root

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import com.shilapi.xcertplay.settings.SettingsTheme
import com.shilapi.xcertplay.settings.SettingsWidgets

/** Geometry follows the screen's navigation-bar switch; only service actions live in Advanced. */
object TweaksFullscreenSettings {
    @JvmStatic fun add(activity: Activity,parent: LinearLayout,actions: LinearLayout,theme: SettingsTheme) {
        actions.addView(SettingsWidgets.createCategoryHeader(activity,"Границы окна VoyahPlay",theme))
        var busy=false
        var dialog:AlertDialog?=null
        val status=Button(activity).apply {
            text="Проверить расширение окна";isAllCaps=false;setTextColor(theme.textPrimary)
            backgroundTintList=ColorStateList.valueOf(theme.trackOff)
            setOnClickListener {
                if(!busy) {
                    busy=true;isEnabled=false
                    dialog=AlertDialog.Builder(activity).setTitle("Границы окна VoyahPlay")
                        .setMessage("Проверка root-помощника…").setPositiveButton("Закрыть",null).show()
                    VoyahTweaksFullscreen.status(activity) { _,report ->
                        busy=false;isEnabled=true
                        if(!activity.isFinishing && !activity.isDestroyed) dialog?.setMessage(report)
                    }
                }
            }
        }
        actions.addView(status)
        actions.addView(Button(activity).apply {
            text="Скопировать отчёт границ окна";isAllCaps=false;setTextColor(theme.textPrimary)
            backgroundTintList=ColorStateList.valueOf(theme.trackOff)
            setOnClickListener {
                (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
                    ClipData.newPlainText("VoyahPlay window",VoyahTweaksFullscreen.lastReport(activity)))
                Toast.makeText(activity,"Отчёт скопирован",Toast.LENGTH_SHORT).show()
            }
        })
        status.addOnAttachStateChangeListener(object:android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view:android.view.View) {}
            override fun onViewDetachedFromWindow(view:android.view.View) { dialog?.dismiss() }
        })
    }
}
