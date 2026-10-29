package com.keveny.legendajp

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * Dicionário de gramática: todos os padrões do banco do app (N5 a N1, Fora do JLPT e Kansai-ben; ver
 * GrammarBank), com busca e filtros por nível, "Nas minhas frases" e "Com conjugação". Tocar num
 * padrão abre a tela dele (GrammarPointActivity), com o material completo e o botão de estudar.
 *
 * Leveza: ListView (só as fichas visíveis existem) e a busca dos padrões nas suas frases fica
 * guardada por arquivo (GrammarIndex), refeita só para arquivos novos ou alterados.
 */
class GrammarDictActivity : Activity() {

    companion object {
        const val EXTRA_ID = "point"
        private val FILTERS = listOf("N5", "N4", "N3", "N2", "N1", "Fora do JLPT", "Kansai-ben")
    }

    /** Um padrão da lista: do banco (quase sempre) ou só do catálogo do app (se o banco não tiver). */
    private class Entry(val cat: Grammar.Point?, val bank: GrammarBank.Entry?, val sort: Int) {
        val key: String get() = bank?.key ?: cat!!.id
        val level: String get() = bank?.level ?: cat!!.level
        val name: String get() = bank?.name ?: cat!!.name
        val summary: String get() = bank?.summary ?: cat!!.explanation
    }

    private var level: String? = null          // null = todos
    private var onlyMine = false
    private var onlyDrills = false
    private var query = ""
    private var entries: List<Entry> = emptyList()
    private var mine: Map<String, GrammarIndex.Mine> = emptyMap()
    private var scanned = false
    private var items: List<Entry> = emptyList()
    private lateinit var list: ListView
    private lateinit var header: TextView
    private val chips = LinkedHashMap<String, Button>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Dicionário de gramática"
        // Pedido de um padrão específico: abre a tela dele por cima da lista
        intent.getStringExtra(EXTRA_ID)?.let { GrammarPointActivity.open(this, it) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        val search = EditText(this).apply {
            hint = "Buscar padrão, significado ou romaji"; setSingleLine(); textSize = 15f
            setBackgroundResource(R.drawable.bg_card); setPadding(dp(14), dp(10), dp(14), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s?.toString()?.trim() ?: ""; render() }
            })
        }
        root.addView(search)
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        fun chip(key: String, label: String, onClick: () -> Unit) {
            val b = Button(this).apply {
                text = label; isAllCaps = false; textSize = 13f; minHeight = 0; minimumHeight = 0
                setPadding(dp(12), dp(6), dp(12), dp(6)); setOnClickListener { onClick(); render() }
            }
            chips[key] = b
            chipRow.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        }
        chip("all", "Todos") { level = null }
        for (l in FILTERS) chip(l, l) { level = if (level == l) null else l }
        chip("mine", "Nas minhas frases") { onlyMine = !onlyMine }
        chip("drills", "Com conjugação") { onlyDrills = !onlyDrills }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) })
        header = TextView(this).apply { textSize = 13f; setTextColor(getColor(R.color.text_secondary)); setPadding(0, dp(8), 0, 0) }
        root.addView(header)
        list = ListView(this).apply {
            adapter = this@GrammarDictActivity.adapter
            divider = null; dividerHeight = dp(10); clipToPadding = false; setPadding(0, dp(8), 0, dp(16))
            isVerticalScrollBarEnabled = false; cacheColorHint = 0; selector = android.graphics.drawable.ColorDrawable(0)
            setOnItemClickListener { _, _, pos, _ -> items.getOrNull(pos)?.let { GrammarPointActivity.open(this@GrammarDictActivity, it.key) } }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        render()
        load()
    }

    override fun onResume() {
        super.onResume()
        if (entries.isNotEmpty()) render()   // a revisão pode ter mudado depois de estudar
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ---------- dados ----------

    /** Junta o banco e o catálogo do app, e depois procura os padrões nas suas frases. */
    private fun load() {
        Thread {
            val merged = mergeEntries()
            runOnUiThread { if (!isFinishing) { entries = merged; render() } }
            val found = try { GrammarIndex.scan(this) } catch (_: Exception) { emptyMap() }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                mine = found
                scanned = true
                render()
            }
        }.start()
    }

    private fun mergeEntries(): List<Entry> {
        val bank = GrammarBank.all()
        val out = bank.map { b -> Entry(b.app?.let { Grammar.point(it) }, b, b.order) }.toMutableList()
        val inBank = bank.mapNotNull { it.app }.toSet()
        Grammar.all.forEachIndexed { i, c -> if (c.id !in inBank) out.add(Entry(c, null, 100000 + i)) }
        return out
    }

    // ---------- lista ----------

    private fun levelRank(l: String) = FILTERS.indexOf(l).let { if (it < 0) FILTERS.size else it }

    private fun matches(e: Entry, q: String): Boolean {
        if (q.isEmpty()) return true
        val c = e.cat; val b = e.bank
        val qs = q.replace(" ", "")
        return (c != null && (c.name.lowercase().contains(q) || c.explanation.lowercase().contains(q) || c.id.contains(q))) ||
            (b != null && (b.title.lowercase().contains(q) || b.reading.contains(q) || b.romaji.lowercase().replace(" ", "").contains(qs) ||
                b.summary.lowercase().contains(q) || b.meaning.lowercase().contains(q)))
    }

    private fun visible(): List<Entry> {
        val q = query.lowercase()
        val all = entries.filter { e ->
            (level == null || e.level == level) &&
                (!onlyMine || mine.containsKey(e.key)) &&
                (!onlyDrills || GrammarDrill.spec(e.key) != null) &&
                matches(e, q)
        }
        return if (onlyMine) all.sortedByDescending { mine[it.key]?.count ?: 0 }
        else all.sortedWith(compareBy<Entry> { levelRank(it.level) }.thenBy { it.sort })
    }

    private fun render() {
        for ((k, b) in chips) {
            val on = when (k) { "all" -> level == null; "mine" -> onlyMine; "drills" -> onlyDrills; else -> level == k }
            b.setBackgroundResource(if (on) R.drawable.bg_button_primary else R.drawable.bg_button_outline)
            b.setTextColor(if (on) 0xFFFFFFFF.toInt() else getColor(R.color.text))
        }
        items = visible()
        val byLevel = entries.groupingBy { it.level }.eachCount()
        header.text = if (entries.isEmpty()) "Carregando…" else "${items.size} de ${entries.size} padrões · " +
            FILTERS.filter { (byLevel[it] ?: 0) > 0 }.joinToString(" · ") { "$it ${byLevel[it]}" } +
            (if (scanned) " · ${mine.size} aparecem nas suas frases" else " · procurando nas suas frases…") +
            "\nToque num padrão para ver a explicação, os exemplos e estudar."
        adapter.notifyDataSetChanged()
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = card(items[position])
    }

    private fun card(e: Entry): View {
        val m = mine[e.key]
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.bg_card); setPadding(dp(14), dp(12), dp(14), dp(12)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply { text = e.name; textSize = 18f; setTypeface(null, Typeface.BOLD); setTextColor(getColor(R.color.text)) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(TextView(this).apply {
            text = e.level; textSize = 12f; setTextColor(getColor(R.color.accent)); setPadding(dp(8), dp(2), dp(8), dp(2)); setBackgroundResource(R.drawable.bg_chip)
        })
        box.addView(top)
        val b = e.bank
        if (b != null && b.reading.isNotBlank() && b.reading != e.name) box.addView(TextView(this).apply {
            text = b.reading + (if (b.romaji.isNotBlank()) " · ${b.romaji}" else ""); textSize = 13f; setTextColor(getColor(R.color.text_secondary)); setPadding(0, dp(2), 0, 0)
        })
        box.addView(TextView(this).apply { text = e.summary; textSize = 15f; setTextColor(getColor(R.color.text)); setPadding(0, dp(4), 0, 0) })
        val meta = mutableListOf<String>()
        if (m != null) meta.add("nas suas frases: ${m.count}")
        Srs.get(this, Srs.GRAMMAR, e.key)?.let { meta.add("revisão: ${Srs.bucket(it)}") }
        if (GrammarDrill.spec(e.key) != null) meta.add("conjugação")
        if (meta.isNotEmpty()) box.addView(TextView(this).apply { text = meta.joinToString(" · "); textSize = 12f; setTextColor(getColor(R.color.text_secondary)); setPadding(0, dp(4), 0, 0) })
        return box
    }
}
