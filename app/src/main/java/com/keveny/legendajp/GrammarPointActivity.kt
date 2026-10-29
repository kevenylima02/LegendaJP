package com.keveny.legendajp

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Tela de um padrão de gramática: tudo o que o banco do app tem sobre ele (significado, a que se liga,
 * como se forma, quando usar, exemplos com o padrão em negrito, parecidos, erros comuns), as suas
 * frases onde ele aparece e o botão para estudar só esse padrão (lacunas com os exemplos, conjugação
 * com verbos dos 3 grupos quando o padrão é forma + sufixo, e as suas frases).
 */
class GrammarPointActivity : Activity() {

    companion object {
        const val EXTRA_KEY = "key"

        fun open(act: Activity, key: String?) {
            if (key == null) act.startActivity(Intent(act, GrammarDictActivity::class.java))
            else act.startActivity(Intent(act, GrammarPointActivity::class.java).putExtra(EXTRA_KEY, key))
        }
    }

    private lateinit var key: String
    private lateinit var body: LinearLayout
    private lateinit var mineBox: LinearLayout
    private var mine: GrammarIndex.Mine? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        key = intent.getStringExtra(EXTRA_KEY) ?: run { finish(); return }
        val e = GrammarBank.entry(key)
        val cat = Grammar.point(e?.key ?: key)
        if (e == null && cat == null) { finish(); return }
        title = e?.name ?: cat!!.name
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(24)) }
        setContentView(ScrollView(this).apply { addView(body) })
        build(e, cat!!)
        mine = GrammarIndex.last?.get(cat.id)
        showMine(cat)
        if (GrammarIndex.last == null) Thread {
            val m = try { GrammarIndex.scan(this)[cat.id] } catch (_: Exception) { null }
            runOnUiThread { if (!isFinishing) { mine = m; showMine(cat) } }
        }.start()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ---------- montagem ----------

    private fun text(s: CharSequence, size: Float = 15f, color: Int = getColor(R.color.text), top: Int = 4) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); setPadding(0, dp(top), 0, 0); setLineSpacing(dp(2).toFloat(), 1f)
    }

    private fun section(title: String) {
        body.addView(text(title, 13f, getColor(R.color.accent), 18).apply { setTypeface(null, Typeface.BOLD); isAllCaps = true; letterSpacing = 0.05f })
    }

    private fun bullets(lines: List<String>) {
        for (l in lines) body.addView(text("• $l", 15f, top = 4))
    }

    private fun bold(s: String, start: Int, end: Int): CharSequence {
        val sb = SpannableStringBuilder(s)
        if (start in 0 until end && end <= s.length) sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    private fun build(e: GrammarBank.Entry?, p: Grammar.Point) {
        val sec = getColor(R.color.text_secondary)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(text(e?.name ?: p.name, 24f, top = 0).apply { setTypeface(null, Typeface.BOLD) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(TextView(this).apply {
            text = e?.level ?: p.level; textSize = 13f; setTextColor(getColor(R.color.accent)); setPadding(dp(10), dp(3), dp(10), dp(3)); setBackgroundResource(R.drawable.bg_chip)
        })
        body.addView(top)
        if (e != null) {
            val sub = listOf(e.reading.takeIf { it.isNotBlank() && it != e.name }, e.romaji.takeIf { it.isNotBlank() }).filterNotNull().joinToString(" · ")
            if (sub.isNotEmpty()) body.addView(text(sub, 14f, sec, 2))
            if (e.app != null && e.title != e.name) body.addView(text("Também escrito: ${e.title}", 13f, sec, 2))
            body.addView(text(e.summary, 17f, top = 10).apply { setTypeface(null, Typeface.BOLD) })
        }
        // Situação: revisão e exercícios
        val bucket = Srs.get(this, Srs.GRAMMAR, p.id)?.let { Srs.bucket(it) }
        val spec = GrammarDrill.spec(p.id)
        body.addView(text(listOfNotNull(
            if (bucket != null) "revisão: $bucket" else "ainda sem cartão de revisão",
            if (spec != null) "com exercício de conjugação" else null,
            if (Grammar.isCatalog(p.id) || (e?.detect?.isNotEmpty() == true)) "o app reconhece nas suas frases" else "o app ainda não reconhece nas frases",
        ).joinToString(" · "), 12f, sec, 6))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        row.addView(Button(this).apply {
            text = "Estudar este padrão"; isAllCaps = false; textSize = 15f
            setBackgroundResource(R.drawable.bg_button_primary); setTextColor(Color.WHITE)
            minHeight = 0; minimumHeight = 0; setPadding(dp(18), dp(10), dp(18), dp(10))
            setOnClickListener { study(p.id, e) }
        })
        body.addView(row)

        if (e == null) {
            // Padrão do app sem ficha no banco (não deveria acontecer): o que o catálogo tem, e a IA
            section("Significado"); body.addView(text(p.explanation))
            if (spec != null) { section("Como se forma"); body.addView(text(spec.rule() + "\n\n" + spec.formation(), 14f)) }
            section("Exemplo"); body.addView(text(p.example, 17f))
            if (Ai.enabled(this)) {
                val panelBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(16), 0, 0) }
                body.addView(panelBox)
                AiPanel(this, panelBox, listOf(Ai.Request(Ai.Task.POINT, p.name, null, "", "nível ${p.level}", Prefs(this).level))).show()
            }
        } else {
            section("Significado")
            body.addView(text(e.meaning))
            if (e.app != null && p.explanation.isNotBlank() && p.explanation != e.meaning) body.addView(text(p.explanation, 14f, sec, 6))
            if (e.attaches.isNotEmpty()) {
                section("Liga-se a")
                body.addView(text(e.attaches.joinToString(", ") { GrammarBank.ATTACH[it] ?: it }))
            }
            section("Como se forma")
            bullets(e.formation)
            if (spec != null) {
                val more = text("▸ Conjugação grupo por grupo", 14f, getColor(R.color.accent), 8)
                var open = false
                val full = spec.rule() + "\n\n" + spec.formation()
                more.setOnClickListener {
                    open = !open
                    more.text = if (open) "▾ Conjugação grupo por grupo\n$full" else "▸ Conjugação grupo por grupo"
                    more.setTextColor(getColor(if (open) R.color.text else R.color.accent))
                }
                body.addView(more)
            }
            section("Quando usar")
            body.addView(text(e.usage))
            section("Exemplos")
            for (x in e.examples) example(x)
            if (e.similar.isNotEmpty()) {
                section("Parecidos")
                for (s in e.similar) {
                    val sb = SpannableStringBuilder(s.pattern)
                    sb.setSpan(StyleSpan(Typeface.BOLD), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.append(": ").append(s.diff)
                    body.addView(text(sb, 15f, top = 6))
                }
            }
            if (e.errors.isNotEmpty()) {
                section("Erros comuns")
                bullets(e.errors)
            }
        }
        section("Nas suas frases")
        mineBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(mineBox)
    }

    /** Exemplo: palavras tocáveis (ficha da palavra), o padrão em negrito, a tradução embaixo. */
    private fun example(x: GrammarBank.Ex) {
        val tv = TextView(this).apply {
            textSize = 18f; setTextColor(getColor(R.color.text)); setPadding(0, dp(10), 0, 0)
            movementMethod = LinkMovementMethod.getInstance(); highlightColor = Color.TRANSPARENT
        }
        fun paint() {
            val deco = SpannableStringBuilder(Jp.decorate(x.jp, VocabDb.studyKeys, getColor(R.color.saved_word), VocabDb.knownKeys, getColor(R.color.known_word), all = true) { w ->
                WordCard.show(this, w, x.jp, "", 0, onChanged = { paint() })
            })
            val at = x.jp.indexOf(x.target)
            if (at >= 0) deco.setSpan(StyleSpan(Typeface.BOLD), at, at + x.target.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            Furigana.set(this, tv, deco, x.jp)
        }
        paint()
        body.addView(tv)
        body.addView(text(x.pt, 14f, getColor(R.color.text_secondary), 2))
    }

    private fun showMine(p: Grammar.Point) {
        mineBox.removeAllViews()
        val m = mine
        if (m == null) {
            mineBox.addView(text(if (GrammarIndex.last == null) "Procurando nas suas gravações e textos…" else "Ainda não apareceu nas suas gravações e textos.", 14f, getColor(R.color.text_secondary)))
            return
        }
        mineBox.addView(text("Aparece ${m.count} ${if (m.count == 1) "vez" else "vezes"}" + (if (m.lines.size < m.count) " (mostrando ${m.lines.size})" else "") + ".", 13f, getColor(R.color.text_secondary)))
        for ((l, h) in m.lines.take(10)) {
            mineBox.addView(text(bold(l.text, h.start, h.end), 16f, top = 8).apply {
                setOnClickListener { explainMine(p, l, h) }
            })
        }
        if (Ai.enabled(this)) mineBox.addView(text("Toque numa frase para a IA explicar o padrão nela.", 12f, getColor(R.color.text_secondary), 8))
    }

    /** ✦ IA explica o padrão numa frase sua. */
    private fun explainMine(p: Grammar.Point, l: StudyGen.Line, h: Grammar.Hit) {
        if (!Ai.enabled(this)) return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        box.addView(TextView(this).apply { text = bold(l.text, h.start, h.end); textSize = 17f; setTextColor(getColor(R.color.text)) })
        val panelBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(panelBox)
        val note = if (h.end > h.start) l.text.substring(h.start, h.end) else ""
        val panel = AiPanel(this, panelBox, listOf(
            Ai.Request(Ai.Task.GRAMMAR, l.text, l.recording, p.name, note, Prefs(this).level),
            Ai.Request(Ai.Task.TRANSLATE, l.text, l.recording, level = Prefs(this).level),
        ))
        panel.show()
        AlertDialog.Builder(this).setTitle("✦ ${p.name}").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Fechar", null).setOnDismissListener { panel.hide() }.show()
    }

    /** Sessão só com este padrão: lacunas com os exemplos, conjugação (se houver) e as suas frases. */
    private fun study(id: String, e: GrammarBank.Entry?) {
        if (e == null && mine == null && GrammarDrill.spec(id) == null) {
            android.widget.Toast.makeText(this, "Esse padrão ainda não tem exercícios.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        StudyGen.pendingLines = mine?.lines?.map { it.first } ?: emptyList()
        startActivity(Intent(this, StudyActivity::class.java)
            .putExtra(StudyActivity.EXTRA_NAME, Folders.STUDY_PREFIX + Folders.ALL)
            .putExtra(StudyActivity.EXTRA_FOCUS, StudyGen.Focus.GRAMATICA.name)
            .putStringArrayListExtra(StudyActivity.EXTRA_POINTS, arrayListOf(id)))
    }
}
