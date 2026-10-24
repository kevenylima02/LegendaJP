package com.keveny.legendajp

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.view.View
import android.view.animation.LinearInterpolator

/** Mostra a ordem dos traços de um kanji: traço por traço, numerado. Toque repete a animação. */
class StrokeView(ctx: Context) : View(ctx) {

    private var strokes: List<Path> = emptyList()
    private var lengths: FloatArray = FloatArray(0)
    private var progress = 0f // 0..strokes.size
    private var anim: ValueAnimator? = null

    private val density = resources.displayMetrics.density
    private val done = Paint().apply {
        color = ctx.getColor(R.color.text); style = Paint.Style.STROKE; isAntiAlias = true
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val drawing = Paint(done).apply { color = ctx.getColor(R.color.accent) }
    private val ghost = Paint(done).apply { color = (ctx.getColor(R.color.text_secondary) and 0x00FFFFFF) or 0x33000000 }
    private val numbers = Paint().apply { color = ctx.getColor(R.color.stop); isAntiAlias = true; textAlign = Paint.Align.CENTER }
    private val frame = Paint().apply {
        color = ctx.getColor(R.color.outline); style = Paint.Style.STROKE; strokeWidth = density
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }
    private val seg = Path()
    private val pos = FloatArray(2)

    init {
        setOnClickListener { play() }
    }

    fun setStrokes(list: List<Path>) {
        strokes = list
        lengths = FloatArray(list.size) { PathMeasure(list[it], false).length }
        play()
    }

    fun play() {
        anim?.cancel()
        if (strokes.isEmpty()) return
        anim = ValueAnimator.ofFloat(0f, strokes.size.toFloat()).apply {
            duration = (strokes.size * 550L).coerceAtMost(9000L)
            interpolator = LinearInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = (110 * density).toInt()
        setMeasuredDimension(resolveSize(s, widthMeasureSpec), resolveSize(s, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawLine(w / 2, 0f, w / 2, h, frame)
        canvas.drawLine(0f, h / 2, w, h / 2, frame)
        if (strokes.isEmpty()) return
        val scale = minOf(w, h) / Strokes.VIEWBOX
        canvas.save()
        canvas.translate((w - Strokes.VIEWBOX * scale) / 2, (h - Strokes.VIEWBOX * scale) / 2)
        canvas.scale(scale, scale)
        val sw = 4f
        done.strokeWidth = sw; drawing.strokeWidth = sw; ghost.strokeWidth = sw
        numbers.textSize = 9f
        for (p in strokes) canvas.drawPath(p, ghost)
        val full = progress.toInt()
        for (k in strokes.indices) {
            if (k < full) canvas.drawPath(strokes[k], done)
            else if (k == full) {
                val frac = progress - full
                if (frac > 0f) {
                    seg.reset()
                    PathMeasure(strokes[k], false).getSegment(0f, lengths[k] * frac, seg, true)
                    canvas.drawPath(seg, drawing)
                }
            }
            if (k <= full && k < strokes.size) {
                PathMeasure(strokes[k], false).getPosTan(0f, pos, null)
                canvas.drawText("${k + 1}", pos[0] - 4f, pos[1] - 2f, numbers)
            }
        }
        canvas.restore()
    }
}
