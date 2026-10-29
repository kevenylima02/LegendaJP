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
import android.view.Menu
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
 * Dicionário de gramática: os padrões que o app reconhece nas frases (catálogo próprio, com
 * exercícios) juntos com a lista completa do Bunpro (importada para o armazenamento do app; ver
 * BunproData), ligados pelo nome quando são o mesmo padrão. Busca, filtros por nível (N5–N1, Fora do
 * JLPT, Kansai-ben), "Nas minhas frases" e "Com exercícios". Cada ficha: explicação, como se forma,
 * exemplo, uma frase sua, revisão, Estudar (padrões do app) e ✦ explicação em português pela IA.
 *
 * Leveza: ListView (só as fichas visíveis existem) e a busca dos padrões nas suas frases fica
 * guardada por arquivo (files/grammar_index), refeita só para arquivos novos ou alterados.
 */
class GrammarDictActivity : Activity() {

    companion object {
        const val EXTRA_ID = "point"
        /** Quantas frases por padrão ficam guardadas para a ficha e para a sessão de estudo. */
        private const val KEEP = 30
        private const val REQ_IMPORT = 41
        private val FILTERS = listOf("N5", "N4", "N3", "N2", "N1", "Fora do JLPT", "Kansai-ben")
    }

    private class Mine(var count: Int, val lines: MutableList<Pair<StudyGen.Line, Grammar.Hit>>)

    /** Um padrão do dicionário: do catálogo do app, do Bunpro, ou dos dois (ligados). */
    private class Entry(val cat: Grammar.Point?, val bp: BunproData.Point?, val sort: Int) {
        val key get() = cat?.id ?: "bp:${bp!!.id}"
        val level get() = bp?.level ?: cat!!.level
        val name get() = cat?.name ?: bp!!.title
    }

    private var level: String? = null          // null = todos
    private var onlyMine = false
    private var onlyDrills = false
    private var query = ""
    private var entries: List<Entry> = Grammar.all.mapIndexed { i, p -> Entry(p, null, 100000 + i) }
    private var mine: Map<String, Mine> = emptyMap()
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
        chip("drills", "Com exercícios") { onlyDrills = !onlyDrills }
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
        load()
    }

    // ---------- dados ----------

    /** Junta o catálogo do app e a lista do Bunpro, e depois procura os padrões do app nas suas frases. */
    private fun load() {
        Thread {
            val merged = mergeEntries()
            runOnUiThread { if (!isFinishing) { entries = merged; render() } }
            val found = scanSentences()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                mine = found
                scanned = true
                render()
            }
        }.start()
    }

    private fun mergeEntries(): List<Entry> {
        val bp = BunproData.all(this)
        if (bp.isEmpty()) return Grammar.all.mapIndexed { i, p -> Entry(p, null, 100000 + i) }
        val links = BunproData.links(this)                 // id do app → ponto do Bunpro
        val linkedBp = links.values.associateBy { it.id }
        val catByBp = links.entries.associate { it.value.id to it.key }
        val out = mutableListOf<Entry>()
        for (p in bp) out.add(Entry(catByBp[p.id]?.let { Grammar.point(it) }, p, p.order))
        // Padrões do app sem par no Bunpro: entram também (com exercícios), depois dos do Bunpro do mesmo nível
        Grammar.all.forEachIndexed { i, c -> if (c.id !in links) out.add(Entry(c, null, 100000 + i)) }
        check(linkedBp.size == links.size)
        return out
    }

    private fun indexDir() = File(filesDir, "grammar_index").apply { mkdirs() }

    /**
     * Procura os padrões do app em todas as frases (gravações e textos). O resultado de cada arquivo
     * fica em files/grammar_index/<nome>.json (chave: tamanho do texto), para não refazer o Kuromoji.
     */
    private fun scanSentences(): Map<String, Mine> {
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
                if (o.optInt("len", -1) == text.length && o.optInt("v") == Grammar.all.size) hits = o.optJSONArray("hits")
            } catch (_: Exception) {}
            if (hits == null) {
                hits = JSONArray()
                for ((i, l) in lines.withIndex()) {
                    if (l.text.length < 4) continue
                    for (h in (try { Grammar.find(l.text) } catch (_: Exception) { emptyList() }))
                        hits.put(JSONArray().put(i).put(h.point.id).put(h.start).put(h.end))
                }
                try { f.writeText(JSONObject().put("len", text.length).put("v", Grammar.all.size).put("hits", hits).toString()) } catch (_: Exception) {}
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
        dir.listFiles()?.forEach { if (it.name !in seen) it.delete() }
        return byPoint
    }

    // ---------- lista ----------

    private fun levelRank(l: String) = FILTERS.indexOf(l).let { if (it < 0) FILTERS.size else it }

    private fun matches(e: Entry, q: String): Boolean {
        if (q.isEmpty()) return true
        val c = e.cat; val b = e.bp
        return (c != null && (c.name.lowercase().contains(q) || c.explanation.lowercase().contains(q) || c.id.contains(q) ||
            (GrammarDrill.spec(c.id)?.meaning?.lowercase()?.contains(q) ?: false))) ||
            (b != null && (b.title.lowercase().contains(q) || b.furigana.contains(q) || b.meaning.lowercase().contains(q) ||
                b.keywords.lowercase().contains(q)))
    }

    private fun visible(): List<Entry> {
        val q = query.lowercase()
        val all = entries.filter { e ->
            (level == null || e.level == level) &&
                (!onlyMine || (e.cat != null && mine.containsKey(e.cat.id))) &&
                (!onlyDrills || (e.cat != null && GrammarDrill.spec(e.cat.id) != null)) &&
                matches(e, q)
        }
        return if (onlyMine) all.sortedByDescending { mine[it.cat?.id]?.count ?: 0 }
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
        val hasBp = entries.any { it.bp != null }
        header.text = "${items.size} de ${entries.size} padrões · " +
            FILTERS.filter { (byLevel[it] ?: 0) > 0 }.joinToString(" · ") { "$it ${byLevel[it]}" } +
            (if (scanned) " · ${mine.size} aparecem nas suas frases" else " · procurando nas suas frases…") +
            (if (hasBp) "" else "\nSó os padrões que o app reconhece. Para a lista completa (N5 a N1, Fora do JLPT, Kansai-ben): ⋮ → Baixar a lista do Bunpro.")
        adapter.notifyDataSetChanged()
        val want = intent.getStringExtra(EXTRA_ID)
        if (want != null && scanned) {   // só depois da busca nas frases: as fichas mudam de altura
            intent.removeExtra(EXTRA_ID)
            val i = items.indexOfFirst { it.cat?.id == want }
            if (i >= 0) list.post { list.setSelection(i) }
        }
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = card(items[position])
    }

    private fun card(e: Entry): View {
        val cat = e.cat; val bp = e.bp
        val spec = cat?.let { GrammarDrill.spec(it.id) }
        val m = cat?.let { mine[it.id] }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.bg_card); setPadding(dp(14), dp(12), dp(14), dp(12)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply { text = e.name; textSize = 18f; setTypeface(null, Typeface.BOLD); setTextColor(getColor(R.color.text)) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(TextView(this).apply {
            text = e.level; textSize = 12f; setTextColor(getColor(R.color.accent)); setPadding(dp(8), dp(2), dp(8), dp(2)); setBackgroundResource(R.drawable.bg_chip)
        })
        box.addView(top)
        if (bp != null && (bp.furigana != bp.title || cat != null)) box.addView(TextView(this).apply {
            text = (if (cat != null) "Bunpro: " else "") + bp.furigana
            textSize = 13f; setTextColor(getColor(R.color.text_secondary)); setPadding(0, dp(2), 0, 0)
        })
        if (cat != null) box.addView(TextView(this).apply { text = cat.explanation; textSize = 14f; setTextColor(getColor(R.color.text)); setPadding(0, dp(4), 0, 0) })
        if (bp != null && bp.meaning.isNotBlank()) box.addView(TextView(this).apply {
            text = "EN: " + bp.meaning; textSize = 13f; setTextColor(getColor(if (cat == null) R.color.text else R.color.text_secondary)); setPadding(0, dp(4), 0, 0)
        })
        val meta = mutableListOf<String>()
        if (cat != null) {
            val bucket = Srs.get(this, Srs.GRAMMAR, cat.id)?.let { Srs.bucket(it) }
            meta.add(if (m != null) "nas suas frases: ${m.count}" else if (scanned) "ainda não apareceu nas suas frases" else "…")
            meta.add(if (bucket != null) "revisão: $bucket" else "ainda sem cartão")
            meta.add(if (spec != null) "com exercícios de uso" else "só reconhecer")
        } else meta.add("só leitura: o app ainda não reconhece esse padrão nas frases")
        box.addView(TextView(this).apply { text = meta.joinToString(" · "); textSize = 12f; setTextColor(getColor(R.color.text_secondary)); setPadding(0, dp(4), 0, 0) })
        if (spec != null) {
            val formation = spec.rule() + "\n\n" + spec.formation()
            val tv = TextView(this).apply { textSize = 13f; setTextColor(getColor(R.color.accent)); setPadding(0, dp(6), 0, 0); text = "▸ Como se forma (grupos 1, 2 e 3)" }
            var open = false
            tv.setOnClickListener {
                open = !open
                tv.text = if (open) "▾ Como se forma\n" + formation else "▸ Como se forma (grupos 1, 2 e 3)"
                tv.setTextColor(getColor(if (open) R.color.text else R.color.accent))
            }
            box.addView(tv)
        }
        if (cat != null) box.addView(TextView(this).apply { text = "Ex.: " + cat.example; textSize = 15f; setTextColor(getColor(R.color.text)); setPadding(0, dp(8), 0, 0) })
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
        if (cat != null) row.addView(small("Estudar") { study(cat, m) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        if (Ai.enabled(this)) row.addView(small("✦ Explicar com IA") { if (cat != null) explain(cat, m, bp) else explainBunpro(bp!!) })
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

    /** ✦ IA explica o padrão do app numa frase sua (ou no exemplo do catálogo). */
    private fun explain(p: Grammar.Point, m: Mine?, bp: BunproData.Point?) {
        val spec = GrammarDrill.spec(p.id)
        val (text, start, end, rec) = if (m != null) {
            val (l, h) = m.lines.first(); Quad(l.text, h.start, h.end, l.recording)
        } else Quad(p.example, 0, 0, null)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        box.addView(TextView(this).apply { this.text = boldSpan(text, start, end); textSize = 17f; setTextColor(getColor(R.color.text)) })
        box.addView(TextView(this).apply {
            this.text = p.explanation + (bp?.let { "\nEN: ${it.meaning}" } ?: "") + (spec?.let { "\n\nComo se forma:\n" + it.formation() } ?: "")
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

    /** ✦ IA explica em português um padrão que só está na lista do Bunpro (sem frase sua). */
    private fun explainBunpro(bp: BunproData.Point) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        box.addView(TextView(this).apply { text = bp.furigana; textSize = 20f; setTextColor(getColor(R.color.text)) })
        box.addView(TextView(this).apply {
            text = "${bp.level} · EN: ${bp.meaning}"; textSize = 13f; setTextColor(getColor(R.color.text_secondary)); setPadding(0, dp(4), 0, dp(8))
        })
        val panelBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(panelBox)
        val panel = AiPanel(this, panelBox, listOf(Ai.Request(Ai.Task.POINT, bp.title, null, bp.meaning,
            "nível ${bp.level}; leitura ${bp.furigana}; palavras-chave: ${bp.keywords.take(200)}", Prefs(this).level)))
        panel.show()
        AlertDialog.Builder(this).setTitle("✦ ${bp.title}").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Fechar", null).setOnDismissListener { panel.hide() }.show()
    }

    private data class Quad(val text: String, val start: Int, val end: Int, val rec: String?)

    /** ✦ Material de estudo do nível (o filtrado, ou o seu nível): a IA escreve e o app guarda. */
    private fun guide() {
        val lvl = level ?: "N${Prefs(this).level}"
        val points = entries.filter { it.level == lvl }.sortedBy { it.sort }
        if (points.isEmpty()) { Toast.makeText(this, "Sem padrões do nível $lvl", Toast.LENGTH_SHORT).show(); return }
        val cap = 80
        val used = points.take(cap)
        val note = used.joinToString("\n") { e -> "${e.name} — ${e.cat?.explanation ?: e.bp?.meaning ?: ""}" }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        box.addView(TextView(this).apply {
            text = "Material sobre ${if (points.size > cap) "os primeiros $cap de ${points.size}" else "os ${points.size}"} padrões do $lvl. " +
                "A resposta fica guardada: da próxima vez abre na hora, sem internet."
            textSize = 13f; setTextColor(getColor(R.color.text_secondary)); setPadding(0, 0, 0, dp(8))
        })
        val panelBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(panelBox)
        val panel = AiPanel(this, panelBox, listOf(Ai.Request(Ai.Task.GUIDE, lvl, null, "", note, Prefs(this).level)))
        panel.show()
        AlertDialog.Builder(this).setTitle("✦ Material de estudo · $lvl").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Fechar", null).setOnDismissListener { panel.hide() }.show()
    }

    // ---------- lista do Bunpro: importar ou baixar ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Baixar a lista do Bunpro agora")
        menu.add(0, 2, 1, "Importar a lista do Bunpro (arquivo .json)…")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            1 -> download()
            2 -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream")), REQ_IMPORT)
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun download() {
        val msg = if (BunproData.has(this)) "Baixar de novo a lista de gramática do Bunpro (última: ${BunproData.fetched(this).ifBlank { "?" }})? É uma requisição à página pública; depois o app usa só o arquivo guardado."
        else "Baixar a lista de gramática do Bunpro (N5 a N1, Fora do JLPT e Kansai-ben)? É uma requisição à página pública; depois o app usa só o arquivo guardado no celular."
        AlertDialog.Builder(this).setMessage(msg).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Baixar") { _, _ ->
                val label = TextView(this).apply { text = "Baixando a lista do Bunpro…"; textSize = 14f; setTextColor(getColor(R.color.text)); setPadding(dp(24), dp(20), dp(24), dp(12)) }
                val dlg = AlertDialog.Builder(this).setView(label).setCancelable(false).show()
                Thread {
                    val res = try { Result.success(BunproData.download(this)) } catch (e: Exception) { Result.failure(e) }
                    runOnUiThread {
                        dlg.dismiss()
                        res.onSuccess { n -> Toast.makeText(this, "$n pontos de gramática guardados", Toast.LENGTH_LONG).show(); load() }
                            .onFailure { e -> Toast.makeText(this, "Não consegui baixar: ${e.message}", Toast.LENGTH_LONG).show() }
                    }
                }.start()
            }.show()
    }

    @Deprecated("Activity simples sem AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Thread {
            val res = try {
                Result.success(contentResolver.openInputStream(uri)?.use { BunproData.import(this, it) } ?: throw IllegalStateException("não consegui abrir o arquivo"))
            } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                res.onSuccess { n -> Toast.makeText(this, "$n pontos de gramática importados", Toast.LENGTH_LONG).show(); load() }
                    .onFailure { e -> Toast.makeText(this, "Arquivo inválido: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }
}
