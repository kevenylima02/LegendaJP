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
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Dicionário de gramática: todos os padrões do catálogo, com busca, filtro por nível e "nas minhas
 * frases". Cada ficha tem explicação, como se forma (grupos 1, 2 e 3), exemplo, uma frase sua onde o
 * padrão apareceu, estado na revisão, ✦ explicação pela IA e "Estudar" (sessão só com as frases desse
 * padrão). No alto, ✦ material de estudo do nível escrito pela IA (guardado para ler depois, sem internet).
 *
 * Leveza: a lista é um ListView (só as fichas visíveis existem) e a busca dos padrões nas suas frases
 * fica guardada por arquivo (files/grammar_index), refeita só para arquivos novos ou alterados.
 */
class GrammarDictActivity : Activity() {

    companion object {
        const val EXTRA_ID = "point"
        /** Quantas frases por padrão ficam guardadas para a ficha e para a sessão de estudo. */
        private const val KEEP = 30
    }

    private class Mine(var count: Int, val lines: MutableList<Pair<StudyGen.Line, Grammar.Hit>>)

    private var level: String? = null          // null = todos
    private var onlyMine = false
    private var query = ""
    private var mine: Map<String, Mine> = emptyMap()
    private var scanned = false
    private var items: List<Grammar.Point> = emptyList()
    private lateinit var list: ListView
    private lateinit var header: TextView
    private val chips = HashMap<String, Button>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Dicionário de gramática"
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
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
        list = ListView(this).apply {
            adapter = this@GrammarDictActivity.adapter
            divider = null; dividerHeight = dp(10); clipToPadding = false; setPadding(0, dp(8), 0, dp(16))
            isVerticalScrollBarEnabled = false; cacheColorHint = 0; selector = android.graphics.drawable.ColorDrawable(0)
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        render()
        scan()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ---------- padrões nas suas frases (com índice por arquivo) ----------

    private fun indexDir() = File(filesDir, "grammar_index").apply { mkdirs() }

    /**
     * Procura cada padrão em todas as frases (gravações e textos), em segundo plano. O resultado de
     * cada arquivo fica em files/grammar_index/<nome>.json (chave: tamanho do texto), para a próxima
     * abertura não refazer o Kuromoji em tudo.
     */
    private fun scan() {
        Thread {
            val byPoint = HashMap<String, Mine>()
            val dir = indexDir()
            val seen = HashSet<String>()
            for (item in (try { Recordings.list(this, ".txt") } catch (_: Exception) { emptyList() })) {
                val base = Recordings.baseName(item.name)
                val text = try { Recordings.readText(this, item.uri) } catch (_: Exception) { continue }
                val lines = StudyGen.lines(text, base)
                val f = File(dir, base.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".json")
                seen.add(f.name)
                var hits: JSONArray? = null
                if (f.exists()) try {
                    val o = JSONObject(f.readText())
                    if (o.optInt("len", -1) == text.length) hits = o.optJSONArray("hits")
                } catch (_: Exception) {}
                if (hits == null) {
                    hits = JSONArray()
                    for ((i, l) in lines.withIndex()) {
                        if (l.text.length < 4) continue
                        for (h in (try { Grammar.find(l.text) } catch (_: Exception) { emptyList() }))
                            hits.put(JSONArray().put(i).put(h.point.id).put(h.start).put(h.end))
                    }
                    try { f.writeText(JSONObject().put("len", text.length).put("hits", hits).toString()) } catch (_: Exception) {}
                }
                for (k in 0 until hits.length()) {
                    val a = hits.optJSONArray(k) ?: continue
                    val l = lines.getOrNull(a.optInt(0)) ?: continue
                    val p = Grammar.point(a.optString(1)) ?: continue
                    val m = byPoint.getOrPut(p.id) { Mine(0, mutableListOf()) }
                    m.count++
                    if (m.lines.size < KEEP) m.lines.add(l to Grammar.Hit(p, a.optInt(2), a.optInt(3)))
                }
            }
            // Índices de arquivos apagados
            dir.listFiles()?.forEach { if (it.name !in seen) it.delete() }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                mine = byPoint
                scanned = true
                render()
            }
        }.start()
    }

    // ---------- lista ----------

    private fun levelOf(p: Grammar.Point) = p.level.removePrefix("N").toIntOrNull() ?: 5

    private fun visible(): List<Grammar.Point> {
        val q = query.lowercase()
        val all = Grammar.all.filter { p ->
            (level == null || p.level == level) &&
                (!onlyMine || mine.containsKey(p.id)) &&
                (q.isEmpty() || p.name.lowercase().contains(q) || p.explanation.lowercase().contains(q) || p.id.contains(q) ||
                    (GrammarDrill.spec(p.id)?.meaning?.lowercase()?.contains(q) ?: false))
        }
        return if (onlyMine) all.sortedByDescending { mine[it.id]?.count ?: 0 } else all.sortedByDescending { levelOf(it) }
    }

    private fun render() {
        for ((k, b) in chips) {
            val on = when (k) { "all" -> level == null; "mine" -> onlyMine; else -> level == k }
            b.setBackgroundResource(if (on) R.drawable.bg_button_primary else R.drawable.bg_button_outline)
            b.setTextColor(if (on) 0xFFFFFFFF.toInt() else getColor(R.color.text))
        }
        items = visible()
        val byLevel = Grammar.all.groupingBy { it.level }.eachCount()
        header.text = "${items.size} de ${Grammar.all.size} padrões · " + listOf("N5", "N4", "N3").joinToString(" · ") { "$it ${byLevel[it] ?: 0}" } +
            (if (scanned) " · ${mine.size} aparecem nas suas frases" else " · procurando nas suas frases…")
        adapter.notifyDataSetChanged()
        val want = intent.getStringExtra(EXTRA_ID)
        if (want != null && scanned) {   // só depois da busca nas frases: as fichas mudam de altura
            intent.removeExtra(EXTRA_ID)
            val i = items.indexOfFirst { it.id == want }
            if (i >= 0) list.post { list.setSelection(i) }
        }
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = card(items[position])
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
        meta.add(if (m != null) "nas suas frases: ${m.count}" else if (scanned) "ainda não apareceu nas suas frases" else "…")
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

    /** Sessão só com este padrão: as suas frases onde ele aparece (até [KEEP]) mais os exercícios de uso para cada grupo. */
    private fun study(p: Grammar.Point, m: Mine?) {
        if (m == null && GrammarDrill.spec(p.id) == null) {
            Toast.makeText(this, "Esse padrão ainda não apareceu nas suas frases e não tem exercícios de uso: por enquanto só dá para ler.", Toast.LENGTH_LONG).show()
            return
        }
        StudyGen.pendingLines = m?.lines?.map { it.first } ?: emptyList()
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
