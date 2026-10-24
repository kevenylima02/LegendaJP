package com.keveny.legendajp

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/** Uma palavra salva e as frases onde apareceu. */
class VocabDetailActivity : Activity() {

    companion object { const val EXTRA_ID = "id" }

    private var wordId = -1L
    private var word: VocabDb.Word? = null
    private var occ: List<VocabDb.Occurrence> = emptyList()
    private lateinit var list: ListView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vocab_detail)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        wordId = intent.getLongExtra(EXTRA_ID, -1L)
        list = findViewById(R.id.occ)
        list.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        Thread {
            val db = VocabDb.get(this)
            val w = db.word(wordId)
            val o = if (w != null) db.occurrences(wordId) else emptyList()
            runOnUiThread {
                if (w == null) { finish(); return@runOnUiThread }
                word = w
                occ = o
                title = w.base
                findViewById<TextView>(R.id.base).text = w.base
                findViewById<TextView>(R.id.reading).text = if (w.reading != w.base) w.reading else ""
                findViewById<TextView>(R.id.meta).text = w.pos
                adapter.notifyDataSetChanged()
            }
        }.start()
    }

    private fun play(o: VocabDb.Occurrence) {
        Thread {
            val item = Recordings.find(this, o.recording + ".txt")
            runOnUiThread {
                if (item == null) {
                    Toast.makeText(this, "A gravação dessa frase não está mais na pasta", Toast.LENGTH_SHORT).show()
                } else {
                    startActivity(
                        Intent(this, ViewerActivity::class.java)
                            .putExtra(ViewerActivity.EXTRA_URI, item.uri)
                            .putExtra(ViewerActivity.EXTRA_NAME, item.name)
                            .putExtra(ViewerActivity.EXTRA_SEEK, o.sec)
                    )
                }
            }
        }.start()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_vocab_detail, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            R.id.action_copy -> {
                val w = word ?: return true
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Michi", w.base))
                Toast.makeText(this, "${w.base} copiada", Toast.LENGTH_SHORT).show()
            }
            R.id.action_delete -> {
                AlertDialog.Builder(this)
                    .setMessage("Remover ${word?.base} do vocabulário?")
                    .setPositiveButton("Remover") { _, _ ->
                        VocabDb.get(this).removeById(wordId)
                        finish()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = occ.size
        override fun getItem(position: Int) = occ[position]
        override fun getItemId(position: Int) = occ[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_occurrence, parent, false)
            val o = occ[position]
            v.findViewById<TextView>(R.id.sentence).text =
                Jp.highlight(o.sentence, VocabDb.savedKeys, getColor(R.color.saved_word))
            v.findViewById<TextView>(R.id.meta).text =
                "${Recordings.prettyName(o.recording)} · ${CaptureService.formatTime(o.sec)}"
            v.findViewById<TextView>(R.id.play).setOnClickListener { play(o) }
            val trView = v.findViewById<TextView>(R.id.translation)
            val trBtn = v.findViewById<TextView>(R.id.translate)
            val cached = Tr.cached(this@VocabDetailActivity, o.sentence)
            if (cached != null) {
                trView.text = cached; trView.visibility = View.VISIBLE; trBtn.visibility = View.GONE
            } else {
                trView.visibility = View.GONE; trBtn.visibility = View.VISIBLE
                trBtn.setOnClickListener {
                    trBtn.text = "Traduzindo…"
                    Tr.translate(this@VocabDetailActivity, o.sentence) { t ->
                        if (t != null) {
                            trView.text = t; trView.visibility = View.VISIBLE; trBtn.visibility = View.GONE
                        } else {
                            trBtn.text = "Traduzir"
                            Toast.makeText(this@VocabDetailActivity, "Não consegui traduzir agora", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            return v
        }
    }
}
