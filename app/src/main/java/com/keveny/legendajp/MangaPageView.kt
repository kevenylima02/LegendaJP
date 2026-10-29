package com.keveny.legendajp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * Página do mangá: mostra o bitmap inteiro na tela, com pinça para zoom, arrasto quando ampliada,
 * toque duplo para ampliar/voltar, e deslizar para trocar de página (só sem zoom). Desenha os
 * balões lidos pelo ✦ (números) e o ponto tocado pela varinha.
 */
class MangaPageView(ctx: Context) : View(ctx) {

    class Marker(val x: Float, val y: Float, val label: String)   // x, y em pixels do bitmap

    var bitmap: Bitmap? = null
        set(v) { field = v; fit(); invalidate() }
    var markers: List<Marker> = emptyList()
        set(v) { field = v; invalidate() }
    /** Ponto tocado pela varinha (pixels do bitmap), desenhado como um anel. */
    var tapMark: Pair<Float, Float>? = null
        set(v) { field = v; invalidate() }
    var background0: Int = Color.BLACK

    /** Toque simples: coordenadas no bitmap e na tela. */
    var onTap: (bx: Float, by: Float, sx: Float, sy: Float) -> Unit = { _, _, _, _ -> }
    /** Deslizar sem zoom: +1 = dedo para a direita, −1 = para a esquerda. */
    var onSwipe: (dir: Int) -> Unit = {}

    private val m = Matrix()
    private var minScale = 1f
    private val d = ctx.resources.displayMetrics.density
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE63D5AFE.toInt() }
    private val dotText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 12 * d; isFakeBoldText = true }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d; color = 0xFFE53935.toInt() }

    private fun scale(): Float { val v = FloatArray(9); m.getValues(v); return v[Matrix.MSCALE_X] }
    val zoomed get() = scale() > minScale * 1.05f

    fun fit() {
        val b = bitmap ?: return
        if (width == 0 || height == 0) return
        minScale = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
        m.reset()
        m.postScale(minScale, minScale)
        m.postTranslate((width - b.width * minScale) / 2f, (height - b.height * minScale) / 2f)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); fit() }

    /** Mantém a página na tela: centralizada quando menor que a tela, sem bordas vazias quando maior. */
    private fun clamp() {
        val b = bitmap ?: return
        val r = RectF(0f, 0f, b.width.toFloat(), b.height.toFloat()); m.mapRect(r)
        var dx = 0f; var dy = 0f
        if (r.width() <= width) dx = (width - r.width()) / 2f - r.left
        else { if (r.left > 0) dx = -r.left; if (r.right < width) dx = width - r.right }
        if (r.height() <= height) dy = (height - r.height()) / 2f - r.top
        else { if (r.top > 0) dy = -r.top; if (r.bottom < height) dy = height - r.bottom }
        m.postTranslate(dx, dy)
    }

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            val s = scale()
            val f = (s * det.scaleFactor).coerceIn(minScale, minScale * 6f) / s
            m.postScale(f, f, det.focusX, det.focusY); clamp(); invalidate(); return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!zoomed) return false
            m.postTranslate(-dx, -dy); clamp(); invalidate(); return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (zoomed || scaler.isInProgress) return false
            if (kotlin.math.abs(vx) > kotlin.math.abs(vy) * 1.3f && kotlin.math.abs(vx) > 600 * d / 2) { onSwipe(if (vx > 0) 1 else -1); return true }
            return false
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoomed) fit() else { m.postScale(2.5f, 2.5f, e.x, e.y); clamp(); invalidate() }
            return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val inv = Matrix(); m.invert(inv)
            val p = floatArrayOf(e.x, e.y); inv.mapPoints(p)
            onTap(p[0], p[1], e.x, e.y); return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        if (!scaler.isInProgress) gestures.onTouchEvent(e)
        return true
    }

    /** Distância na tela (px) entre um ponto do bitmap e um toque. */
    fun screenDistance(bx: Float, by: Float, sx: Float, sy: Float): Float {
        val p = floatArrayOf(bx, by); m.mapPoints(p)
        return kotlin.math.hypot(p[0] - sx, p[1] - sy)
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(background0)
        val b = bitmap ?: return
        c.drawBitmap(b, m, paint)
        val r = 11 * d
        for (mk in markers) {
            val p = floatArrayOf(mk.x, mk.y); m.mapPoints(p)
            c.drawCircle(p[0], p[1], r, dot)
            c.drawText(mk.label, p[0], p[1] + dotText.textSize / 3f, dotText)
        }
        tapMark?.let { (x, y) ->
            val p = floatArrayOf(x, y); m.mapPoints(p)
            c.drawCircle(p[0], p[1], 16 * d, ring)
        }
    }
}
