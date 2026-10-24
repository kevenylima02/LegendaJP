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
import android.widget.Button
import android.widget.ListView
import android.widget.TextView

/** Fila de erros acumulados: revisar em blocos de 10; acertou, sai da fila. */
class MistakesActivity : Activity() {

    private var items: List<VocabDb.Mistake> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mistakes)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Erros"
        val list = findViewById<ListView>(R.id.list)
        list.adapter = adapter
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val m = items[pos]
            AlertDialog.Builder(this).setMessage("Remover este item da fila de erros?")
                .setPositiveButton("Remover") { _, _ -> VocabDb.get(this).removeMistake(m.identity); load() }
                .setNegativeButton(android.R.string.cancel, null).show()
            true
        }
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            startActivity(Intent(this, StudyActivity::class.java).putExtra(StudyActivity.EXTRA_MISTAKES, true))
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        Thread {
            val m = VocabDb.get(this).mistakes()
            runOnUiThread {
                items = m
                adapter.notifyDataSetChanged()
                val n = m.size
                findViewById<TextView>(R.id.summary).text = when (n) {
                    0 -> "Nenhum erro acumulado."
                    1 -> "1 erro acumulado."
                    else -> "$n erros acumulados."
                }
                val b = findViewById<Button>(R.id.btnStart)
                b.isEnabled = n > 0
                b.text = "Revisar ${minOf(10, n).coerceAtLeast(1)} " + if (minOf(10, n) == 1) "erro" else "erros"
                findViewById<TextView>(R.id.empty).visibility = if (n == 0) View.VISIBLE else View.GONE
            }
        }.start()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_recording, parent, false)
            val m = items[position]
            v.findViewById<TextView>(R.id.title).text = m.kind
            v.findViewById<TextView>(R.id.subtitle).text = m.prompt
            val pv = v.findViewById<TextView>(R.id.preview)
            pv.maxLines = 3
            pv.text = "Certa: ${m.answer}\n${m.sentence}"
            return v
        }
    }
}
