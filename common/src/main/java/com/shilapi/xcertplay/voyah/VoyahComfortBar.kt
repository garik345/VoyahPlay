package com.shilapi.xcertplay.voyah

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import com.shilapi.xcertplay.settings.SettingsTheme
import com.shilapi.xcertplay.settings.SettingsWidgets

/** Car controls own a separate area; they never receive or synthesize CarPlay touches. */
class VoyahComfortBar(private val activity: Activity, private val overlay: FrameLayout, private val openSettings: () -> Unit) : LinearLayout(activity) {
    companion object {
        const val HEIGHT_PX = 109 // Rounded 75% of the previous 145 physical pixels.
        private const val MASTER = "comfort_bar_enabled"
        val IDS = listOf("light", "wheel", "heat_l", "vent_l", "mass_l", "aroma", "mass_r", "vent_r", "heat_r")
        private val TITLES = listOf("Свет", "Руль", "Подогрев · В", "Вентиляция · В", "Массаж · В", "Аромат", "Массаж · П", "Вентиляция · П", "Подогрев · П")
        private fun activeColor(id: String, variant: Int): Int = when {
            id == "wheel" || id.startsWith("heat") -> Color.rgb(204, 85, 0)
            id.startsWith("vent") -> Color.rgb(33, 150, 243)
            id.startsWith("mass") || id.startsWith("program") -> Color.rgb(171, 71, 188)
            id == "light" || id == "rear_fog" || id == "auto_high" -> Color.rgb(76, 175, 80)
            id.startsWith("aroma") -> when(variant) {
                1 -> Color.rgb(76, 175, 80)
                2 -> Color.rgb(171, 71, 188)
                3 -> Color.rgb(33, 150, 243)
                else -> Color.rgb(113, 215, 255)
            }
            else -> Color.rgb(113, 215, 255)
        }
        private fun prefs(c: Context) = c.getSharedPreferences("voyah_vehicle", Context.MODE_PRIVATE)
        @JvmStatic fun reservedHeight(c: Context): Int = if (prefs(c).getBoolean(MASTER, false)) HEIGHT_PX else 0
        @JvmStatic fun addSettings(a: Activity, parent: LinearLayout, theme: SettingsTheme) {
            parent.addView(SettingsWidgets.createSwitchRow(a, "Нижняя панель автомобиля",
                "Высота 109 px. Удержание «Руль» открывает настройки, «Свет» — дополнительные кнопки. При скрытой панели включите её на главной странице VoyahPlay.", prefs(a).getBoolean(MASTER, false), theme,
                onChanged = { prefs(a).edit().putBoolean(MASTER, it).apply() }).rowView)
            IDS.forEachIndexed { i, id ->
                if(id == "light") return@forEachIndexed
                parent.addView(SettingsWidgets.createSwitchRow(a, TITLES[i], "Показывать кнопку на нижней панели",
                    prefs(a).getBoolean("comfort_$id", true), theme,
                    onChanged = { prefs(a).edit().putBoolean("comfort_$id", it).apply() }).rowView)
            }
        }
    }
    private data class Cell(val root: LinearLayout, val icon: Icon, val status: TextView,
        var lastLit: Boolean? = null)
    private data class Command(val name: String, val side: Int, val function: Int, val target: Int)
    private var queued: Command? = null
    private var commandPending = false
    private var lastRead = 0L
    private val extraCells = linkedMapOf<String, Cell>()
    private val cells = linkedMapOf<String, Cell>()
    private val main = Handler(Looper.getMainLooper())
    private var values: Map<String, Int> = emptyMap()
    private var active = false
    private var pending = false
    private var generation = 0
    private var ticket: VoyahControls.Ticket? = null
    private var popup: LinearLayout? = null
    private var requestedAutoHigh = -1
    private var requestedLight = -1
    private var lightSent = false
    private var observedLight = -1
    private val lightStatus = VoyahLightStatus(activity) { mode ->
        observedLight = mode
        // Once feedback arrives, it replaces the last requested mode, including external changes.
        lightSent = false
        render()
    }
    private val poll = Runnable { request("comfort-read") }
    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(5, 5, 5, 5)
        setBackgroundColor(Color.rgb(16, 22, 30))
        IDS.forEachIndexed { i, id ->
            val cell = makeCell(id, TITLES[i])
            val box = cell.root
            addView(box, LayoutParams(0, -1, 1f).apply { setMargins(3, 0, 3, 0) })
            cells[id] = cell
            box.setOnClickListener { if(!commandPending) click(id) }
            if(id == "wheel") box.setOnLongClickListener {
                dismissExtra(); openSettings(); true
            }
            if (id.startsWith("mass") || id == "aroma" || id == "light") box.setOnLongClickListener {
                if (active && !commandPending) showExtra(id)
                true // Do not also execute the short-click command on release.
            }
        }
        refreshVisibility()
    }
    private fun makeCell(id: String, title: String): Cell {
        val box = LinearLayout(activity).apply {
            orientation = VERTICAL; gravity = Gravity.CENTER; isClickable = true; isFocusable = true
            setPadding(3, 3, 3, 3)
        }
        val icon = Icon(activity, id)
        box.addView(icon, LayoutParams(38, 34))
        box.addView(TextView(activity).apply {
            text = title; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, if(id in listOf("rear_fog", "auto_high")) 15f else 17f); maxLines = if(id in listOf("rear_fog", "auto_high")) 3 else 1
        }, LayoutParams(-1, if(id in listOf("rear_fog", "auto_high")) 54 else 21))
        val status = TextView(activity).apply {
            gravity = Gravity.CENTER; setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 15f)
            maxLines = 2; setTextColor(Color.LTGRAY)
        }
        box.addView(status, LayoutParams(-1, 30))
        return Cell(box, icon, status)
    }
    private fun paint(cell: Cell, text: String, lit: Boolean, variant: Int, known: Boolean) {
        if (cell.status.text.toString() != text) cell.status.text = text
        val tint = activeColor(cell.icon.id, if(cell.icon.id.startsWith("aroma")) value("AROMA_TASTE") else variant)
        cell.status.setTextColor(if(lit) tint else Color.LTGRAY)
        if (cell.lastLit != lit || (cell.icon.id == "aroma" && cell.icon.variant != variant)) {
            cell.lastLit = lit
            cell.root.background = GradientDrawable().apply {
                cornerRadius = 12f; setColor(if(lit && cell.icon.id == "aroma") Color.rgb(Color.red(tint)/4, Color.green(tint)/4, Color.blue(tint)/4) else if(lit) Color.rgb(28,53,69) else Color.rgb(29,36,46))
            }
        }
        if (cell.icon.lit != lit || cell.icon.variant != variant) {
            cell.icon.lit = lit; cell.icon.variant = variant; cell.icon.invalidate()
        }
        cell.root.isEnabled = active && !commandPending && known
        // An ordinary background read must never blink or dim the panel.
        val alpha = if(active && known) 1f else .5f
        if(cell.root.alpha != alpha) cell.root.alpha = alpha
    }
    fun refreshVisibility() {
        dismissExtra()
        IDS.forEach { cells.getValue(it).root.visibility = if (it == "light" || prefs(activity).getBoolean("comfort_$it", true)) View.VISIBLE else View.GONE }
        visibility = if (reservedHeight(activity) > 0) View.VISIBLE else View.GONE
        render()
    }
    fun setHostActive(value: Boolean) {
        val wanted = value && visibility == View.VISIBLE
        if (active == wanted) return
        active = wanted; generation++; main.removeCallbacks(poll)
        lightStatus.stop(); requestedAutoHigh = -1; observedLight = -1; requestedLight = -1; lightSent = false
        ticket?.cancel(); ticket = null; pending = false; commandPending = false; queued = null; lastRead = 0L; values = emptyMap(); dismissExtra(); render()
        if (active) { lightStatus.start(); main.post(poll) }
    }
    fun dispose() { setHostActive(false); main.removeCallbacksAndMessages(null); dismissExtra() }
    private fun value(key: String) = values[key] ?: -1
    private fun key(id: String) = id.uppercase(java.util.Locale.ROOT)
    private fun seatLevel(id: String): Int {
        val name = key(id); val sw = value(name)
        return if (sw == 1) 0 else if (sw == 2) value(name.substringBeforeLast('_') + "_LEVEL_" + name.substringAfterLast('_')).takeIf { it in 1..3 } ?: -1 else -1
    }
    private fun render() {
        for ((id, cell) in cells) {
            var enabled = false
            val text = when {
                id == "light" -> {
                    val names = listOf("Выключен", "Габариты", "Ближний", "Авто")
                    enabled = observedLight in 1..3
                    if (observedLight in 0..3) names[observedLight]
                    else if (lightSent) "${names[requestedLight]} · запрос"
                    else "Режим неизвестен"
                }
                id == "wheel" -> when(value("WHEEL")) { 1 -> "Выключен"; 2 -> { enabled = true; "Включён" }; else -> "Нет данных" }
                id == "aroma" -> {
                    val taste=value("AROMA_TASTE"); enabled=value("AROMA_SWITCH")==2
                    if(taste in 1..3) "${listOf("Бамбук", "Пион", "Туман")[taste-1]} · ${if(enabled) "вкл" else if(value("AROMA_SWITCH")==1) "выкл" else "—"}" else "Нет данных"
                }
                else -> when(val level=seatLevel(id)) { 0 -> "Выключен"; in 1..3 -> {enabled=true; "${"●".repeat(level)}${"○".repeat(3-level)}  $level/3"}; else -> "Нет данных" }
            }
            val known=when(id) { "light" -> true; "wheel" -> value("WHEEL") in 1..2; "aroma" -> value("AROMA_TASTE") in 1..3; else -> seatLevel(id)>=0 }
            paint(cell, text, enabled, if(id=="aroma") value("AROMA_TASTE") else if(id=="light") observedLight else 0, known)
            if(id == "light" || id == "wheel") cell.root.isEnabled = active
            cell.root.contentDescription = "${TITLES[IDS.indexOf(id)]}. $text"
        }
        if (extraCells.containsKey("rear_fog") && observedLight != 2) dismissExtra()
        renderExtra()
    }
    private fun click(id: String) {
        dismissExtra()
        when(id) {
            "light" -> {
                // Use OEM feedback when available; a sent pulse is not a confirmed status.
                val current = if (observedLight in 0..3) observedLight else requestedLight
                val target=(current+1)%4
                request("light-mode", target=target)
            }
            "wheel" -> if(value("WHEEL") in 1..2) request("wheel-toggle")
            "aroma" -> request("aroma-taste")
            else -> request("seat-cycle", side=if(id.endsWith("_l")) 0 else 1, function=when(id.substringBefore('_')) { "heat" -> 0; "vent" -> 1; else -> 2 })
        }
    }
    private fun request(command: String, side: Int=0, function: Int=0, target: Int=0) {
        if(!active) return
        if(pending) {
            // Preserve one tap arriving during a cache poll, without duplicating commands.
            if(command != "comfort-read" && !commandPending && queued == null) queued=Command(command,side,function,target)
            return
        }
        main.removeCallbacks(poll); pending=true; commandPending=command!="comfort-read"
        if(commandPending) render()
        val token=generation
        ticket=VoyahControls.request(activity,command,side,function,target) { state, message ->
            if(token==generation && active) {
                pending=false; commandPending=false; ticket=null
                val readOk = message == "Кэш автомобиля прочитан." || message == "Команда подтверждена кэшем автомобиля." || message.startsWith("Запрос света отправлен") || message.startsWith("Запрос автодальнего отправлен")
                if(state.isNotEmpty() && readOk) {
                    values=state.toMap(); lastRead=android.os.SystemClock.elapsedRealtime()
                } else if(android.os.SystemClock.elapsedRealtime()-lastRead>10000L) {
                    values=emptyMap()
                }
                if(command=="light-mode" && message.startsWith("Запрос света отправлен")) { requestedLight=target; lightSent=true }
                if(command=="auto-high" && message.startsWith("Запрос автодальнего отправлен")) requestedAutoHigh=target
                render()
                // Success is visible in the button. Keep error messages, not success toasts.
                if(command!="comfort-read" && !readOk) Toast.makeText(activity,message,Toast.LENGTH_LONG).show()
                val next=queued; queued=null
                if(next!=null) request(next.name,next.side,next.function,next.target)
                else main.postDelayed(poll,2000)
            }
        }
    }
    private fun renderExtra() {
        for((id,cell) in extraCells) {
            val current=when(id) {
                "aroma_intensity" -> if(value("AROMA_SWITCH")==1) 0 else if(value("AROMA_SWITCH")==2) value("AROMA_INTENSITY") else -1
                "aroma_time" -> value("AROMA_TIME")
                "rear_fog", "auto_high" -> -1
                "program_l" -> value("MASS_PROGRAM_L")
                else -> value("MASS_PROGRAM_R")
            }
            val known=if(id=="auto_high") true else if(id=="aroma_time") current in 0..2 else if(id=="aroma_intensity") current in 0..3 else current in 1..2
            val text=if(!known) "Нет данных" else when(id) {
                "auto_high" -> when(requestedAutoHigh) { 1 -> "Вкл · запрос"; 2 -> "Выкл · запрос"; else -> "Режим неизвестен" }
                "aroma_intensity" -> if(current==0) "Выключен" else "${"●".repeat(current)}${"○".repeat(3-current)}  $current/3"
                "aroma_time" -> when(current) { 0->"Без таймера"; 1->"30 мин"; else->"60 мин" }
                else -> if(current==1) "Волны" else "Ролики"
            }
            val lit=when(id) {
                "rear_fog", "auto_high" -> false
                "program_l" -> value("MASS_L")==2
                "program_r" -> value("MASS_R")==2
                else -> value("AROMA_SWITCH")==2
            }
            paint(cell,text,lit,current,known)
            if(id == "light" || id == "wheel") cell.root.isEnabled = active
            cell.root.contentDescription = "$id. $text"
        }
    }
    private fun showExtra(id: String) {
        dismissExtra()
        val anchor=cells.getValue(id).root
        val extra=LinearLayout(activity).apply {
            orientation=HORIZONTAL; elevation=20f; isClickable=true; setPadding(5,5,5,5)
            background=GradientDrawable().apply { cornerRadius=12f; setColor(Color.rgb(16,22,30)) }
        }
        extra.setOnClickListener { dismissExtra() } // Blank popup area.
        val cellWidth = if(id=="light") maxOf(anchor.width, 220) else anchor.width
        val cellHeight = if(id=="light") 145 else HEIGHT_PX-10
        fun add(key:String, title:String, action:()->Unit) {
            val cell=makeCell(key,title)
            extraCells[key]=cell
            extra.addView(cell.root,LayoutParams(cellWidth,-1).apply { setMargins(3,0,3,0) })
            cell.root.setOnClickListener { dismissExtra(); action() }
        }
        if(id=="aroma") {
            add("aroma_intensity","Интенсивность") { request("aroma-intensity") }
            add("aroma_time","Время работы") { request("aroma-time") }
        } else if(id=="light") {
            // Rear fog stays disabled until an OEM command is verified.
            if(observedLight==2) add("rear_fog","Задний противотуманный фонарь") {}
            add("auto_high","Автоматический дальний свет") {
                request("auto-high", target=if(requestedAutoHigh==1) 2 else 1)
            }
        } else {
            val side=if(id.endsWith("_l")) 0 else 1
            add(if(side==0) "program_l" else "program_r","Программа") { request("seat-program-cycle",side=side) }
        }
        val a=IntArray(2);val b=IntArray(2);anchor.getLocationInWindow(a);overlay.getLocationInWindow(b)
        val stacked=(cellWidth+6)*extraCells.size+10>overlay.width
        val popupWidth=if(stacked) cellWidth+16 else (cellWidth+6)*extraCells.size+10
        val popupHeight=if(stacked) cellHeight*extraCells.size+10 else cellHeight+10
        if(stacked) {
            extra.orientation=VERTICAL
            extraCells.values.forEach { it.root.layoutParams=LayoutParams(cellWidth,cellHeight).apply { setMargins(3,0,3,0) } }
        }
        val x=(a[0]-b[0]+anchor.width/2-popupWidth/2).coerceIn(0,(overlay.width-popupWidth).coerceAtLeast(0))
        popup=extra
        overlay.addView(extra,FrameLayout.LayoutParams(popupWidth,popupHeight,Gravity.BOTTOM or Gravity.LEFT).apply {
            leftMargin=x;bottomMargin=HEIGHT_PX+8
        })
        renderExtra()
    }
    fun dismissExtra() { popup?.let { overlay.removeView(it) }; popup=null; extraCells.clear() }
    fun afterTouch(event: MotionEvent) {
        // Android may post performClick after dispatchTouchEvent has returned.
        // Removing the popup on ACTION_UP cancels that pending click.
        if(event.actionMasked == MotionEvent.ACTION_CANCEL) dismissExtra()
    }
    /** Called before Activity dispatch: dismiss outside, but never consume the CarPlay gesture. */
    fun beforeTouch(event: MotionEvent) {
        val extra=popup ?: return
        if(event.actionMasked!=MotionEvent.ACTION_DOWN && event.actionMasked!=MotionEvent.ACTION_POINTER_DOWN) return
        val position=IntArray(2);extra.getLocationOnScreen(position)
        if(event.rawX<position[0] || event.rawX>=position[0]+extra.width || event.rawY<position[1] || event.rawY>=position[1]+extra.height) dismissExtra()
    }
    private class Icon(c:Context,val id:String):View(c) {
        var lit=false; var variant=0
        private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas:Canvas) {
            p.color=if(lit) activeColor(id,variant) else Color.LTGRAY;p.strokeWidth=2.2f;p.style=Paint.Style.STROKE
            canvas.save();canvas.scale(width/48f,height/46f)
            // Mirror passenger seat artwork only; labels and level indicators stay readable.
            if (id in listOf("heat_r", "vent_r", "mass_r", "program_r")) canvas.scale(-1f, 1f, 24f, 23f)
            when {
                id=="aroma_time" -> { canvas.drawCircle(24f,23f,17f,p);canvas.drawLine(24f,23f,24f,11f,p);canvas.drawLine(24f,23f,34f,28f,p) }
                id=="aroma_intensity" -> { for(i in 1..3) { p.style=if(i<=variant) Paint.Style.FILL else Paint.Style.STROKE;canvas.drawRect((7+i*9).toFloat(),(36-i*8).toFloat(),(12+i*9).toFloat(),38f,p) } }
                id=="wheel" -> { canvas.drawCircle(24f,23f,17f,p);canvas.drawCircle(24f,23f,5f,p);canvas.drawLine(7f,19f,19f,23f,p);canvas.drawLine(29f,23f,41f,19f,p);canvas.drawLine(24f,28f,24f,40f,p) }
                id=="rear_fog" || id=="auto_high" -> {
                    canvas.drawArc(6f,11f,29f,35f,90f,180f,false,p)
                    canvas.drawLine(17f,11f,17f,35f,p)
                    for(y in listOf(13f,23f,33f)) canvas.drawLine(25f,y,43f,y,p)
                    if(id=="auto_high") { p.textSize=16f;p.style=Paint.Style.FILL;p.textAlign=Paint.Align.CENTER;canvas.drawText("A",17f,29f,p) }
                    else { val path=Path();path.moveTo(33f,7f);path.cubicTo(23f,17f,43f,27f,33f,39f);canvas.drawPath(path,p) }
                }
                id=="light" -> { canvas.drawArc(19f,11f,42f,35f,-90f,180f,false,p);canvas.drawLine(30f,11f,30f,35f,p);for(y in listOf(13f,22f,31f))canvas.drawLine(5f,y,23f,y+if(variant==2) 4 else 0,p);if(variant==3){p.textSize=14f;canvas.drawText("A",32f,27f,p)};if(variant==0)canvas.drawLine(5f,40f,42f,5f,p) }
                id=="aroma" -> { val path=Path();path.moveTo(24f,4f);path.cubicTo(3f,26f,8f,39f,24f,41f);path.cubicTo(42f,39f,44f,25f,24f,4f);canvas.drawPath(path,p);p.textSize=21f;p.style=Paint.Style.FILL;p.textAlign=Paint.Align.CENTER;canvas.drawText(if(variant in 1..3) variant.toString() else "?",24f,32f,p) }
                else -> { canvas.drawLine(11f,7f,14f,30f,p);canvas.drawLine(14f,30f,36f,30f,p);canvas.drawLine(36f,30f,40f,40f,p);canvas.drawLine(13f,36f,32f,36f,p)
                    if(id.startsWith("program") && variant==1) { for(y in listOf(13f,22f)){val path=Path();path.moveTo(18f,y);path.cubicTo(23f,y-7,29f,y+7,36f,y);canvas.drawPath(path,p)} }
                    else if(id.startsWith("mass") || id.startsWith("program")) for(y in listOf(12f,21f))canvas.drawCircle(24f,y,4f,p)
                    else if(id.startsWith("vent")){for(angle in listOf(0f,120f,240f)){canvas.save();canvas.rotate(angle,28f,17f);canvas.drawOval(26f,7f,31f,17f,p);canvas.restore()}}
                    else for(x in listOf(22f,29f,36f)){val path=Path();path.moveTo(x,25f);path.cubicTo(x-6,20f,x+6,15f,x,8f);canvas.drawPath(path,p)}
                }
            }
            canvas.restore()
        }
    }
}
