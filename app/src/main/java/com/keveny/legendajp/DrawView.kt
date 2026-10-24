package com.keveny.legendajp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.google.mlkit.vision.digitalink.recognition.Ink

/** Área para desenhar um kanji com o dedo; guarda os traços para o reconhecedor. */
class DrawView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    private val paint = Paint().apply {
        color = Color.parseColor("#1A1C23")
        strokeWidth = 10f * resources.displayMetrics.density
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }
    private val guide = Paint().apply {
        color = Color.parseColor("#33000000")
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(12f, 12f), 0f)
    }
    private val paths = mutableListOf<Path>()
    private var current: Path? = null
    private var inkBuilder = Ink.builder()
    private var strokeBuilder: Ink.Stroke.Builder? = null

    var onChanged: (() -> Unit)? = null
    val hasStrokes get() = paths.isNotEmpty()

    fun setStrokeColor(color: Int) { paint.color = color; invalidate() }

    fun clear() {
        paths.clear()
        current = null
        inkBuilder = Ink.builder()
        invalidate()
        onChanged?.invoke()
    }

    fun ink(): Ink = inkBuilder.build()

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val t = System.currentTimeMillis()
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                current = Path().apply { moveTo(ev.x, ev.y) }
                strokeBuilder = Ink.Stroke.builder().also { it.addPoint(Ink.Point.create(ev.x, ev.y, t)) }
            }
            MotionEvent.ACTION_MOVE -> {
                current?.lineTo(ev.x, ev.y)
                strokeBuilder?.addPoint(Ink.Point.create(ev.x, ev.y, t))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                current?.let { it.lineTo(ev.x, ev.y); paths.add(it) }
                strokeBuilder?.let { it.addPoint(Ink.Point.create(ev.x, ev.y, t)); inkBuilder.addStroke(it.build()) }
                current = null
                strokeBuilder = null
                parent?.requestDisallowInterceptTouchEvent(false)
                onChanged?.invoke()
            }
            else -> return false
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawLine(w / 2, 0f, w / 2, h, guide)
        canvas.drawLine(0f, h / 2, w, h / 2, guide)
        for (p in paths) canvas.drawPath(p, paint)
        current?.let { canvas.drawPath(it, paint) }
    }
}
