package com.keveny.legendajp

import android.content.Context

/** Gera os exercícios de uma gravação por foco (gramática, vocabulário, kanji ou misto). */
object StudyGen {

    enum class Focus(val label: String) { MISTO("Misto"), GRAMATICA("Gramática"), VOCAB("Vocabulário"), KANJI("Kanji") }

    /** Uma frase da gravação com início e fim (em segundos) no áudio. */
    data class Line(val sec: Int, val text: String, val endSec: Int)

    sealed class Item(val focus: Focus, val kind: String, val sentence: String, val sec: Int, val endSec: Int) {
        class Order(s: Line, val chunks: List<String>) :
            Item(Focus.GRAMATICA, "Ordenar a frase", s.text, s.sec, s.endSec)
        class GrammarQ(s: Line, val hit: Grammar.Hit, val options: List<Grammar.Point>) :
            Item(Focus.GRAMATICA, "Identificar o padrão", s.text, s.sec, s.endSec)
        class GrammarCloze(s: Line, val hit: Grammar.Hit, val options: List<String>) :
            Item(Focus.GRAMATICA, "Lacuna gramatical", s.text, s.sec, s.endSec)
        class Cloze(s: Line, val word: Jp.Word, val options: List<String>) :
            Item(Focus.VOCAB, "Lacuna com áudio", s.text, s.sec, s.endSec)
        class Choice(f: Focus, kind: String, s: Line, val prompt: String, val answer: String, val options: List<String>, val note: String) :
            Item(f, kind, s.text, s.sec, s.endSec)
        class Kanji(s: Line, val word: Jp.Word, val tiles: List<String>) :
            Item(Focus.KANJI, "Montar a palavra com kanji", s.text, s.sec, s.endSec)
    }

    /** Tipos de exercício por foco (para a tela do menu). */
    val kinds: Map<Focus, List<String>> = mapOf(
        Focus.GRAMATICA to listOf("Identificar o padrão", "Lacuna gramatical", "Ordenar a frase"),
        Focus.VOCAB to listOf("Lacuna com áudio", "Tradução → palavra", "Palavra → tradução"),
        Focus.KANJI to listOf("Montar a palavra com kanji", "Palavra em kanji → leitura", "Leitura → palavra em kanji"),
    )

    fun isKanji(c: Char) = c in '一'..'鿿'

    private fun levelNum(level: String) = level.removePrefix("N").toIntOrNull() ?: 5

    /** Quantos exercícios cada foco consegue gerar desta gravação. */
    fun counts(ctx: Context, lines: List<Line>, level: Int): Map<Focus, Int> {
        val all = generateAll(ctx, lines, level, kotlin.random.Random(1))
        return Focus.values().associateWith { f ->
            if (f == Focus.MISTO) all.size else all.count { it.focus == f }
        }
    }

    fun build(ctx: Context, lines: List<Line>, focus: Focus, level: Int, max: Int): List<Item> {
        val rnd = kotlin.random.Random(System.nanoTime())
        val all = generateAll(ctx, lines, level, rnd)
        val pool = if (focus == Focus.MISTO) all else all.filter { it.focus == focus }
        // Mistura mantendo variedade de tipos: intercala por tipo
        val byKind = pool.groupBy { it.kind }.values.map { it.shuffled(rnd).toMutableList() }.shuffled(rnd)
        val out = mutableListOf<Item>()
        while (out.size < max && byKind.any { it.isNotEmpty() }) {
            for (q in byKind) if (q.isNotEmpty() && out.size < max) out.add(q.removeAt(0))
        }
        return out
    }

    private fun generateAll(ctx: Context, lines: List<Line>, level: Int, rnd: kotlin.random.Random): List<Item> {
        val sentences = lines.filter { it.text.length >= 4 }
        if (sentences.isEmpty()) return emptyList()
        val analyzed = sentences.map { it to (try { Jp.words(it.text) } catch (_: Exception) { emptyList() }) }
        val allWords = analyzed.flatMap { it.second }
            .filter { it.posKey in setOf("名詞", "動詞", "形容詞", "副詞") }
            .distinctBy { it.key }
        val kanjiPool = allWords.flatMap { w -> w.base.filter(::isKanji).toList() }.distinct()
        val saved = VocabDb.savedKeys
        val hitsBySentence = sentences.map { it to (try { Grammar.find(it.text) } catch (_: Exception) { emptyList() }) }
        val allHits = hitsBySentence.flatMap { it.second }
        val out = mutableListOf<Item>()

        for ((line, ws) in analyzed) {
            val s = line.text

            // ----- Gramática -----
            val chunks = try { Jp.chunks(s) } catch (_: Exception) { emptyList() }
            if (chunks.size in 4..9) out.add(Item.Order(line, chunks))

            val hits = hitsBySentence.first { it.first === line }.second
            if (hits.isNotEmpty()) {
                // Preferência pelo nível do usuário: pontos do nível (ou acima) primeiro
                val preferred = hits.filter { levelNum(it.point.level) <= level }
                val chosen = (preferred.ifEmpty { hits }).random(rnd)
                val present = hits.map { it.point.id }.toSet()
                val wrong = Grammar.all.filter { it.id !in present }.shuffled(rnd).take(3)
                out.add(Item.GrammarQ(line, chosen, (wrong + chosen.point).shuffled(rnd)))

                // Lacuna gramatical: apaga o trecho do padrão; alternativas de outros padrões da gravação
                val span = s.substring(chosen.start, chosen.end)
                val others = allHits.filter { it.point.id != chosen.point.id }
                    .map { h -> h.let { hitsBySentence.first { p -> p.second.contains(it) }.first.text.substring(it.start, it.end) } }
                    .filter { it != span && it.length in 1..12 }.distinct().shuffled(rnd).take(3)
                if (others.size == 3 && span.length in 1..12) {
                    out.add(Item.GrammarCloze(line, chosen, (others + span).shuffled(rnd)))
                }
            }

            // ----- Vocabulário -----
            val candidates = ws.filter { it.posKey in setOf("名詞", "動詞", "形容詞") && it.surface.length >= 2 }
            val target = candidates.firstOrNull { it.key in saved } ?: candidates.randomOrNull(rnd)
            if (target != null) {
                val same = allWords.filter { it.key != target.key && it.posKey == target.posKey }.shuffled(rnd).map { it.surface }
                val fallback = allWords.filter { it.key != target.key }.shuffled(rnd).map { it.surface }
                val opts = (same + fallback).filter { it != target.surface }.distinct().take(3)
                if (opts.size == 3) out.add(Item.Cloze(line, target, (opts + target.surface).shuffled(rnd)))
            }
            val translated = ws.filter { it.posKey in setOf("名詞", "動詞", "形容詞") }
                .mapNotNull { w -> Tr.cached(ctx, w.base)?.let { w to it } }
            val tw = translated.randomOrNull(rnd)
            if (tw != null) {
                val (w, tr) = tw
                val others = allWords.filter { it.key != w.key }.shuffled(rnd).take(3)
                if (others.size == 3) {
                    out.add(Item.Choice(Focus.VOCAB, "Tradução → palavra", line, "Qual palavra significa \"$tr\"?", w.base,
                        (others.map { it.base } + w.base).shuffled(rnd), "${w.base} (${w.reading}) · ${w.pos}"))
                    val otherTr = allWords.filter { it.key != w.key }.mapNotNull { Tr.cached(ctx, it.base) }
                        .filter { it != tr }.distinct().shuffled(rnd).take(3)
                    if (otherTr.size == 3) {
                        out.add(Item.Choice(Focus.VOCAB, "Palavra → tradução", line, "O que significa 「${w.base}」?", tr,
                            (otherTr + tr).shuffled(rnd), "${w.base} (${w.reading}) · ${w.pos}"))
                    }
                }
            }

            // ----- Kanji -----
            val kw = ws.filter { w -> w.base.count(::isKanji) >= 2 && w.base.all { isKanji(it) || it in '぀'..'ヿ' } }
                .randomOrNull(rnd)
            if (kw != null) {
                val need = kw.base.filter(::isKanji).toList()
                val extra = kanjiPool.filter { it !in need }.shuffled(rnd).take(maxOf(0, 8 - need.size))
                if (need.size + extra.size >= 4) out.add(Item.Kanji(line, kw, (need + extra).map { it.toString() }.shuffled(rnd)))
            }
            val cw = ws.filter { it.posKey in setOf("名詞", "動詞", "形容詞") && it.reading != it.base && it.base.any(::isKanji) }
                .randomOrNull(rnd)
            if (cw != null) {
                val others = allWords.filter { it.key != cw.key && it.reading != it.base && it.base.any(::isKanji) }.shuffled(rnd).take(3)
                if (others.size == 3) {
                    out.add(Item.Choice(Focus.KANJI, "Palavra em kanji → leitura", line, "Como se lê 「${cw.base}」?", cw.reading,
                        (others.map { it.reading } + cw.reading).distinct().shuffled(rnd), "${cw.base}: ${cw.pos}"))
                    out.add(Item.Choice(Focus.KANJI, "Leitura → palavra em kanji", line, "Qual palavra se lê 「${cw.reading}」?", cw.base,
                        (others.map { it.base } + cw.base).distinct().shuffled(rnd), "${cw.base}: ${cw.pos}"))
                }
            }
        }
        return out.filter { it !is Item.Choice || it.options.size == 4 }
    }

    /** Lê o .txt da gravação e monta as linhas com fim estimado (início da próxima frase). */
    fun lines(text: String): List<Line> {
        val parsed = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { Recordings.parseLine(it) }
        return parsed.mapIndexed { i, (sec, t) ->
            val next = parsed.getOrNull(i + 1)?.first ?: -1
            val end = if (next > sec) next else sec + 6
            Line(sec, t, end)
        }
    }
}
