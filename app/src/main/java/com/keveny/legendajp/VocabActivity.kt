package com.keveny.legendajp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lista de palavras salvas, com filtros (todas, estudando, conheço, novas, com kanji, expressões),
 * contagem em cada filtro e ordenação (recentes, leitura, mais/menos dominadas, mais frequentes).
 */
class VocabActivity : Activity() {

    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var summary: TextView
    private lateinit var search: EditText
    private lateinit var filtersRow: LinearLayout
    private var rows: List<VocabDb.Word> = emptyList()
    private var all: List<VocabDb.Word> = emptyList()
    private var studied: Set<String> = emptySet()
    private var filter = "all"
    private var sort = "recent"
    private val dateFmt = SimpleDateFormat("dd/MM/yyyy", Locale.US)

    /** id, rótulo, teste. "Novas" = sem nenhum exercício respondido ainda. */
    private val filters: List<Triple<String, String, (VocabDb.Word) -> Boolean>> = listOf(
        Triple("all", "Todas") { true },
        Triple("studying", "Estudando") { !it.known },
        Triple("known", "Conheço") { it.known },
        Triple("new", "Novas") { !it.known && it.kanjiScore == 0 && it.meaningScore == 0 && "${it.base}|${it.posKey}" !in studied },
        Triple("kanji", "Com kanji") { it.base.any(StudyGen::isKanji) },
        Triple("expr", "Expressões") { it.posKey == VocabDb.EXPR },
    )

    private val sorts = listOf(
        "recent" to "Mais recentes", "reading" to "Leitura (あ→ん)", "mastered" to "Mais dominadas",
        "weak" to "Menos dominadas", "frequent" to "Mais frequentes (frases)"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vocab)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.vocab)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        summary = findViewById(R.id.summary)
        search = findViewById(R.id.search)
        filtersRow = findViewById(R.id.filters)
        filter = Prefs(this).vocabFilter
        sort = Prefs(this).vocabSort
        buildFilters()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            startActivity(Intent(this, VocabDetailActivity::class.java).putExtra(VocabDetailActivity.EXTRA_ID, rows[pos].id))
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            copyWord(rows[pos])
            true
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { apply() }
        })
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        Thread {
            val result = VocabDb.get(this).words("", true)
            val st = Srs.studiedKeys(this)
            runOnUiThread {
                all = result
                studied = st
                apply()
            }
        }.start()
    }

    /** Fichas de filtro com a contagem de cada uma. */
    private fun buildFilters() {
        filtersRow.removeAllViews()
        for ((id, label, _) in filters) {
            val chip = TextView(this).apply {
                textSize = 13f
                setPadding(dp(12), dp(6), dp(12), dp(6))
                tag = id
                text = label
                setOnClickListener { filter = id; Prefs(this@VocabActivity).vocabFilter = id; apply() }
            }
            filtersRow.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        }
    }

    /** Aplica busca, filtro e ordenação em memória (a lista é pequena) e atualiza as contagens. */
    private fun apply() {
        val q = search.text.toString().trim()
        val searched = if (q.isEmpty()) all else all.filter { it.base.contains(q) || it.reading.contains(q) }
        for ((id, label, test) in filters) {
            val chip = filtersRow.findViewWithTag<TextView>(id) ?: continue
            val n = searched.count(test)
            chip.text = "$label · $n"
            val on = id == filter
            chip.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(if (on) getColor(R.color.accent_soft) else getColor(R.color.card))
                setStroke(dp(1), if (on) getColor(R.color.accent) else getColor(R.color.outline))
            }
            chip.setTextColor(getColor(if (on) R.color.accent else R.color.text))
        }
        val test = filters.first { it.first == filter }.third
        val filtered = searched.filter(test)
        rows = when (sort) {
            "reading" -> filtered.sortedBy { it.reading }
            "mastered" -> filtered.sortedWith(compareByDescending<VocabDb.Word> { it.kanjiScore + it.meaningScore }.thenByDescending { it.created })
            "weak" -> filtered.sortedWith(compareBy<VocabDb.Word> { it.kanjiScore + it.meaningScore }.thenByDescending { it.created })
            "frequent" -> filtered.sortedWith(compareByDescending<VocabDb.Word> { it.count }.thenByDescending { it.created })
            else -> filtered.sortedByDescending { it.created }
        }
        adapter.notifyDataSetChanged()
        empty.visibility = if (all.isEmpty() && q.isEmpty()) View.VISIBLE else View.GONE
        val known = all.count { it.known }
        summary.text = "${rows.size} de ${all.size} palavras · estudando ${all.size - known} · conheço $known · " +
            sorts.first { it.first == sort }.second.lowercase()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_vocab, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            R.id.action_study -> startActivity(
                Intent(this, StudyMenuActivity::class.java).putExtra(StudyActivity.EXTRA_SAVED, true)
            )
            R.id.action_sort -> {
                val cur = sorts.indexOfFirst { it.first == sort }.coerceAtLeast(0)
                android.app.AlertDialog.Builder(this).setTitle("Ordenar por")
                    .setSingleChoiceItems(sorts.map { it.second }.toTypedArray(), cur) { d, i ->
                        sort = sorts[i].first; Prefs(this).vocabSort = sort; apply(); d.dismiss()
                    }.setNegativeButton(android.R.string.cancel, null).show()
            }
            R.id.action_export -> export()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun copyWord(w: VocabDb.Word) {
        getSystemService(android.content.ClipboardManager::class.java)
            .setPrimaryClip(android.content.ClipData.newPlainText("Michi", w.base))
        Toast.makeText(this, "${w.base} copiada", Toast.LENGTH_SHORT).show()
    }

    private fun export() {
        Thread {
            val csv = VocabDb.get(this).exportCsv()
            val name = "vocabulario_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) + ".csv"
            val uri = Recordings.create(this, name, "text/csv")
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
            }
            runOnUiThread {
                if (uri == null) {
                    Toast.makeText(this, "Não consegui criar o arquivo", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Salvo em Download/LegendaJP/$name", Toast.LENGTH_LONG).show()
                    val send = Intent(Intent.ACTION_SEND).setType("text/csv")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(Intent.createChooser(send, "Exportar vocabulário"))
                }
            }
        }.start()
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = rows[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_vocab, parent, false)
            val w = rows[position]
            v.findViewById<TextView>(R.id.base).text = w.base
            v.findViewById<TextView>(R.id.reading).text = if (w.reading != w.base) w.reading else ""
            v.findViewById<TextView>(R.id.meta).text = "${w.pos} · ${dateFmt.format(Date(w.created))}"
            val mastery = (w.kanjiScore + w.meaningScore) / 20
            v.findViewById<TextView>(R.id.count).text = (if (w.known) "conheço ✓" else if (w.count == 1) "1 frase" else "${w.count} frases") +
                (if (!w.known && mastery > 0) " · $mastery%" else "")
            v.alpha = if (w.known) 0.55f else 1f
            Score.bind(v.findViewById(R.id.barKanji), w.kanjiScore)
            Score.bind(v.findViewById(R.id.barMeaning), w.meaningScore)
            return v
        }
    }
}
