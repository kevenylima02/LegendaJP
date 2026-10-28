package com.keveny.legendajp

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan

/**
 * Marcação mínima para explicações: linha "## Título" vira negrito (com espaço antes); as outras
 * linhas ficam na cor secundária. Linhas em branco são ignoradas (o espaço vem dos títulos).
 */
object Markup {
    private const val F = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

    fun append(sb: SpannableStringBuilder, text: String, normal: Int, secondary: Int) {
        var first = true
        for (raw in text.split("\n")) {
            if (raw.isBlank()) continue
            if (raw.startsWith("## ")) {
                if (!first) sb.append("\n")
                sb.append("\n")
                val a = sb.length; sb.append(raw.removePrefix("## "))
                sb.setSpan(StyleSpan(Typeface.BOLD), a, sb.length, F)
                sb.setSpan(ForegroundColorSpan(normal), a, sb.length, F)
            } else {
                if (!first) sb.append("\n")
                val a = sb.length; sb.append(raw)
                sb.setSpan(ForegroundColorSpan(secondary), a, sb.length, F)
            }
            first = false
        }
    }
}
