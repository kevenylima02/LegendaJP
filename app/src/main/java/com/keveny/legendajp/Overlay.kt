package com.keveny.legendajp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

/**
 * Legenda flutuante sobre outros apps, no estilo da legenda instantânea do Galaxy.
 * Arraste para mover; toque para alternar entre 2 e 5 linhas.
 */
class Overlay(private val ctx: Context, private val prefs: Prefs) {

    companion object {
        fun canDraw(ctx: Context) = Settings.canDrawOverlays(ctx)
    }

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val ui = Handler(Looper.getMainLooper())
    private var root: View? = null
    private lateinit var text: TextView
    private lateinit var partial: TextView
    private lateinit var params: WindowManager.LayoutParams
    private var expanded = prefs.overlayExpanded
    private var lastRendered = ""

    private val listener: () -> Unit = { ui.post { render() } }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    fun show() {
        if (root != null) return
        val v = LayoutInflater.from(ctx).inflate(R.layout.overlay, null)
        text = v.findViewById(R.id.overlayText)
        partial = v.findViewById(R.id.overlayPartial)

        val metrics = ctx.resources.displayMetrics
        val margin = (12 * metrics.density).toInt()
        params = WindowManager.LayoutParams(
            metrics.widthPixels - 2 * margin,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = if (prefs.overlayY >= 0) prefs.overlayY else (metrics.heightPixels * 0.62).toInt()
        }

        var downY = 0f
        var startY = 0
        var moved = false
        v.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = ev.rawY; startY = params.y; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (ev.rawY - downY).toInt()
                    if (abs(dy) > 8) moved = true
                    if (moved) {
                        params.y = (startY + dy).coerceIn(0, metrics.heightPixels - v.height)
                        try { wm.updateViewLayout(v, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moved) {
                        prefs.overlayY = params.y
                    } else if (ev.actionMasked == MotionEvent.ACTION_UP) {
                        expanded = !expanded
                        prefs.overlayExpanded = expanded
                        applyLines()
                    }
                    true
                }
                else -> false
            }
        }

        applyLines()
        wm.addView(v, params)
        root = v
        TranscriptStore.overlayListener = listener
        render()
    }

    fun hide() {
        TranscriptStore.overlayListener = null
        root?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        root = null
    }

    private fun applyLines() {
        text.maxLines = if (expanded) 5 else 2
        partial.maxLines = if (expanded) 3 else 2
    }

    private fun render() {
        if (root == null) return
        val lines = TranscriptStore.lines
        val recent = lines.takeLast(if (expanded) 4 else 2)
            .joinToString("\n") { Recordings.parseLine(it).second }
        val p = TranscriptStore.partial
        val key = recent + "\u0000" + p
        if (key == lastRendered) return
        lastRendered = key
        text.text = recent.ifEmpty { ctx.getString(R.string.partial_hint) }
        text.alpha = if (recent.isEmpty()) 0.6f else 1f
        partial.text = p
        partial.visibility = if (p.isEmpty()) View.GONE else View.VISIBLE
    }
}
