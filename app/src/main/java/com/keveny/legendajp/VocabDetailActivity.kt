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
                findViewById<TextView>(R.id.meta).text = "${w.pos} · kanji ${Score.percent(w.kanjiScore)}% · significado ${Score.percent(w.meaningScore)}%"
                Score.bind(findViewById(R.id.barKanji), w.kanjiScore)
                Score.bind(findViewById(R.id.barMeaning), w.meaningScore)
                showStrokes(w.base)
                val kb = findViewById<android.widget.Button>(R.id.btnKnown)
                kb.text = if (w.known) "Conheço ✓" else "Já conheço"
                if (w.known) findViewById<TextView>(R.id.meta).append(" · já conheço (fora dos exercícios)")
                kb.setOnClickListener {
                    VocabDb.get(this).setKnown(w.key, !w.known)
                    Toast.makeText(this,
                        if (!w.known) "${w.base}: já conheço. Não aparece mais nos exercícios" else "${w.base} volta a aparecer nos exercícios",
                        Toast.LENGTH_SHORT).show()
                    load()
                }
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
            v.findViewById<TextView>(R.id.sentence).text =
                Jp.highlight(o.sentence, VocabDb.studyKeys, getColor(R.color.saved_word))
            v.findViewById<TextView>(R.id.meta).text =
                "${Recordings.prettyName(o.recording)} · ${CaptureService.formatTime(o.sec)}"
            v.findViewById<TextView>(R.id.play).setOnClickListener { play(o) }
            val trView = v.findViewById<TextView>(R.id.translation)
            val trBtn = v.findViewById<TextView>(R.id.translate)
            val cached = Tr.cached(this@VocabDetailActivity, o.sentence)
            if (cached != null) {
                trView.text = cached; trView.visibility = View.VISIBLE; trBtn.visibility = View.GONE
            } else {
                trView.visibility = View.GONE; trBtn.visibility = View.VISIBLE
                trBtn.setOnClickListener {
                    trBtn.text = "Traduzindo…"
                    Tr.translate(this@VocabDetailActivity, o.sentence) { t ->
                        if (t != null) {
                            trView.text = t; trView.visibility = View.VISIBLE; trBtn.visibility = View.GONE
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
