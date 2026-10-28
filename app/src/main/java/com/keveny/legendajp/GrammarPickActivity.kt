package com.keveny.legendajp

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Escolha de pontos gramaticais antes de estudar Gramática: lista os padrões do catálogo que aparecem
 * nas frases da gravação (ou pasta), com nível, explicação curta, quantas frases, estado na revisão e
 * uma frase de exemplo; ✦ IA explica o padrão naquela frase. Até [MAX] escolhidos → sessão só com
 * esses padrões, com exercícios de uso para todos os grupos de verbo (GrammarDrill.coverage).
 */
class GrammarPickActivity : Activity() {

    companion object { const val MAX = 5 }

    private class Row(val point: Grammar.Point, val lines: List<Pair<StudyGen.Line, Grammar.Hit>>, val spec: GrammarDrill.Spec?, val bucket: String?)

    private var uri: Uri? = null
    private var name = ""
    private var rows: List<Row> = emptyList()
    private val chosen = LinkedHashSet<String>()
    private lateinit var listBox: LinearLayout
    private lateinit var header: TextView
    private lateinit var btnStart: Button

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Escolher gramática"
        uri = intent.getParcelableExtra(StudyActivity.EXTRA_URI, Uri::class.java)
        name = intent.getStringExtra(StudyActivity.EXTRA_NAME) ?: ""
        if (uri == null && !Folders.isStudyName(name)) { finish(); return }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        header = TextView(this).apply { textSize = 13f; setTextColor(getColor(R.color.text_secondary)); text = "Procurando os padrões nas frases…" }
        root.addView(header)
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(listBox) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
        val btnAll = Button(this).apply {
            text = "Tudo misturado"; isAllCaps = false; setBackgroundResource(R.drawable.bg_button_outline); setTextColor(getColor(R.color.text))
            setOnClickListener { start(null) }
        }
        btnStart = Button(this).apply {
            isAllCaps = false; setBackgroundResource(R.drawable.bg_button_primary); setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { if (chosen.isEmpty()) Toast.makeText(this@GrammarPickActivity, "Marque pelo menos um padrão", Toast.LENGTH_SHORT).show() else start(chosen.toList()) }
        }
        bottom.addView(btnAll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        bottom.addView(btnStart, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bottom)
        setContentView(root)
        updateHeader()
        load()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun load() {
        Thread {
            val lines = if (Folders.isStudyName(name)) Folders.lines(this, name) else {
                val text = try { Recordings.readText(this, uri!!) } catch (_: Exception) { "" }
                StudyGen.lines(text, Recordings.baseName(name))
            }
            val byPoint = LinkedHashMap<String, MutableList<Pair<StudyGen.Line, Grammar.Hit>>>()
            for (l in lines) {
                if (l.text.length < 4) continue
                for (h in (try { Grammar.find(l.text) } catch (_: Exception) { emptyList() })) byPoint.getOrPut(h.point.id) { mutableListOf() }.add(l to h)
            }
            val levels = Prefs(this).levels
            fun lvl(p: Grammar.Point) = p.level.removePrefix("N").toIntOrNull() ?: 5
            val list = byPoint.entries.map { (id, ls) ->
                val p = ls.first().second.point
                Row(p, ls, GrammarDrill.spec(id), Srs.get(this, Srs.GRAMMAR, id)?.let { Srs.bucket(it) })
            }.sortedWith(compareBy<Row> { if (lvl(it.point) in levels) 0 else 1 }.thenByDescending { it.lines.size }.thenByDescending { lvl(it.point) })
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                rows = list
                render()
                updateHeader()
            }
        }.start()
    }

    private fun updateHeader() {
        header.text = if (rows.isEmpty()) "Procurando os padrões nas frases…"
        else "${rows.size} padrões nestas frases · escolhidos ${chosen.size} de $MAX. Marque os que quer treinar: a sessão fica só com eles, " +
            "com exercícios de uso para cada grupo de verbo (godan, ichidan, する, 来る) e adjetivo-い. Toque longo numa ficha para ver todas as frases."
        btnStart.text = if (chosen.isEmpty()) "Começar" else "Começar (${chosen.size})"
    }

    private fun render() {
        listBox.removeAllViews()
        if (rows.isEmpty()) {
            listBox.addView(TextView(this).apply { text = "Nenhum padrão do catálogo nestas frases."; textSize = 15f; setTextColor(getColor(R.color.text_secondary)) })
            return
        }
        for (r in rows) listBox.addView(card(r), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
    }

    private fun card(r: Row): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.bg_card); setPadding(dp(14), dp(12), dp(14), dp(12)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = r.point.name; textSize = 18f; setTypeface(null, Typeface.BOLD); setTextColor(getColor(R.color.text))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(TextView(this).apply {
            text = r.point.level; textSize = 12f; setTextColor(getColor(R.color.accent))
            setPadding(dp(8), dp(2), dp(8), dp(2)); setBackgroundResource(R.drawable.bg_chip)
        })
        val check = CheckBox(this).apply {
            isChecked = r.point.id in chosen
            setOnCheckedChangeListener { _, on ->
                if (on) {
                    if (chosen.size >= MAX) { isChecked = false; Toast.makeText(this@GrammarPickActivity, "No máximo $MAX padrões por sessão", Toast.LENGTH_SHORT).show() }
                    else chosen.add(r.point.id)
                } else chosen.remove(r.point.id)
                updateHeader()
            }
        }
        top.addView(check, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        box.addView(top)
        box.addView(TextView(this).apply { text = r.point.explanation; textSize = 14f; setTextColor(getColor(R.color.text)); setPadding(0, dp(4), 0, 0) })
        val meta = mutableListOf("em ${r.lines.size} ${if (r.lines.size == 1) "frase" else "frases"}")
        meta.add(if (r.bucket != null) "revisão: ${r.bucket}" else "ainda sem cartão")
        meta.add(if (r.spec != null) "com exercícios de uso" else "só reconhecer")
        box.addView(TextView(this).apply { text = meta.joinToString(" · "); textSize = 12f; setTextColor(getColor(R.color.text_secondary)); setPadding(0, dp(4), 0, 0) })
        val (line, hit) = r.lines.first()
        box.addView(TextView(this).apply {
            text = boldSpan(line.text, hit.start, hit.end); textSize = 16f; setTextColor(getColor(R.color.text)); setPadding(0, dp(8), 0, 0)
        })
        if (Ai.enabled(this)) {
            box.addView(Button(this).apply {
                text = "✦ Explicar com IA"; isAllCaps = false; textSize = 13f
                setBackgroundResource(R.drawable.bg_button_outline); setTextColor(getColor(R.color.text))
                minHeight = 0; minimumHeight = 0; setPadding(dp(14), dp(6), dp(14), dp(6))
                setOnClickListener { explain(r) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        box.setOnClickListener { check.isChecked = !check.isChecked }
        box.setOnLongClickListener { showAll(r); true }
        return box
    }

    private fun boldSpan(text: String, start: Int, end: Int): CharSequence {
        val s = SpannableString(text)
        if (start in 0 until end && end <= text.length) s.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }

    /** Todas as frases onde o padrão aparece. */
    private fun showAll(r: Row) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        for ((l, h) in r.lines) box.addView(TextView(this).apply {
            text = boldSpan(l.text, h.start, h.end); textSize = 16f; setTextColor(getColor(R.color.text)); setPadding(0, dp(6), 0, dp(6))
        })
        AlertDialog.Builder(this).setTitle("${r.point.name} · ${r.lines.size} frases").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Fechar", null).show()
    }

    /** ✦ IA: explica o padrão na primeira frase em que aparece. */
    private fun explain(r: Row) {
        val (line, hit) = r.lines.first()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        box.addView(TextView(this).apply { text = boldSpan(line.text, hit.start, hit.end); textSize = 17f; setTextColor(getColor(R.color.text)) })
        val panelBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(panelBox)
        val rec = line.recording ?: Recordings.baseName(name)
        val panel = AiPanel(this, panelBox, listOf(
            Ai.Request(Ai.Task.GRAMMAR, line.text, rec, r.point.name, line.text.substring(hit.start, hit.end), Prefs(this).level),
            Ai.Request(Ai.Task.TRANSLATE, line.text, rec, level = Prefs(this).level),
        ))
        panel.show()
        AlertDialog.Builder(this).setTitle("✦ ${r.point.name}").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Fechar", null).setOnDismissListener { panel.hide() }.show()
    }

    private fun start(points: List<String>?) {
        startActivity(Intent(this, StudyActivity::class.java)
            .putExtra(StudyActivity.EXTRA_URI, uri)
            .putExtra(StudyActivity.EXTRA_NAME, name)
            .putExtra(StudyActivity.EXTRA_FOCUS, StudyGen.Focus.GRAMATICA.name)
            .apply { if (points != null) putStringArrayListExtra(StudyActivity.EXTRA_POINTS, ArrayList(points)) })
        finish()
    }
}
