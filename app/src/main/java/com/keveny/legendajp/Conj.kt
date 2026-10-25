package com.keveny.legendajp

/**
 * Conjugador de verbos e adjetivos-い a partir da forma de dicionário e do tipo que o Kuromoji
 * (IPADIC) informa: godan (五段), ichidan (一段), する, 来る e adjetivos-い. Gera só as formas
 * que os exercícios de gramática usam; verbos de tipo especial (くださる, ござる…) ficam de fora.
 */
object Conj {

    /** Formas de verbo. NA = forma ない sem o い (食べな → 食べなかった, 食べなければ, 食べなくても). */
    enum class V(val label: String) {
        DICT("forma de dicionário"), STEM("radical da forma ます"), TE("forma て"), TA("forma た (passado)"),
        NAI("forma ない"), NA("forma ない sem o い"), VOL("forma volitiva (よう)"), BA("forma ば"),
        PASS("forma passiva"), CAUS("forma causativa"), MASU("forma ます")
    }

    /** Formas de adjetivo-い. */
    enum class A(val label: String) {
        DICT("adjetivo-い inteiro"), STEM("adjetivo sem o い"), KU("forma く"), KATTA("forma かった"),
        KUTE("forma くて"), KEREBA("forma ければ")
    }

    private val aRow = mapOf('く' to 'か', 'ぐ' to 'が', 'す' to 'さ', 'つ' to 'た', 'ぬ' to 'な', 'ぶ' to 'ば', 'む' to 'ま', 'る' to 'ら', 'う' to 'わ')
    private val iRow = mapOf('く' to 'き', 'ぐ' to 'ぎ', 'す' to 'し', 'つ' to 'ち', 'ぬ' to 'に', 'ぶ' to 'び', 'む' to 'み', 'る' to 'り', 'う' to 'い')
    private val eRow = mapOf('く' to 'け', 'ぐ' to 'げ', 'す' to 'せ', 'つ' to 'て', 'ぬ' to 'ね', 'ぶ' to 'べ', 'む' to 'め', 'る' to 'れ', 'う' to 'え')
    private val oRow = mapOf('く' to 'こ', 'ぐ' to 'ご', 'す' to 'そ', 'つ' to 'と', 'ぬ' to 'の', 'ぶ' to 'ぼ', 'む' to 'も', 'る' to 'ろ', 'う' to 'お')

    /** Final da forma て de um godan pela última letra (く→いて, む→んで…). */
    fun godanTe(last: Char): String = when (last) {
        'く' -> "いて"; 'ぐ' -> "いで"; 'す' -> "して"
        'つ', 'る', 'う' -> "って"
        else -> "んで" // ぬ, ぶ, む
    }

    enum class Kind { GODAN, ICHIDAN, SURU, KURU }

    /** Tipo do verbo (pelo Kuromoji), ou null se não for um caso suportado. */
    fun kindOf(base: String): Kind? {
        if (base.isEmpty()) return null
        if (base == "する") return Kind.SURU
        if (base == "来る" || base == "くる") return Kind.KURU
        val t = try { Jp.tokenize(base) } catch (_: Exception) { return null }
        if (t.size != 1 || t[0].partOfSpeechLevel1 != "動詞") return null
        val type = t[0].conjugationType ?: return null
        return when {
            type.startsWith("一段") -> Kind.ICHIDAN
            type == "五段・ラ行特殊" || type.contains("ウ音便") -> null
            type.startsWith("五段") && base.last() in aRow -> Kind.GODAN
            type.startsWith("サ変・スル") -> Kind.SURU
            else -> null
        }
    }

    /** Todas as formas do verbo, ou null se não der para conjugar com segurança. */
    fun verb(base: String): Map<V, String>? {
        val k = kindOf(base) ?: return null
        val m = HashMap<V, String>()
        when (k) {
            Kind.ICHIDAN -> {
                val s = base.dropLast(1)
                m[V.STEM] = s; m[V.TE] = s + "て"; m[V.TA] = s + "た"; m[V.NAI] = s + "ない"
                m[V.VOL] = s + "よう"; m[V.BA] = s + "れば"; m[V.PASS] = s + "られる"; m[V.CAUS] = s + "させる"
            }
            Kind.GODAN -> {
                val s = base.dropLast(1)
                val c = base.last()
                val te = if (base == "行く" || base == "いく") "って" else godanTe(c)
                m[V.STEM] = s + iRow.getValue(c); m[V.TE] = s + te; m[V.TA] = s + te.dropLast(1) + (if (te.last() == 'で') "だ" else "た")
                m[V.NAI] = if (base == "ある") "ない" else s + aRow.getValue(c) + "ない"
                m[V.VOL] = s + oRow.getValue(c) + "う"; m[V.BA] = s + eRow.getValue(c) + "ば"
                m[V.PASS] = s + aRow.getValue(c) + "れる"; m[V.CAUS] = s + aRow.getValue(c) + "せる"
            }
            Kind.SURU -> {
                val p = base.dropLast(2) // 勉強する → 勉強
                m[V.STEM] = p + "し"; m[V.TE] = p + "して"; m[V.TA] = p + "した"; m[V.NAI] = p + "しない"
                m[V.VOL] = p + "しよう"; m[V.BA] = p + "すれば"; m[V.PASS] = p + "される"; m[V.CAUS] = p + "させる"
            }
            Kind.KURU -> if (base == "来る") {
                m[V.STEM] = "来"; m[V.TE] = "来て"; m[V.TA] = "来た"; m[V.NAI] = "来ない"
                m[V.VOL] = "来よう"; m[V.BA] = "来れば"; m[V.PASS] = "来られる"; m[V.CAUS] = "来させる"
            } else {
                m[V.STEM] = "き"; m[V.TE] = "きて"; m[V.TA] = "きた"; m[V.NAI] = "こない"
                m[V.VOL] = "こよう"; m[V.BA] = "くれば"; m[V.PASS] = "こられる"; m[V.CAUS] = "こさせる"
            }
        }
        m[V.DICT] = base
        m[V.MASU] = m.getValue(V.STEM) + "ます"
        m[V.NA] = m.getValue(V.NAI).dropLast(1)
        return m
    }

    /** Formas do adjetivo-い (いい/良い usam よ-), ou null se não for adjetivo-い. */
    fun adj(base: String): Map<A, String>? {
        if (base.length < 2 || base.last() != 'い') return null
        val t = try { Jp.tokenize(base) } catch (_: Exception) { return null }
        if (t.size != 1 || t[0].partOfSpeechLevel1 != "形容詞") return null
        val s = when (base) { "いい" -> "よ"; "良い" -> "良"; else -> base.dropLast(1) }
        return mapOf(
            A.DICT to base, A.STEM to s, A.KU to s + "く", A.KATTA to s + "かった",
            A.KUTE to s + "くて", A.KEREBA to s + "ければ"
        )
    }

    /** Erros comuns na forma て/た de um godan (as outras terminações), para alternativas erradas. */
    fun wrongTe(base: String, past: Boolean): List<String> {
        if (kindOf(base) != Kind.GODAN) return emptyList()
        val s = base.dropLast(1)
        val right = verb(base)?.get(if (past) V.TA else V.TE)
        return listOf("って", "んで", "いて", "して", "いで").map { e ->
            s + if (past) e.dropLast(1) + (if (e.last() == 'で') "だ" else "た") else e
        }.filter { it != right }.distinct()
    }
}
