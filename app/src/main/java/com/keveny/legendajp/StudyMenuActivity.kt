package com.keveny.legendajp

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Menu de estudo de uma gravação: foco dos exercícios e histórico das sessões. */
class StudyMenuActivity : Activity() {

    private var uri: Uri? = null
    private var name = ""
    private var saved = false
    private var sessions: List<VocabDb.StudySession> = emptyList()
    private val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.US)
    private lateinit var list: ListView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_study_menu)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        saved = intent.getBooleanExtra(StudyActivity.EXTRA_SAVED, false)
        uri = intent.getParcelableExtra(StudyActivity.EXTRA_URI, Uri::class.java)
        name = if (saved) StudyActivity.SAVED_RECORDING else (intent.getStringExtra(StudyActivity.EXTRA_NAME) ?: "")
        val folder = Folders.isStudyName(name)
        if (!saved && !folder && uri == null) { finish(); return }
        title = if (saved) "Estudar · Vocabulário salvo" else if (folder) "Estudar · " + Folders.studyTitle(this, name) else "Estudar · " + Recordings.display(this, name)
        list = findViewById(R.id.history)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            startActivity(Intent(this, StudyHistoryActivity::class.java).putExtra(StudyHistoryActivity.EXTRA_ID, sessions[pos].id))
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            AlertDialog.Builder(this).setMessage("Apagar esta sessão do histórico?")
                .setPositiveButton("Apagar") { _, _ -> VocabDb.get(this).deleteSession(sessions[pos].id); loadHistory() }
                .setNegativeButton(android.R.string.cancel, null).show()
            true
        }

        val focusButtons = mapOf(
            StudyGen.Focus.MISTO to R.id.btnMisto,
            StudyGen.Focus.GRAMATICA to R.id.btnGramatica,
            StudyGen.Focus.VOCAB to R.id.btnVocab,
            StudyGen.Focus.KANJI to R.id.btnKanji,
        )
        for ((focus, id) in focusButtons) {
            findViewById<Button>(id).setOnClickListener {
                // Gramática vai direto; os outros focos passam pela escolha das palavras
                val target = if (focus == StudyGen.Focus.GRAMATICA) StudyActivity::class.java else WordPickActivity::class.java
                startActivity(
                    Intent(this, target)
                        .putExtra(StudyActivity.EXTRA_URI, uri)
                        .putExtra(StudyActivity.EXTRA_NAME, name)
                        .putExtra(StudyActivity.EXTRA_FOCUS, focus.name)
                        .putExtra(StudyActivity.EXTRA_SAVED, saved)
                )
            }
        }
        findViewById<TextView>(R.id.level).text = "Níveis em primeiro: " + Prefs(this).levels.sortedDescending().joinToString(", ") { "N$it" } + " (Configurações)"
        findViewById<Button>(R.id.btnMistakes).setOnClickListener {
            startActivity(Intent(this, MistakesActivity::class.java))
        }

        // Contagem de exercícios disponíveis por foco
        Thread {
            val lines = if (saved) StudyGen.linesFromVocab(this) else if (folder) Folders.lines(this, name) else {
                val text = try { Recordings.readText(this, uri!!) } catch (_: Exception) { "" }
                StudyGen.lines(text, Recordings.baseName(name))
            }
            val counts = StudyGen.counts(this, lines, Prefs(this).level, if (saved) VocabDb.savedKeys else null)
            runOnUiThread {
                for ((focus, id) in focusButtons) {
                    val n = counts[focus] ?: 0
                    val b = findViewById<Button>(id)
                    b.text = "${focus.label}  ·  $n"
                    b.isEnabled = n > 0
                    if (saved && focus == StudyGen.Focus.GRAMATICA) b.visibility = View.GONE
                }
                val kinds = StudyGen.kinds.entries.joinToString("\n") { (f, ks) -> "${f.label}: " + ks.joinToString(", ") }
                findViewById<TextView>(R.id.kinds).text = "Tipos de exercício\n$kinds"
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        loadHistory()
    }

    private fun loadHistory() {
        Thread {
            val s = VocabDb.get(this).sessions(if (saved) StudyActivity.SAVED_RECORDING else Recordings.baseName(name))
            val errors = VocabDb.get(this).mistakeCount()
            runOnUiThread {
                findViewById<Button>(R.id.btnMistakes).text = "Revisar erros  ·  $errors"
                sessions = s
                adapter.notifyDataSetChanged()
                findViewById<TextView>(R.id.historyEmpty).visibility = if (s.isEmpty()) View.VISIBLE else View.GONE
            }
        }.start()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = sessions.size
        override fun getItem(position: Int) = sessions[position]
        override fun getItemId(position: Int) = sessions[position].id
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_vocab, parent, false)
            val s = sessions[position]
            v.findViewById<TextView>(R.id.base).text = s.focus
            v.findViewById<TextView>(R.id.base).textSize = 17f
            v.findViewById<TextView>(R.id.reading).text = ""
            v.findViewById<TextView>(R.id.meta).text = fmt.format(Date(s.created))
            v.findViewById<TextView>(R.id.count).text = "${s.correct}/${s.total}"
            v.findViewById<View>(R.id.bars).visibility = View.GONE
            return v
        }
    }
}
