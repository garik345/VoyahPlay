package com.shilapi.xcertplay.voyah

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*
import com.shilapi.xcertplay.settings.SettingsTheme

/** Shared categories keep screen, sound and vehicle controls out of Advanced. */
class VoyahSettingsMenu(private val a: Activity, parent: LinearLayout, private val theme: SettingsTheme) {
    enum class Tab(val title: String) { OVERVIEW("Обзор"), CONNECTION("Подключение"), SCREEN("Экран"), SOUND("Звук"),
        CAR("Автомобиль"), CLUSTER("Приборная панель"), LANGUAGE("Язык"), DIAGNOSTICS("Диагностика"), ADVANCED("Дополнительно") }
    private val prefs=a.getSharedPreferences("voyah_settings_menu",0)
    private var selected=Tab.entries.firstOrNull { it.name==prefs.getString("tab",null) } ?: Tab.OVERVIEW
    private val pages=Tab.entries.associateWith { LinearLayout(a).apply { orientation=LinearLayout.VERTICAL } }
    private val buttons=linkedMapOf<Tab,Button>()
    init {
        val root=LinearLayout(a).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.TOP }
        val body=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL }
        if(a.resources.configuration.screenWidthDp>=650) {
            val nav=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;setPadding(0,0,theme.dp(a,16),0) }
            for(tab in Tab.entries) {
                val button=Button(a).apply {
                    text=tab.title;isAllCaps=false;textSize=15f;gravity=Gravity.CENTER_VERTICAL
                    setOnClickListener { show(tab) }
                }
                buttons[tab]=button;nav.addView(button,LinearLayout.LayoutParams(-1,theme.dp(a,48)))
            }
            root.addView(nav,LinearLayout.LayoutParams(theme.dp(a,185),-2))
        } else {
            val spinner=Spinner(a).apply {
                adapter=ArrayAdapter(a,android.R.layout.simple_spinner_dropdown_item,Tab.entries.map { it.title })
                setSelection(selected.ordinal)
                onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent:AdapterView<*>?) {}
                    override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) { show(Tab.entries[position]) }
                }
            }
            parent.addView(spinner)
        }
        for((_,page) in pages) body.addView(page)
        root.addView(body,LinearLayout.LayoutParams(0,-2,1f));parent.addView(root)
        show(selected)
    }
    fun page(tab:Tab)=pages.getValue(tab)
    fun show(tab:Tab) {
        selected=tab;prefs.edit().putString("tab",tab.name).apply()
        for((key,page) in pages) page.visibility=if(key==tab) View.VISIBLE else View.GONE
        for((key,button) in buttons) {
            button.setTextColor(if(key==tab) theme.accent else theme.textPrimary)
            button.backgroundTintList=ColorStateList.valueOf(if(key==tab) Color.rgb(36,58,81) else theme.trackOff)
            button.isSelected=key==tab
        }
    }
    companion object {
        fun brand(view: View) {
            if (view is TextView) view.text=caption(view.text.toString())
            if (view is android.view.ViewGroup) for(i in 0 until view.childCount) brand(view.getChildAt(i))
        }
        fun caption(text:String)=text.replace(Regex("(?i)\\bDiPlay\\b"),"VoyahPlay").replace(Regex("(?i)\\bBYD\\b"),"Voyah")
    }
}
