package com.keveny.legendajp

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView

/** Lista as transcrições salvas em Download/LegendaJP. */
class RecordingsActivity : Activity() {

    private data class Row(val item: Recordings.Item, val lines: Int, val preview: String, val hasWav: Boolean, val title: String?)

    private lateinit var list: ListView
    private lateinit var empty: TextView
    private var rows: List<Row> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recordings)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.recordings)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            val r = rows[pos]
            startActivity(
                Intent(this, ViewerActivity::class.java)
                    .putExtra(ViewerActivity.EXTRA_URI, r.item.uri)
                    .putExtra(ViewerActivity.EXTRA_NAME, r.item.name)
            )
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val row = rows[pos]
            AlertDialog.Builder(this).setTitle(row.title ?: Recordings.prettyName(row.item.name))
                .setItems(arrayOf("Renomear", "Apagar")) { _, which ->
                    if (which == 0) Recordings.renameDialog(this, row.item.name) { load() } else confirmDelete(row)
                }
                .show()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun load() {
        Thread {
            val wavs = Recordings.list(this, ".wav").map { Recordings.baseName(it.name) }.toSet()
            val result = Recordings.list(this, ".txt").map { item ->
                val text = try { Recordings.readText(this, item.uri) } catch (_: Exception) { "" }
                val lines = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }
                val preview = lines.take(2).joinToString(" ") { Recordings.parseLine(it).second }
                Row(item, lines.size, preview, Recordings.baseName(item.name) in wavs, Recordings.title(this, item.name))
            }
            runOnUiThread {
                rows = result
                adapter.notifyDataSetChanged()
                empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            }
        }.start()
    }

    private fun confirmDelete(row: Row) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_confirm) + "\n" + (row.title?.let { "$it · " } ?: "") + Recordings.prettyName(row.item.name))
            .setPositiveButton(R.string.menu_delete) { _, _ ->
                Recordings.delete(this, row.item.uri)
                Recordings.find(this, Recordings.baseName(row.item.name) + ".wav")?.let { Recordings.delete(this, it.uri) }
                load()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_recording, parent, false)
            val r = rows[position]
            v.findViewById<TextView>(R.id.title).text = r.title ?: Recordings.prettyName(r.item.name)
            val parts = mutableListOf("${r.lines} frases", Recordings.prettySize(r.item.size))
            if (r.title != null) parts.add(0, Recordings.prettyName(r.item.name))
            if (r.hasWav) parts.add("com áudio")
            v.findViewById<TextView>(R.id.subtitle).text = parts.joinToString(" · ")
            v.findViewById<TextView>(R.id.preview).text = r.preview
            return v
        }
    }
}
