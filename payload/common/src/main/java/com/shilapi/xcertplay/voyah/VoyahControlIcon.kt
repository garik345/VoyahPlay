package com.shilapi.xcertplay.voyah

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Vector contours follow the supplied OEM seat, heated-wheel and lighting screenshots. */
class VoyahControlIcon(context: Context, val id: String) : View(context) {
    data class State(val active: Boolean = false, val variant: Int = 0, val level: Int = 0, val known: Boolean = true)
    var state = State()
        set(value) { if (field != value) { field = value; invalidate() } }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    companion object {
        private const val ARTWORK_SCALE = 0.8f
        val OFF = 0xFF8B9199.toInt()
        val GREEN = 0xFF4CCA74.toInt()
        val ORANGE = 0xFFFF8428.toInt()
        val BLUE = 0xFF36AFF9.toInt()
        val PURPLE = 0xFFB85BE6.toInt()
        fun tint(id: String, active: Boolean, variant: Int): Int = if (!active) OFF else when {
            id == "wheel" || id.startsWith("heat") -> ORANGE
            id.startsWith("vent") -> BLUE
            id.startsWith("mass") || id.startsWith("program") -> PURPLE
            id.startsWith("aroma") -> when (variant) { 1 -> GREEN; 2 -> PURPLE; 3 -> BLUE; else -> OFF }
            else -> GREEN
        }
    }
    private fun path(canvas: Canvas, build: Path.() -> Unit) { canvas.drawPath(Path().apply(build), paint) }
    private fun stroke(width: Float = 2.4f) { paint.style = Paint.Style.STROKE; paint.strokeWidth = width }
    private fun fill() { paint.style = Paint.Style.FILL }
    private fun heat(canvas: Canvas, x: Float, bottom: Float, height: Float) {
        stroke(2.2f)
        path(canvas) {
            moveTo(x, bottom); cubicTo(x+6, bottom-height*.38f, x-5, bottom-height*.56f, x, bottom-height)
        }
    }
    private fun seat(canvas: Canvas) {
        fill()
        // OEM driver artwork leans right. Passenger artwork mirrors only this contour.
        path(canvas) { moveTo(36f, 10f); cubicTo(39f, 10f, 40f, 7f, 39f, 5f); cubicTo(38f, 2f, 33f, 3f, 33f, 6f); cubicTo(32f, 8f, 33f, 10f, 36f, 10f); close() }
        path(canvas) { moveTo(34f, 13f); cubicTo(36f, 10f, 40f, 12f, 39f, 15f); lineTo(31f, 34f); cubicTo(30f, 37f, 26f, 36f, 27f, 33f); lineTo(32f, 17f); close() }
        path(canvas) { moveTo(11f, 34f); cubicTo(18f, 33f, 27f, 33f, 30f, 38f); cubicTo(31f, 40f, 28f, 41f, 26f, 41f); lineTo(12f, 41f); cubicTo(8f, 41f, 8f, 35f, 11f, 34f); close() }
    }
    private fun fan(canvas: Canvas) {
        fill()
        for (angle in listOf(0f, 90f, 180f, 270f)) {
            canvas.save(); canvas.rotate(angle, 19f, 20f)
            path(canvas) { moveTo(19f, 19f); cubicTo(12f, 16f, 10f, 8f, 16f, 9f); cubicTo(21f, 9f, 23f, 15f, 19f, 19f); close() }
            canvas.restore()
        }
        canvas.drawCircle(19f, 20f, 2f, paint)
    }
    private fun lights(canvas: Canvas) {
        when (state.variant) {
            0 -> {
                stroke(2f); canvas.drawCircle(24f, 22f, 7f, paint)
                for (angle in 0..7) { canvas.save();canvas.rotate(angle*45f,24f,22f);canvas.drawLine(24f,10f,24f,12f,paint);canvas.restore() }
            }
            1 -> {
                stroke()
                path(canvas) { moveTo(20f, 13f);cubicTo(7f, 13f, 7f, 33f, 20f, 33f);close() }
                path(canvas) { moveTo(28f, 13f);cubicTo(41f, 13f, 41f, 33f, 28f, 33f);close() }
                for (y in listOf(14f,23f,32f)) { canvas.drawLine(3f,y-2,8f,y,paint);canvas.drawLine(40f,y,45f,y-2,paint) }
            }
            3 -> { fill(); paint.textSize=13f;paint.typeface=android.graphics.Typeface.DEFAULT_BOLD;paint.textAlign=Paint.Align.CENTER;canvas.drawText("AUTO",24f,28f,paint) }
            else -> {
                stroke()
                path(canvas) { moveTo(23f, 12f);lineTo(23f, 34f);cubicTo(45f, 35f, 45f, 11f, 23f, 12f);close() }
                for (y in listOf(14f,20f,26f,32f)) canvas.drawLine(6f,y+4,18f,y,paint)
            }
        }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width / 48f, height / 55f) * ARTWORK_SCALE
        canvas.save();canvas.translate((width-48f*scale)/2, (height-55f*scale)/2);canvas.scale(scale,scale)
        paint.color=tint(id,state.active,state.variant)
        canvas.save()
        if (id in listOf("heat_r","vent_r","mass_r","program_r")) canvas.scale(-1f,1f,24f,23f)
        when {
            id == "light" -> lights(canvas)
            id == "wheel" -> {
                stroke(2.3f);canvas.drawCircle(24f,27f,13f,paint);canvas.drawCircle(24f,27f,4f,paint)
                path(canvas) { moveTo(11f,25f);lineTo(20f,27f);moveTo(28f,27f);lineTo(37f,25f);moveTo(22f,31f);lineTo(20f,39f);moveTo(26f,31f);lineTo(28f,39f) }
                for (x in listOf(18f,24f,30f)) heat(canvas,x,13f,10f)
            }
            id == "aroma_time" -> { stroke();canvas.drawCircle(24f,23f,16f,paint);canvas.drawLine(24f,23f,24f,12f,paint);canvas.drawLine(24f,23f,34f,28f,paint) }
            id == "aroma_intensity" -> { fill();for (i in 1..3) canvas.drawRoundRect((6+i*9).toFloat(),(39-i*9).toFloat(),(11+i*9).toFloat(),39f,2f,2f,paint) }
            id == "aroma" -> {
                stroke(2f)
                path(canvas) { moveTo(15f,30f);lineTo(33f,30f);lineTo(36f,41f);lineTo(12f,41f);close() }
                for (x in listOf(17f,24f,31f)) heat(canvas,x,25f,15f)
            }
            id == "auto_high" || id == "rear_fog" -> {
                lights(canvas)
                fill();paint.textAlign=Paint.Align.CENTER;paint.textSize=13f
                canvas.drawText(if(id=="auto_high") "A" else "≋",30f,28f,paint)
            }
            else -> {
                seat(canvas)
                when {
                    id.startsWith("vent") -> fan(canvas)
                    id.startsWith("mass") || id.startsWith("program") -> {
                        fill()
                        for ((x,y) in listOf(17f to 14f, 24f to 14f, 14f to 21f, 21f to 21f, 12f to 28f, 19f to 28f)) canvas.drawCircle(x,y,2f,paint)
                        if(id.startsWith("program") && state.variant==2) { stroke(1.5f);canvas.drawCircle(19f,21f,9f,paint) }
                    }
                    else -> for (x in listOf(13f,19f,25f)) heat(canvas,x,28f,18f)
                }
            }
        }
        canvas.restore()
        // Dots never mirror; filled dots are the confirmed OEM level, the rest stay grey.
        if (state.active && (id.startsWith("heat") || id.startsWith("vent") || id.startsWith("mass") || id=="aroma_intensity")) {
            fill()
            for (i in 1..3) { paint.color=if(i<=state.level) tint(id,true,state.variant) else Color.rgb(62,69,79);canvas.drawCircle(12f+i*6f,50f,2.2f,paint) }
        }
        if (!state.known) { fill();paint.color=OFF;paint.textSize=12f;paint.textAlign=Paint.Align.RIGHT;canvas.drawText("?",46f,46f,paint) }
        canvas.restore()
    }
}
