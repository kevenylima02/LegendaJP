package com.keveny.legendajp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** Contorno dos traços (com um mínimo de largura/altura, para traços retos não virarem uma linha sem área). */
private fun boundsOf(paths: List<Path>): RectF {
    val r = RectF()
    val tmp = RectF()
    for ((i, p) in paths.withIndex()) {
        p.computeBounds(tmp, true)
        if (i == 0) r.set(tmp) else r.union(tmp)
    }
    if (r.width() < 8f) r.inset((r.width() - 8f) / 2, 0f)
    if (r.height() < 8f) r.inset(0f, (r.height() - 8f) / 2)
    return r
}

/** Leva os traços do retângulo src para dst; em partes muito finas mantém a proporção. */
private fun fit(paths: List<Path>, src: RectF, dst: RectF, keepRatio: Boolean): List<Path> {
    var sx = dst.width() / src.width()
    var sy = dst.height() / src.height()
    if (keepRatio || src.width() < 14f || src.height() < 14f) { val s = minOf(sx, sy); sx = s; sy = s }
    val m = Matrix()
    m.postTranslate(-src.centerX(), -src.centerY())
    m.postScale(sx, sy)
    m.postTranslate(dst.centerX(), dst.centerY())
    return paths.map { Path(it).apply { transform(m) } }
}

private fun strokePaint(ctx: Context, color: Int) = Paint().apply {
    this.color = color; style = Paint.Style.STROKE; isAntiAlias = true
    strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
}

/** Peça do exercício "Montar o kanji": o desenho da parte e, embaixo, o caractere dela. */
class PartTileView(ctx: Context) : View(ctx) {
    private val density = resources.displayMetrics.density
    private var paths: List<Path> = emptyList()
    private var label = ""
    private val ink = strokePaint(ctx, ctx.getColor(R.color.text))
    private val text = Paint().apply {
        color = ctx.getColor(R.color.text_secondary); isAntiAlias = true; textAlign = Paint.Align.CENTER
        textSize = 12 * resources.displayMetrics.scaledDensity
    }

    init {
        setBackgroundResource(R.drawable.bg_button_outline)
        isClickable = true
    }

    fun set(partPaths: List<Path>, element: String) {
        paths = partPaths
        label = element
        contentDescription = element
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((78 * density).toInt(), (94 * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = 10 * density
        val box = RectF(pad, pad, width - pad, width - pad)
        if (paths.isNotEmpty()) {
            val drawn = fit(paths, boundsOf(paths), box, keepRatio = true)
            ink.strokeWidth = 3.2f * density
            for (p in drawn) canvas.drawPath(p, ink)
        }
        canvas.drawText(label, width / 2f, height - 9 * density, text)
    }
}

/**
 * O quadrado do kanji com uma lacuna tracejada por parte (no lugar e no tamanho que ela ocupa no
 * kanji). As partes colocadas são esticadas para caber na lacuna; depois de verificar, ficam verdes
 * (certas) ou vermelhas (erradas).
 */
class KanjiBuildView(ctx: Context) : View(ctx) {
    private val density = resources.displayMetrics.density
    private var slots: List<RectF> = emptyList()
    private val filled = mutableListOf<List<Path>>()
    private var result: List<Boolean>? = null

    private val ink = strokePaint(ctx, ctx.getColor(R.color.text))
    private val okInk = strokePaint(ctx, ctx.getColor(R.color.ok))
    private val badInk = strokePaint(ctx, ctx.getColor(R.color.stop))
    private val grid = Paint().apply {
        color = ctx.getColor(R.color.outline); style = Paint.Style.STROKE; strokeWidth = density
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }
    private val slotLine = Paint().apply {
        color = ctx.getColor(R.color.text_secondary); style = Paint.Style.STROKE; isAntiAlias = true
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }
    private val activeLine = Paint(slotLine).apply { color = ctx.getColor(R.color.accent) }
    private val number = Paint().apply {
        color = ctx.getColor(R.color.text_secondary); isAntiAlias = true; textAlign = Paint.Align.CENTER
    }
    private val activeNumber = Paint(number).apply { color = ctx.getColor(R.color.accent) }

    /** Lacunas a partir dos traços das partes certas (espaço 109×109). */
    fun setSlots(partPaths: List<List<Path>>) {
        slots = partPaths.map { if (it.isEmpty()) RectF(20f, 20f, 89f, 89f) else boundsOf(it) }
        filled.clear()
        result = null
        invalidate()
    }

    fun setFilled(parts: List<List<Path>>, ok: List<Boolean>? = null) {
        filled.clear()
        filled.addAll(parts.take(slots.size))
        result = ok
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = (210 * density).toInt()
        setMeasuredDimension(s, s)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        canvas.drawRect(1f, 1f, w - 1, w - 1, grid)
        canvas.drawLine(w / 2, 0f, w / 2, w, grid)
        canvas.drawLine(0f, w / 2, w, w / 2, grid)
        val scale = w / Strokes.VIEWBOX
        canvas.save()
        canvas.scale(scale, scale)
        val sw = 1.4f / scale * density
        slotLine.strokeWidth = sw; activeLine.strokeWidth = sw
        number.textSize = 9f; activeNumber.textSize = 9f
        ink.strokeWidth = 4f; okInk.strokeWidth = 4f; badInk.strokeWidth = 4f
        val active = if (result == null) filled.size else -1
        for ((i, r) in slots.withIndex()) {
            if (i < filled.size) continue
            canvas.drawRect(RectF(r).apply { inset(-3f, -3f) }, if (i == active) activeLine else slotLine)
            canvas.drawText("${i + 1}", r.centerX(), r.centerY() + 3f, if (i == active) activeNumber else number)
        }
        for ((i, paths) in filled.withIndex()) {
            if (paths.isEmpty()) continue
            val paint = when (result?.getOrNull(i)) { true -> okInk; false -> badInk; else -> ink }
            for (p in fit(paths, boundsOf(paths), slots[i], keepRatio = false)) canvas.drawPath(p, paint)
        }
        canvas.restore()
    }
}
