package com.keveny.legendajp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

/**
 * Legenda flutuante sobre outros apps, no estilo da legenda instantânea do Galaxy.
 * - Arraste (pela barrinha ou pelas bordas) para mover em qualquer direção.
 * - Jogue para fora pela lateral: a legenda vira um bloquinho grudado na borda;
 *   toque no bloquinho para abrir de novo.
 * - Toque numa palavra: abre o cartão logo abaixo (mesmo mecanismo de dentro do app).
 */
class Overlay(private val ctx: Context, private val prefs: Prefs) {

    companion object {
        fun canDraw(ctx: Context) = Settings.canDrawOverlays(ctx)
        private const val CARD_TIMEOUT_MS = 8000L
        const val DOCK_NONE = 0
        const val DOCK_LEFT = 1
        const val DOCK_RIGHT = 2
    }

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val ui = Handler(Looper.getMainLooper())
    private val metrics = ctx.resources.displayMetrics
    private val screenW = metrics.widthPixels
    private val screenH = metrics.heightPixels
    private val margin = (12 * metrics.density).toInt()
    private val fullWidth = screenW - 2 * margin
    private val tabW = (34 * metrics.density).toInt()
    private val tabH = (84 * metrics.density).toInt()

    private var root: View? = null
    private lateinit var expandedBox: View
    private lateinit var handleArea: View
    private lateinit var text: TextView
    private lateinit var partial: TextView
    private lateinit var cardBox: View
    private lateinit var dockTab: TextView
    private lateinit var params: WindowManager.LayoutParams

    private var expanded = prefs.overlayExpanded
    private var dock = prefs.overlayDock
    private var lastRendered = ""
    private var cardWord: Jp.Word? = null
    private val closeCard = Runnable { hideCard() }

    private val listener: () -> Unit = { ui.post { render() } }

    // ---------- ciclo de vida ----------

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    fun show() {
        if (root != null) return
        val v = LayoutInflater.from(ctx).inflate(R.layout.overlay, null)
        expandedBox = v.findViewById(R.id.expandedBox)
        handleArea = v.findViewById(R.id.handleArea)
        text = v.findViewById(R.id.overlayText)
        partial = v.findViewById(R.id.overlayPartial)
        cardBox = v.findViewById(R.id.cardBox)
        dockTab = v.findViewById(R.id.dockTab)

        // Palavras tocáveis: mesmo mecanismo do visualizador e da tela principal
        text.movementMethod = LinkMovementMethod.getInstance()
        text.highlightColor = Color.TRANSPARENT

        params = WindowManager.LayoutParams(
            fullWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (prefs.overlayX != Int.MIN_VALUE) prefs.overlayX else margin
            y = if (prefs.overlayY >= 0) prefs.overlayY else (screenH * 0.62).toInt()
        }

        v.setOnTouchListener { _, ev -> onTouch(ev) }
        applyLines()
        applyDock()
        wm.addView(v, params)
        root = v
        TranscriptStore.overlayListener = listener
        render()
    }

    fun hide() {
        TranscriptStore.overlayListener = null
        ui.removeCallbacks(closeCard)
        root?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        root = null
    }

    private fun applyLines() {
        text.maxLines = if (expanded) 5 else 2
        partial.maxLines = if (expanded) 3 else 2
    }

    // ---------- toque: arrastar e bloquinho ----------

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false
    private var moved = false

    private fun hit(view: View, ev: MotionEvent): Boolean {
        if (view.visibility != View.VISIBLE) return false
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        return Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height).contains(ev.x.toInt(), ev.y.toInt())
    }

    private fun onTouch(ev: MotionEvent): Boolean {
        val r = root ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_OUTSIDE -> { hideCard(); return false }
            MotionEvent.ACTION_DOWN -> {
                // Toques no cartão e nas palavras são tratados pelos próprios views
                if (hit(cardBox, ev) || hit(text, ev)) return false
                downX = ev.rawX; downY = ev.rawY
                startX = params.x; startY = params.y
                moved = false
                dragging = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                val dx = (ev.rawX - downX).toInt()
                val dy = (ev.rawY - downY).toInt()
                if (abs(dx) > 8 || abs(dy) > 8) moved = true
                if (moved) {
                    hideCard()
                    params.x = startX + dx
                    params.y = (startY + dy).coerceIn(0, screenH - r.height)
                    if (dock != DOCK_NONE) params.x = params.x.coerceIn(0, screenW - tabW)
                    update()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                val up = ev.actionMasked == MotionEvent.ACTION_UP
                if (dock != DOCK_NONE) {
                    if (moved) { prefs.overlayY = params.y; update() } else if (up) undock()
                } else if (moved) {
                    settleAfterDrag()
                } else if (up) {
                    if (hit(handleArea, ev)) toggleExpanded() else hideCard()
                }
                return true
            }
        }
        return false
    }

    private fun update() {
        root?.let { try { wm.updateViewLayout(it, params) } catch (_: Exception) {} }
    }

    private fun toggleExpanded() {
        expanded = !expanded
        prefs.overlayExpanded = expanded
        applyLines()
        lastRendered = ""
        render()
    }

    /** Soltou depois de arrastar: ou gruda na borda, ou volta para dentro da tela. */
    private fun settleAfterDrag() {
        val r = root ?: return
        val left = params.x
        val right = params.x + fullWidth
        when {
            left < -fullWidth / 3 -> dockTo(DOCK_LEFT)
            right > screenW + fullWidth / 3 -> dockTo(DOCK_RIGHT)
            else -> {
                params.x = params.x.coerceIn(margin / 2, screenW - fullWidth - margin / 2)
                params.y = params.y.coerceIn(0, screenH - r.height)
                prefs.overlayX = params.x
                prefs.overlayY = params.y
                update()
            }
        }
    }

    // ---------- bloquinho na borda ----------

    private fun dockTo(side: Int) {
        hideCard()
        dock = side
        prefs.overlayDock = side
        root?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        applyDock()
        update()
    }

    private fun undock() {
        val side = dock
        dock = DOCK_NONE
        prefs.overlayDock = DOCK_NONE
        applyDock()
        params.x = if (side == DOCK_LEFT) margin else screenW - fullWidth - margin
        params.y = params.y.coerceIn(0, screenH - tabH)
        prefs.overlayX = params.x
        prefs.overlayY = params.y
        update()
        lastRendered = ""
        render()
    }

    private fun applyDock() {
        if (dock == DOCK_NONE) {
            expandedBox.visibility = View.VISIBLE
            dockTab.visibility = View.GONE
            params.width = fullWidth
        } else {
            expandedBox.visibility = View.GONE
            dockTab.visibility = View.VISIBLE
            params.width = tabW
            params.x = if (dock == DOCK_LEFT) 0 else screenW - tabW
            params.y = params.y.coerceIn(0, screenH - tabH)
            dockTab.text = if (dock == DOCK_LEFT) "字›" else "‹字"
        }
    }

    // ---------- conteúdo ----------

    private fun render() {
        val r = root ?: return
        // Com o Michi aberto a legenda já está na tela; esconde o balão
        val wanted = if (TranscriptStore.appVisible) View.GONE else View.VISIBLE
        if (r.visibility != wanted) {
            r.visibility = wanted
            if (wanted == View.GONE) hideCard()
        }
        val lines = TranscriptStore.lines.takeLast(if (expanded) 4 else 2)
        val p = TranscriptStore.partial
        val key = lines.joinToString("\n") + "\u0000" + p + "\u0000" + VocabDb.keysVersion
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
                sb.append(Jp.decorate(sentence, VocabDb.studyKeys, Jp.OVERLAY_SAVED_COLOR, VocabDb.knownKeys, Jp.OVERLAY_KNOWN_COLOR) { w ->
                    openCard(w, sentence, sec)
                })
            }
            text.text = sb
            text.alpha = 1f
        }
        partial.text = p
        partial.visibility = if (p.isEmpty()) View.GONE else View.VISIBLE
    }

    // ---------- cartão da palavra ----------

    private fun openCard(w: Jp.Word, sentence: String, sec: Int) {
        cardWord = w
        WordCard.bind(
            ctx, cardBox, w, sentence, TranscriptStore.recording, sec,
            onPlay = null,
            onChanged = { lastRendered = ""; hideCard(); render() },
            light = true
        )
        cardBox.visibility = View.VISIBLE
        root?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        ui.removeCallbacks(closeCard)
        ui.postDelayed(closeCard, CARD_TIMEOUT_MS)
        // Se o cartão não couber embaixo, sobe a janela para ele aparecer
        cardBox.post {
            val r = root ?: return@post
            val overflow = params.y + r.height - screenH
            if (overflow > 0) { params.y = maxOf(0, params.y - overflow); update() }
        }
    }

    private fun hideCard() {
        ui.removeCallbacks(closeCard)
        cardWord = null
        if (::cardBox.isInitialized) cardBox.visibility = View.GONE
    }
}
