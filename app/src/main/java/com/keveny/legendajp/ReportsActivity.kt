package com.keveny.legendajp

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reportes (só no celular, por enquanto só para o Keven): lista os erros reportados. Tradução da
 * frase e da palavra têm "✦ Corrigir com IA": a IA propõe, você confere/edita e aceita; a correção
 * passa a valer no app inteiro. Os outros tipos ficam para ajuste no código (Copiar todos).
 */
class ReportsActivity : Activity() {

    private lateinit var list: ListView
    private lateinit var empty: TextView
    private var items: List<Reports.Report> = emptyList()
    private var showAll = false
    private val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Reportes"
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(16)) }
        empty = TextView(this).apply {
            text = "Nenhum reporte aberto. Reporte pelo menu ⋮ no exercício ou na escolha de palavras."
            setTextColor(getColor(R.color.text_secondary)); textSize = 15f; setPadding(0, dp(24), 0, 0)
        }
        list = ListView(this).apply { divider = null }
        root.addView(empty)
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> open(items[pos]) }
        load()
    }

    private fun load() {
        Thread {
            val l = Reports.list(this, open = !showAll)
            runOnUiThread {
                items = l
                adapter.notifyDataSetChanged()
                empty.visibility = if (l.isEmpty()) View.VISIBLE else View.GONE
                title = if (showAll) "Reportes (todos)" else "Reportes abertos · ${l.size}"
            }
        }.start()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Mostrar resolvidos também")
        menu.add(0, 2, 0, "Copiar todos (texto)")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            1 -> { showAll = !showAll; item.title = if (showAll) "Só os abertos" else "Mostrar resolvidos também"; load() }
            2 -> {
                val text = items.joinToString("\n\n") { r ->
                    "[${Reports.kindLabel(r.kind)}] ${r.screen} · ${fmt.format(Date(r.created))} · ${r.status}\n" +
                        "Frase: ${r.sentence}\n" + (if (r.word.isNotEmpty()) "Palavra: ${r.word} (${r.reading}, ${r.pos})\n" else "") +
                        "Na tela: ${r.shown}\n" + (if (r.comment.isNotEmpty()) "Comentário: ${r.comment}" else "")
                }
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Reportes", text))
                Toast.makeText(this, "${items.size} reportes copiados", Toast.LENGTH_SHORT).show()
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ---------- um reporte ----------

    private fun open(r: Reports.Report) {
        val sb = SpannableStringBuilder()
        fun bold(t: String) { val a = sb.length; sb.append(t); sb.setSpan(StyleSpan(Typeface.BOLD), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        fun grey(t: String) { val a = sb.length; sb.append(t); sb.setSpan(ForegroundColorSpan(getColor(R.color.text_secondary)), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        grey("${r.screen} · ${fmt.format(Date(r.created))} · ${r.status}\n\n")
        if (r.sentence.isNotBlank()) { bold("Frase: "); sb.append(r.sentence).append("\n") }
        if (r.word.isNotBlank()) { bold("Palavra: "); sb.append("${r.word}  ${r.reading} · ${r.pos}\n") }
        if (r.shown.isNotBlank()) { bold("Na tela: "); sb.append(r.shown).append("\n") }
        if (r.comment.isNotBlank()) { bold("Comentário: "); sb.append(r.comment).append("\n") }
        val b = AlertDialog.Builder(this).setTitle(Reports.kindLabel(r.kind)).setMessage(sb)
            .setNegativeButton("Fechar", null)
            .setNeutralButton(if (r.status == "aberto") "Resolvido" else "Reabrir") { _, _ ->
                Thread { Reports.setStatus(this, r.id, if (r.status == "aberto") "resolvido" else "aberto") }.start()
                list.postDelayed({ load() }, 200)
            }
        val fixable = (r.kind == Reports.SENTENCE && r.sentence.isNotBlank()) || (r.kind == Reports.WORD && r.word.isNotBlank())
        if (fixable) b.setPositiveButton("✦ Corrigir com IA") { _, _ -> fixWithAi(r) }
        b.show()
    }

    /** A IA propõe a tradução; você confere/edita; Aceitar grava e marca como resolvido. */
    private fun fixWithAi(r: Reports.Report) {
        if (!Ai.enabled(this)) { Toast.makeText(this, "Ative a IA em Configurações", Toast.LENGTH_LONG).show(); return }
        val wait = AlertDialog.Builder(this).setMessage("✦ Pedindo a correção ao ${Ai.modelLabel(this)}…").setCancelable(false).show()
        Ai.fixTranslation(this, r) { proposal, err ->
            wait.dismiss()
            if (err != null || proposal == null) {
                Toast.makeText(this, "Não consegui: ${err ?: "resposta vazia"}", Toast.LENGTH_LONG).show()
                return@fixTranslation
            }
            val isWord = r.kind == Reports.WORD
            val current = if (isWord) Tr.cached(this, r.word) else (Tr.aiCached(this, r.sentence) ?: Tr.cached(this, r.sentence))
            val edit = EditText(this).apply { setText(proposal.first); textSize = 17f }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), 0)
                addView(TextView(this@ReportsActivity).apply {
                    text = (if (isWord) "Palavra: ${r.word} (${r.reading})" else "Frase: ${r.sentence}") +
                        "\n\nAtual: ${current ?: "(sem tradução)"}\n\nProposta da IA (pode editar):"
                    textSize = 14f
                    setTextColor(getColor(R.color.text))
                })
                addView(edit)
                if (proposal.second.isNotBlank()) addView(TextView(this@ReportsActivity).apply {
                    text = proposal.second; textSize = 13f; setTextColor(getColor(R.color.text_secondary))
                })
            }
            AlertDialog.Builder(this).setTitle("✦ Correção").setView(ScrollView(this).apply { addView(box) })
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Aceitar") { _, _ ->
                    val value = edit.text.toString().trim()
                    if (value.isEmpty()) return@setPositiveButton
                    Thread {
                        val db = VocabDb.get(this)
                        // Palavra: troca a tradução usada no app inteiro (exercícios e cartões).
                        // Frase: vira a tradução do Gemini (a do ML Kit continua para comparar).
                        if (isWord) db.putTranslation(r.word, value) else db.putAiTranslation(r.sentence, value)
                        Reports.setStatus(this, r.id, "resolvido")
                        runOnUiThread { Toast.makeText(this, "Corrigido", Toast.LENGTH_SHORT).show(); load() }
                    }.start()
                }
                .show()
        }
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_vocab, parent, false)
            val r = items[position]
            v.findViewById<TextView>(R.id.base).text = if (r.word.isNotBlank() && r.kind == Reports.WORD) r.word else Reports.kindLabel(r.kind)
            v.findViewById<TextView>(R.id.base).textSize = 16f
            v.findViewById<TextView>(R.id.reading).text = if (r.kind == Reports.WORD) Reports.kindLabel(r.kind) else ""
            v.findViewById<TextView>(R.id.meta).text = r.sentence.ifBlank { r.screen } + if (r.comment.isNotBlank()) "\n“${r.comment}”" else ""
            v.findViewById<TextView>(R.id.count).text = r.status
            v.findViewById<View>(R.id.bars).visibility = View.GONE
            return v
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
