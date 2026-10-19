package com.shilapi.xcertplay.voyah

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.*
import com.shilapi.xcertplay.settings.SettingsTheme
import com.shilapi.xcertplay.settings.SettingsWidgets

object VoyahComfortSettings {
    @JvmStatic fun add(a: Activity, parent: LinearLayout, theme: SettingsTheme) {
        parent.addView(SettingsWidgets.createSwitchRow(a,"Кнопки управления автомобилем",
            "CarPlay занимает область между панелями. Уровни: 3 → 2 → 1 → выключено.",VoyahComfortLayout.enabled(a),theme,
            onChanged={ VoyahComfortLayout.setEnabled(a,it) }).rowView)
        val modes=VoyahComfortLayout.Mode.entries
        fun <T> choice(title: String, options: List<Pair<T, String>>, current: T, save: (T) -> Unit) {
            var selected = current
            val row = Button(a).apply {
                text = "$title · ${options.firstOrNull { it.first == selected }?.second.orEmpty()}"
                isAllCaps = false
                minHeight = theme.dp(a, 60)
                setTextColor(theme.textPrimary)
                backgroundTintList = ColorStateList.valueOf(theme.trackOff)
            }
            row.setOnClickListener {
                AlertDialog.Builder(a).setTitle(title).setSingleChoiceItems(
                    options.map { it.second }.toTypedArray(), options.indexOfFirst { it.first == selected }) { dialog, index ->
                    selected = options[index].first
                    save(selected)
                    row.text = "$title · ${options[index].second}"
                    dialog.dismiss()
                }.setNegativeButton(android.R.string.cancel, null).show()
            }
            parent.addView(row, LinearLayout.LayoutParams(-1,-2))
        }
        choice("Расположение кнопок", modes.map { it to it.title }, VoyahComfortLayout.mode(a)) {
            VoyahComfortLayout.setMode(a,it)
        }
        fun action(title: String, block: ()->Unit) = Button(a).apply {
            text=title;isAllCaps=false;setTextColor(theme.textPrimary);backgroundTintList=ColorStateList.valueOf(theme.trackOff)
            setOnClickListener { block() }
        }
        for(edge in VoyahComfortLayout.Edge.entries) parent.addView(action("${edge.title}: кнопки и порядок") { editOrder(a,edge,theme) })
        val options=VoyahComfortLayout.settingsKeys.map { it to VoyahComfortLayout.titles.getValue(it) }
        choice("Открывать настройки удержанием",options,VoyahComfortLayout.settingsKey(a)) {
            VoyahComfortLayout.setSettingsKey(a,it)
        }
        parent.addView(TextView(a).apply {
            text="Выбранная кнопка остаётся доступной в активной панели. Удержание света открывает дополнительные функции, массажа — программу, ароматизации — интенсивность и время."
            textSize=14f;setTextColor(theme.textSecondary);setPadding(0,theme.dp(a,8),0,theme.dp(a,8))
        })
    }
    private fun editOrder(a: Activity, edge: VoyahComfortLayout.Edge, theme: SettingsTheme) {
        val shown=VoyahComfortLayout.visible(a)
        val selected=(shown[edge] ?: VoyahComfortLayout.order(a,edge)).toMutableList()
        val list=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;setPadding(16,8,16,8) }
        fun render() {
            list.removeAllViews()
            val order=selected+VoyahComfortLayout.ids.filter { it !in selected }
            for(id in order) {
                val row=LinearLayout(a).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
                val check=CheckBox(a).apply {
                    text=VoyahComfortLayout.titles.getValue(id);isChecked=id in selected;setTextColor(theme.textPrimary)
                    isEnabled=!(id==VoyahComfortLayout.settingsKey(a) && edge in shown && shown.filterKeys { it!=edge }.values.none { id in it })
                    setOnCheckedChangeListener { _,on -> if(on) selected.add(id) else selected.remove(id);render() }
                }
                row.addView(check,LinearLayout.LayoutParams(0,-2,1f))
                for((label,step) in listOf("↑" to -1,"↓" to 1)) row.addView(Button(a).apply {
                    text=label;isAllCaps=false;val index=selected.indexOf(id);isEnabled=index>=0 && index+step in selected.indices
                    contentDescription="${VoyahComfortLayout.titles[id]} ${if(step<0) "раньше" else "позже"}"
                    setOnClickListener { val from=selected.indexOf(id);val target=from+step;if(from>=0 && target in selected.indices) { selected.removeAt(from);selected.add(target,id);render() } }
                },LinearLayout.LayoutParams(theme.dp(a,52),theme.dp(a,48)))
                list.addView(row)
            }
        }
        render()
        AlertDialog.Builder(a).setTitle(edge.title).setView(ScrollView(a).apply { addView(list) })
            .setPositiveButton("Сохранить") { _,_ -> VoyahComfortLayout.setOrder(a,edge,selected) }
            .setNegativeButton("Отмена",null).show()
    }
}
