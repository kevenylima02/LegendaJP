package com.keveny.legendajp

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Dicionário de gramática: todos os padrões do catálogo, com busca, filtro por nível e "nas minhas
 * frases". Cada ficha tem explicação, como se forma (grupos 1, 2 e 3), exemplo, as suas frases onde o
 * padrão apareceu, estado na revisão, ✦ explicação pela IA e "Estudar este padrão". No alto,
 * ✦ material de estudo do nível escrito pela IA (guardado para ler depois, sem internet).
 */
class GrammarDictActivity : Activity() {

    companion object { const val EXTRA_ID = "point" }

    private class Mine(val lines: List<Pair<StudyGen.Line, Grammar.Hit>>)

    private var level: String? = null          // null = todos
    private var onlyMine = false
    private var query = ""
    private var mine: Map<String, Mine> = emptyMap()
    private var scanned = false
    private val cards = HashMap<String, View>()
    private lateinit var listBox: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var header: TextView
    private val chips = HashMap<String, Button>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Dicionário de gramática"
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        val search = EditText(this).apply {
            hint = "Buscar padrão ou significado"; setSingleLine(); textSize = 15f
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
        for (l in listOf("N5", "N4", "N3")) chip(l, l) { level = if (level == l) null else l }
        chip("mine", "Nas minhas frases") { onlyMine = !onlyMine }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) })
        header = TextView(this).apply { textSize = 13f; setTextColor(getColor(R.color.text_secondary)); setPadding(0, dp(8), 0, 0) }
        root.addView(header)
        if (Ai.enabled(this)) {
            root.addView(Button(this).apply {
                text = "✦ Material de estudo do nível (IA)"; isAllCaps = false; textSize = 13f
                setBackgroundResource(R.drawable.bg_button_outline); setTextColor(getColor(R.color.text))
                minHeight = 0; minimumHeight = 0; setPadding(dp(14), dp(6), dp(14), dp(6))
                setOnClickListener { guide() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(16)) }
        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(listBox) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        render()
        scan()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    /** Procura cada padrão em todas as frases (gravações e textos), em segundo plano. */
    private fun scan() {
        Thread {
            val lines = try { Folders.lines(this, Folders.STUDY_PREFIX + Folders.ALL) } catch (_: Exception) { emptyList() }
            val byPoint = LinkedHashMap<String, MutableList<Pair<StudyGen.Line, Grammar.Hit>>>()
            for (l in lines) {
                if (l.text.length < 4) continue
                for (h in (try { Grammar.find(l.text) } catch (_: Exception) { emptyList() })) byPoint.getOrPut(h.point.id) { mutableListOf() }.add(l to h)
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                mine = byPoint.mapValues { Mine(it.value) }
                scanned = true
                render()
            }
        }.start()
    }

    private fun levelOf(p: Grammar.Point) = p.level.removePrefix("N").toIntOrNull() ?: 5

    private fun visible(): List<Grammar.Point> {
        val q = query.lowercase()
        val all = Grammar.all.filter { p ->
            (level == null || p.level == level) &&
                (!onlyMine || mine.containsKey(p.id)) &&
                (q.isEmpty() || p.name.lowercase().contains(q) || p.explanation.lowercase().contains(q) || p.id.contains(q) ||
                    (GrammarDrill.spec(p.id)?.meaning?.lowercase()?.contains(q) ?: false))
        }
        return if (onlyMine) all.sortedByDescending { mine[it.id]?.lines?.size ?: 0 } else all.sortedByDescending { levelOf(it) }
    }

    private fun render() {
        for ((k, b) in chips) {
            val on = when (k) { "all" -> level == null; "mine" -> onlyMine; else -> level == k }
            b.setBackgroundResource(if (on) R.drawable.bg_button_primary else R.drawable.bg_button_outline)
            b.setTextColor(if (on) 0xFFFFFFFF.toInt() else getColor(R.color.text))
        }
        val list = visible()
        val byLevel = Grammar.all.groupingBy { it.level }.eachCount()
        header.text = "${list.size} de ${Grammar.all.size} padrões · " + listOf("N5", "N4", "N3").joinToString(" · ") { "$it ${byLevel[it] ?: 0}" } +
            (if (scanned) " · ${mine.size} aparecem nas suas frases" else " · procurando nas suas frases…")
        listBox.removeAllViews()
        cards.clear()
        if (list.isEmpty()) {
            listBox.addView(TextView(this).apply { text = "Nenhum padrão com esse filtro."; textSize = 15f; setTextColor(getColor(R.color.text_secondary)) })
            return
        }
        for (p in list) {
            val v = card(p)
            cards[p.id] = v
            listBox.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }
        val want = intent.getStringExtra(EXTRA_ID)
        if (want != null && scanned && cards.containsKey(want)) {  // só depois da busca nas frases: as fichas mudam de altura
            intent.removeExtra(EXTRA_ID)
            scroll.post { cards[want]?.let { scroll.smoothScrollTo(0, it.top) } }
        }
    }

    private fun card(p: Grammar.Point): View {
        val spec = GrammarDrill.spec(p.id)
        val m = mine[p.id]
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.bg_card); setPadding(dp(14), dp(12), dp(14), dp(12)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply { text = p.name; textSize = 18f; setTypeface(null, Typeface.BOLD); setTextColor(getColor(R.color.text)) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(TextView(this).apply {
            text = p.level; textSize = 12f; setTextColor(getColor(R.color.accent)); setPadding(dp(8), dp(2), dp(8), dp(2)); setBackgroundResource(R.drawable.bg_chip)
        })
        box.addView(top)
        box.addView(TextView(this).apply { text = p.explanation; textSize = 14f; setTextColor(getColor(R.color.text)); setPadding(0, dp(4), 0, 0) })
        val bucket = Srs.get(this, Srs.GRAMMAR, p.id)?.let { Srs.bucket(it) }
        val meta = mutableListOf<String>()
        meta.add(if (m != null) "nas suas frases: ${m.lines.size}" else if (scanned) "ainda não apareceu nas suas frases" else "…")
        meta.add(if (bucket != null) "revisão: $bucket" else "ainda sem cartão")
        meta.add(if (spec != null) "com exercícios de uso" else "só reconhecer")
        box.addView(TextView(this).apply { text = meta.joinToString(" · "); textSize = 12f; setTextColor(getColor(R.color.text_secondary)); setPadding(0, dp(4), 0, 0) })
        if (spec != null) {
            val formation = spec.rule() + "\n" + spec.formation()
            val tv = TextView(this).apply { textSize = 13f; setTextColor(getColor(R.color.accent)); setPadding(0, dp(6), 0, 0); text = "▸ Como se forma (grupos 1, 2 e 3)" }
            var open = false
            tv.setOnClickListener {
                open = !open
                tv.text = if (open) "▾ Como se forma\n" + formation else "▸ Como se forma (grupos 1, 2 e 3)"
                tv.setTextColor(getColor(if (open) R.color.text else R.color.accent))
            }
            box.addView(tv)
        }
        box.addView(TextView(this).apply { text = "Ex.: " + p.example; textSize = 15f; setTextColor(getColor(R.color.text)); setPadding(0, dp(8), 0, 0) })
        if (m != null) {
            val (line, hit) = m.lines.first()
            box.addView(TextView(this).apply {
                text = boldSpan(line.text, hit.start, hit.end); textSize = 15f; setTextColor(getColor(R.color.text)); setPadding(0, dp(6), 0, 0)
                setOnClickListener { showAll(p, m) }
            })
            if (m.lines.size > 1) box.addView(TextView(this).apply {
                text = "ver as ${m.lines.size} frases"; textSize = 12f; setTextColor(getColor(R.color.accent)); setPadding(0, dp(2), 0, 0)
                setOnClickListener { showAll(p, m) }
            })
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        fun small(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; textSize = 13f
            setBackgroundResource(R.drawable.bg_button_outline); setTextColor(getColor(R.color.text))
            minHeight = 0; minimumHeight = 0; setPadding(dp(12), dp(6), dp(12), dp(6)); setOnClickListener { onClick() }
        }
        row.addView(small("Estudar") { study(p, m) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        if (Ai.enabled(this)) row.addView(small("✦ Explicar com IA") { explain(p, m) })
        box.addView(row)
        return box
    }

    private fun boldSpan(text: String, start: Int, end: Int): CharSequence {
        val s = SpannableString(text)
        if (start in 0 until end && end <= text.length) s.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }

    private fun showAll(p: Grammar.Point, m: Mine) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        for ((l, h) in m.lines) box.addView(TextView(this).apply {
            text = boldSpan(l.text, h.start, h.end); textSize = 16f; setTextColor(getColor(R.color.text)); setPadding(0, dp(6), 0, dp(6))
        })
        AlertDialog.Builder(this).setTitle("${p.name} · ${m.lines.size} frases").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Fechar", null).show()
    }

    /** Sessão só com este padrão: as suas frases onde ele aparece (todas as pastas) mais os exercícios de uso para cada grupo. */
    private fun study(p: Grammar.Point, m: Mine?) {
        if (m == null && GrammarDrill.spec(p.id) == null) {
            Toast.makeText(this, "Esse padrão ainda não apareceu nas suas frases e não tem exercícios de uso: por enquanto só dá para ler.", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent(this, StudyActivity::class.java)
            .putExtra(StudyActivity.EXTRA_NAME, Folders.STUDY_PREFIX + Folders.ALL)
            .putExtra(StudyActivity.EXTRA_FOCUS, StudyGen.Focus.GRAMATICA.name)
            .putStringArrayListExtra(StudyActivity.EXTRA_POINTS, arrayListOf(p.id)))
    }

    /** ✦ IA explica o padrão numa frase sua (ou no exemplo do catálogo). */
    private fun explain(p: Grammar.Point, m: Mine?) {
        val spec = GrammarDrill.spec(p.id)
        val (text, start, end, rec) = if (m != null) {
            val (l, h) = m.lines.first(); Quad(l.text, h.start, h.end, l.recording)
        } else Quad(p.example, 0, 0, null)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        box.addView(TextView(this).apply { this.text = boldSpan(text, start, end); textSize = 17f; setTextColor(getColor(R.color.text)) })
        box.addView(TextView(this).apply {
            this.text = p.explanation + (spec?.let { "\n\nComo se forma:\n" + it.formation() } ?: "")
            textSize = 14f; setTextColor(getColor(R.color.text)); setPadding(0, dp(8), 0, dp(8))
        })
        val panelBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(panelBox)
        val note = if (end > start) text.substring(start, end) else ""
        val panel = AiPanel(this, panelBox, listOf(
            Ai.Request(Ai.Task.GRAMMAR, text, rec, p.name, note, Prefs(this).level),
            Ai.Request(Ai.Task.TRANSLATE, text, rec, level = Prefs(this).level),
        ))
        panel.show()
        AlertDialog.Builder(this).setTitle("✦ ${p.name}").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Fechar", null).setOnDismissListener { panel.hide() }.show()
    }

    private data class Quad(val text: String, val start: Int, val end: Int, val rec: String?)

    /** ✦ Material de estudo do nível (o filtrado, ou o seu nível): a IA escreve e o app guarda. */
    private fun guide() {
        val lvl = level ?: "N${Prefs(this).level}"
        val points = Grammar.all.filter { it.level == lvl }
        if (points.isEmpty()) { Toast.makeText(this, "Sem padrões do nível $lvl no catálogo", Toast.LENGTH_SHORT).show(); return }
        val note = points.joinToString("\n") { "${it.name} — ${it.explanation}" }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        box.addView(TextView(this).apply {
            text = "Material sobre os ${points.size} padrões do $lvl que estão no catálogo. A resposta fica guardada: da próxima vez abre na hora, sem internet."
            textSize = 13f; setTextColor(getColor(R.color.text_secondary)); setPadding(0, 0, 0, dp(8))
        })
        val panelBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(panelBox)
        val panel = AiPanel(this, panelBox, listOf(Ai.Request(Ai.Task.GUIDE, lvl, null, "", note, Prefs(this).level)))
        panel.show()
        AlertDialog.Builder(this).setTitle("✦ Material de estudo · $lvl").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Fechar", null).setOnDismissListener { panel.hide() }.show()
    }
}
