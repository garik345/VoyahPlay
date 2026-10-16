package com.shilapi.xcertplay.voyah

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.widget.*
import com.shilapi.xcertplay.hud.VoyahTrackOutput
import com.shilapi.xcertplay.settings.SettingsTheme
import com.shilapi.xcertplay.settings.SettingsWidgets

/** The same settings rows and palette as the surrounding start-screen/CarPlay settings. */
object VoyahSettings {
    private fun label(a: Activity, text: String, theme: SettingsTheme) = TextView(a).apply {
        this.text = text; textSize = 14f; setTextColor(theme.textSecondary)
        setPadding(0, theme.dp(a, 8), 0, theme.dp(a, 8))
    }
    private fun button(a: Activity, title: String, theme: SettingsTheme, enabled: Boolean = true,
                       action: () -> Unit) = Button(a).apply {
        text = title; isAllCaps = false; textSize = 16f; minHeight = theme.dp(a, 52)
        setTextColor(theme.textPrimary); backgroundTintList = ColorStateList.valueOf(theme.trackOff)
        isEnabled = enabled; setOnClickListener { action() }
    }
    @JvmStatic fun addTracks(a: Activity, parent: LinearLayout, theme: SettingsTheme) {
        parent.addView(SettingsWidgets.createSwitchRow(a, "Названия треков на приборной панели",
            "Название и исполнитель при смене трека, до 30 секунд.", VoyahTrackOutput.enabled(a), theme,
            onChanged = { VoyahTrackOutput.setEnabled(a, it) }).rowView)
        fun slider(title: String, max: Int, progress: Int, describe: (Int) -> String, save: (Int) -> Unit) {
            val text = label(a, "$title: ${describe(progress)}", theme)
            parent.addView(text)
            parent.addView(SeekBar(a).apply {
                this.max = max; this.progress = progress
                progressTintList = ColorStateList.valueOf(theme.accent)
                thumbTintList = ColorStateList.valueOf(theme.accent)
                contentDescription = title
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, value: Int, user: Boolean) {
                        text.text = "$title: ${describe(value)}"
                    }
                    override fun onStartTrackingTouch(bar: SeekBar) {}
                    override fun onStopTrackingTouch(bar: SeekBar) { save(bar.progress) }
                })
            })
        }
        slider("Скорость прокрутки", 26, (1500 - VoyahTrackOutput.scrollInterval(a)) / 50,
            { "${1500 - it * 50} мс на символ · вправо быстрее" },
            { VoyahTrackOutput.setScroll(a, VoyahTrackOutput.visibleCharacters(a), 1500 - it * 50) })
        slider("Символов одновременно", 24, VoyahTrackOutput.visibleCharacters(a) - 6,
            { (it + 6).toString() },
            { VoyahTrackOutput.setScroll(a, it + 6, VoyahTrackOutput.scrollInterval(a)) })
        parent.addView(label(a, "Изменения сохраняются сразу. Значок трубки остаётся; обнаруженный звонок CarPlay прерывает строку.", theme))
    }
    @JvmStatic fun showTracks(a: Activity) {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,24); setBackgroundColor(Color.rgb(20,28,40)) }
        addTracks(a, box, SettingsTheme.CARD)
        AlertDialog.Builder(a).setTitle("Музыка на приборной панели").setView(ScrollView(a).apply { addView(box) })
            .setPositiveButton("Закрыть", null).show()
    }
    @JvmStatic @JvmOverloads fun addSigns(a: Activity, parent: LinearLayout, theme: SettingsTheme, actionsParent: LinearLayout = parent) {
        Controls(a, parent, theme, actionsParent)
    }
    private class Controls(val a: Activity, parent: LinearLayout, val theme: SettingsTheme, actionsParent: LinearLayout) {
        val actions = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        var values: Map<String, Int> = emptyMap()
        var message = "Чтение состояния…"
        var busy = true
        var attached = false
        var ticket: VoyahControls.Ticket? = null
        val history = StringBuilder()
        init {
            parent.addView(root)
            actionsParent.addView(actions)
            root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    attached = true; query("sign-read")
                }
                override fun onViewDetachedFromWindow(v: View) { attached = false; ticket?.cancel(); ticket = null }
            })
            render()
            if (root.isAttachedToWindow) { attached = true; query("sign-read") }
        }
        fun query(command: String, side: Int = 0, function: Int = 0, value: Int = 0) {
            busy = true; message = "Чтение / выполнение…"; render()
            ticket = VoyahControls.request(a, command, side, function, value) { state, result ->
                if (attached && !a.isFinishing && !a.isDestroyed) {
                    values = state; message = result; busy = false
                    history.append("${System.currentTimeMillis()} $command side=$side function=$function value=$value\n$result\n$state\n\n")
                    if (history.length > 24000) history.delete(0, history.length - 24000)
                    render()
                }
            }
        }
        fun value(name: String) = values[name] ?: -1
        fun knownSwitch(name: String) = value(name) in 1..2
        fun switch(title: String, description: String, field: String, onValue: Int, command: String, enabled: Boolean = true) {
            root.addView(SettingsWidgets.createSwitchRow(a, title, description, value(field) == onValue,
                theme, enabled = !busy && knownSwitch(field) && enabled, onChanged = {
                    query(command, value = if (it) onValue else 3 - onValue)
                }).rowView)
        }
        fun render() {
            root.removeAllViews()
            renderSigns()
            root.addView(label(a, message, theme))
            actions.removeAllViews()
            actions.addView(button(a, "Обновить состояние TSR / ISA", theme, !busy) { query("sign-read") })
            actions.addView(button(a, "Скопировать отчёт TSR / ISA", theme) {
                (a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("VoyahPlay controls", history.toString()))
                Toast.makeText(a, "Отчёт скопирован", Toast.LENGTH_SHORT).show()
            })
        }
        fun renderSigns() {
            switch("TSR — дорожные знаки", "Штатное распознавание дорожных знаков.", "TSR", 1, "sign-tsr")
            switch("ISA/ISLC — ограничения скорости", "Режимы 2 и 3 связаны с корректировкой скорости круиза.", "ISA", 1, "sign-isa")
            val modes = listOf(4 to "Только распознавание", 3 to "Коррекция круиза с подтверждением", 2 to "Автоматическая коррекция круиза")
            val mode = value("MODE")
            val choice = SettingsWidgets.createChoiceRow(a, "Режим ISA/ISLC", modes, mode, theme) {
                if (attached && !busy) query("sign-mode", value = it)
            }
            val canChangeMode = !busy && mode in 2..4 && knownSwitch("ISA")
            for (i in 0 until choice.radioGroup.childCount) choice.radioGroup.getChildAt(i).isEnabled = canChangeMode
            root.addView(choice.container)
            switch("Предупреждение о превышении", "Способ предупреждения зависит от прошивки автомобиля.", "WARNING", 2, "sign-warning", value("ISA") == 1)
            root.addView(label(a, "Читается кэш автомобиля. Неизвестные значения не считаются выключенным состоянием. При смене режима активная ISA временно выключается; повторное включение — после подтверждения режима.", theme))
            root.addView(label(a, "TSR: ${value("TSR_STATUS")} · ISA: ${value("ISA_STATUS")} · ограничение: ${value("SPEED")} · единицы: ${value("UNIT")} (сырые значения)", theme))
        }
    }
}
