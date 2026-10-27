package com.keveny.legendajp

/**
 * Dificuldade de uma transcrição para você: quantas palavras (contando repetições) você já conhece,
 * quantas está estudando e quantas são novas; o nível JLPT estimado do texto (o nível até o qual
 * vão 90% das palavras, pelo kanji mais difícil de cada uma) e os padrões de gramática por nível.
 */
object TextLevel {

    data class Result(
        val tokens: Int, val known: Int, val studying: Int,
        val distinct: Int, val knownDistinct: Int, val studyingDistinct: Int,
        val estLevel: Int, val grammar: Map<String, Int>
    ) {
        val coverage: Double get() = if (tokens == 0) 0.0 else (known + studying).toDouble() / tokens

        /** Para você, pela parte do texto que já conhece ou está estudando. */
        val label: String get() = when {
            tokens == 0 -> "sem palavras"
            coverage >= 0.95 -> "fácil"
            coverage >= 0.85 -> "confortável"
            coverage >= 0.70 -> "desafiador"
            else -> "difícil"
        }
    }

    private val content = setOf("名詞", "動詞", "形容詞", "副詞")

    /** Chame fora da UI (analisa todas as frases). */
    fun analyze(texts: List<String>): Result {
        val known = VocabDb.knownKeys
        val studying = VocabDb.studyKeys
        var tokens = 0; var k = 0; var s = 0
        val distinct = HashSet<String>()
        val perLevel = IntArray(6)
        val points = HashMap<String, String>() // id → nível
        for (t in texts) {
            for (w in (try { Jp.wordsAll(t) + Jp.expressions(t) } catch (_: Exception) { emptyList() })) {
                if (w.posKey !in content || w.base.all { it.isDigit() }) continue
                tokens++
                distinct.add(w.key)
                when (w.key) { in known -> k++; in studying -> s++ }
                perLevel[Jlpt.wordLevel(w.base)]++
            }
            for (h in (try { Grammar.find(t) } catch (_: Exception) { emptyList() })) points[h.point.id] = h.point.level
        }
        var est = 5
        var cum = 0
        for (lvl in 5 downTo 1) {
            cum += perLevel[lvl]
            est = lvl
            if (tokens > 0 && cum >= tokens * 0.9) break
        }
        val grammar = points.values.groupingBy { it }.eachCount()
        return Result(tokens, k, s, distinct.size, distinct.count { it in known }, distinct.count { it in studying }, est, grammar)
    }
}
