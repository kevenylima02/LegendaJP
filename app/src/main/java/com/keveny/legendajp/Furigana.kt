package com.keveny.legendajp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.widget.TextView

/**
 * Furigana: a leitura em hiragana, pequena, por cima dos kanji (só na parte em kanji: 食べる → た
 * sobre 食). Configurações → Furigana: todos os kanji, só os que você não domina, ou nenhum.
 * "Não domino" = a palavra não está marcada "Já conheço" nem tem o cartão de kanji dominado na
 * revisão espaçada. O espaço para a leitura vem do espaçamento entre linhas (style()).
 */
object Furigana {
    const val ALL = "all"
    const val UNKNOWN = "unknown"
    const val NONE = "none"

    /** Trecho [start, end) da frase com kanji e a leitura dele. */
    data class Ruby(val start: Int, val end: Int, val reading: String)

    private fun isKana(c: Char) = c in '぀'..'ヿ'

    private val cache = android.util.LruCache<String, List<Ruby>>(400)

    /** Leituras dos trechos em kanji da frase (Kuromoji), sem o okurigana. */
    fun of(sentence: String): List<Ruby> {
        cache.get(sentence)?.let { return it }
        return compute(sentence).also { cache.put(sentence, it) }
    }

    private fun compute(sentence: String): List<Ruby> {
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

    fun mode(ctx: Context): String = Prefs(ctx).furiganaMode

    fun enabled(ctx: Context) = mode(ctx) != NONE

    /**
     * Põe o furigana de `sentence` em `sb` (a frase começa em `offset`), conforme a configuração.
     * skip: trecho da frase que não pode ganhar leitura (a palavra de um exercício de kanji).
     * Devolve true se colocou alguma leitura (para ajustar o espaçamento com style()).
     */
    fun decorate(ctx: Context, sb: SpannableStringBuilder, offset: Int, sentence: String, skip: IntRange? = null, force: Boolean = false): Boolean {
        val mode = if (force) ALL else mode(ctx)
        if (mode == NONE) return false
        val rubies = of(sentence)
        if (rubies.isEmpty()) return false
        val hidden: List<IntRange> = if (mode == UNKNOWN) {
            val mastered = Srs.masteredKanjiKeys(ctx)
            val known = VocabDb.knownKeys
            (try { Jp.words(sentence) } catch (_: Exception) { emptyList() })
                .filter { Jp.keyIn(it, known) != null || Jp.keyIn(it, mastered) != null }.map { it.start until it.end }
        } else emptyList()
        var any = false
        for (r in rubies) {
            val range = r.start until r.end
            if (skip != null && range.first <= skip.last && skip.first <= range.last) continue
            if (hidden.any { range.first <= it.last && it.first <= range.last }) continue
            sb.setSpan(Span(r.reading), offset + r.start, offset + r.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            any = true
        }
        return any
    }

    /** Atalho: a frase inteira já decorada (palavras tocáveis vêm de fora). */
    fun apply(ctx: Context, text: CharSequence, sentence: String, skip: IntRange? = null): Pair<CharSequence, Boolean> {
        val sb = SpannableStringBuilder(text)
        val on = decorate(ctx, sb, 0, sentence, skip)
        return sb to on
    }

    /**
     * Espaço para a leitura: aumenta o espaçamento entre linhas e o topo quando há furigana e volta
     * ao original quando não há (guarda os valores originais na própria view).
     */
    fun style(tv: TextView, on: Boolean) {
        val base = (tv.getTag(R.id.furiBase) as? FloatArray)
            ?: floatArrayOf(tv.lineSpacingExtra, tv.paddingTop.toFloat()).also { tv.setTag(R.id.furiBase, it) }
        val px = tv.textSize
        tv.setLineSpacing(if (on) maxOf(base[0], px * 0.62f) else base[0], 1f)
        tv.setPadding(tv.paddingLeft, if (on) (base[1] + px * 0.55f).toInt() else base[1].toInt(), tv.paddingRight, tv.paddingBottom)
    }

    /** Decora e ajusta o espaçamento de uma vez (frase única numa TextView). */
    fun set(ctx: Context, tv: TextView, text: CharSequence, sentence: String, skip: IntRange? = null) {
        val (t, on) = apply(ctx, text, sentence, skip)
        tv.text = t
        style(tv, on)
    }

    /**
     * Desenha o texto do trecho e a leitura em cima, centralizados. scale: tamanho da leitura em
     * relação ao texto.
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
