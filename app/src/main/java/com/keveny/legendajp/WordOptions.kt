package com.keveny.legendajp

import android.content.Context

/**
 * Alternativas erradas parecidas para os exercícios de vocabulário: primeiro palavras que dividem
 * um kanji com a resposta (医者 → 歯医者, 医学, 患者), depois leitura parecida e mesma classe, e só
 * no fim palavras quaisquer. O conjunto vem das palavras do texto, do vocabulário salvo e das
 * palavras curtas já traduzidas (todas reais, já vistas pelo usuário).
 */
object WordOptions {

    class Cand(val base: String, val reading: String, val posKey: String)

    fun pool(ctx: Context, textWords: List<Jp.Word>): List<Cand> {
        val out = LinkedHashMap<String, Cand>()
        for (w in textWords) out.putIfAbsent(w.base, Cand(w.base, w.reading, w.posKey))
        val db = VocabDb.get(ctx)
        for (w in (try { db.words() } catch (_: Exception) { emptyList() })) {
            if (w.posKey == VocabDb.EXPR || w.posKey == VocabDb.AI) continue
            out.putIfAbsent(w.base, Cand(w.base, w.reading, w.posKey))
        }
        for (t in (try { db.translatedShort() } catch (_: Exception) { emptyList() })) {
            if (t.length > 8 || !Jp.isLexical(t) || t.any { it.isWhitespace() || it in "、。！？「」（）…" }) continue
            out.putIfAbsent(t, Cand(t, "", ""))
        }
        return out.values.filter { Jp.isLexical(it.base) }
    }

    private fun kanjiOf(s: String) = s.filter(StudyGen::isKanji).toSet()

    /** Até n candidatas parecidas com w, da mais parecida para a menos; nunca a própria palavra. */
    fun similar(w: Jp.Word, pool: List<Cand>, rnd: kotlin.random.Random, n: Int = 3): List<Cand> {
        val k = kanjiOf(w.base)
        val others = pool.filter { it.base != w.base && it.base != w.surface }
        val out = LinkedHashSet<Cand>()
        fun add(list: List<Cand>) { for (c in list) { if (out.size >= n) return; if (out.none { it.base == c.base }) out.add(c) } }
        if (k.isNotEmpty()) {
            // Mesmo kanji: mais kanji em comum primeiro; entre iguais, ordem aleatória
            val shared = others.map { it to (kanjiOf(it.base) intersect k).size }.filter { it.second > 0 }
                .shuffled(rnd).sortedByDescending { it.second }.map { it.first }
            add(shared)
        }
        // Leitura parecida (começa igual ou mesmo tamanho) e mesma classe
        val r = w.reading
        add(others.filter { it.reading.isNotEmpty() && it.posKey == w.posKey && (it.reading.length == r.length || (r.isNotEmpty() && it.reading.startsWith(r.take(1)))) }.shuffled(rnd))
        add(others.filter { it.posKey == w.posKey }.shuffled(rnd))
        add(others.shuffled(rnd))
        return out.toList()
    }
}
