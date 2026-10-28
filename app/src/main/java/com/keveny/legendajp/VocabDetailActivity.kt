package com.keveny.legendajp

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/** Uma palavra salva e as frases onde apareceu. */
class VocabDetailActivity : Activity() {

    companion object { const val EXTRA_ID = "id" }

    private var wordId = -1L
    private var word: VocabDb.Word? = null
    private var occ: List<VocabDb.Occurrence> = emptyList()
    private lateinit var list: ListView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vocab_detail)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        wordId = intent.getLongExtra(EXTRA_ID, -1L)
        list = findViewById(R.id.occ)
        list.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        Thread {
            val db = VocabDb.get(this)
            val w = db.word(wordId)
            val o = if (w != null) db.occurrences(wordId) else emptyList()
            runOnUiThread {
                if (w == null) { finish(); return@runOnUiThread }
                word = w
                occ = o
                title = w.base
                findViewById<TextView>(R.id.base).text = w.base
                findViewById<TextView>(R.id.reading).text = if (w.reading != w.base) w.reading else ""
                val hasKanji = w.base.any(StudyGen::isKanji)
                findViewById<TextView>(R.id.meta).text = w.pos + skillSummary(w, hasKanji)
                Score.bind(findViewById(R.id.barKanji), w.kanjiScore)
                Score.bind(findViewById(R.id.barMeaning), w.meaningScore)
                showStrokes(w.base)
                val kb = findViewById<android.widget.Button>(R.id.btnKnown)
                kb.text = if (w.known) "Conheço ✓" else "Já conheço"
                kb.setOnClickListener {
                    VocabDb.get(this).setKnown(w.key, !w.known)
                    Toast.makeText(this,
                        if (!w.known) "${w.base}: conheço a palavra. Sai dos exercícios de vocabulário" + (if (hasKanji && !w.knownKanji) "; em Kanji ficam só os de escrita" else "")
                        else "${w.base} volta a aparecer nos exercícios de vocabulário",
                        Toast.LENGTH_LONG).show()
                    load()
                }
                val kk = findViewById<android.widget.Button>(R.id.btnKnownKanji)
                kk.visibility = if (hasKanji) View.VISIBLE else View.GONE
                kk.text = if (w.knownKanji) "Escrevo ✓" else "Sei escrever"
                kk.setOnClickListener {
                    VocabDb.get(this).setKnownKanji(w.key, !w.knownKanji)
                    Toast.makeText(this,
                        if (!w.knownKanji) "${w.base}: sei escrever. Sai dos exercícios de kanji" else "${w.base} volta a aparecer nos exercícios de kanji",
                        Toast.LENGTH_SHORT).show()
                    load()
                }
                val ai = findViewById<android.widget.Button>(R.id.btnAi)
                val first = o.firstOrNull()
                ai.visibility = if (Ai.enabled(this) && first != null) android.view.View.VISIBLE else android.view.View.GONE
                ai.setOnClickListener { if (first != null) explainWithAi(w, first) }
                findViewById<android.widget.Button>(R.id.btnStudyWord).setOnClickListener {
                    startActivity(
                        Intent(this, StudyActivity::class.java)
                            .putExtra(StudyActivity.EXTRA_SAVED, true)
                            .putExtra(StudyActivity.EXTRA_WORD_KEY, w.key)
                            .putExtra(StudyActivity.EXTRA_WORD_LABEL, w.base)
                    )
                }
                adapter.notifyDataSetChanged()
            }
        }.start()
    }

    /**
     * Leitura / escrita / significado, cada uma com o seu estado: "conheço" (marca), "sei escrever" (marca)
     * ou o domínio treinado (Score) e o cartão da revisão (kanji ou significado).
     */
    private fun skillSummary(w: VocabDb.Word, hasKanji: Boolean): String {
        val st = VocabDb.get(this).skillStats(w.key)
        fun ex(p: Pair<Int, Int>) = if (p.first == 0) "" else " · ${p.first} ${if (p.first == 1) "exercício" else "exercícios"}, ${p.second} ${if (p.second == 1) "certo" else "certos"}"
        val meaning = (if (w.known) "conheço ✓" else "${Score.percent(w.meaningScore)}% treinado") + ex(st.meaning)
        val reading = (if (w.known) "conheço ✓" else if (hasKanji) "${Score.percent(w.kanjiScore)}% treinado" else "—") + (if (hasKanji) ex(st.reading) else "")
        val writing = (if (!hasKanji) "—" else if (w.knownKanji) "sei escrever ✓" else "${Score.percent(w.kanjiScore)}% treinado") + (if (hasKanji) ex(st.writing) else "")
        val sb = StringBuilder()
        sb.append("\nSignificado: $meaning")
        if (hasKanji) sb.append("\nLeitura do kanji: $reading\nEscrita do kanji: $writing")
        val cards = listOfNotNull(
            Srs.get(this, Srs.MEANING, w.key)?.let { "significado " + Srs.bucket(it) },
            if (hasKanji) Srs.get(this, Srs.KANJI, w.key)?.let { "kanji " + Srs.bucket(it) } else null,
        )
        if (cards.isNotEmpty()) sb.append("\nRevisão: " + cards.joinToString(" · "))
        if (w.known && !w.knownKanji && hasKanji) sb.append("\nEm Kanji ficam só os exercícios de escrita (Leitura → kanji, Montar, Partes, À mão).")
        return sb.toString()
    }

    /** ✦ IA: explica a palavra na primeira frase onde ela apareceu e traduz a frase com contexto. */
    private fun explainWithAi(w: VocabDb.Word, o: VocabDb.Occurrence) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val head = TextView(this).apply {
            text = o.sentence
            textSize = 17f
            setTextColor(getColor(R.color.text))
        }
        val panelBox = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        box.addView(head)
        box.addView(panelBox)
        val scroll = android.widget.ScrollView(this).apply { addView(box) }
        val level = Prefs(this).level
        val panel = AiPanel(this, panelBox, listOf(
            Ai.Request(Ai.Task.WORD, o.sentence, o.recording, o.surface, "forma de dicionário ${w.base}, leitura ${w.reading}, ${w.pos}", level),
            Ai.Request(Ai.Task.TRANSLATE, o.sentence, o.recording, level = level),
        ))
        panel.show()
        android.app.AlertDialog.Builder(this)
            .setTitle("✦ ${w.base}")
            .setView(scroll)
            .setPositiveButton("Fechar", null)
            .setOnDismissListener { panel.hide() }
            .show()
    }

    private var strokesShownFor = ""

    /** Ordem dos traços de cada kanji da palavra. */
    private fun showStrokes(word: String) {
        if (strokesShownFor == word) return
        strokesShownFor = word
        val kanji = word.filter(StudyGen::isKanji).toList()
        val box = findViewById<android.view.View>(R.id.strokeBox)
        if (kanji.isEmpty()) { box.visibility = android.view.View.GONE; return }
        Thread {
            Strokes.load(this, kanji.toSet())
            val paths = kanji.map { Strokes.get(this, it) }
            val info = Kanjidic.describe(this, word)
            runOnUiThread {
                val sb = android.text.SpannableStringBuilder()
                for ((i, pair) in info.withIndex()) {
                    if (i > 0) sb.append("\n")
                    val a = sb.length
                    sb.append(pair.first.toString())
                    sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), a, sb.length, 0)
                    sb.append("  ").append(pair.second)
                }
                findViewById<TextView>(R.id.kanjiInfo).text = sb
                val row = findViewById<android.widget.LinearLayout>(R.id.strokeRow)
                row.removeAllViews()
                for (p in paths) if (p != null) row.addView(StrokeView(this).also { it.setStrokes(p) })
                box.visibility = if (row.childCount > 0 || info.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            }
        }.start()
    }

    private fun play(o: VocabDb.Occurrence) {
        Thread {
            val item = Recordings.find(this, o.recording + ".txt")
            runOnUiThread {
                if (item == null) {
                    Toast.makeText(this, "A gravação dessa frase não está mais na pasta", Toast.LENGTH_SHORT).show()
                } else {
                    startActivity(
                        Intent(this, ViewerActivity::class.java)
                            .putExtra(ViewerActivity.EXTRA_URI, item.uri)
                            .putExtra(ViewerActivity.EXTRA_NAME, item.name)
                            .putExtra(ViewerActivity.EXTRA_SEEK, o.sec)
                    )
                }
            }
        }.start()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_vocab_detail, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            R.id.action_copy -> {
                val w = word ?: return true
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Michi", w.base))
                Toast.makeText(this, "${w.base} copiada", Toast.LENGTH_SHORT).show()
            }
            R.id.action_delete -> {
                AlertDialog.Builder(this)
                    .setMessage("Remover ${word?.base} do vocabulário?")
                    .setPositiveButton("Remover") { _, _ ->
                        VocabDb.get(this).removeById(wordId)
                        finish()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = occ.size
        override fun getItem(position: Int) = occ[position]
        override fun getItemId(position: Int) = occ[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_occurrence, parent, false)
            val o = occ[position]
            Furigana.set(this@VocabDetailActivity, v.findViewById(R.id.sentence),
                Jp.highlight(o.sentence, VocabDb.studyKeys, getColor(R.color.saved_word), VocabDb.knownKeys, getColor(R.color.known_word)), o.sentence)
            v.findViewById<TextView>(R.id.meta).text =
                "${Recordings.display(this@VocabDetailActivity, o.recording)} · ${CaptureService.formatTime(o.sec)}"
            v.findViewById<TextView>(R.id.play).setOnClickListener { play(o) }
            val trView = v.findViewById<TextView>(R.id.translation)
            val trBtn = v.findViewById<TextView>(R.id.translate)
            val cached = Tr.cached(this@VocabDetailActivity, o.sentence) ?: Tr.aiCached(this@VocabDetailActivity, o.sentence)
            if (cached != null) {
                trView.visibility = View.VISIBLE; trBtn.visibility = View.GONE
                TrSwitch.bind(this@VocabDetailActivity, trView, o.sentence)
            } else {
                trView.visibility = View.GONE; trBtn.visibility = View.VISIBLE
                trBtn.setOnClickListener {
                    trBtn.text = "Traduzindo…"
                    Tr.translate(this@VocabDetailActivity, o.sentence) { t ->
                        if (t != null) {
                            trView.visibility = View.VISIBLE; trBtn.visibility = View.GONE
                            TrSwitch.bind(this@VocabDetailActivity, trView, o.sentence)
                        } else {
                            trBtn.text = "Traduzir"
                            Toast.makeText(this@VocabDetailActivity, "Não consegui traduzir agora", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            return v
        }
    }
}
