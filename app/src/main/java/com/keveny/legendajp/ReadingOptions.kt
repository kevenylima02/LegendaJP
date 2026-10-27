package com.keveny.legendajp

import android.content.Context

/**
 * Alternativas erradas dos exercícios de leitura. Antes, as outras opções eram leituras de palavras
 * quaisquer: em 「歩く」 só uma alternativa terminava em く e dava para acertar sem saber a leitura.
 * Agora todas as alternativas têm o mesmo okurigana (あるく / あゆく / あるぐ / かく) e, na direção
 * contrária, as palavras em kanji também (歩く / 書く / 泣く / 聞く).
 */
object ReadingOptions {

    /** Palavra dividida: kana igual no começo e no fim (okurigana) e o núcleo em kanji com a leitura dele. */
    data class Split(val prefix: String, val core: String, val coreReading: String, val suffix: String)

    private fun isKana(c: Char) = c in '぀'..'ヿ'

    fun split(base: String, reading: String): Split? {
        val bh = Jp.hiragana(base)
        var a = 0
        while (a < base.length && a < reading.length && isKana(base[a]) && bh[a] == reading[a]) a++
        var b = 0
        while (b < base.length - a && b < reading.length - a && isKana(base[base.length - 1 - b]) &&
            bh[base.length - 1 - b] == reading[reading.length - 1 - b]) b++
        val core = base.substring(a, base.length - b)
        val r = reading.substring(a, reading.length - b)
        if (core.isEmpty() || r.isEmpty() || core.none(StudyGen::isKanji)) return null
        return Split(reading.substring(0, a), core, r, reading.substring(reading.length - b))
    }

    // Pares surda/sonora e linhas de vogal para as confusões de leitura mais comuns
    private const val DAKU = "かがきぎくぐけげこごさざしじすずせぜそぞただちぢつづてでとどはばひびふぶへべほぼ"
    private const val HA = "はひふへほ"
    private const val BA = "ばびぶべぼ"
    private const val PA = "ぱぴぷぺぽ"
    private const val A_ROW = "あかさたなはまやらわがざだばぱゃ"
    private const val I_ROW = "いきしちにひみりぎじぢびぴ"
    private const val U_ROW = "うくすつぬふむゆるぐずづぶぷゅ"
    private const val E_ROW = "えけせてねへめれげぜでべぺ"
    private const val O_ROW = "おこそとのほもよろごぞどぼぽょ"
    private const val BEFORE_SOKUON = "かきくけこさしすせそたちつてとぱぴぷぺぽ"

    /** Leituras parecidas com `r` mas erradas: troca de dakuten, vogal longa, っ, ん, ゃゅょ. */
    fun mutations(r: String): List<String> {
        val out = LinkedHashSet<String>()
        fun put(s: String) { if (s.isNotEmpty() && s != r) out.add(s) }
        for (i in r.indices) {
            val c = r[i]
            val head = r.substring(0, i)
            val tail = r.substring(i + 1)
            val j = DAKU.indexOf(c)
            if (j >= 0) put(head + DAKU[if (j % 2 == 0) j + 1 else j - 1] + tail)
            HA.indexOf(c).takeIf { it >= 0 }?.let { put(head + PA[it] + tail) }
            BA.indexOf(c).takeIf { it >= 0 }?.let { put(head + PA[it] + tail) }
            PA.indexOf(c).takeIf { it >= 0 }?.let { put(head + HA[it] + tail); put(head + BA[it] + tail) }
            // vogal longa: こ ↔ こう, け ↔ けい, か ↔ かあ
            val next = r.getOrNull(i + 1)
            val lv = when (c) {
                in A_ROW -> 'あ'
                in I_ROW -> 'い'
                in U_ROW -> 'う'
                in E_ROW -> 'い'
                in O_ROW -> 'う'
                else -> null
            }
            if (lv != null) {
                if (next == lv) put(head + c + r.substring(i + 2)) else put(head + c + lv + tail)
            }
            // っ
            if (c == 'っ') put(head + tail)
            else if (i > 0 && c in BEFORE_SOKUON && r[i - 1] != 'っ' && r[i - 1] != 'ん') put(head + 'っ' + c + tail)
            // ん
            if (c == 'ん') put(head + tail)
            else if (i < r.length - 1 && c != 'っ' && next != 'ん' && next != 'っ') put(head + c + 'ん' + tail)
            // ゃゅょ
            when (c) {
                'ゃ' -> { put(head + 'ゅ' + tail); put(head + 'ょ' + tail) }
                'ゅ' -> { put(head + 'ゃ' + tail); put(head + 'ょ' + tail) }
                'ょ' -> { put(head + 'ゃ' + tail); put(head + 'ゅ' + tail) }
            }
        }
        return out.toList()
    }

    /** Outras leituras do kanji do núcleo (KANJIDIC): 歩 → あゆ, ほ, ぶ. Só para núcleo de um kanji. */
    private fun kanjidicReadings(ctx: Context, s: Split): List<String> {
        if (s.core.length != 1) return emptyList()
        val k = Kanjidic.get(ctx, s.core[0]) ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (kun in k.kun) {
            val clean = kun.replace("-", "")
            val dot = clean.indexOf('.')
            val stem = if (dot < 0) clean else clean.substring(0, dot)
            val oku = if (dot < 0) "" else clean.substring(dot + 1)
            // Mesmo okurigana = leitura alternativa válida (行く: いく e ゆく); não serve de erro
            if (oku == s.suffix || stem.isEmpty()) continue
            out.add(stem)
        }
        for (on in k.on) out.add(Jp.hiragana(on))
        return out.filter { it != s.coreReading }
    }

    /** Toma uma alternativa de cada grupo, em rodízio, até juntar n; `fallback` só entra se faltar. */
    private fun pick(tiers: List<List<String>>, n: Int, exclude: Set<String>, fallback: List<String> = emptyList()): List<String> {
        val out = LinkedHashSet<String>()
        val queues = tiers.map { ArrayDeque(it) }
        while (out.size < n && queues.any { it.isNotEmpty() }) {
            for (q in queues) {
                while (q.isNotEmpty()) {
                    val c = q.removeFirst()
                    if (c !in exclude && c !in out) { out.add(c); break }
                }
                if (out.size >= n) break
            }
        }
        for (c in fallback) { if (out.size >= n) break; if (c !in exclude) out.add(c) }
        return out.toList()
    }

    /** Três leituras erradas de `w`, todas com o okurigana de `w`. */
    fun readings(ctx: Context, w: Jp.Word, pool: List<Jp.Word>, rnd: kotlin.random.Random, n: Int = 3): List<String> {
        val s = split(w.base, w.reading) ?: return emptyList()
        fun wrap(core: String) = s.prefix + core + s.suffix
        val t1 = kanjidicReadings(ctx, s).shuffled(rnd).map(::wrap)
        val t2 = mutations(s.coreReading).shuffled(rnd).map(::wrap)
        val t3 = pool.filter { it.key != w.key }.mapNotNull { split(it.base, it.reading)?.coreReading }
            .filter { it != s.coreReading }.distinct().shuffled(rnd)
            .sortedBy { kotlin.math.abs(it.length - s.coreReading.length) }.map(::wrap)
        return pick(listOf(t1, t2, t3), n, setOf(w.reading))
    }

    /** É uma palavra que o dicionário conhece inteira (para não oferecer um homófono válido como erro). */
    private fun isRealWord(text: String): Boolean = try {
        val t = Jp.tokenize(text)
        t.size == 1 && t[0].reading != "*"
    } catch (_: Exception) { false }

    /** Três palavras em kanji erradas para a leitura de `w`, escritas com o mesmo okurigana. */
    fun words(ctx: Context, w: Jp.Word, pool: List<Jp.Word>, kanjiPool: List<Char>, rnd: kotlin.random.Random, n: Int = 3): List<String> {
        val s = split(w.base, w.reading) ?: return emptyList()
        val basePrefix = w.base.substring(0, s.prefix.length)
        val baseSuffix = w.base.substring(w.base.length - s.suffix.length)
        fun wrap(core: String) = basePrefix + core + baseSuffix
        // 1) palavras do áudio com o mesmo okurigana (書く, 聞く)
        val t1 = pool.filter { it.key != w.key && it.base != w.base && it.reading != w.reading }
            .mapNotNull { o -> split(o.base, o.reading)?.takeIf { it.prefix == s.prefix && it.suffix == s.suffix }?.let { o.base } }
            .distinct().shuffled(rnd)
        // 2) kanji cuja leitura kun tem esse okurigana (泣く, 咲く), primeiro os do áudio; se há poucos,
        //    também os de okurigana parecido (楽しみ → 悲しみ, de かな.しい)
        val t2 = if (s.suffix.isEmpty()) emptyList() else {
            val exact = Kanjidic.byOkurigana(ctx, s.suffix)
            val like = if (exact.size >= 6) emptyList() else Kanjidic.byOkuriganaLike(ctx, s.suffix).shuffled(rnd)
            val cands = (exact.shuffled(rnd) + like).filter { it.char != s.core[0] }.distinctBy { it.char }
            val (near, far) = cands.partition { it.char in kanjiPool }
            (near + far).map { wrap(it.char.toString()) }.filter { Jp.hiragana(it) != w.reading }
        }
        // 3) sem okurigana: troca um kanji por outro com a mesma leitura on (学校 → 学高), se não for palavra real
        val t3 = if (s.suffix.isNotEmpty() || s.prefix.isNotEmpty()) emptyList() else {
            val out = mutableListOf<String>()
            for ((i, c) in s.core.withIndex()) {
                val info = Kanjidic.get(ctx, c) ?: continue
                for (on in info.on) for (alt in Kanjidic.byOn(ctx, Jp.hiragana(on)).shuffled(rnd).take(3)) {
                    if (alt.char == c) continue
                    val cand = s.core.substring(0, i) + alt.char + s.core.substring(i + 1)
                    if (!isRealWord(cand)) out.add(cand)
                }
            }
            out.shuffled(rnd)
        }
        // 4) palavras do áudio do mesmo tamanho: com okurigana, só se faltar (o final entregaria a resposta)
        val t4 = pool.filter { it.key != w.key && it.base != w.base && it.reading != w.reading && it.base.any(StudyGen::isKanji) }
            .map { it.base }.distinct().shuffled(rnd).sortedBy { kotlin.math.abs(it.length - w.base.length) }
        // 5) um kanji só, sem okurigana (会): outros kanji comuns que não se leem igual (homófonos como
        //    海 seriam resposta válida, por isso ficam de fora)
        val t5 = if (s.core.length != 1 || s.suffix.isNotEmpty() || s.prefix.isNotEmpty()) emptyList() else
            Kanjidic.sample(ctx, setOf(s.core[0]), 12, rnd).filter { k ->
                k.on.none { Jp.hiragana(it) == w.reading } && k.kun.none { it.replace("-", "").substringBefore('.') == w.reading }
            }.map { it.char.toString() }
        return if (s.suffix.isEmpty()) pick(listOf(t1, t3, t4, t5), n, setOf(w.base))
        else pick(listOf(t1, t2), n, setOf(w.base), fallback = t3 + t4)
    }
}
