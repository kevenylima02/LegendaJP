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
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lista de palavras salvas. */
class VocabActivity : Activity() {

    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var summary: TextView
    private lateinit var search: EditText
    private var rows: List<VocabDb.Word> = emptyList()
    private var byDate = true
    private val dateFmt = SimpleDateFormat("dd/MM/yyyy", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vocab)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.vocab)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        summary = findViewById(R.id.summary)
        search = findViewById(R.id.search)
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
            override fun afterTextChanged(s: Editable?) { load() }
        })
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        val q = search.text.toString().trim()
        Thread {
            val result = VocabDb.get(this).words(q, byDate)
            runOnUiThread {
                rows = result
                adapter.notifyDataSetChanged()
                empty.visibility = if (rows.isEmpty() && q.isEmpty()) View.VISIBLE else View.GONE
                summary.text = when (rows.size) { 0 -> ""; 1 -> "1 palavra"; else -> "${rows.size} palavras" }
            }
        }.start()
    }

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
                byDate = !byDate
                item.isChecked = !byDate
                load()
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
            v.findViewById<TextView>(R.id.count).text = if (w.known) "conheço ✓" else if (w.count == 1) "1 frase" else "${w.count} frases"
            v.alpha = if (w.known) 0.55f else 1f
            Score.bind(v.findViewById(R.id.barKanji), w.kanjiScore)
            Score.bind(v.findViewById(R.id.barMeaning), w.meaningScore)
            return v
        }
    }
}
