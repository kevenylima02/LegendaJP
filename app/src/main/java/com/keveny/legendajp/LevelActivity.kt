package com.keveny.legendajp

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.MenuItem
import android.widget.ScrollView
import android.widget.TextView

/** O que o catálogo de gramática cobre em cada nível. */
class LevelActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Gramática por nível"
        val mine = Prefs(this).levels
        val sb = SpannableStringBuilder()
        val byLevel = Grammar.all.groupBy { it.level }
        for (lvl in listOf("N5", "N4", "N3", "N2", "N1")) {
            val points = byLevel[lvl] ?: continue
            val a = sb.length
            sb.append(lvl).append(if (lvl.removePrefix("N").toInt() in mine) "  (marcado)" else "").append("  ·  ${points.size} padrões\n")
            sb.setSpan(StyleSpan(Typeface.BOLD), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(1.25f), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(getColor(R.color.accent)), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            for (p in points) {
                val b = sb.length
                sb.append(p.name)
                sb.setSpan(StyleSpan(Typeface.BOLD), b, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append(" — ").append(p.explanation).append("\n")
            }
            sb.append("\n")
        }
        sb.append("Os níveis N2 e N1 ainda não têm padrões no catálogo; os exercícios de gramática usam o que há de mais avançado.")
        val tv = TextView(this).apply {
            text = sb
            textSize = 15f
            setLineSpacing(0f, 1.2f)
            setTextColor(getColor(R.color.text))
            setPadding(dp(20), dp(16), dp(20), dp(24))
            setTextIsSelectable(true)
        }
        setContentView(ScrollView(this).apply { addView(tv) })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
