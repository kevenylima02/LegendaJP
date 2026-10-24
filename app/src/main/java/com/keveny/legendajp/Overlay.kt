package com.keveny.legendajp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

/**
 * Legenda flutuante sobre outros apps, no estilo da legenda instantânea do Galaxy.
 * Arraste pela barrinha para mover; toque na barrinha alterna 2/5 linhas;
 * toque numa palavra abre o cartão (segundo balão, logo abaixo).
 */
class Overlay(private val ctx: Context, private val prefs: Prefs) {

    companion object {
        fun canDraw(ctx: Context) = Settings.canDrawOverlays(ctx)
        private const val CARD_TIMEOUT_MS = 8000L
    }

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val ui = Handler(Looper.getMainLooper())
    private val metrics = ctx.resources.displayMetrics
    private var root: View? = null
    private lateinit var text: TextView
    private lateinit var partial: TextView
    private lateinit var params: WindowManager.LayoutParams
    private var expanded = prefs.overlayExpanded
    private var lastRendered = ""

    private var card: View? = null
    private val closeCard = Runnable { hideCard() }

    private val listener: () -> Unit = { ui.post { render() } }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    fun show() {
        if (root != null) return
        val v = LayoutInflater.from(ctx).inflate(R.layout.overlay, null)
        text = v.findViewById(R.id.overlayText)
        partial = v.findViewById(R.id.overlayPartial)
        text.movementMethod = LinkMovementMethod.getInstance()
        text.highlightColor = Color.TRANSPARENT

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
        val drag = View.OnTouchListener { _, ev ->
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
                        hideCard()
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
                        lastRendered = ""
                        render()
                    }
                    true
                }
                else -> false
            }
        }
        v.findViewById<View>(R.id.handleArea).setOnTouchListener(drag)
        v.setOnTouchListener(drag)

        applyLines()
        wm.addView(v, params)
        root = v
        TranscriptStore.overlayListener = listener
        render()
    }

    fun hide() {
        TranscriptStore.overlayListener = null
        hideCard()
        root?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        root = null
    }

    private fun applyLines() {
        text.maxLines = if (expanded) 5 else 2
        partial.maxLines = if (expanded) 3 else 2
    }

    private fun render() {
        if (root == null) return
        val lines = TranscriptStore.lines.takeLast(if (expanded) 4 else 2)
        val p = TranscriptStore.partial
        val key = lines.joinToString("\n") + "\u0000" + p + "\u0000" + VocabDb.savedKeys.size
        if (key == lastRendered) return
        lastRendered = key
        if (lines.isEmpty()) {
            text.text = ctx.getString(R.string.partial_hint)
            text.alpha = 0.6f
        } else {
            val sb = SpannableStringBuilder()
            for ((i, line) in lines.withIndex()) {
                val (sec, sentence) = Recordings.parseLine(line)
                if (i > 0) sb.append("\n")
                sb.append(Jp.decorate(sentence, VocabDb.savedKeys, Jp.OVERLAY_SAVED_COLOR) { w ->
                    showCard(w, sentence, sec)
                })
            }
            text.text = sb
            text.alpha = 1f
        }
        partial.text = p
        partial.visibility = if (p.isEmpty()) View.GONE else View.VISIBLE
    }

    // ---------- cartão da palavra (segundo balão) ----------

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun showCard(w: Jp.Word, sentence: String, sec: Int) {
        hideCard()
        val base = root ?: return
        val v = LayoutInflater.from(ctx).inflate(R.layout.word_card, null)
        v.setBackgroundResource(R.drawable.bg_overlay_card)
        WordCard.bind(
            ctx, v, w, sentence, TranscriptStore.recording, sec,
            onPlay = null,
            onChanged = { lastRendered = ""; render(); hideCard() },
            light = true
        )
        v.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) { hideCard(); true } else {
                ui.removeCallbacks(closeCard); ui.postDelayed(closeCard, CARD_TIMEOUT_MS); false
            }
        }
        val gap = (8 * metrics.density).toInt()
        val below = params.y + base.height + gap
        val cardParams = WindowManager.LayoutParams(
            params.width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = below
        }
        try {
            wm.addView(v, cardParams)
        } catch (_: Exception) { return }
        card = v
        // Se não couber embaixo, sobe para cima da legenda
        v.post {
            if (below + v.height > metrics.heightPixels) {
                cardParams.y = maxOf(0, params.y - v.height - gap)
                try { wm.updateViewLayout(v, cardParams) } catch (_: Exception) {}
            }
        }
        ui.removeCallbacks(closeCard)
        ui.postDelayed(closeCard, CARD_TIMEOUT_MS)
    }

    private fun hideCard() {
        ui.removeCallbacks(closeCard)
        card?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        card = null
    }
}
