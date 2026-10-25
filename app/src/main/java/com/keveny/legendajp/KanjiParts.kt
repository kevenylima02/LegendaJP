package com.keveny.legendajp

import android.content.Context
import android.graphics.Path

/**
 * Partes do 1º nível de cada kanji (KanjiVG, CC BY-SA 3.0; assets/parts.txt):
 * 雪 = 雨 (em cima, traços 1–8) + ⺕ (embaixo, traços 9–11).
 * Linha: caractere TAB elemento:posição:traços;… (traços contados a partir de 1, em faixas "1-8" ou "1-2,5").
 */
object KanjiParts {

    /** Uma parte desenhada a partir dos traços do kanji de origem (src). strokes: índices a partir de 0. */
    data class Part(val element: String, val pos: String, val src: Char, val strokes: List<Int>) {
        fun encode() = "$element|$pos|$src|" + strokes.joinToString(",")
    }

    data class Entry(val char: Char, val parts: List<Part>)

    @Volatile private var map: Map<Char, Entry>? = null

    private fun all(ctx: Context): Map<Char, Entry> {
        map?.let { return it }
        synchronized(this) {
            map?.let { return it }
            val m = HashMap<Char, Entry>(5500)
            try {
                ctx.assets.open("parts.txt").bufferedReader(Charsets.UTF_8).useLines { seq ->
                    for (line in seq) {
                        val tab = line.indexOf('\t')
                        if (tab != 1) continue
                        val c = line[0]
                        val parts = line.substring(2).split(';').mapNotNull { p ->
                            val f = p.split(':')
                            if (f.size != 3) null else Part(f[0], f[1], c, strokeList(f[2]))
                        }
                        if (parts.size >= 2) m[c] = Entry(c, parts)
                    }
                }
            } catch (_: Exception) {}
            map = m
            return m
        }
    }

    private fun strokeList(s: String): List<Int> = s.split(',').flatMap { r ->
        val d = r.indexOf('-')
        if (d < 0) listOf(r.toInt() - 1) else (r.substring(0, d).toInt() - 1 until r.substring(d + 1).toInt())
    }

    fun get(ctx: Context, c: Char): Entry? = all(ctx)[c]

    fun decode(s: String): Part? = try {
        val f = s.split('|')
        Part(f[0], f[1], f[2][0], if (f[3].isEmpty()) emptyList() else f[3].split(',').map { it.toInt() })
    } catch (_: Exception) { null }

    /** Traços da parte, já como Path (no espaço 109×109 do kanji de origem). Lê o asset; chame fora da UI. */
    fun paths(ctx: Context, p: Part): List<Path> {
        val all = Strokes.get(ctx, p.src) ?: return emptyList()
        return p.strokes.mapNotNull { all.getOrNull(it) }
    }

    @Volatile private var common: List<Part>? = null

    /** Partes dos kanji do JLPT (N5–N2), uma por elemento e posição. */
    private fun commonParts(m: Map<Char, Entry>): List<Part> = common ?: m.values
        .filter { Jlpt.kanjiLevel(it.char) >= 2 }.flatMap { it.parts }
        .distinctBy { it.element + "|" + it.pos }.also { common = it }

    /**
     * Partes de distração: na mesma posição de alguma parte certa (em cima, à esquerda…), com outro
     * elemento. Prefere partes dos kanji próximos (os da gravação), depois kanji do JLPT.
     */
    fun distractors(ctx: Context, entry: Entry, n: Int, near: List<Char>, rnd: kotlin.random.Random): List<Part> {
        val m = all(ctx)
        val taken = entry.parts.map { it.element }.toMutableSet()
        val positions = entry.parts.map { it.pos }.toSet()
        val nearParts = near.filter { it != entry.char }.mapNotNull { m[it] }
            .flatMap { it.parts }.filter { it.pos in positions }.shuffled(rnd)
        val common = commonParts(m).filter { it.src != entry.char && it.pos in positions }.shuffled(rnd)
        val out = mutableListOf<Part>()
        // Uma de cada posição primeiro, para todas as lacunas terem concorrentes
        for (pos in positions) {
            (nearParts + common).firstOrNull { it.pos == pos && it.element !in taken }?.let { out.add(it); taken.add(it.element) }
        }
        for (p in nearParts + common) {
            if (out.size >= n) break
            if (p.element !in taken) { out.add(p); taken.add(p.element) }
        }
        return out.take(n)
    }
}
