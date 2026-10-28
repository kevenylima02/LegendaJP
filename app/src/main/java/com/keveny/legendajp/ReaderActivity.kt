package com.keveny.legendajp

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Leitor de textos com cara de página: tema de papel (ou sépia/noite), progresso e volta de onde
 * parou. A barra de ferramentas embaixo muda o que o toque faz: ✋ só rola; ✨ varinha mostra o
 * significado da palavra num balão; 🖍 marcador livre (arrasta o dedo sobre um trecho: tradução,
 * palavras do trecho, salvar como expressão); 🔊 lê a frase tocada (áudio do texto ou voz do
 * celular). Toque longo numa frase: tradução, gramática e áudio.
 */
class ReaderActivity : Activity() {

    companion object {
        const val EXTRA_NAME = "name"
        const val HAND = "hand"
        const val WAND = "wand"
        const val MARK = "mark"
        const val LISTEN = "listen"

        /** Cores da página: fundo, texto, secundário, palavra salva, conhecida, marcação, cartão, borda. */
        class Theme(val id: String, val label: String, val bg: Int, val text: Int, val secondary: Int,
                    val saved: Int, val known: Int, val highlight: Int, val card: Int, val outline: Int)

        val themes = listOf(
            Theme("paper", "Papel", 0xFFFBF6EA.toInt(), 0xFF2B2418.toInt(), 0xFF8A7B63.toInt(), 0xFF4F43D6.toInt(), 0xFF1E7A45.toInt(), 0x66F5C542, 0xFFFFFDF7.toInt(), 0xFFE3D9C2.toInt()),
            Theme("sepia", "Sépia", 0xFFF0E2C4.toInt(), 0xFF4A3A22.toInt(), 0xFF8E7A58.toInt(), 0xFF4036B8.toInt(), 0xFF1B6B3E.toInt(), 0x66F0B429, 0xFFF7EDD8.toInt(), 0xFFD9C8A4.toInt()),
            Theme("night", "Noite", 0xFF141519.toInt(), 0xFFD9DAE0.toInt(), 0xFF8F93A3.toInt(), 0xFFC5CBFF.toInt(), 0xFF7BD88F.toInt(), 0x66F5C542, 0xFF1F2026.toInt(), 0xFF34363F.toInt()),
        )
    }

    private lateinit var name: String
    private lateinit var base: String
    private lateinit var root: FrameLayout
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var progressLabel: TextView
    private val toolButtons = HashMap<String, TextView>()
    private var paragraphs: List<List<Texts.Sentence>> = emptyList()
    private var blocks: List<Texts.Block> = emptyList()
    private var hasAudio = false
    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private val ui = Handler(Looper.getMainLooper())
    private var stopAt = Runnable {}
    private var restored = false
    private var tool = WAND
    private var popup: PopupWindow? = null

    /** Onde cada frase está desenhada (parágrafo e trecho), para destacar e posicionar o balão. */
    private val placed = HashMap<Texts.Sentence, Pair<TextView, IntRange>>()

    /** Tema "app" = as cores do aplicativo (claro ou escuro), que é o padrão. */
    private val appTheme: Theme get() = Theme("app", "Cores do app", getColor(R.color.background), getColor(R.color.text), getColor(R.color.text_secondary),
        getColor(R.color.saved_word), getColor(R.color.known_word), 0x66F5C542, getColor(R.color.card), getColor(R.color.outline))

    private val allThemes: List<Theme> get() = listOf(appTheme) + themes

    private val theme: Theme get() = allThemes.firstOrNull { it.id == Prefs(this).readerTheme } ?: appTheme

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        name = intent.getStringExtra(EXTRA_NAME) ?: run { finish(); return }
        base = Recordings.baseName(name)
        tool = Prefs(this).readerTool
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), dp(96)) }
        scroll = ScrollView(this).apply { addView(content); isVerticalScrollBarEnabled = false }
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> updateProgress() }
        root = FrameLayout(this).apply { addView(scroll) }
        buildToolbar()
        setContentView(root)
        load()
    }

    private fun load() {
        Thread {
            val bl = Texts.readBlocks(this, name)
            val p = Texts.groups(bl)
            val audio = Recordings.find(this, "$base.wav") != null
            val t = Recordings.display(this, name)
            val level = try { TextLevel.analyze(p.flatten().map { it.text }) } catch (_: Exception) { null }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                title = t
                actionBar?.subtitle = if (t != Recordings.prettyName(name)) "Criado em ${Recordings.prettyName(name)}" else null
                paragraphs = p
                blocks = bl
                hasAudio = audio
                render(level)
                invalidateOptionsMenu()
                if (!restored) {
                    restored = true
                    val y = Prefs(this).readerPos(base)
                    if (y > 0) scroll.post { scroll.scrollTo(0, y); updateProgress() }
                }
            }
        }.start()
    }

    // ---------- barra de ferramentas ----------

    private fun buildToolbar() {
        toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            elevation = dp(6).toFloat()
        }
        fun button(id: String, icon: String, hint: String, onClick: () -> Unit) {
            val b = TextView(this).apply {
                text = icon; textSize = 19f; gravity = Gravity.CENTER
                setPadding(dp(10), dp(6), dp(10), dp(6))
                contentDescription = hint
                setOnClickListener { onClick() }
                setOnLongClickListener { Toast.makeText(this@ReaderActivity, hint, Toast.LENGTH_SHORT).show(); true }
            }
            toolButtons[id] = b
            toolbar.addView(b)
        }
        button(HAND, "✋", "Mão: só rolar a página") { setTool(HAND) }
        button(WAND, "✨", "Varinha: toque na palavra para ver o significado") { setTool(WAND) }
        button(MARK, "🖍", "Marcador: arraste o dedo sobre um trecho") { setTool(MARK) }
        button(LISTEN, "🔊", "Ouvir: toque na frase para ouvir") { setTool(LISTEN) }
        toolbar.addView(View(this), LinearLayout.LayoutParams(dp(1), dp(22)).apply { marginStart = dp(4); marginEnd = dp(4) })
        button("furi", "ふ", "Furigana") {
            val prefs = Prefs(this)
            if (!Furigana.enabled(this)) Toast.makeText(this, "Furigana desligado em Configurações", Toast.LENGTH_SHORT).show()
            else { prefs.furigana = !prefs.furigana; render(); styleToolbar() }
        }
        button("minus", "A−", "Letra menor") { val p = Prefs(this); p.readerSize = (p.readerSize - 2).coerceAtLeast(14); render() }
        button("plus", "A+", "Letra maior") { val p = Prefs(this); p.readerSize = (p.readerSize + 2).coerceAtMost(34); render() }
        progressLabel = TextView(this).apply {
            text = ""; textSize = 12f; setPadding(dp(8), 0, dp(6), 0)
            setOnClickListener { Toast.makeText(this@ReaderActivity, "Quanto do texto você já rolou", Toast.LENGTH_SHORT).show() }
        }
        toolbar.addView(progressLabel)
        // A barra engole o toque: sem isso o toque passa para o texto que está embaixo dela
        toolbar.isClickable = true
        root.addView(toolbar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(14)
        })
        styleToolbar()
    }

    private fun setTool(t: String) {
        tool = t
        Prefs(this).readerTool = t
        styleToolbar()
        val hint = when (t) {
            HAND -> "Mão: o toque não abre nada"
            WAND -> "Varinha: toque numa palavra"
            MARK -> "Marcador: arraste o dedo sobre um trecho"
            else -> "Ouvir: toque numa frase"
        }
        Toast.makeText(this, hint, Toast.LENGTH_SHORT).show()
    }

    private fun styleToolbar() {
        val th = theme
        val sep = toolbar.getChildAt(4)
        sep.setBackgroundColor(th.outline)
        toolbar.background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(th.card); setStroke(dp(1), th.outline) }
        val furiOn = Prefs(this).furigana && Furigana.enabled(this)
        for ((id, b) in toolButtons) {
            val on = id == tool || (id == "furi" && furiOn)
            b.background = if (on) GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(getColor(R.color.accent_soft)) } else null
            b.setTextColor(th.text)
        }
        progressLabel.setTextColor(th.secondary)
    }

    /** Posição no texto; some quando o texto cabe na tela. */
    private fun updateProgress() {
        val max = content.height - scroll.height
        if (max <= dp(40)) { progressLabel.text = ""; progressLabel.visibility = View.GONE; return }
        progressLabel.visibility = View.VISIBLE
        progressLabel.text = "${(scroll.scrollY * 100 / max).coerceIn(0, 100)}%"
    }

    private var lastLevel: TextLevel.Result? = null

    /** Desenha tudo de novo (tema, furigana, tamanho, cores e traduções). */
    private fun render(given: TextLevel.Result? = null) {
        // O "conheço X%" acompanha o que você marca (as frases já estão analisadas, é rápido)
        val level = given ?: (try { TextLevel.analyze(paragraphs.flatten().map { it.text }) } catch (_: Exception) { lastLevel })
        lastLevel = level
        content.removeAllViews()
        placed.clear()
        highlighted = null
        val th = theme
        root.setBackgroundColor(th.bg)
        val prefs = Prefs(this)
        val size = prefs.readerSize.toFloat()
        val furigana = prefs.furigana && Furigana.enabled(this)
        if (level != null && level.tokens > 0) {
            fun pct(n: Int) = (n * 100 + level.tokens / 2) / level.tokens
            content.addView(TextView(this).apply {
                text = "Para você: ${level.label} · nível estimado N${level.estLevel} · conheço ${pct(level.known)}% · estudando ${pct(level.studying)}%"
                textSize = 12f
                setTextColor(th.secondary)
                setPadding(0, 0, 0, dp(16))
            })
        }
        if (paragraphs.isEmpty()) {
            content.addView(TextView(this).apply { text = "Texto vazio."; textSize = 16f; setTextColor(th.text) })
            return
        }
        for (b in blocks) when (b) {
            is Texts.Block.Para -> {
                content.addView(sentenceView(b.sentences, size, furigana, prefix = if (b.bullet) "• " else ""))
                if (prefs.readerTranslations) content.addView(paragraphTranslation(b.sentences))
            }
            is Texts.Block.Heading -> {
                val factor = when (b.level) { 1 -> 1.45f; 2 -> 1.25f; else -> 1.1f }
                content.addView(sentenceView(listOf(Texts.Sentence(-1, b.text)), size * factor, furigana, bold = true))
            }
            is Texts.Block.Table -> {
                val table = TableLayout(this)
                for ((ri, row) in b.rows.withIndex()) {
                    val tr = TableRow(this)
                    val head = b.header && ri == 0
                    for (cell in row) {
                        val tv = sentenceView(cell, size * 0.9f, furigana, bold = head)
                        tv.setPadding(dp(10), dp(6), dp(10), dp(6))
                        tv.maxWidth = dp(260)
                        tv.background = GradientDrawable().apply {
                            setColor(if (head) th.card else Color.TRANSPARENT)
                            setStroke(dp(1), th.outline)
                        }
                        tr.addView(tv, TableRow.LayoutParams(TableRow.LayoutParams.WRAP_CONTENT, TableRow.LayoutParams.MATCH_PARENT))
                    }
                    table.addView(tr)
                }
                content.addView(HorizontalScrollView(this).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(table)
                    setPadding(0, 0, 0, dp(16))
                })
            }
        }
        scroll.post { updateProgress() }
    }

    /** Um parágrafo (ou título, item ou célula): as frases decoradas e tocáveis, com furigana se for o caso. */
    private fun sentenceView(sents: List<Texts.Sentence>, size: Float, furigana: Boolean, bold: Boolean = false, prefix: String = ""): TextView {
        val th = theme
        val sb = SpannableStringBuilder()
        val ranges = mutableListOf<Pair<IntRange, Texts.Sentence>>()
        val tv = TextView(this)
        if (prefix.isNotEmpty()) sb.append(prefix)
        for (s in sents) {
            val off = sb.length
            sb.append(Jp.decorate(s.text, VocabDb.studyKeys, th.saved, VocabDb.knownKeys, th.known, all = true) { w -> onWordTap(tv, w, s) })
            if (furigana) Furigana.decorate(this, sb, off, s.text)
            ranges.add(off until sb.length to s)
        }
        tv.apply {
            text = sb
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(th.text)
            if (bold) setTypeface(null, Typeface.BOLD)
            // O furigana ocupa o espaço entre as linhas (e o topo do parágrafo)
            val extra = if (furigana) size * 0.62f else size * 0.42f
            setLineSpacing(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, extra, resources.displayMetrics), 1f)
            setPadding(0, if (furigana) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size * 0.55f, resources.displayMetrics).toInt() else 0, 0, dp(14))
            movementMethod = LinkMovementMethod.getInstance()
            highlightColor = Color.TRANSPARENT
        }
        for ((r, s) in ranges) placed[s] = tv to r
        attachTouch(tv, ranges)
        return tv
    }

    /** O que o toque faz depende da ferramenta (a mão não faz nada; o marcador é tratado no toque). */
    private fun onWordTap(tv: TextView, w: Jp.Word, s: Texts.Sentence) {
        when (tool) {
            HAND, MARK -> {}
            WAND -> showWandPopup(tv, w, s)
            LISTEN -> speak(s)
        }
    }

    /** Toque longo: a frase sob o dedo. Marcador: arrastar seleciona o trecho. */
    private fun attachTouch(tv: TextView, ranges: List<Pair<IntRange, Texts.Sentence>>) {
        var lastX = 0f
        var lastY = 0f
        var anchor = -1
        var selStart = -1
        var selEnd = -1
        val spannable = tv.text as Spannable
        val markSpan = BackgroundColorSpan(theme.highlight)
        fun select(a: Int, b: Int) {
            selStart = minOf(a, b); selEnd = maxOf(a, b) + 1
            spannable.removeSpan(markSpan)
            spannable.setSpan(markSpan, selStart, selEnd.coerceAtMost(spannable.length), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        tv.setOnTouchListener { _, ev ->
            if (tool != MARK) {
                if (ev.action == MotionEvent.ACTION_DOWN) { lastX = ev.x; lastY = ev.y }
                return@setOnTouchListener false
            }
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tv.parent.requestDisallowInterceptTouchEvent(true)
                    anchor = tv.getOffsetForPosition(ev.x, ev.y).coerceIn(0, maxOf(0, spannable.length - 1))
                    select(anchor, anchor)
                }
                MotionEvent.ACTION_MOVE -> if (anchor >= 0) select(anchor, tv.getOffsetForPosition(ev.x, ev.y).coerceIn(0, maxOf(0, spannable.length - 1)))
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    tv.parent.requestDisallowInterceptTouchEvent(false)
                    if (ev.actionMasked == MotionEvent.ACTION_UP && anchor >= 0 && selEnd > selStart) {
                        val text = spannable.subSequence(selStart, selEnd.coerceAtMost(spannable.length)).toString().trim()
                        val s = ranges.firstOrNull { selStart in it.first }?.second ?: ranges.last().second
                        if (text.isNotEmpty()) showSelection(text, s) { spannable.removeSpan(markSpan) }
                        else spannable.removeSpan(markSpan)
                    } else spannable.removeSpan(markSpan)
                    anchor = -1
                }
            }
            true
        }
        tv.setOnLongClickListener {
            if (tool == MARK) return@setOnLongClickListener true
            val o = tv.getOffsetForPosition(lastX, lastY)
            val s = ranges.firstOrNull { o in it.first }?.second ?: ranges.lastOrNull()?.second
            if (s != null) {
                Jp.suppressTapUntil = android.os.SystemClock.uptimeMillis() + 1000
                showSentence(s)
            }
            true
        }
    }

    // ---------- varinha: balão com o significado ----------

    private fun showWandPopup(tv: TextView, w: Jp.Word, s: Texts.Sentence) {
        popup?.dismiss()
        val th = theme
        val db = VocabDb.get(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(th.card); setStroke(dp(1), th.outline) }
            elevation = dp(8).toFloat()
        }
        val head = SpannableStringBuilder()
        head.append(w.base)
        head.setSpan(StyleSpan(Typeface.BOLD), 0, head.length, 0)
        if (w.reading != w.base) head.append("  ").append(w.reading)
        box.addView(TextView(this).apply { text = head; textSize = 20f; setTextColor(th.text) })
        val info = buildString {
            append(w.pos)
            if (w.conjugation.isNotEmpty() && w.surface != w.base) append(" · ${w.surface} = ${w.base} + ${w.conjugation}")
        }
        box.addView(TextView(this).apply { text = info; textSize = 12f; setTextColor(th.secondary) })
        val tr = TextView(this).apply { text = "…"; textSize = 15f; setTextColor(th.text); setPadding(0, dp(6), 0, dp(6)); maxWidth = dp(280) }
        box.addView(tr)
        val meaning = Tr.cached(this, w.base)
        if (meaning != null) tr.text = meaning else Tr.translate(this, w.base) { t -> if (!isFinishing) tr.text = t ?: "(sem tradução)" }
        // Botões em fluxo (quebram de linha), para nenhum ficar cortado
        val row = FlowLayout(this)
        fun small(label: String): Button = Button(this).apply {
            text = label; textSize = 12f; isAllCaps = false
            setTextColor(th.text); background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(th.bg); setStroke(dp(1), th.outline) }
            setPadding(dp(12), dp(4), dp(12), dp(4)); minHeight = 0; minimumHeight = 0
            row.addView(this)
        }
        val saveBtn = small("")
        val knownBtn = small("")
        val kanjiBtn = if (w.base.any(StudyGen::isKanji)) small("") else null
        fun refresh() {
            saveBtn.text = if (db.isSaved(w)) "★ Salva" else "☆ Salvar"
            knownBtn.text = if (db.isKnown(w)) "✓ Conheço" else "Já conheço"
            kanjiBtn?.text = if (db.isKnownKanji(w)) "✓ Escrevo" else "Sei escrever"
        }
        refresh()
        saveBtn.setOnClickListener {
            if (db.isSaved(w)) db.remove(w) else db.save(w, s.text, base, maxOf(0, s.sec))
            refresh(); render()
        }
        knownBtn.setOnClickListener {
            if (db.isKnown(w)) db.setKnown(w, false) else { if (!db.isSaved(w)) db.save(w, s.text, base, maxOf(0, s.sec)); db.setKnown(w, true) }
            refresh(); render()
        }
        kanjiBtn?.setOnClickListener {
            if (db.isKnownKanji(w)) db.setKnownKanji(w, false) else { if (!db.isSaved(w)) db.save(w, s.text, base, maxOf(0, s.sec)); db.setKnownKanji(w, true) }
            refresh()
        }
        small("Mais…").setOnClickListener {
            popup?.dismiss()
            WordCard.show(this, w, s.text, base, maxOf(0, s.sec), onPlay = if (hasAudio && s.sec >= 0) ({ playSentence(s) }) else null, onChanged = { render() })
        }
        small("✦ IA").setOnClickListener { popup?.dismiss(); aiWords(s.text, s) }
        box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        val width = minOf(resources.displayMetrics.widthPixels - dp(32), dp(360))
        val p = PopupWindow(box, width, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        popup = p
        // Logo abaixo da linha onde a palavra está
        val layout = tv.layout
        val off = ((placed[s]?.second?.first ?: 0) + w.start).coerceIn(0, maxOf(0, tv.text.length - 1))
        val line = layout?.getLineForOffset(off) ?: 0
        val x = layout?.getPrimaryHorizontal(off)?.toInt() ?: 0
        val yOff = if (layout != null) layout.getLineBottom(line) + tv.paddingTop - tv.height else 0
        p.showAsDropDown(tv, (x - dp(8)).coerceAtLeast(0), yOff + dp(4))
    }

    // ---------- marcador livre: trecho selecionado ----------

    /** Trecho marcado: tradução (ML Kit e IA), palavras do trecho com salvar/conheço, salvar como expressão. */
    private fun showSelection(sel: String, s: Texts.Sentence, onClose: () -> Unit) {
        val db = VocabDb.get(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(16), dp(22), dp(8)) }
        box.addView(TextView(this).apply {
            textSize = 22f; setTextColor(getColor(R.color.text))
            Furigana.set(this@ReaderActivity, this, Jp.highlight(sel, VocabDb.studyKeys, getColor(R.color.saved_word), VocabDb.knownKeys, getColor(R.color.known_word)), sel)
        })
        if (sel != s.text) box.addView(TextView(this).apply { text = "Na frase: ${s.text}"; textSize = 12f; setTextColor(getColor(R.color.text_secondary)); setPadding(0, dp(2), 0, dp(8)) })
        val tr = TextView(this).apply { text = "ML Kit: …"; textSize = 15f; setTextColor(getColor(R.color.text)) }
        box.addView(tr)
        Tr.translate(this, sel) { t -> if (!isFinishing) tr.text = "ML Kit: " + (t ?: "(sem tradução)") }
        val aiTr = TextView(this).apply { textSize = 15f; setTextColor(getColor(R.color.text)); visibility = View.GONE; setPadding(0, dp(4), 0, 0) }
        box.addView(aiTr)
        val aiBtn = Button(this).apply {
            text = "✦ Traduzir com IA"; isAllCaps = false; textSize = 13f
            setOnClickListener {
                if (!Ai.enabled(this@ReaderActivity)) { Toast.makeText(this@ReaderActivity, "Ative a IA em Configurações", Toast.LENGTH_LONG).show(); return@setOnClickListener }
                aiTr.visibility = View.VISIBLE; aiTr.text = "✦ pensando…"
                Ai.ask(this@ReaderActivity, Ai.Request(Ai.Task.TRANSLATE, sel, base, note = if (sel != s.text) "Trecho marcado dentro da frase: ${s.text}" else ""), fresh = false) { r ->
                    if (isFinishing) return@ask
                    aiTr.text = if (r.error != null) "✦ ${r.error}" else r.fields.joinToString("\n") { (k, v) -> if (k.isBlank()) v else "$k: $v" }
                }
            }
        }
        val aiRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; addView(aiBtn) }
        aiRow.addView(Button(this).apply {
            text = "✦ Palavras com IA"; isAllCaps = false; textSize = 13f
            setOnClickListener { aiWords(sel, s) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        box.addView(aiRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })

        // Palavras do trecho
        val words = try { Jp.words(sel) } catch (_: Exception) { emptyList() }
        if (words.isNotEmpty()) {
            box.addView(TextView(this).apply { text = "Palavras no trecho"; textSize = 13f; setTypeface(null, Typeface.BOLD); setTextColor(getColor(R.color.text)); setPadding(0, dp(14), 0, dp(4)) })
            for (w in words) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
                val label = TextView(this).apply { textSize = 15f; setTextColor(getColor(R.color.text)) }
                fun fill(t: String?) {
                    label.text = (if (w.reading != w.base) "${w.base}（${w.reading}）" else w.base) + " · ${w.pos}\n" + (t ?: "…")
                }
                fill(Tr.cached(this, w.base))
                if (Tr.cached(this, w.base) == null) Tr.translate(this, w.base) { t -> if (!isFinishing) fill(t ?: "(sem tradução)") }
                row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val save = Button(this).apply { textSize = 12f; isAllCaps = false; minHeight = 0; minimumHeight = 0; setPadding(dp(10), dp(4), dp(10), dp(4)) }
                val known = Button(this).apply { textSize = 12f; isAllCaps = false; minHeight = 0; minimumHeight = 0; setPadding(dp(10), dp(4), dp(10), dp(4)) }
                val kanji = if (w.base.any(StudyGen::isKanji)) Button(this).apply { textSize = 12f; isAllCaps = false; minHeight = 0; minimumHeight = 0; setPadding(dp(10), dp(4), dp(10), dp(4)) } else null
                fun refresh() {
                    save.text = if (db.isSaved(w)) "★" else "☆"
                    known.text = if (db.isKnown(w)) "✓" else "Conheço"
                    kanji?.text = if (db.isKnownKanji(w)) "✍✓" else "✍"
                }
                save.setOnClickListener { if (db.isSaved(w)) db.remove(w) else db.save(w, s.text, base, maxOf(0, s.sec)); refresh(); render() }
                known.setOnClickListener {
                    if (db.isKnown(w)) db.setKnown(w, false) else { if (!db.isSaved(w)) db.save(w, s.text, base, maxOf(0, s.sec)); db.setKnown(w, true) }
                    refresh(); render()
                }
                kanji?.setOnClickListener {
                    if (db.isKnownKanji(w)) db.setKnownKanji(w, false) else { if (!db.isSaved(w)) db.save(w, s.text, base, maxOf(0, s.sec)); db.setKnownKanji(w, true) }
                    refresh()
                    Toast.makeText(this, if (db.isKnownKanji(w)) "${w.base}: sei escrever (fora dos exercícios de kanji)" else "${w.base} volta aos exercícios de kanji", Toast.LENGTH_SHORT).show()
                }
                refresh()
                row.addView(save, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
                row.addView(known, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
                if (kanji != null) row.addView(kanji, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
                box.addView(row)
            }
        }
        val hits = try { Grammar.find(sel) } catch (_: Exception) { emptyList() }
        if (hits.isNotEmpty()) {
            box.addView(TextView(this).apply {
                text = hits.joinToString("\n") { "${it.point.name} ${it.point.level}: ${it.point.explanation}" }
                textSize = 13f; setTextColor(getColor(R.color.text)); setPadding(0, dp(10), 0, 0)
            })
        }

        // O trecho inteiro como expressão (quando o Kuromoji cortou a palavra errado, por exemplo)
        val expr = expressionWord(sel, s.text)
        val multi = words.size != 1 || words[0].surface != sel
        val b = AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Fechar", null).setOnDismissListener { onClose() }
        if (multi && sel.length <= 30) {
            b.setPositiveButton(if (db.isSaved(expr)) "Remover expressão" else "Salvar como expressão") { _, _ ->
                if (db.isSaved(expr)) { db.remove(expr); Toast.makeText(this, "Expressão removida", Toast.LENGTH_SHORT).show() }
                else { db.save(expr, s.text, base, maxOf(0, s.sec)); Toast.makeText(this, "「$sel」 salva como expressão: fica destacada e tocável nos textos", Toast.LENGTH_LONG).show() }
                render()
            }
        }
        b.setNeutralButton("🔊 Ouvir") { _, _ -> speakText(sel) }
        b.show()
    }

    // ---------- IA: as palavras certas de um trecho ----------

    /**
     * Pede ao Gemini as palavras do trecho (inteiras, com leitura, classe e significado) e deixa salvar
     * cada uma: a que o Kuromoji já conhece é salva como palavra normal; as outras viram palavras
     * próprias (classe "ia") com o significado guardado, destacadas e tocáveis onde aparecerem.
     */
    private fun aiWords(sel: String, s: Texts.Sentence) {
        if (!Ai.enabled(this)) { Toast.makeText(this, "Ative a IA em Configurações", Toast.LENGTH_LONG).show(); return }
        val (dlg, label) = progressDialog("✦ Separando as palavras")
        label.text = sel
        Ai.analyzeWords(this, sel, s.text) { list, err ->
            if (dlg.isShowing) dlg.dismiss()
            if (isFinishing) return@analyzeWords
            if (list == null || list.isEmpty()) { Toast.makeText(this, "Não consegui: ${err ?: "sem palavras"}", Toast.LENGTH_LONG).show(); return@analyzeWords }
            val db = VocabDb.get(this)
            val known = try { Jp.wordsAll(s.text) } catch (_: Exception) { emptyList() }
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(12), dp(22), dp(8)) }
            box.addView(TextView(this).apply { text = sel; textSize = 18f; setTextColor(getColor(R.color.text)); setPadding(0, 0, 0, dp(8)) })
            for (aw in list) {
                // Palavra que o dicionário já conhece: salva como palavra normal (sem duplicar)
                val w = known.firstOrNull { it.base == aw.base } ?: Jp.Word(aw.surface, aw.surface, aw.base, aw.reading, aw.pos, VocabDb.AI, "", 0, 0)
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
                row.addView(TextView(this).apply {
                    textSize = 15f; setTextColor(getColor(R.color.text))
                    this.text = (if (aw.reading != aw.base) "${aw.base}（${aw.reading}）" else aw.base) + " · ${aw.pos}\n" + aw.meaning +
                        (if (w.posKey != VocabDb.AI) "" else "  ✦")
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val save = Button(this).apply { textSize = 12f; isAllCaps = false; minHeight = 0; minimumHeight = 0; setPadding(dp(10), dp(4), dp(10), dp(4)) }
                val kn = Button(this).apply { textSize = 12f; isAllCaps = false; minHeight = 0; minimumHeight = 0; setPadding(dp(10), dp(4), dp(10), dp(4)) }
                val kanji = if (w.base.any(StudyGen::isKanji)) Button(this).apply { textSize = 12f; isAllCaps = false; minHeight = 0; minimumHeight = 0; setPadding(dp(10), dp(4), dp(10), dp(4)) } else null
                fun refresh() { save.text = if (db.isSaved(w)) "★" else "☆"; kn.text = if (db.isKnown(w)) "✓" else "Conheço"; kanji?.text = if (db.isKnownKanji(w)) "✍✓" else "✍" }
                fun saveIt() {
                    db.save(w, s.text, base, maxOf(0, s.sec))
                    if (aw.meaning.isNotBlank()) db.putTranslation(w.base, aw.meaning)
                }
                kanji?.setOnClickListener {
                    if (db.isKnownKanji(w)) db.setKnownKanji(w, false) else { if (!db.isSaved(w)) saveIt(); db.setKnownKanji(w, true) }
                    refresh()
                    Toast.makeText(this, if (db.isKnownKanji(w)) "${w.base}: sei escrever (fora dos exercícios de kanji)" else "${w.base} volta aos exercícios de kanji", Toast.LENGTH_SHORT).show()
                }
                save.setOnClickListener { if (db.isSaved(w)) db.remove(w) else saveIt(); refresh(); render() }
                kn.setOnClickListener {
                    if (db.isKnown(w)) db.setKnown(w, false) else { if (!db.isSaved(w)) saveIt(); db.setKnown(w, true) }
                    refresh(); render()
                }
                refresh()
                row.addView(save, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
                row.addView(kn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
                if (kanji != null) row.addView(kanji, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
                box.addView(row)
            }
            box.addView(TextView(this).apply {
                text = "✦ = palavra que o dicionário do app não tinha: fica guardada com o significado da IA e destacada nos textos."
                textSize = 12f; setTextColor(getColor(R.color.text_secondary)); setPadding(0, dp(10), 0, 0)
            })
            AlertDialog.Builder(this).setTitle("✦ Palavras do trecho").setView(ScrollView(this).apply { addView(box) })
                .setNegativeButton("Fechar", null).show()
        }
    }

    /** O trecho como palavra do vocabulário (classe "expressão", leitura pelo Kuromoji). */
    private fun expressionWord(text: String, sentence: String): Jp.Word {
        val reading = try {
            Jp.hiragana(Jp.tokenize(text).joinToString("") { t -> if (t.reading == "*" || t.reading.isNullOrEmpty()) t.surface else t.reading })
        } catch (_: Exception) { text }
        val start = sentence.indexOf(text).coerceAtLeast(0)
        return Jp.Word(text, text, text, reading, "expressão", VocabDb.EXPR, "", start, start + text.length)
    }

    /** Tradução do parágrafo (Gemini quando houver, senão ML Kit; pede o que falta). */
    private fun paragraphTranslation(para: List<Texts.Sentence>): TextView {
        val tv = TextView(this).apply {
            textSize = 14f
            setTextColor(theme.secondary)
            setPadding(0, 0, 0, dp(20))
        }
        fun fill() {
            val parts = para.map { s -> Tr.aiCached(this, s.text) ?: Tr.cached(this, s.text) }
            tv.text = parts.joinToString(" ") { it ?: "…" }
        }
        fill()
        for (s in para) if (Tr.aiCached(this, s.text) == null && Tr.cached(this, s.text) == null) {
            Tr.translate(this, s.text) { if (!isFinishing) fill() }
        }
        return tv
    }

    /** Frase: texto, tradução (tocar alterna), padrões de gramática e áudio. */
    private fun showSentence(s: Texts.Sentence) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(16), dp(22), dp(6)) }
        box.addView(TextView(this).apply {
            textSize = 20f; setTextColor(getColor(R.color.text)); setTextIsSelectable(true)
            Furigana.set(this@ReaderActivity, this, Jp.highlight(s.text, VocabDb.studyKeys, getColor(R.color.saved_word), VocabDb.knownKeys, getColor(R.color.known_word)), s.text)
        })
        val tr = TextView(this).apply { textSize = 15f; setPadding(0, dp(8), 0, dp(8)) }
        TrSwitch.bind(this, tr, s.text)
        box.addView(tr)
        val hits = try { Grammar.find(s.text) } catch (_: Exception) { emptyList() }
        if (hits.isNotEmpty()) {
            val sb = SpannableStringBuilder()
            for ((i, h) in hits.withIndex()) {
                if (i > 0) sb.append("\n\n")
                val a = sb.length
                sb.append(h.point.name).append("  ").append(h.point.level)
                sb.setSpan(StyleSpan(Typeface.BOLD), a, sb.length, 0)
                sb.append("\n").append(h.point.explanation)
            }
            box.addView(TextView(this).apply { text = sb; textSize = 14f; setTextColor(getColor(R.color.text)) })
        }
        val b = AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(box) }).setNegativeButton("Fechar", null)
        b.setPositiveButton("▶ Ouvir", null)
        val dlg = b.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { speak(s) }
    }

    // ---------- ouvir: áudio do texto ou voz do celular ----------

    private var highlighted: Pair<TextView, BackgroundColorSpan>? = null

    private fun highlight(s: Texts.Sentence?) {
        highlighted?.let { (tv, span) -> (tv.text as? Spannable)?.removeSpan(span) }
        highlighted = null
        val (tv, r) = placed[s ?: return] ?: return
        val span = BackgroundColorSpan(theme.highlight)
        (tv.text as? Spannable)?.setSpan(span, r.first, (r.last + 1).coerceAtMost(tv.text.length), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        highlighted = tv to span
    }

    /** Lê a frase: com o áudio do texto quando houver, senão com a voz do celular. */
    private fun speak(s: Texts.Sentence) {
        if (hasAudio && s.sec >= 0) playSentence(s) else speakText(s.text, s)
    }

    private fun speakText(text: String, s: Texts.Sentence? = null) {
        val t = tts
        if (t == null) {
            Toast.makeText(this, "Abrindo a voz do celular…", Toast.LENGTH_SHORT).show()
            Thread {
                val opened = try { AudioGen.openTts(this) } catch (e: Exception) { runOnUiThread { Toast.makeText(this, e.message, Toast.LENGTH_LONG).show() }; null }
                if (opened != null) runOnUiThread { if (isFinishing) opened.shutdown() else { tts = opened; speakText(text, s) } }
            }.start()
            return
        }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) { runOnUiThread { highlight(null) } }
            @Deprecated("API antiga") override fun onError(utteranceId: String) { runOnUiThread { highlight(null) } }
        })
        highlight(s)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reader")
    }

    private fun playSentence(s: Texts.Sentence) {
        val all = paragraphs.flatten()
        val next = all.getOrNull(all.indexOf(s) + 1)?.sec ?: (s.sec + 8)
        val p = player ?: run {
            val uri = Recordings.find(this, "$base.wav")?.uri ?: return
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(this@ReaderActivity, uri)
                prepare()
            }.also { player = it }
        }
        try {
            val start = maxOf(0, s.sec * 1000 - 300)
            p.seekTo(start)
            p.start()
            highlight(s)
            ui.removeCallbacks(stopAt)
            stopAt = Runnable { try { if (p.isPlaying) p.pause() } catch (_: IllegalStateException) {}; highlight(null) }
            ui.postDelayed(stopAt, (maxOf(next, s.sec + 1) * 1000L - 200 - start))
        } catch (_: Exception) {}
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(stopAt)
        try { if (player?.isPlaying == true) player?.pause() } catch (_: IllegalStateException) {}
        try { tts?.stop() } catch (_: Exception) {}
        if (restored) Prefs(this).setReaderPos(base, scroll.scrollY)
    }

    override fun onDestroy() {
        popup?.dismiss()
        player?.release(); player = null
        tts?.shutdown(); tts = null
        super.onDestroy()
    }

    // ---------- menu ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean = true

    /**
     * Montado a cada abertura (o motor de voz e o áudio podem mudar): leitura, página, áudio,
     * ✦ IA num submenu (tradução e, com o Gemini, o áudio), arquivo.
     */
    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val prefs = Prefs(this)
        menu.clear()
        menu.add(0, 1, 0, "Estudar").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        // Leitura
        if (hasAudio) menu.add(0, 8, 0, "Ouvir com legenda")
        menu.add(0, 5, 0, if (prefs.readerTranslations) "Esconder traduções" else "Mostrar traduções")
        menu.add(0, 2, 0, "Tema da página")
        // Áudio e IA
        val audioLabel = if (hasAudio) "Criar áudio de novo" else "Criar áudio do texto"
        val gemini = prefs.ttsEngine == AudioGen.GEMINI
        if (!gemini) menu.add(0, 7, 0, "$audioLabel (voz do celular)")
        val ai = menu.addSubMenu(0, 20, 0, "✦ Inteligência artificial")
        ai.add(0, 6, 0, "Traduzir o texto")
        if (gemini) ai.add(0, 7, 0, "$audioLabel (Gemini)")
        // Arquivo
        menu.add(0, 9, 0, "Renomear")
        menu.add(0, 10, 0, "Apagar")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val prefs = Prefs(this)
        when (item.itemId) {
            android.R.id.home -> finish()
            1 -> Recordings.find(this, "$base.txt")?.let {
                startActivity(Intent(this, StudyMenuActivity::class.java).putExtra(StudyActivity.EXTRA_URI, it.uri).putExtra(StudyActivity.EXTRA_NAME, it.name))
            }
            2 -> {
                val list = allThemes
                val cur = list.indexOfFirst { it.id == prefs.readerTheme }.coerceAtLeast(0)
                AlertDialog.Builder(this).setTitle("Tema da página")
                    .setSingleChoiceItems(list.map { it.label }.toTypedArray(), cur) { d, i -> prefs.readerTheme = list[i].id; render(); styleToolbar(); d.dismiss() }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
            5 -> { prefs.readerTranslations = !prefs.readerTranslations; render() }
            6 -> translateAll()
            7 -> createAudio()
            8 -> Recordings.find(this, "$base.txt")?.let {
                startActivity(Intent(this, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_URI, it.uri).putExtra(ViewerActivity.EXTRA_NAME, it.name))
            }
            9 -> Recordings.renameDialog(this, name) { load() }
            10 -> AlertDialog.Builder(this).setMessage("Apagar este texto?").setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Apagar") { _, _ ->
                    Recordings.find(this, "$base.txt")?.let { Recordings.delete(this, it.uri) }
                    Recordings.find(this, "$base.wav")?.let { Recordings.delete(this, it.uri) }
                    finish()
                }.show()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun progressDialog(title: String): Pair<AlertDialog, TextView> {
        val label = TextView(this).apply { text = "Preparando…"; textSize = 14f; setTextColor(getColor(R.color.text)); setPadding(dp(24), dp(16), dp(24), dp(8)) }
        return AlertDialog.Builder(this).setTitle(title).setView(label).setCancelable(false)
            .setNegativeButton("Parar") { _, _ -> Ai.stopRequested = true; AudioGen.stopRequested = true }.show() to label
    }

    private fun translateAll() {
        if (!Ai.enabled(this)) { Toast.makeText(this, "Ative a IA em Configurações", Toast.LENGTH_LONG).show(); return }
        val (dlg, label) = progressDialog("✦ Traduzindo o texto")
        Ai.translateRecording(this, base, onlyMissing = true,
            progress = { d, t -> label.text = "$d de $t frases" },
            done = { n, err ->
                if (dlg.isShowing) dlg.dismiss()
                Toast.makeText(this, err?.let { "Parou: $it ($n traduzidas)" } ?: "$n frases traduzidas", Toast.LENGTH_LONG).show()
                Prefs(this).readerTranslations = true
                render(); invalidateOptionsMenu()
            })
    }

    private fun createAudio(engine: String = Prefs(this).ttsEngine, allowOther: Boolean = false) {
        val (dlg, label) = progressDialog("Criando o áudio")
        AudioGen.generate(this, "$base.txt", engine, allowOther,
            progress = { msg, d, t -> label.text = "$msg\n$d de $t frases" },
            done = { err, ok, total ->
                if (dlg.isShowing) dlg.dismiss()
                if (err == null) { Toast.makeText(this, "Áudio criado", Toast.LENGTH_LONG).show(); player?.release(); player = null; load(); return@generate }
                // Parou no meio: o que foi gerado fica guardado; dá para completar com o outro motor
                val b = AlertDialog.Builder(this).setTitle("O áudio não terminou").setMessage(
                    err + if (ok in 1 until total) "\n\nCriar de novo continua de onde parou." else ""
                ).setNegativeButton("Fechar", null)
                if (total > 0 && ok < total) {
                    if (engine == AudioGen.GEMINI) b.setPositiveButton("Completar com a voz do celular") { _, _ -> createAudio(AudioGen.PHONE, allowOther = true) }
                    else if (Ai.mode(this) == Ai.CLOUD) b.setPositiveButton("Completar com o Gemini") { _, _ -> createAudio(AudioGen.GEMINI, allowOther = true) }
                }
                b.show()
            })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
