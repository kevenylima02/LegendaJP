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

    private lateinit var uri: Uri
    private var name = ""
    private var sessions: List<VocabDb.StudySession> = emptyList()
    private val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.US)
    private lateinit var list: ListView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_study_menu)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        uri = intent.getParcelableExtra(StudyActivity.EXTRA_URI, Uri::class.java) ?: run { finish(); return }
        name = intent.getStringExtra(StudyActivity.EXTRA_NAME) ?: ""
        title = "Estudar · " + Recordings.prettyName(name)
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
                startActivity(
                    Intent(this, StudyActivity::class.java)
                        .putExtra(StudyActivity.EXTRA_URI, uri)
                        .putExtra(StudyActivity.EXTRA_NAME, name)
                        .putExtra(StudyActivity.EXTRA_FOCUS, focus.name)
                )
            }
        }
        findViewById<TextView>(R.id.level).text = "Nível atual: N${Prefs(this).level} (mude em Configurações)"

        // Contagem de exercícios disponíveis por foco
        Thread {
            val text = try { Recordings.readText(this, uri) } catch (_: Exception) { "" }
            val lines = StudyGen.lines(text)
            val counts = StudyGen.counts(this, lines, Prefs(this).level)
            runOnUiThread {
                for ((focus, id) in focusButtons) {
                    val n = counts[focus] ?: 0
                    val b = findViewById<Button>(id)
                    b.text = "${focus.label}  ·  $n"
                    b.isEnabled = n > 0
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
            val s = VocabDb.get(this).sessions(Recordings.baseName(name))
            runOnUiThread {
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
            return v
        }
    }
}
