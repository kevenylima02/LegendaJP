package com.keveny.legendajp

import android.content.Context

/**
 * Leituras e significados dos kanji (KANJIDIC2, EDRDG, CC BY-SA 4.0; assets/kanjidic.txt).
 * Significados em português quando o KANJIDIC2 tem, senão em inglês.
 */
object Kanjidic {

    data class Info(
        val char: Char, val on: List<String>, val kun: List<String>, val meanings: List<String>,
        val english: Boolean, val strokes: Int, val grade: Int
    ) {
        /** Significado curto para alternativas: até dois sentidos. */
        val shortMeaning: String get() = meanings.take(2).joinToString(", ")

        /** kun formatado: か.わる → か(わる); remove os marcadores de prefixo/sufixo. */
        val kunPretty: List<String> get() = kun.map { k ->
            val s = k.replace("-", "")
            val dot = s.indexOf('.')
            if (dot < 0) s else s.substring(0, dot) + "(" + s.substring(dot + 1) + ")"
        }.distinct()
    }

    @Volatile private var map: Map<Char, Info>? = null

    private fun all(ctx: Context): Map<Char, Info> {
        map?.let { return it }
        synchronized(this) {
            map?.let { return it }
            val m = HashMap<Char, Info>(3200)
            try {
                ctx.assets.open("kanjidic.txt").bufferedReader(Charsets.UTF_8).useLines { seq ->
                    for (line in seq) {
                        val f = line.split('\t')
                        if (f.size < 7 || f[0].isEmpty()) continue
                        fun list(s: String, sep: String) = if (s.isEmpty()) emptyList() else s.split(sep)
                        m[f[0][0]] = Info(
                            f[0][0], list(f[1], "、"), list(f[2], "、"), list(f[3], "; "),
                            f[4] == "en", f[5].toIntOrNull() ?: 0, f[6].toIntOrNull() ?: 0
                        )
                    }
                }
            } catch (_: Exception) {}
            map = m
            return m
        }
    }

    fun get(ctx: Context, c: Char): Info? = all(ctx)[c]

    /** Kanji com dados, para alternativas de distração. */
    fun sample(ctx: Context, exclude: Set<Char>, n: Int, rnd: kotlin.random.Random): List<Info> =
        all(ctx).values.filter { it.char !in exclude && it.meanings.isNotEmpty() && it.grade in 1..8 }
            .shuffled(rnd).take(n)

    /** Texto de uma linha por kanji: "男  on ダン、ナン · kun おとこ — masculino". */
    fun describe(ctx: Context, word: String): List<Pair<Char, String>> =
        word.filter(StudyGen::isKanji).toList().distinct().mapNotNull { c ->
            val i = get(ctx, c) ?: return@mapNotNull null
            val parts = mutableListOf<String>()
            if (i.on.isNotEmpty()) parts.add("on ${i.on.joinToString("、")}")
            if (i.kunPretty.isNotEmpty()) parts.add("kun ${i.kunPretty.joinToString("、")}")
            val meaning = i.meanings.joinToString(", ") + if (i.english) " (inglês)" else ""
            c to (parts.joinToString(" · ") + if (meaning.isNotBlank()) "\n   $meaning" else "")
        }
}
