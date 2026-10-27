package com.keveny.legendajp

import android.graphics.Canvas
import android.graphics.Paint
import android.text.style.ReplacementSpan

/**
 * Furigana: a leitura em hiragana, pequena, por cima dos kanji (só na parte em kanji: 食べる → た
 * sobre 食). O espaço vem do espaçamento entre linhas do TextView (Reader); ligar/desligar no leitor.
 */
object Furigana {

    /** Trecho [start, end) da frase com kanji e a leitura dele. */
    data class Ruby(val start: Int, val end: Int, val reading: String)

    private fun isKana(c: Char) = c in '぀'..'ヿ'

    /** Leituras dos trechos em kanji da frase (Kuromoji), sem o okurigana. */
    fun of(sentence: String): List<Ruby> {
        val tokens = try { Jp.tokenize(sentence) } catch (_: Exception) { return emptyList() }
        val out = mutableListOf<Ruby>()
        for (t in tokens) {
            val surf = t.surface
            if (surf.none(StudyGen::isKanji)) continue
            val reading = t.reading?.takeIf { it != "*" }?.let { Jp.hiragana(it) } ?: continue
            val surfH = Jp.hiragana(surf)
            // Tira o kana igual no começo e no fim (食べる/たべる → 食/た)
            var a = 0
            while (a < surf.length && a < reading.length && isKana(surf[a]) && surfH[a] == reading[a]) a++
            var b = 0
            while (b < surf.length - a && b < reading.length - a && isKana(surf[surf.length - 1 - b]) &&
                surfH[surf.length - 1 - b] == reading[reading.length - 1 - b]) b++
            val core = surf.substring(a, surf.length - b)
            val r = reading.substring(a, reading.length - b)
            if (core.isEmpty() || r.isEmpty() || core == r) continue
            out.add(Ruby(t.position + a, t.position + surf.length - b, r))
        }
        return out
    }

    /**
     * Desenha o texto do trecho e a leitura em cima, centralizados. scale: tamanho da leitura em
     * relação ao texto; gap: distância (px) acima do topo das letras.
     */
    class Span(private val ruby: String, private val scale: Float = 0.5f) : ReplacementSpan() {
        private fun rubyPaint(p: Paint) = Paint(p).apply { textSize = p.textSize * scale; isFakeBoldText = false }

        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            if (fm != null) paint.getFontMetricsInt(fm)
            return maxOf(paint.measureText(text, start, end), rubyPaint(paint).measureText(ruby)).toInt() + 1
        }

        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val baseW = paint.measureText(text, start, end)
            val rp = rubyPaint(paint)
            val rubyW = rp.measureText(ruby)
            val w = maxOf(baseW, rubyW)
            canvas.drawText(text, start, end, x + (w - baseW) / 2, y.toFloat(), paint)
            canvas.drawText(ruby, x + (w - rubyW) / 2, y + paint.ascent() - rp.descent() * 0.6f, rp)
        }
    }
}
