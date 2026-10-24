package com.keveny.legendajp

import android.app.Activity
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView

/** Itens de uma sessão de estudo: o que foi respondido, o que estava certo. */
class StudyHistoryActivity : Activity() {

    companion object { const val EXTRA_ID = "id" }

    private var items: List<VocabDb.StudyItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recordings)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Sessão"
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        val list = findViewById<ListView>(R.id.list)
        list.adapter = adapter
        Thread {
            val its = VocabDb.get(this).studyItems(id)
            runOnUiThread {
                items = its
                val right = its.count { it.correct }
                title = "Sessão · $right/${its.size} certas"
                adapter.notifyDataSetChanged()
                findViewById<TextView>(R.id.empty).visibility = if (its.isEmpty()) View.VISIBLE else View.GONE
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
            val it = items[position]
            val ok = getColor(R.color.ok)
            val bad = getColor(R.color.stop)
            v.findViewById<TextView>(R.id.title).text = (if (it.correct) "✓ " else "✗ ") + it.kind
            v.findViewById<TextView>(R.id.title).setTextColor(if (it.correct) ok else bad)
            v.findViewById<TextView>(R.id.subtitle).text = it.prompt
            val sb = SpannableStringBuilder()
            if (!it.correct) {
                val a = sb.length; sb.append("Sua resposta: ").append(it.given)
                sb.setSpan(ForegroundColorSpan(bad), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append("\n")
            }
            val b = sb.length; sb.append("Certa: ").append(it.answer)
            sb.setSpan(ForegroundColorSpan(ok), b, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (it.sentence.isNotEmpty() && it.sentence != it.answer) sb.append("\n").append(it.sentence)
            val pv = v.findViewById<TextView>(R.id.preview)
            pv.maxLines = 6
            pv.text = sb
            return v
        }
    }
}
