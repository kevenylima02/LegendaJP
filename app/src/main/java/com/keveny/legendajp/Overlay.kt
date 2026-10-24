package com.keveny.legendajp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
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
 * - Arraste pela barrinha para mover; toque na barrinha alterna 2/5 linhas.
 * - Encoste o dedo no texto: a palavra sob o dedo fica marcada e o cartão abre num
 *   segundo balão; arraste o dedo para trocar de palavra; solte para o cartão ficar.
 */
class Overlay(private val ctx: Context, private val prefs: Prefs) {

    companion object {
        fun canDraw(ctx: Context) = Settings.canDrawOverlays(ctx)
        private const val CARD_TIMEOUT_MS = 8000L
        private const val SELECT_BG = 0x55C5CBFF
    }

    /** Uma palavra tocável dentro do texto combinado das linhas. */
    private data class Entry(val start: Int, val end: Int, val word: Jp.Word, val sentence: String, val sec: Int)

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val ui = Handler(Looper.getMainLooper())
    private val metrics = ctx.resources.displayMetrics
    private var root: View? = null
    private lateinit var text: TextView
    private lateinit var partial: TextView
    private lateinit var params: WindowManager.LayoutParams
    private var expanded = prefs.overlayExpanded
    private var lastRendered = ""
    private var entries: List<Entry> = emptyList()
    private var selected: Entry? = null
    private var selectionSpan: BackgroundColorSpan? = null

    private var card: View? = null
    private var cardParams: WindowManager.LayoutParams? = null
    private val closeCard = Runnable { clearSelection() }

    /** Chamado quando o usuário fecha a legenda soltando no ×. */
    var onDismissed: (() -> Unit)? = null
    private var dismissTarget: TextView? = null
    private var dismissArmed = false

    private val listener: () -> Unit = { ui.post { render() } }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    fun show() {
        if (root != null) return
        val v = LayoutInflater.from(ctx).inflate(R.layout.overlay, null)
        text = v.findViewById(R.id.overlayText)
        partial = v.findViewById(R.id.overlayPartial)

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

        // Arraste vertical pela barrinha (e pelas bordas do balão); soltar no × do rodapé fecha
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
                    if (abs(dy) > 8 && !moved) { moved = true; showDismissTarget() }
                    if (moved) {
                        params.y = (startY + dy).coerceIn(0, metrics.heightPixels - v.height)
                        try { wm.updateViewLayout(v, params) } catch (_: Exception) {}
                        clearSelection()
                        setDismissArmed(inDismissZone(v))
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moved) {
                        val close = ev.actionMasked == MotionEvent.ACTION_UP && inDismissZone(v)
                        hideDismissTarget()
                        if (close) {
                            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            onDismissed?.invoke()
                            hide()
                        } else {
                            prefs.overlayY = params.y
                        }
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

        // Esfregar o dedo pelo texto seleciona a palavra sob ele
        text.setOnTouchListener { tv, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    ui.removeCallbacks(closeCard)
                    val e = entryAt(tv as TextView, ev.x, ev.y)
                    if (e != null && e !== selected) {
                        select(e)
                        tv.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (selected != null) ui.postDelayed(closeCard, CARD_TIMEOUT_MS)
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
        clearSelection()
        hideDismissTarget()
        root?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        root = null
    }

    private fun applyLines() {
        text.maxLines = if (expanded) 5 else 2
        partial.maxLines = if (expanded) 3 else 2
    }

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
            // Texto mudou: a seleção antiga não vale mais
            val keep = selected?.let { s -> list.firstOrNull { it.word == s.word && it.sentence == s.sentence } }
            selected = null
            selectionSpan = null
            text.text = sb
            text.alpha = 1f
            if (keep != null) select(keep) else if (card != null) clearSelection()
        }
        partial.text = p
        partial.visibility = if (p.isEmpty()) View.GONE else View.VISIBLE
    }

    // ---------- alvo de fechar (×) no rodapé ----------

    private fun dismissZoneTop() = metrics.heightPixels - (140 * metrics.density).toInt()

    private fun inDismissZone(v: View) = params.y + v.height > dismissZoneTop()

    private fun showDismissTarget() {
        if (dismissTarget != null) return
        val size = (64 * metrics.density).toInt()
        val tv = TextView(ctx).apply {
            text = "✕"
            textSize = 26f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_dismiss)
            alpha = 0.85f
        }
        // Janela maior que o círculo, para a animação de escala não ser recortada
        val box = android.widget.FrameLayout(ctx).apply {
            addView(tv, android.widget.FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        }
        val winSize = (100 * metrics.density).toInt()
        val p = WindowManager.LayoutParams(
            winSize, winSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (24 * metrics.density).toInt()
        }
        try { wm.addView(box, p) } catch (_: Exception) { return }
        dismissTarget = tv
        dismissArmed = false
    }

    private fun setDismissArmed(armed: Boolean) {
        val tv = dismissTarget ?: return
        if (armed == dismissArmed) return
        dismissArmed = armed
        tv.animate().scaleX(if (armed) 1.3f else 1f).scaleY(if (armed) 1.3f else 1f).setDuration(120).start()
        tv.alpha = if (armed) 1f else 0.85f
        tv.setBackgroundResource(if (armed) R.drawable.bg_dismiss_armed else R.drawable.bg_dismiss)
        if (armed) tv.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun hideDismissTarget() {
        dismissTarget?.let { try { wm.removeView(it.parent as View) } catch (_: Exception) {} }
        dismissTarget = null
        dismissArmed = false
    }

    // ---------- seleção por toque/arraste ----------

    private fun entryAt(tv: TextView, x: Float, y: Float): Entry? {
        val layout = tv.layout ?: return null
        val lx = (x - tv.totalPaddingLeft + tv.scrollX).toInt()
        val ly = (y - tv.totalPaddingTop + tv.scrollY).toInt().coerceIn(0, layout.height - 1)
        val line = layout.getLineForVertical(ly)
        if (lx < layout.getLineLeft(line) - 20 || lx > layout.getLineRight(line) + 20) return null
        val off = layout.getOffsetForHorizontal(line, lx.toFloat())
        return entries.firstOrNull { off >= it.start && off < it.end }
            ?: entries.firstOrNull { off == it.end } // dedo logo depois da última letra
    }

    private fun select(e: Entry) {
        val sp = text.text as? Spannable ?: return
        selectionSpan?.let { sp.removeSpan(it) }
        val span = BackgroundColorSpan(SELECT_BG)
        sp.setSpan(span, e.start, e.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        selectionSpan = span
        selected = e
        showCard(e)
    }

    private fun clearSelection() {
        ui.removeCallbacks(closeCard)
        (text.text as? Spannable)?.let { sp -> selectionSpan?.let { sp.removeSpan(it) } }
        selectionSpan = null
        selected = null
        hideCard()
    }

    // ---------- cartão da palavra (segundo balão) ----------

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun showCard(e: Entry) {
        val base = root ?: return
        val existing = card
        val v = existing ?: LayoutInflater.from(ctx).inflate(R.layout.word_card, null).also {
            it.setBackgroundResource(R.drawable.bg_overlay_card)
            it.setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) { clearSelection(); true } else {
                    ui.removeCallbacks(closeCard); ui.postDelayed(closeCard, CARD_TIMEOUT_MS); false
                }
            }
        }
        WordCard.bind(
            ctx, v, e.word, e.sentence, TranscriptStore.recording, e.sec,
            onPlay = null,
            onChanged = { lastRendered = ""; clearSelection(); render() },
            light = true
        )
        if (existing != null) return

        val gap = (8 * metrics.density).toInt()
        val below = params.y + base.height + gap
        val cp = WindowManager.LayoutParams(
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
            wm.addView(v, cp)
        } catch (_: Exception) { return }
        card = v
        cardParams = cp
        // Se não couber embaixo, sobe para cima da legenda
        v.post {
            if (below + v.height > metrics.heightPixels) {
                cp.y = maxOf(0, params.y - v.height - gap)
                try { wm.updateViewLayout(v, cp) } catch (_: Exception) {}
            }
        }
    }

    private fun hideCard() {
        card?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        card = null
        cardParams = null
    }
}
