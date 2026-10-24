package com.keveny.legendajp

import android.app.Activity
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/**
 * Sessão de estudo gerada a partir de uma gravação: ordenar a frase, lacuna com áudio,
 * montar a palavra com kanji, múltipla escolha (leitura/palavra) e gramática da frase.
 */
class StudyActivity : Activity() {

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_NAME = "name"
        private const val MAX_ITEMS = 12
    }

    private sealed class Item(val sentence: String, val sec: Int) {
        class Order(sentence: String, sec: Int, val chunks: List<String>) : Item(sentence, sec)
        class Cloze(sentence: String, sec: Int, val word: Jp.Word, val options: List<String>) : Item(sentence, sec)
        class Kanji(sentence: String, sec: Int, val word: Jp.Word, val tiles: List<String>) : Item(sentence, sec)
        class Choice(sentence: String, sec: Int, val prompt: String, val answer: String, val options: List<String>, val note: String) : Item(sentence, sec)
        class GrammarQ(sentence: String, sec: Int, val hit: Grammar.Hit, val options: List<Grammar.Point>) : Item(sentence, sec)
    }

    private lateinit var progress: TextView
    private lateinit var prompt: TextView
    private lateinit var sentenceView: TextView
    private lateinit var built: TextView
    private lateinit var btnAudio: Button
    private lateinit var btnUndo: Button
    private lateinit var choices: FlowLayout
    private lateinit var feedback: TextView
    private lateinit var btnNext: Button

    private var items: List<Item> = emptyList()
    private var index = -1
    private var correct = 0
    private var answered = false
    private val builtParts = mutableListOf<String>()
    private val usedTiles = mutableListOf<Button>()
    private var player: MediaPlayer? = null
    private var prepared = false
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_study)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Estudar"
        progress = findViewById(R.id.progress)
        prompt = findViewById(R.id.prompt)
        sentenceView = findViewById(R.id.sentence)
        built = findViewById(R.id.built)
        btnAudio = findViewById(R.id.btnAudio)
        btnUndo = findViewById(R.id.btnUndo)
        choices = findViewById(R.id.choices)
        feedback = findViewById(R.id.feedback)
        btnNext = findViewById(R.id.btnNext)

        val uri = intent.getParcelableExtra(EXTRA_URI, Uri::class.java) ?: run { finish(); return }
        val name = intent.getStringExtra(EXTRA_NAME) ?: ""
        btnUndo.setOnClickListener { undoTile() }
        btnNext.setOnClickListener { onNext() }
        prompt.text = "Preparando os exercícios…"

        Thread {
            val text = try { Recordings.readText(this, uri) } catch (_: Exception) { "" }
            val lines = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }
                .map { Recordings.parseLine(it) }
            val wav = Recordings.find(this, Recordings.baseName(name) + ".wav")?.uri
            val built = buildItems(lines)
            runOnUiThread {
                if (wav != null) setupPlayer(wav)
                if (built.isEmpty()) {
                    prompt.text = "Esta gravação não tem frases suficientes para montar exercícios."
                    btnNext.text = "Fechar"
                    btnNext.setOnClickListener { finish() }
                } else {
                    items = built
                    next()
                }
            }
        }.start()
    }

    // ---------- geração dos itens ----------

    private fun isKanji(c: Char) = c in '一'..'鿿'

    private fun buildItems(lines: List<Pair<Int, String>>): List<Item> {
        val rnd = kotlin.random.Random(System.nanoTime())
        val sentences = lines.filter { it.second.length >= 4 }
        if (sentences.isEmpty()) return emptyList()
        val wordsBySentence = sentences.map { (sec, s) -> Triple(sec, s, try { Jp.words(s) } catch (_: Exception) { emptyList() }) }
        val allWords = wordsBySentence.flatMap { it.third }
            .filter { it.posKey in setOf("名詞", "動詞", "形容詞", "副詞") && it.base.length >= 1 }
            .distinctBy { it.key }
        val kanjiPool = allWords.flatMap { w -> w.base.filter(::isKanji).toList() }.distinct()
        val saved = VocabDb.savedKeys

        val order = mutableListOf<Item>()
        val cloze = mutableListOf<Item>()
        val kanji = mutableListOf<Item>()
        val choice = mutableListOf<Item>()
        val grammar = mutableListOf<Item>()

        for ((sec, s, ws) in wordsBySentence.shuffled(rnd)) {
            // Ordenar: frases de 4 a 9 pedaços
            val chunks = try { Jp.chunks(s) } catch (_: Exception) { emptyList() }
            if (chunks.size in 4..9 && order.size < 4) order.add(Item.Order(s, sec, chunks))

            // Lacuna: prefere palavra salva; alternativas da mesma classe
            val candidates = ws.filter { it.posKey in setOf("名詞", "動詞", "形容詞") && it.surface.length >= 2 }
            val target = candidates.firstOrNull { it.key in saved } ?: candidates.randomOrNull(rnd)
            if (target != null && cloze.size < 4) {
                val distractors = allWords.filter { it.key != target.key && it.posKey == target.posKey }
                    .shuffled(rnd).take(3).map { it.surface }
                val fallback = allWords.filter { it.key != target.key }.shuffled(rnd).map { it.surface }
                val opts = (distractors + fallback).distinct().take(3)
                if (opts.size == 3) cloze.add(Item.Cloze(s, sec, target, (opts + target.surface).shuffled(rnd)))
            }

            // Kanji: palavra com 2+ kanji; 8 peças
            val kw = ws.filter { w -> w.base.count(::isKanji) >= 2 && w.base.all { isKanji(it) || it in '぀'..'ヿ' } }
                .randomOrNull(rnd)
            if (kw != null && kanji.size < 3) {
                val need = kw.base.filter(::isKanji).toList()
                val extra = kanjiPool.filter { it !in need }.shuffled(rnd).take(maxOf(0, 8 - need.size))
                if (need.size + extra.size >= 4) kanji.add(Item.Kanji(s, sec, kw, (need + extra).map { it.toString() }.shuffled(rnd)))
            }

            // Múltipla escolha: leitura → palavra, ou palavra → leitura
            val cw = ws.filter { it.posKey in setOf("名詞", "動詞", "形容詞") && it.reading != it.base && it.base.any(::isKanji) }
                .randomOrNull(rnd)
            if (cw != null && choice.size < 3) {
                val others = allWords.filter { it.key != cw.key && it.reading != it.base }.shuffled(rnd).take(3)
                if (others.size == 3) {
                    if (rnd.nextBoolean()) {
                        choice.add(Item.Choice(s, sec, "Qual palavra se lê 「${cw.reading}」?", cw.base,
                            (others.map { it.base } + cw.base).shuffled(rnd), "${cw.base}: ${cw.pos}"))
                    } else {
                        choice.add(Item.Choice(s, sec, "Como se lê 「${cw.base}」?", cw.reading,
                            (others.map { it.reading } + cw.reading).shuffled(rnd), "${cw.base}: ${cw.pos}"))
                    }
                }
            }

            // Gramática: qual padrão aparece na frase
            val hits = try { Grammar.find(s) } catch (_: Exception) { emptyList() }
            val hit = hits.filter { it.point.level != "N5" || hits.size == 1 }.randomOrNull(rnd) ?: hits.randomOrNull(rnd)
            if (hit != null && grammar.size < 3) {
                val present = hits.map { it.point.id }.toSet()
                val wrong = Grammar.all.filter { it.id !in present }.shuffled(rnd).take(3)
                grammar.add(Item.GrammarQ(s, sec, hit, (wrong + hit.point).shuffled(rnd)))
            }
        }
        val mixed = (order + cloze + kanji + choice + grammar).shuffled(rnd)
        return mixed.take(MAX_ITEMS)
    }

    // ---------- apresentação ----------

    private fun next() {
        index++
        if (index >= items.size) { summary(); return }
        val item = items[index]
        answered = false
        builtParts.clear()
        usedTiles.clear()
        choices.removeAllViews()
        feedback.visibility = View.GONE
        built.visibility = View.GONE
        btnUndo.visibility = View.GONE
        sentenceView.visibility = View.GONE
        btnAudio.visibility = if (prepared) View.VISIBLE else View.GONE
        btnAudio.setOnClickListener { playFrom(item.sec) }
        progress.text = "${index + 1} de ${items.size} · $correct certas"
        btnNext.text = "Verificar"
        btnNext.isEnabled = true

        when (item) {
            is Item.Order -> {
                prompt.text = "Monte a frase na ordem certa (ouça o áudio se precisar)."
                built.visibility = View.VISIBLE
                built.text = ""
                btnUndo.visibility = View.VISIBLE
                for (c in item.chunks.shuffled()) addTile(c)
            }
            is Item.Cloze -> {
                prompt.text = "Qual palavra completa a frase?"
                sentenceView.visibility = View.VISIBLE
                sentenceView.text = item.sentence.replaceRange(item.word.start, item.word.end, "＿＿＿")
                for (o in item.options) addChoice(o) { chosen -> chosen == item.word.surface }
                btnNext.text = "Próxima"; btnNext.isEnabled = false
            }
            is Item.Kanji -> {
                val tr = Tr.cached(this, item.word.base)
                prompt.text = "Monte a palavra 「${item.word.reading}」" + (if (tr != null) " ($tr)" else "") + " com os kanji."
                built.visibility = View.VISIBLE
                built.text = ""
                btnUndo.visibility = View.VISIBLE
                for (t in item.tiles) addTile(t)
            }
            is Item.Choice -> {
                prompt.text = item.prompt
                for (o in item.options) addChoice(o) { chosen -> chosen == item.answer }
                btnNext.text = "Próxima"; btnNext.isEnabled = false
            }
            is Item.GrammarQ -> {
                prompt.text = "Qual padrão gramatical aparece nesta frase?"
                sentenceView.visibility = View.VISIBLE
                sentenceView.text = item.sentence
                for (o in item.options) addChoice(o.name) { chosen -> chosen == item.hit.point.name }
                btnNext.text = "Próxima"; btnNext.isEnabled = false
            }
        }
    }

    private fun addTile(label: String) {
        val b = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 20f
            setBackgroundResource(R.drawable.bg_button_outline)
            setTextColor(getColor(R.color.text))
            minHeight = 0; minimumHeight = 0
            setPadding(dp(14), dp(10), dp(14), dp(10))
            stateListAnimator = null
        }
        b.setOnClickListener {
            if (answered) return@setOnClickListener
            builtParts.add(label)
            usedTiles.add(b)
            b.visibility = View.INVISIBLE
            built.text = builtParts.joinToString("")
        }
        choices.addView(b)
    }

    private fun undoTile() {
        if (answered || builtParts.isEmpty()) return
        builtParts.removeAt(builtParts.size - 1)
        usedTiles.removeAt(usedTiles.size - 1).visibility = View.VISIBLE
        built.text = builtParts.joinToString("")
    }

    private fun addChoice(label: String, isRight: (String) -> Boolean) {
        val b = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 18f
            setBackgroundResource(R.drawable.bg_button_outline)
            setTextColor(getColor(R.color.text))
            minHeight = 0; minimumHeight = 0
            setPadding(dp(16), dp(12), dp(16), dp(12))
            stateListAnimator = null
        }
        b.setOnClickListener {
            if (answered) return@setOnClickListener
            answered = true
            val ok = isRight(label)
            if (ok) correct++
            // Marca a escolhida e a certa
            for (i in 0 until choices.childCount) {
                val c = choices.getChildAt(i) as Button
                if (isRight(c.text.toString())) { c.setBackgroundResource(R.drawable.bg_button_primary); c.setTextColor(0xFFFFFFFF.toInt()) }
                else if (c === b) { c.setBackgroundResource(R.drawable.bg_button_stop); c.setTextColor(0xFFFFFFFF.toInt()) }
            }
            showFeedback(ok)
            btnNext.isEnabled = true
        }
        choices.addView(b)
    }

    private fun onNext() {
        val item = items.getOrNull(index) ?: return
        if (!answered) {
            when (item) {
                is Item.Order -> {
                    answered = true
                    val target = item.chunks.joinToString("")
                    val ok = builtParts.joinToString("") == target
                    if (ok) correct++
                    built.text = target
                    showFeedback(ok)
                }
                is Item.Kanji -> {
                    answered = true
                    val target = item.word.base.filter(::isKanji)
                    val ok = builtParts.joinToString("") == target
                    if (ok) correct++
                    built.text = item.word.base
                    showFeedback(ok)
                }
                else -> { Toast.makeText(this, "Escolha uma alternativa", Toast.LENGTH_SHORT).show(); return }
            }
            btnNext.text = "Próxima"
            progress.text = "${index + 1} de ${items.size} · $correct certas"
            return
        }
        next()
    }

    private fun showFeedback(ok: Boolean) {
        val item = items[index]
        val sb = StringBuilder(if (ok) "✓ Certo!" else "✗ Não foi dessa vez.")
        when (item) {
            is Item.Order -> sb.append("\n\n").append(item.sentence)
            is Item.Cloze -> sb.append("\n\n").append(item.sentence).append("\n").append(item.word.base)
                .append(if (item.word.reading != item.word.base) " (${item.word.reading})" else "").append(" · ").append(item.word.pos)
            is Item.Kanji -> sb.append("\n\n").append(item.word.base).append(" (").append(item.word.reading).append(") · ").append(item.word.pos)
                .append("\n").append(item.sentence)
            is Item.Choice -> sb.append("\n\n").append(item.note).append("\n").append(item.sentence)
            is Item.GrammarQ -> sb.append("\n\n").append(item.hit.point.name).append(" · ").append(item.hit.point.level)
                .append("\n").append(item.hit.point.explanation).append("\nEx.: ").append(item.hit.point.example)
        }
        Tr.cached(this, item.sentence)?.let { sb.append("\n").append(it) }
        feedback.text = sb
        feedback.visibility = View.VISIBLE
    }

    private fun summary() {
        choices.removeAllViews()
        sentenceView.visibility = View.GONE
        built.visibility = View.GONE
        btnUndo.visibility = View.GONE
        btnAudio.visibility = View.GONE
        feedback.visibility = View.GONE
        progress.text = ""
        prompt.textSize = 18f
        prompt.text = "Sessão concluída: $correct de ${items.size} certas."
        btnNext.text = "Repetir"
        btnNext.isEnabled = true
        btnNext.setOnClickListener {
            prompt.textSize = 14f
            correct = 0; index = -1
            items = items.shuffled()
            btnNext.setOnClickListener { onNext() }
            next()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------- áudio ----------

    private fun setupPlayer(uri: Uri) {
        val p = MediaPlayer()
        p.setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        )
        p.setOnPreparedListener { prepared = true; if (index >= 0) btnAudio.visibility = View.VISIBLE }
        p.setOnErrorListener { _, _, _ -> true }
        try {
            p.setDataSource(this, uri)
            p.prepareAsync()
            player = p
        } catch (_: Exception) { p.release() }
    }

    private var stopAt = Runnable {}

    private fun playFrom(sec: Int) {
        val p = player ?: return
        if (!prepared || sec < 0) return
        try {
            p.seekTo(maxOf(0, sec * 1000 - 500))
            p.start()
            // Toca uns 8 s (a frase e um pouco do que vem depois) e para
            ui.removeCallbacks(stopAt)
            stopAt = Runnable { try { if (p.isPlaying) p.pause() } catch (_: IllegalStateException) {} }
            ui.postDelayed(stopAt, 8000)
        } catch (_: IllegalStateException) {}
    }

    override fun onPause() {
        super.onPause()
        try { if (prepared) player?.pause() } catch (_: IllegalStateException) {}
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
