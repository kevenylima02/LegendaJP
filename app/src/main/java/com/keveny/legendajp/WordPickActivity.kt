package com.keveny.legendajp

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Escolha das palavras antes do estudo. Um cartão de resumo por palavra (leitura, significado,
 * kanji com leituras e partes, a frase da gravação com áudio) e três escolhas:
 * Pular (só desta vez), Já conheço (sai dos exercícios de vez) e Estudar (entra na sessão).
 * Ao chegar no número das Configurações (10 por padrão) mostra a lista e o botão Começar.
 */
class WordPickActivity : Activity() {

    private enum class Choice { SKIP, KNOWN, STUDY }
    private data class Decision(val index: Int, val choice: Choice, val wasSaved: Boolean)

    private var uri: Uri? = null
    private var name = ""
    private var saved = false
    private var focus = StudyGen.Focus.MISTO
    private var target = 10
    private var recording = ""

    private var cands: List<StudyGen.Candidate> = emptyList()
    private var index = 0
    private val decisions = ArrayList<Decision>()
    private val chosen get() = decisions.filter { it.choice == Choice.STUDY }.map { cands[it.index] }
    private var loaded = false
    private var excludedSkip: Set<String> = emptySet()
    private var excludedStudied: Set<String> = emptySet()

    private lateinit var progress: TextView
    private lateinit var scroll: ScrollView
    private lateinit var card: View
    private lateinit var summary: TextView
    private lateinit var actions: View
    private lateinit var btnSave: Button
    private lateinit var btnAudio: Button
    private lateinit var btnBack: Button
    private lateinit var btnStart: Button
    private lateinit var strokeScroll: HorizontalScrollView
    private lateinit var strokeRow: LinearLayout

    // áudio
    private var player: MediaPlayer? = null
    private var playerRec: String? = null
    private val ui = Handler(Looper.getMainLooper())
    private var stopAt = Runnable {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_word_pick)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        saved = intent.getBooleanExtra(StudyActivity.EXTRA_SAVED, false)
        uri = intent.getParcelableExtra(StudyActivity.EXTRA_URI, Uri::class.java)
        if (!saved && uri == null) { finish(); return }
        name = intent.getStringExtra(StudyActivity.EXTRA_NAME) ?: ""
        focus = try { StudyGen.Focus.valueOf(intent.getStringExtra(StudyActivity.EXTRA_FOCUS) ?: "MISTO") } catch (_: Exception) { StudyGen.Focus.MISTO }
        recording = if (saved) StudyActivity.SAVED_RECORDING else Recordings.baseName(name)
        target = Prefs(this).studyWords
        title = "Escolher palavras · ${focus.label}"

        progress = findViewById(R.id.progress)
        scroll = findViewById(R.id.scroll)
        card = findViewById(R.id.card)
        summary = findViewById(R.id.summary)
        actions = findViewById(R.id.actions)
        btnSave = findViewById(R.id.btnSave)
        btnAudio = findViewById(R.id.btnAudio)
        btnBack = findViewById(R.id.btnBack)
        btnStart = findViewById(R.id.btnStart)
        strokeScroll = findViewById(R.id.strokeScroll)
        strokeRow = findViewById(R.id.strokeRow)

        findViewById<Button>(R.id.btnSkip).setOnClickListener { decide(Choice.SKIP) }
        setupOrder()
        findViewById<Button>(R.id.btnKnown).setOnClickListener { decide(Choice.KNOWN) }
        findViewById<Button>(R.id.btnStudy).setOnClickListener { decide(Choice.STUDY) }
        btnBack.setOnClickListener { undo() }
        btnStart.setOnClickListener { start() }

        card.visibility = View.GONE
        actions.visibility = View.GONE
        progress.text = "Separando as palavras…"
        updateBottom()

        Thread {
            val lines = if (saved) StudyGen.linesFromVocab(this) else {
                val text = try { Recordings.readText(this, uri!!) } catch (_: Exception) { "" }
                StudyGen.lines(text, recording)
            }
            // Fora: já estudadas (voltam pela Revisão) e puladas há menos de 24 h
            val db = VocabDb.get(this)
            excludedSkip = db.skippedKeys()
            excludedStudied = Srs.studiedKeys(this)
            val prefs = Prefs(this)
            var all = StudyGen.candidates(this, lines, focus, prefs.levels, if (saved) VocabDb.savedKeys else null,
                excludedSkip + excludedStudied)
            if (saved) {
                // No vocabulário salvo, "ordem" = mais recentes primeiro
                val order = db.words().map { it.key }.withIndex().associate { it.value to it.index }
                all = all.map { it.copy(position = order[it.word.key] ?: Int.MAX_VALUE) }
            }
            val list = StudyGen.sortCandidates(all, prefs.levels, prefs.pickOrder)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                cands = list
                loaded = true
                if (list.isEmpty()) showSummary() else showCard()
            }
        }.start()
    }

    /** Ordem da escolha (aleatória, mais frequentes, ordem do áudio / mais recentes); reordena o que falta. */
    private fun setupOrder() {
        val group = findViewById<android.widget.RadioGroup>(R.id.order)
        if (saved) findViewById<android.widget.RadioButton>(R.id.orderAudio).text = "Recentes"
        val prefs = Prefs(this)
        group.check(when (prefs.pickOrder) { "frequentes" -> R.id.orderFreq; "audio" -> R.id.orderAudio; else -> R.id.orderRandom })
        group.setOnCheckedChangeListener { _, id ->
            prefs.pickOrder = when (id) { R.id.orderFreq -> "frequentes"; R.id.orderAudio -> "audio"; else -> "aleatoria" }
            if (!loaded || index >= cands.size) return@setOnCheckedChangeListener
            cands = cands.take(index) + StudyGen.sortCandidates(cands.drop(index), prefs.levels, prefs.pickOrder)
            showCard()
        }
    }

    // =====================================================================
    // Escolhas
    // =====================================================================

    private fun decide(choice: Choice) {
        if (index >= cands.size) return
        val w = cands[index].word
        val wasSaved = VocabDb.get(this).isSaved(w)
        decisions.add(Decision(index, choice, wasSaved))
        if (choice == Choice.SKIP) {
            // Some da escolha por 24 h (em qualquer gravação)
            val key = w.key
            Thread { VocabDb.get(this).skipWord(key, System.currentTimeMillis() + 24 * 3_600_000L) }.start()
        }
        if (choice == Choice.STUDY && !wasSaved) {
            val line = cands[index].line
            Thread { VocabDb.get(this).let { db -> if (!db.isSaved(w)) db.save(w, line.text, line.recording ?: recording, maxOf(0, line.sec)) } }.start()
        }
        if (choice == Choice.KNOWN) {
            val line = cands[index].line
            Thread {
                val db = VocabDb.get(this)
                if (!db.isSaved(w)) db.save(w, line.text, line.recording ?: recording, maxOf(0, line.sec))
                db.setKnown(w.key, true)
            }.start()
        }
        index++
        if (chosen.size >= target || index >= cands.size) showSummary() else showCard()
    }

    /** Desfaz a última escolha (Já conheço volta a valer para exercícios). */
    private fun undo() {
        val d = decisions.removeLastOrNull() ?: return
        if (d.choice == Choice.SKIP) {
            val key = cands[d.index].word.key
            Thread { VocabDb.get(this).unskipWord(key) }.start()
        }
        if (d.choice == Choice.STUDY && !d.wasSaved) {
            val w = cands[d.index].word
            Thread { VocabDb.get(this).remove(w) }.start()
        }
        if (d.choice == Choice.KNOWN) {
            val w = cands[d.index].word
            Thread {
                val db = VocabDb.get(this)
                db.setKnown(w.key, false)
                if (!d.wasSaved) db.remove(w)
            }.start()
        }
        index = d.index
        showCard()
    }

    private fun start() {
        val keys = ArrayList(chosen.map { it.word.key })
        if (keys.isEmpty()) return
        startActivity(
            Intent(this, StudyActivity::class.java)
                .putExtra(StudyActivity.EXTRA_URI, uri)
                .putExtra(StudyActivity.EXTRA_NAME, name)
                .putExtra(StudyActivity.EXTRA_FOCUS, focus.name)
                .putExtra(StudyActivity.EXTRA_SAVED, saved)
                .putStringArrayListExtra(StudyActivity.EXTRA_KEYS, keys)
        )
        finish()
    }

    private fun updateBottom() {
        val n = chosen.size
        btnBack.isEnabled = decisions.isNotEmpty()
        btnStart.isEnabled = n > 0
        btnStart.text = if (n > 0) "Começar ($n)" else "Começar"
        val done = loaded && (n >= target || index >= cands.size)
        btnStart.setBackgroundResource(if (done && n > 0) R.drawable.bg_button_primary else R.drawable.bg_button_outline)
        btnStart.setTextColor(if (done && n > 0) 0xFFFFFFFF.toInt() else getColor(R.color.text))
    }

    // =====================================================================
    // Cartão de resumo
    // =====================================================================

    private fun showCard() {
        stopAudio()
        val c = cands[index]
        val w = c.word
        card.visibility = View.VISIBLE
        summary.visibility = View.GONE
        actions.visibility = View.VISIBLE
        scroll.scrollTo(0, 0)
        progress.text = "Escolhidas ${chosen.size} de $target · palavra ${index + 1} de ${cands.size}"
        updateBottom()

        findViewById<TextView>(R.id.base).text = w.base
        findViewById<TextView>(R.id.reading).text = if (w.reading != w.base) w.reading else ""
        val meta = mutableListOf(w.pos)
        if (w.base.any(StudyGen::isKanji)) meta.add("kanji N${c.level}")
        meta.add(if (c.sentences == 1) "em 1 frase" else "em ${c.sentences} frases")
        findViewById<TextView>(R.id.meta).text = meta.joinToString(" · ")
        updateSave(w)
        btnSave.setOnClickListener { toggleSave(c) }

        // Significado
        val meaning = findViewById<TextView>(R.id.meaning)
        val tr = Tr.cached(this, w.base)
        meaning.text = tr ?: "traduzindo…"
        if (tr == null) Tr.translate(this, w.base) { t -> if (cands.getOrNull(index) === c) meaning.text = t ?: "(sem tradução)" }

        // Kanji: leituras, significado e partes
        val info = findViewById<TextView>(R.id.kanjiInfo)
        val sec = getColor(R.color.text_secondary)
        val sb = SpannableStringBuilder()
        for ((ch, text) in Kanjidic.describe(this, w.base)) {
            if (sb.isNotEmpty()) sb.append("\n")
            val a = sb.length
            sb.append(ch.toString())
            sb.setSpan(StyleSpan(Typeface.BOLD), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append("  ").append(text)
            KanjiParts.get(this, ch)?.let { e ->
                val b = sb.length
                sb.append("\n   partes: ").append(e.parts.joinToString(" + ") { p ->
                    val m = p.element.firstOrNull()?.let { Kanjidic.get(this, it) }?.shortMeaning
                    p.element + if (!m.isNullOrBlank()) " ($m)" else ""
                })
                sb.setSpan(ForegroundColorSpan(sec), b, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        info.text = sb
        info.visibility = if (sb.isEmpty()) View.GONE else View.VISIBLE
        showStrokes(c)

        // Frase com a palavra destacada
        val s = c.line.text
        val ss = SpannableStringBuilder(s)
        if (w.start in 0 until w.end && w.end <= s.length) {
            ss.setSpan(ForegroundColorSpan(getColor(R.color.accent)), w.start, w.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            ss.setSpan(StyleSpan(Typeface.BOLD), w.start, w.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        findViewById<TextView>(R.id.sentence).text = ss
        val sentenceTr = findViewById<TextView>(R.id.sentenceTr)
        TrSwitch.bind(this, sentenceTr, s)

        btnAudio.visibility = if (c.line.sec >= 0 && c.line.recording != null) View.VISIBLE else View.GONE
        btnAudio.setOnClickListener { play(c.line) }
    }

    private fun showStrokes(c: StudyGen.Candidate) {
        strokeRow.removeAllViews()
        strokeScroll.visibility = View.GONE
        val kanji = c.word.base.filter(StudyGen::isKanji).toList().distinct()
        if (kanji.isEmpty()) return
        Thread {
            Strokes.load(this, kanji.toSet())
            val paths = kanji.mapNotNull { Strokes.get(this, it) }
            runOnUiThread {
                if (cands.getOrNull(index) !== c) return@runOnUiThread
                for (p in paths) strokeRow.addView(StrokeView(this).also { it.setStrokes(p) })
                strokeScroll.visibility = if (paths.isEmpty()) View.GONE else View.VISIBLE
            }
        }.start()
    }

    private fun updateSave(w: Jp.Word) {
        val on = VocabDb.get(this).isSaved(w)
        btnSave.text = if (on) "★ Salva" else "☆ Salvar"
    }

    private fun toggleSave(c: StudyGen.Candidate) {
        val w = c.word
        Thread {
            val db = VocabDb.get(this)
            if (db.isSaved(w)) db.remove(w) else db.save(w, c.line.text, c.line.recording ?: recording, maxOf(0, c.line.sec))
            runOnUiThread { if (cands.getOrNull(index) === c) updateSave(w) }
        }.start()
    }

    // =====================================================================
    // Fim da escolha
    // =====================================================================

    private fun showSummary() {
        stopAudio()
        card.visibility = View.GONE
        actions.visibility = View.GONE
        summary.visibility = View.VISIBLE
        scroll.scrollTo(0, 0)
        val list = chosen
        progress.text = "Escolhidas ${list.size} de $target"
        updateBottom()
        val sb = SpannableStringBuilder()
        if (cands.isEmpty()) {
            sb.append("Não há palavras novas para este foco nesta gravação.")
        } else if (list.isEmpty()) {
            sb.append("Nenhuma palavra escolhida. Toque em Voltar para rever as últimas.")
        } else {
            val a = sb.length
            sb.append("Você vai estudar ${list.size} palavra" + (if (list.size > 1) "s" else "") + ":")
            sb.setSpan(StyleSpan(Typeface.BOLD), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            for (c in list) {
                val w = c.word
                sb.append("\n• ").append(w.base)
                if (w.reading != w.base) sb.append(" (").append(w.reading).append(")")
                Tr.cached(this, w.base)?.let { sb.append(" — ").append(it) }
            }
        }
        if (excludedStudied.isNotEmpty() || excludedSkip.isNotEmpty()) {
            val b0 = sb.length
            sb.append("\n\nFora da escolha: ${excludedStudied.size} já estudadas (voltam pela Revisão) e ${excludedSkip.size} puladas nas últimas 24 h. \"Já conheço\" também fica de fora.")
            sb.setSpan(ForegroundColorSpan(getColor(R.color.text_secondary)), b0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val skipped = decisions.count { it.choice == Choice.SKIP }
        val known = decisions.count { it.choice == Choice.KNOWN }
        if (skipped + known > 0) {
            val b = sb.length
            sb.append("\n\nPuladas: $skipped · Já conheço: $known")
            sb.setSpan(ForegroundColorSpan(getColor(R.color.text_secondary)), b, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (cands.isNotEmpty() && index >= cands.size && list.size < target) {
            val b = sb.length
            sb.append("\nAcabaram as palavras desta gravação para este foco.")
            sb.setSpan(ForegroundColorSpan(getColor(R.color.text_secondary)), b, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        summary.text = sb
    }

    // =====================================================================
    // Áudio: só o trecho da frase
    // =====================================================================

    private fun play(line: StudyGen.Line) {
        val rec = line.recording ?: return
        if (rec == playerRec && player != null) { playRange(line); return }
        btnAudio.isEnabled = false
        Thread {
            val u = Recordings.find(this, "$rec.wav")?.uri
            val p = MediaPlayer()
            var ok = false
            try {
                if (u != null) {
                    p.setAudioAttributes(
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                    )
                    p.setDataSource(this, u)
                    p.prepare()
                    ok = true
                }
            } catch (_: Exception) {}
            runOnUiThread {
                btnAudio.isEnabled = true
                if (isDestroyed) { p.release(); return@runOnUiThread }
                if (ok) {
                    player?.release()
                    player = p; playerRec = rec
                    playRange(line)
                } else {
                    p.release()
                    Toast.makeText(this, "Áudio dessa frase não está na pasta", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun playRange(line: StudyGen.Line) {
        val p = player ?: return
        try {
            val startMs = maxOf(0, line.sec * 1000 - 500)
            val endMs = maxOf(startMs + 1500, line.endSec * 1000 - 300)
            p.seekTo(startMs)
            p.start()
            ui.removeCallbacks(stopAt)
            stopAt = Runnable { try { if (p.isPlaying) p.pause() } catch (_: IllegalStateException) {} }
            ui.postDelayed(stopAt, (endMs - startMs).toLong())
        } catch (_: IllegalStateException) {}
    }

    private fun stopAudio() {
        ui.removeCallbacks(stopAt)
        try { if (player?.isPlaying == true) player?.pause() } catch (_: IllegalStateException) {}
    }

    override fun onPause() {
        super.onPause()
        stopAudio()
    }

    override fun onDestroy() {
        ui.removeCallbacks(stopAt)
        player?.release()
        player = null
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
