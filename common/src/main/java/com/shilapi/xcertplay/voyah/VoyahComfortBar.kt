package com.shilapi.xcertplay.voyah

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import com.shilapi.xcertplay.settings.SettingsTheme

/** Transparent host: only its buttons/popup handle touches; CarPlay owns the remaining rectangle. */
class VoyahComfortBar(private val activity: Activity, private val overlay: FrameLayout, private val openSettings: () -> Unit) : FrameLayout(activity) {
    companion object {
        const val HEIGHT_PX = VoyahComfortLayout.SIZE_PX
        val IDS = VoyahComfortLayout.ids
        @JvmStatic fun reservedHeight(c: Context) = VoyahComfortLayout.insets(c).bottom
        @JvmStatic fun addSettings(a: Activity, parent: LinearLayout, theme: SettingsTheme) = VoyahComfortSettings.add(a,parent,theme)
    }
    private data class Cell(val id: String, val edge: VoyahComfortLayout.Edge, val root: LinearLayout,
        val icon: VoyahControlIcon, val status: TextView)
    private data class Command(val name: String, val side: Int, val function: Int, val target: Int)
    private var queued: Command? = null
    private var commandPending = false
    private var lastRead = 0L
    private val extraCells = linkedMapOf<String, Cell>()
    private val cells = mutableListOf<Cell>()
    private val main = Handler(Looper.getMainLooper())
    private var values: Map<String, Int> = emptyMap()
    private var active = false
    private var pending = false
    private var generation = 0
    private var ticket: VoyahControls.Ticket? = null
    private var popup: LinearLayout? = null
    private var layoutKey = ""
    private var requestedAutoHigh = -1
    private var requestedLight = -1
    private var lightSent = false
    private var observedLight = -1
    private val lightStatus = VoyahLightStatus(activity) { mode ->
        if (active) { observedLight = mode; lightSent = false; render() }
    }
    private val poll = Runnable { request("comfort-read") }
    init { refreshVisibility() }
    fun refreshVisibility() {
        val panels = VoyahComfortLayout.visible(activity)
        val key = panels.toString() + VoyahComfortLayout.settingsKey(activity)
        if (layoutKey != key) {
            layoutKey = key; dismissExtra(); removeAllViews(); cells.clear()
            for ((edge, ids) in panels) {
                val row = LinearLayout(activity).apply {
                    orientation = if(edge==VoyahComfortLayout.Edge.BOTTOM) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                    setPadding(4,4,4,4); setBackgroundColor(Color.rgb(16,22,30))
                }
                val params = when (edge) {
                    VoyahComfortLayout.Edge.BOTTOM -> LayoutParams(-1,HEIGHT_PX,Gravity.BOTTOM)
                    VoyahComfortLayout.Edge.LEFT -> LayoutParams(HEIGHT_PX,-1,Gravity.LEFT)
                    VoyahComfortLayout.Edge.RIGHT -> LayoutParams(HEIGHT_PX,-1,Gravity.RIGHT)
                }
                for (id in ids) {
                    val cell = makeCell(id,edge); cells.add(cell)
                    val cellParams = if(edge==VoyahComfortLayout.Edge.BOTTOM) LinearLayout.LayoutParams(0,-1,1f)
                        else LinearLayout.LayoutParams(-1,0,1f)
                    cellParams.setMargins(2,2,2,2);row.addView(cell.root,cellParams)
                    cell.root.setOnClickListener { if(active && !commandPending) click(id) }
                    cell.root.setOnLongClickListener {
                        if (id == VoyahComfortLayout.settingsKey(activity)) { dismissExtra();openSettings() }
                        else if(active && !commandPending && (id.startsWith("mass") || id=="aroma" || id=="light")) showExtra(cell)
                        true
                    }
                }
                addView(row,params)
            }
        }
        visibility = if(panels.isEmpty()) View.GONE else View.VISIBLE
        render()
    }
    private fun makeCell(id: String, edge: VoyahComfortLayout.Edge): Cell {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL;gravity=Gravity.CENTER;isClickable=true;isFocusable=true
            setPadding(3,2,3,2)
            background=GradientDrawable().apply { cornerRadius=12f;setColor(Color.rgb(29,36,46)) }
        }
        val icon = VoyahControlIcon(activity,id).apply {
            if (edge != VoyahComfortLayout.Edge.BOTTOM) panelScale = VoyahScreenGeometry.SIDE_ICON_SCALE
        }
        box.addView(icon,LinearLayout.LayoutParams(-1,0,1f))
        val title = when {
            id.endsWith("_l") && !id.startsWith("program") -> "Водитель"
            id.endsWith("_r") && !id.startsWith("program") -> "Пассажир"
            id=="aroma_intensity" -> "Интенсивность"
            id=="aroma_time" -> "Время"
            id.startsWith("program") -> "Программа"
            id=="auto_high" -> "Автодальний"
            id=="rear_fog" -> "Задний ПТФ"
            else -> VoyahComfortLayout.titles[id] ?: id
        }
        box.addView(TextView(activity).apply {
            text=title;gravity=Gravity.CENTER;setTextColor(Color.LTGRAY);maxLines=1
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,13f)
        },LinearLayout.LayoutParams(-1,18))
        val status=TextView(activity).apply {
            gravity=Gravity.CENTER;setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,13f)
            setTextColor(Color.LTGRAY);maxLines=1;visibility=View.GONE
        }
        box.addView(status,LinearLayout.LayoutParams(-1,17))
        return Cell(id,edge,box,icon,status)
    }
    private fun paint(cell: Cell, lit: Boolean, variant: Int, level: Int, known: Boolean, detail: String = "") {
        cell.icon.state=VoyahControlIcon.State(lit,variant,level,known)
        if(cell.status.text.toString()!=detail) cell.status.text=detail
        val visible=if(detail.isEmpty()) View.GONE else View.VISIBLE
        if(cell.status.visibility!=visible) cell.status.visibility=visible
        // Polling changes only the icon state; the panel and its backgrounds stay attached.
        cell.root.isEnabled=active
    }
    fun setHostActive(value: Boolean) {
        val wanted=value && visibility==View.VISIBLE
        if(active==wanted) return
        active=wanted;generation++;main.removeCallbacks(poll);lightStatus.stop()
        ticket?.cancel();ticket=null;pending=false;commandPending=false;queued=null;dismissExtra()
        if(android.os.SystemClock.elapsedRealtime()-lastRead>10000L) { values=emptyMap();observedLight=-1 }
        render()
        if(active) { lightStatus.start();main.post(poll) }
    }
    fun dispose() { setHostActive(false);main.removeCallbacksAndMessages(null);dismissExtra() }
    private fun value(key: String)=values[key] ?: -1
    private fun seatLevel(id: String): Int {
        val name=id.uppercase(java.util.Locale.ROOT);val sw=value(name)
        return if(sw==1) 0 else if(sw==2) value(name.substringBeforeLast('_')+"_LEVEL_"+name.substringAfterLast('_')).takeIf { it in 1..3 } ?: -1 else -1
    }
    private fun render() {
        for(cell in cells) {
            val id=cell.id
            val variant=if(id=="light") observedLight else if(id=="aroma") value("AROMA_TASTE") else 0
            val level=if(id.startsWith("heat") || id.startsWith("vent") || id.startsWith("mass")) seatLevel(id) else 0
            val known=when(id) { "light" -> observedLight in 0..3;"wheel" -> value("WHEEL") in 1..2
                "aroma" -> value("AROMA_SWITCH")==1 || value("AROMA_SWITCH")==2 && value("AROMA_TASTE") in 1..3;else -> level>=0 }
            val lit=known && when(id) { "light" -> observedLight in 1..3;"wheel" -> value("WHEEL")==2;"aroma" -> value("AROMA_SWITCH")==2;else -> level in 1..3 }
            paint(cell,lit,variant,level.coerceAtLeast(0),known)
            val state=when {
                !known -> "Нет данных"
                id=="light" -> listOf("Выключен","Габариты","Ближний","Авто")[observedLight]
                id=="aroma" -> if(lit) listOf("Бамбук","Пион","Туман")[variant-1] else "Выключен"
                level>0 -> "Уровень $level из 3"
                lit -> "Включён"
                else -> "Выключен"
            }
            val title=VoyahComfortLayout.titles.getValue(id)
            cell.root.contentDescription="$title. $state" + if(id==VoyahComfortLayout.settingsKey(activity)) ". Удержание: настройки VoyahPlay" else ""
        }
        if(extraCells.containsKey("rear_fog") && observedLight!=2) dismissExtra()
        renderExtra()
    }
    private fun click(id: String) {
        dismissExtra()
        when(id) {
            "light" -> { val current=if(observedLight in 0..3) observedLight else requestedLight;request("light-mode",target=(current+1)%4) }
            "wheel" -> if(value("WHEEL") in 1..2) request("wheel-toggle")
            "aroma" -> if(value("AROMA_TASTE") in 1..3 && value("AROMA_SWITCH") in 1..2) request("aroma-taste")
            else -> if(seatLevel(id)>=0) request("seat-cycle",side=if(id.endsWith("_l")) 0 else 1,
                function=when(id.substringBefore('_')) { "heat"->0;"vent"->1;else->2 })
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
                "program_l" -> value("MASS_PROGRAM_L")
                "program_r" -> value("MASS_PROGRAM_R")
                else -> -1
            }
            val known=when(id) { "auto_high" -> false;"aroma_time" -> current in 0..2;"aroma_intensity" -> current in 0..3;else -> current in 1..2 }
            val lit=known && when(id) { "program_l"->value("MASS_L")==2;"program_r"->value("MASS_R")==2;else->value("AROMA_SWITCH")==2 }
            val detail=when {
                !known -> ""
                id=="aroma_time" -> listOf("Всегда","30 мин","60 мин")[current]
                id.startsWith("program") -> if(current==1) "Волны" else "Ролики"
                else -> ""
            }
            paint(cell,lit,if(id.startsWith("aroma")) value("AROMA_TASTE") else current,current.coerceIn(0,3),known,detail)
            cell.root.isEnabled=active && id!="rear_fog" && (known || id=="auto_high")
            cell.root.contentDescription="$id. ${if(known) detail.ifEmpty { "Уровень $current" } else "Нет данных"}"
        }
    }
    private fun showExtra(anchor: Cell) {
        dismissExtra()
        val extra=LinearLayout(activity).apply {
            orientation=LinearLayout.HORIZONTAL;elevation=20f;isClickable=true;setPadding(4,4,4,4)
            background=GradientDrawable().apply { cornerRadius=12f;setColor(Color.rgb(16,22,30)) }
        }
        extra.setOnClickListener { dismissExtra() }
        val cellWidth=anchor.root.width.coerceAtLeast(64)
        val cellHeight=anchor.root.height.coerceAtLeast(60)
        fun add(key: String, action: ()->Unit) {
            val cell=makeCell(key,anchor.edge);extraCells[key]=cell
            extra.addView(cell.root,LinearLayout.LayoutParams(cellWidth,cellHeight).apply { setMargins(2,0,2,0) })
            cell.root.setOnClickListener { dismissExtra();action() }
        }
        when(anchor.id) {
            "aroma" -> { add("aroma_intensity") { request("aroma-intensity") };add("aroma_time") { request("aroma-time") } }
            "light" -> { if(observedLight==2) add("rear_fog") {};add("auto_high") { request("auto-high",target=if(requestedAutoHigh==1) 2 else 1) } }
            else -> { val side=if(anchor.id.endsWith("_l")) 0 else 1;add(if(side==0) "program_l" else "program_r") { request("seat-program-cycle",side=side) } }
        }
        val a=IntArray(2);val b=IntArray(2);anchor.root.getLocationInWindow(a);overlay.getLocationInWindow(b)
        val stack=anchor.edge!=VoyahComfortLayout.Edge.BOTTOM || (cellWidth+4)*extraCells.size+8>overlay.width
        val popupWidth=if(stack) cellWidth+12 else (cellWidth+4)*extraCells.size+8
        val popupHeight=if(stack) cellHeight*extraCells.size+8 else cellHeight+8
        if(stack) extra.orientation=LinearLayout.VERTICAL
        val x=when(anchor.edge) {
            VoyahComfortLayout.Edge.LEFT -> a[0]-b[0]+anchor.root.width+8
            VoyahComfortLayout.Edge.RIGHT -> a[0]-b[0]-popupWidth-8
            else -> a[0]-b[0]+anchor.root.width/2-popupWidth/2
        }.coerceIn(0,(overlay.width-popupWidth).coerceAtLeast(0))
        val y=(if(anchor.edge==VoyahComfortLayout.Edge.BOTTOM) a[1]-b[1]-popupHeight-8
            else a[1]-b[1]+anchor.root.height/2-popupHeight/2).coerceIn(0,(overlay.height-popupHeight).coerceAtLeast(0))
        popup=extra;overlay.addView(extra,LayoutParams(popupWidth,popupHeight,Gravity.TOP or Gravity.LEFT).apply { leftMargin=x;topMargin=y })
        renderExtra()
    }
    fun dismissExtra() { popup?.let { overlay.removeView(it) };popup=null;extraCells.clear() }
    fun afterTouch(event: MotionEvent) { if(event.actionMasked==MotionEvent.ACTION_CANCEL) dismissExtra() }
    fun beforeTouch(event: MotionEvent) {
        val extra=popup ?: return
        if(event.actionMasked!=MotionEvent.ACTION_DOWN && event.actionMasked!=MotionEvent.ACTION_POINTER_DOWN) return
        val p=IntArray(2);extra.getLocationOnScreen(p)
        if(event.rawX<p[0] || event.rawX>=p[0]+extra.width || event.rawY<p[1] || event.rawY>=p[1]+extra.height) dismissExtra()
    }
}
