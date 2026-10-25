package com.keveny.legendajp

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.widget.TextView

/**
 * Tradução de uma frase com as duas fontes guardadas (ML Kit e Gemini). Mostra a preferida
 * (Configurações; Gemini quando houver) com a etiqueta da fonte; tocar alterna entre as duas.
 * Nada é substituído: as duas ficam salvas.
 */
object TrSwitch {
    private const val ML = "ML Kit"
    private const val AI = "Gemini"

    /**
     * @param prefix o que vem antes da tradução no mesmo TextView (ex.: "Frase" e a frase).
     * Lê o banco; se ainda não houver tradução do ML Kit, pede e redesenha quando chegar.
     */
    fun bind(
        ctx: Context, tv: TextView, sentence: String, prefix: CharSequence = "",
        color: Int = ctx.getColor(R.color.text_secondary), scale: Float = 1f
    ) {
        tv.setTag(R.id.trText, sentence)
        if (sentence.isBlank()) { tv.text = prefix; return }
        val ml = Tr.cached(ctx, sentence)
        val ai = Tr.aiCached(ctx, sentence)
        // Fonte escolhida pelo toque (vale só para esta frase); sem toque, a preferida
        val chosen = (tv.getTag(R.id.trSource) as? Pair<*, *>)?.takeIf { it.first == sentence }?.second as? String
        val src = when {
            chosen == AI && ai != null -> AI
            chosen == ML && ml != null -> ML
            ai != null && (Prefs(ctx).trPreferGemini || ml == null) -> AI
            else -> ML
        }
        val text = if (src == AI) ai else ml
        val sb = SpannableStringBuilder(prefix)
        val a = sb.length
        sb.append(text ?: "traduzindo…")
        sb.setSpan(ForegroundColorSpan(color), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (scale != 1f) sb.setSpan(RelativeSizeSpan(scale), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (text != null) {
            val b = sb.length
            sb.append("  · ").append(src).append(if (ml != null && ai != null) " ⇄" else "")
            sb.setSpan(ForegroundColorSpan(ctx.getColor(R.color.accent)), b, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(0.8f * scale), b, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        tv.text = sb
        if (ml != null && ai != null) {
            tv.setOnClickListener {
                tv.setTag(R.id.trSource, sentence to (if (src == AI) ML else AI))
                bind(ctx, tv, sentence, prefix, color, scale)
            }
        } else tv.setOnClickListener(null)
        if (ml == null) {
            Tr.translate(ctx, sentence) { t ->
                if (t != null && tv.getTag(R.id.trText) == sentence) bind(ctx, tv, sentence, prefix, color, scale)
            }
        }
    }
}
