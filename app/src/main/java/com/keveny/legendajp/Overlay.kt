package com.keveny.legendajp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
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
 * - Encoste o dedo no texto: a palavra sob o dedo fica marcada e o cartão abre logo
 *   abaixo; arraste para trocar de palavra; solte e o cartão fica para salvar.
 */
class Overlay(private val ctx: Context, private val prefs: Prefs) {

    companion object {
        fun canDraw(ctx: Context) = Settings.canDrawOverlays(ctx)
        private const val CARD_TIMEOUT_MS = 8000L
        private const val SELECT_BG = 0x55C5CBFF
        const val DOCK_NONE = 0
        const val DOCK_LEFT = 1
        const val DOCK_RIGHT = 2
    }

    private data class Entry(val start: Int, val end: Int, val word: Jp.Word, val sentence: String, val sec: Int)

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
    private lateinit var captionBox: View
    private lateinit var handleArea: View
    private lateinit var text: TextView
    private lateinit var partial: TextView
    private lateinit var cardBox: View
    private lateinit var dockTab: View
    private lateinit var params: WindowManager.LayoutParams

    private var expanded = prefs.overlayExpanded
    private var dock = prefs.overlayDock
    private var lastRendered = ""
    private var entries: List<Entry> = emptyList()
    private var selected: Entry? = null
    private var selectionSpan: BackgroundColorSpan? = null
    private val closeCard = Runnable { clearSelection() }

    private val listener: () -> Unit = { ui.post { render() } }

    // ---------- ciclo de vida ----------

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    fun show() {
        if (root != null) return
        val v = LayoutInflater.from(ctx).inflate(R.layout.overlay, null)
        expandedBox = v.findViewById(R.id.expandedBox)
        captionBox = v.findViewById(R.id.captionBox)
        handleArea = v.findViewById(R.id.handleArea)
        text = v.findViewById(R.id.overlayText)
        partial = v.findViewById(R.id.overlayPartial)
        cardBox = v.findViewById(R.id.cardBox)
        dockTab = v.findViewById(R.id.dockTab)

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

    // ---------- toque: arrastar, esfregar palavras, bloquinho ----------

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var mode = 0 // 0 nada, 1 arrastar, 2 esfregar palavras, 3 bloquinho
    private var moved = false

    private fun hit(view: View, ev: MotionEvent): Boolean {
        if (view.visibility != View.VISIBLE) return false
        val r = Rect()
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        r.set(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
        return r.contains(ev.x.toInt(), ev.y.toInt())
    }

    private fun onTouch(ev: MotionEvent): Boolean {
        val r = root ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_OUTSIDE -> { clearSelection(); return false }
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX; downY = ev.rawY
                startX = params.x; startY = params.y
                moved = false
                mode = when {
                    dock != DOCK_NONE -> 3
                    hit(cardBox, ev) -> 0 // os botões do cartão tratam o toque
                    hit(text, ev) -> 2
                    else -> 1
                }
                if (mode == 2) {
                    ui.removeCallbacks(closeCard)
                    entryAt(ev)?.let { e -> if (e !== selected) { select(e); r.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) } }
                }
                return mode != 0
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (ev.rawX - downX).toInt()
                val dy = (ev.rawY - downY).toInt()
                when (mode) {
                    1, 3 -> {
                        if (abs(dx) > 8 || abs(dy) > 8) moved = true
                        if (moved) {
                            if (mode == 1) clearSelection()
                            params.x = startX + dx
                            params.y = (startY + dy).coerceIn(0, screenH - r.height)
                            if (mode == 3) params.x = params.x.coerceIn(0, screenW - tabW)
                            update()
                        }
                        return true
                    }
                    2 -> {
                        entryAt(ev)?.let { e -> if (e !== selected) { select(e); r.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) } }
                        return true
                    }
                }
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val up = ev.actionMasked == MotionEvent.ACTION_UP
                when (mode) {
                    1 -> {
                        if (moved) settleAfterDrag() else if (up && hit(handleArea, ev)) toggleExpanded()
                        else if (up) clearSelection()
                    }
                    2 -> if (selected != null) { openCard(); ui.postDelayed(closeCard, CARD_TIMEOUT_MS) } else clearSelection()
                    3 -> if (moved) { prefs.overlayY = params.y; update() } else if (up) undock()
                }
                mode = 0
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
        clearSelection()
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
            (dockTab as TextView).text = if (dock == DOCK_LEFT) "字›" else "‹字"
        }
    }

    // ---------- conteúdo ----------

    private fun render() {
        val r = root ?: return
        // Com o Michi aberto a legenda já está na tela; esconde o balão
        val wanted = if (TranscriptStore.appVisible) View.GONE else View.VISIBLE
        if (r.visibility != wanted) {
            r.visibility = wanted
            if (wanted == View.GONE) clearSelection()
        }
        val lines = TranscriptStore.lines.takeLast(if (expanded) 4 else 2)
        val p = TranscriptStore.partial
        val key = lines.joinToString("\n") + "\u0000" + p + "\u0000" + VocabDb.savedKeys.size
        if (key == lastRendered) return
        lastRendered = key
        if (lines.isEmpty()) {
            entries = emptyList()
            text.text = ctx.getString(R.string.partial_hint)
            text.alpha = 0.6f
        } else {
            val sb = SpannableStringBuilder()
            val list = mutableListOf<Entry>()
            for ((i, line) in lines.withIndex()) {
                val (sec, sentence) = Recordings.parseLine(line)
                if (i > 0) sb.append("\n")
                val base = sb.length
                sb.append(sentence)
                val words = try { Jp.words(sentence) } catch (_: Exception) { emptyList() }
                for (w in words) {
                    list.add(Entry(base + w.start, base + w.end, w, sentence, sec))
                    if (w.key in VocabDb.savedKeys) {
                        sb.setSpan(ForegroundColorSpan(Jp.OVERLAY_SAVED_COLOR), base + w.start, base + w.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }
            entries = list
            val keep = selected?.let { s -> list.firstOrNull { it.word == s.word && it.sentence == s.sentence } }
            selected = null
            selectionSpan = null
            text.text = sb
            text.alpha = 1f
            val cardWasOpen = cardBox.visibility == View.VISIBLE
            if (keep != null) { select(keep); if (cardWasOpen) openCard() } else if (cardWasOpen) clearSelection()
        }
        partial.text = p
        partial.visibility = if (p.isEmpty()) View.GONE else View.VISIBLE
    }

    // ---------- seleção de palavra e cartão ----------

    private fun entryAt(ev: MotionEvent): Entry? {
        val layout = text.layout ?: return null
        val loc = IntArray(2)
        text.getLocationInWindow(loc)
        val lx = (ev.x - loc[0] - text.totalPaddingLeft + text.scrollX).toInt()
        val ly = (ev.y - loc[1] - text.totalPaddingTop + text.scrollY).toInt().coerceIn(0, layout.height - 1)
        val line = layout.getLineForVertical(ly)
        if (lx < layout.getLineLeft(line) - 20 || lx > layout.getLineRight(line) + 20) return null
        val off = layout.getOffsetForHorizontal(line, lx.toFloat())
        return entries.firstOrNull { off >= it.start && off < it.end }
            ?: entries.firstOrNull { off == it.end }
    }

    private fun select(e: Entry) {
        val sp = text.text as? Spannable ?: return
        selectionSpan?.let { sp.removeSpan(it) }
        val span = BackgroundColorSpan(SELECT_BG)
        sp.setSpan(span, e.start, e.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        selectionSpan = span
        selected = e
        cardBox.visibility = View.GONE
    }

    /** Abre o cartão da palavra marcada (ao soltar o dedo). */
    private fun openCard() {
        val e = selected ?: return
        WordCard.bind(
            ctx, cardBox, e.word, e.sentence, TranscriptStore.recording, e.sec,
            onPlay = null,
            onChanged = { lastRendered = ""; clearSelection(); render() },
            light = true
        )
        cardBox.visibility = View.VISIBLE
        // Se o cartão não couber embaixo, sobe a janela para ele aparecer
        cardBox.post {
            val r = root ?: return@post
            val overflow = params.y + r.height - screenH
            if (overflow > 0) { params.y = maxOf(0, params.y - overflow); update() }
        }
    }

    private fun clearSelection() {
        ui.removeCallbacks(closeCard)
        (text.text as? Spannable)?.let { sp -> selectionSpan?.let { sp.removeSpan(it) } }
        selectionSpan = null
        selected = null
        if (::cardBox.isInitialized) cardBox.visibility = View.GONE
    }
}
