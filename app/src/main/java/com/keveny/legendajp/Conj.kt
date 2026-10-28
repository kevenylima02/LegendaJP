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
        PASS("forma passiva"), CAUS("forma causativa"), MASU("forma ます"),
        IMP("forma imperativa"), ZU("forma ず"), CAUSPASS("forma causativo-passiva")
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
        return verb(base, k)
    }

    /** Formas de um verbo cujo grupo já se sabe (serve para a leitura em hiragana: かく + GODAN). */
    fun verb(base: String, k: Kind): Map<V, String> {
        val m = HashMap<V, String>()
        when (k) {
            Kind.ICHIDAN -> {
                val s = base.dropLast(1)
                m[V.STEM] = s; m[V.TE] = s + "て"; m[V.TA] = s + "た"; m[V.NAI] = s + "ない"
                m[V.VOL] = s + "よう"; m[V.BA] = s + "れば"; m[V.PASS] = s + "られる"; m[V.CAUS] = s + "させる"
                m[V.IMP] = s + "ろ"; m[V.ZU] = s + "ず"; m[V.CAUSPASS] = s + "させられる"
            }
            Kind.GODAN -> {
                val s = base.dropLast(1)
                val c = base.last()
                val te = if (base == "行く" || base == "いく") "って" else godanTe(c)
                m[V.STEM] = s + iRow.getValue(c); m[V.TE] = s + te; m[V.TA] = s + te.dropLast(1) + (if (te.last() == 'で') "だ" else "た")
                m[V.NAI] = if (base == "ある") "ない" else s + aRow.getValue(c) + "ない"
                m[V.VOL] = s + oRow.getValue(c) + "う"; m[V.BA] = s + eRow.getValue(c) + "ば"
                m[V.PASS] = s + aRow.getValue(c) + "れる"; m[V.CAUS] = s + aRow.getValue(c) + "せる"
                m[V.IMP] = s + eRow.getValue(c); m[V.ZU] = s + aRow.getValue(c) + "ず"; m[V.CAUSPASS] = s + aRow.getValue(c) + "せられる"
            }
            Kind.SURU -> {
                val p = base.dropLast(2) // 勉強する → 勉強
                m[V.STEM] = p + "し"; m[V.TE] = p + "して"; m[V.TA] = p + "した"; m[V.NAI] = p + "しない"
                m[V.VOL] = p + "しよう"; m[V.BA] = p + "すれば"; m[V.PASS] = p + "される"; m[V.CAUS] = p + "させる"
                m[V.IMP] = p + "しろ"; m[V.ZU] = p + "せず"; m[V.CAUSPASS] = p + "させられる"
            }
            Kind.KURU -> if (base == "来る") {
                m[V.STEM] = "来"; m[V.TE] = "来て"; m[V.TA] = "来た"; m[V.NAI] = "来ない"
                m[V.VOL] = "来よう"; m[V.BA] = "来れば"; m[V.PASS] = "来られる"; m[V.CAUS] = "来させる"
                m[V.IMP] = "来い"; m[V.ZU] = "来ず"; m[V.CAUSPASS] = "来させられる"
            } else {
                m[V.STEM] = "き"; m[V.TE] = "きて"; m[V.TA] = "きた"; m[V.NAI] = "こない"
                m[V.VOL] = "こよう"; m[V.BA] = "くれば"; m[V.PASS] = "こられる"; m[V.CAUS] = "こさせる"
                m[V.IMP] = "こい"; m[V.ZU] = "こず"; m[V.CAUSPASS] = "こさせられる"
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
        return adjForms(base)
    }

    /** Formas do adjetivo-い sem conferir a classe (para a leitura em hiragana). */
    fun adjForms(base: String): Map<A, String> {
        val s = when (base) { "いい" -> "よ"; "良い" -> "良"; else -> base.dropLast(1) }
        return mapOf(
            A.DICT to base, A.STEM to s, A.KU to s + "く", A.KATTA to s + "かった",
            A.KUTE to s + "くて", A.KEREBA to s + "ければ"
        )
    }

    // =====================================================================
    // Grupos, regras de formação (em português) e erros típicos de aprendiz
    // =====================================================================

    fun group(k: Kind): String = when (k) {
        Kind.GODAN -> "grupo 1 (godan, 五段)"
        Kind.ICHIDAN -> "grupo 2 (ichidan, 一段)"
        Kind.SURU -> "grupo 3 (irregular: する)"
        Kind.KURU -> "grupo 3 (irregular: 来る)"
    }

    /** Título + um item por grupo ("Forma ない\n • Grupo 1…\n • Grupo 2…"). Linhas que começam com " • " são itens. */
    private fun form(name: String, vararg groups: String) = name + "\n" + groups.joinToString("\n") { " • $it" }

    /** Como se faz a forma em cada grupo, com exemplos em hiragana: título na primeira linha, um item por grupo. */
    fun rule(f: V): String = when (f) {
        V.DICT -> form("Forma de dicionário",
            "Todos os grupos: a palavra como está no dicionário (かく、たべる、する、くる).")
        V.STEM, V.MASU -> form("Radical da forma ます",
            "Grupo 1 (godan): a última sílaba vai para a linha い: かく→かき、およぐ→およぎ、はなす→はなし、まつ→まち、あそぶ→あそび、よむ→よみ、かえる→かえり、かう→かい.",
            "Grupo 2 (ichidan): tira o る: たべる→たべ、みる→み.",
            "Grupo 3 (irregular): する→し、くる→き.",
            "Daí + ます: かきます、たべます、します、きます.")
        V.TE -> form("Forma て",
            "Grupo 1 (godan), pela última sílaba: く→いて (かく→かいて), ぐ→いで (およぐ→およいで), す→して (はなす→はなして), つ・る・う→って (まつ→まって、かえる→かえって、かう→かって), ぬ・ぶ・む→んで (あそぶ→あそんで、よむ→よんで). Exceção: いく→いって.",
            "Grupo 2 (ichidan): tira o る + て: たべる→たべて.",
            "Grupo 3 (irregular): する→して、くる→きて.")
        V.TA -> form("Forma た (passado)",
            "É a forma て com た no lugar de て (e だ no lugar de で).",
            "Grupo 1 (godan): かく→かいた、およぐ→およいだ、はなす→はなした、まつ→まった、よむ→よんだ. Exceção: いく→いった.",
            "Grupo 2 (ichidan): たべる→たべた.",
            "Grupo 3 (irregular): する→した、くる→きた.")
        V.NAI, V.NA -> form("Forma ない",
            "Grupo 1 (godan): a última sílaba vai para a linha あ + ない: かく→かかない、およぐ→およがない、はなす→はなさない、まつ→またない、よむ→よまない、かえる→かえらない. Verbos em う usam わ: かう→かわない. Exceção: ある→ない.",
            "Grupo 2 (ichidan): tira o る + ない: たべる→たべない.",
            "Grupo 3 (irregular): する→しない、くる→こない.")
        V.VOL -> form("Forma volitiva (vamos…)",
            "Grupo 1 (godan): a última sílaba vai para a linha お + う: かく→かこう、よむ→よもう、かう→かおう.",
            "Grupo 2 (ichidan): tira o る + よう: たべる→たべよう.",
            "Grupo 3 (irregular): する→しよう、くる→こよう.")
        V.BA -> form("Forma ば (se…)",
            "Grupo 1 (godan): a última sílaba vai para a linha え + ば: かく→かけば、よむ→よめば、かう→かえば.",
            "Grupo 2 (ichidan): tira o る + れば: たべる→たべれば.",
            "Grupo 3 (irregular): する→すれば、くる→くれば.")
        V.PASS -> form("Forma passiva",
            "Grupo 1 (godan): linha あ + れる: かく→かかれる、かう→かわれる.",
            "Grupo 2 (ichidan): tira o る + られる: たべる→たべられる.",
            "Grupo 3 (irregular): する→される、くる→こられる.")
        V.CAUS -> form("Forma causativa (fazer ou deixar alguém…)",
            "Grupo 1 (godan): linha あ + せる: かく→かかせる、かう→かわせる.",
            "Grupo 2 (ichidan): tira o る + させる: たべる→たべさせる.",
            "Grupo 3 (irregular): する→させる、くる→こさせる.")
        V.IMP -> form("Forma imperativa (ordem)",
            "Grupo 1 (godan): a última sílaba vai para a linha え: かく→かけ、よむ→よめ、かう→かえ.",
            "Grupo 2 (ichidan): tira o る + ろ: たべる→たべろ (na escrita também よ).",
            "Grupo 3 (irregular): する→しろ、くる→こい.")
        V.ZU -> form("Forma ず (sem…)",
            "É a forma ない com ず no lugar de ない.",
            "Grupo 1 (godan): かく→かかず、かう→かわず.",
            "Grupo 2 (ichidan): たべる→たべず.",
            "Grupo 3 (irregular): する→せず (não しず!), くる→こず.")
        V.CAUSPASS -> form("Forma causativo-passiva (ser obrigado a…)",
            "É o causativo sem o る + られる.",
            "Grupo 1 (godan): かく→かかせられる (falado também かかされる), かう→かわせられる.",
            "Grupo 2 (ichidan): たべる→たべさせられる.",
            "Grupo 3 (irregular): する→させられる、くる→こさせられる.")
    }

    fun ruleAdj(f: A): String = when (f) {
        A.DICT -> form("Adjetivo-い inteiro", "Como está: たかい、あたらしい.")
        A.STEM -> form("Adjetivo sem o い", "たかい→たか. Exceção: いい→よ.")
        A.KU -> form("Forma く", "Tira o い + く: たかい→たかく. Exceção: いい→よく.")
        A.KATTA -> form("Forma かった (passado)", "Tira o い + かった: たかい→たかかった. Exceção: いい→よかった.")
        A.KUTE -> form("Forma くて", "Tira o い + くて: たかい→たかくて. Exceção: いい→よくて.")
        A.KEREBA -> form("Forma ければ (se…)", "Tira o い + ければ: たかい→たかければ. Exceção: いい→よければ.")
    }

    /**
     * Erros típicos de quem está aprendendo, para alternativas erradas (em hiragana): tratar o grupo 1
     * como 2 e vice-versa, linha errada do kana, e os erros clássicos de する e 来る (くない, きない…).
     */
    fun learnerErrors(reading: String, k: Kind, f: V): List<String> {
        if (reading.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        when (k) {
            Kind.GODAN -> {
                val s = reading.dropLast(1); val c = reading.last()
                val a = aRow[c] ?: return emptyList(); val i = iRow.getValue(c); val e = eRow.getValue(c); val o = oRow.getValue(c)
                val asIchidan = c == 'る'
                when (f) {
                    V.STEM, V.MASU -> { out.add(s + a + "ます"); out.add(s + c + "ます"); if (asIchidan) out.add(s + "ます") }
                    V.TE -> { out.addAll(wrongTe(reading, false)); if (asIchidan) out.add(s + "て"); out.add(s + i + "て") }
                    V.TA -> { out.addAll(wrongTe(reading, true)); if (asIchidan) out.add(s + "た"); out.add(s + i + "た") }
                    V.NAI -> { out.add(s + i + "ない"); out.add(s + c + "ない"); if (asIchidan) out.add(s + "ない"); out.add(s + e + "ない") }
                    V.NA -> { out.add(s + i + "な"); out.add(s + c + "な"); if (asIchidan) out.add(s + "な") }
                    V.VOL -> { out.add(s + i + "よう"); if (asIchidan) out.add(s + "よう"); out.add(s + c + "う"); out.add(s + a + "う") }
                    V.BA -> { out.add(s + a + "ば"); out.add(s + i + "ば"); if (asIchidan) out.add(s + "れば"); out.add(s + c + "ば") }
                    V.PASS -> { out.add(s + e + "れる"); if (asIchidan) out.add(s + "られる"); out.add(s + i + "られる"); out.add(s + c + "れる") }
                    V.CAUS -> { out.add(s + e + "せる"); if (asIchidan) out.add(s + "させる"); out.add(s + i + "させる"); out.add(s + o + "せる") }
                    V.IMP -> { out.add(s + i); if (asIchidan) out.add(s + "ろ"); out.add(s + c + "ろ"); out.add(s + o) }
                    V.ZU -> { out.add(s + i + "ず"); out.add(s + c + "ず"); if (asIchidan) out.add(s + "ず"); out.add(s + e + "ず") }
                    V.CAUSPASS -> { out.add(s + e + "せられる"); if (asIchidan) out.add(s + "させられる"); out.add(s + i + "させられる"); out.add(s + a + "さられる") }
                    V.DICT -> {}
                }
            }
            Kind.ICHIDAN -> {
                val s = reading.dropLast(1)
                when (f) {
                    V.STEM, V.MASU -> { out.add(s + "ります"); out.add(s + "るます") }
                    V.TE -> { out.add(s + "って"); out.add(s + "んで"); out.add(s + "いて") }
                    V.TA -> { out.add(s + "った"); out.add(s + "んだ"); out.add(s + "いた") }
                    V.NAI -> { out.add(s + "らない"); out.add(s + "るない"); out.add(s + "れない") }
                    V.NA -> { out.add(s + "らな"); out.add(s + "るな") }
                    V.VOL -> { out.add(s + "ろう"); out.add(s + "るよう"); out.add(s + "よ") }
                    V.BA -> { out.add(s + "らば"); out.add(s + "ば"); out.add(s + "るば") }
                    V.PASS -> { out.add(s + "れる"); out.add(s + "らる"); out.add(s + "られれる") }
                    V.CAUS -> { out.add(s + "らせる"); out.add(s + "させれる"); out.add(s + "せる") }
                    V.IMP -> { out.add(s + "れ"); out.add(s + "るろ"); out.add(s + "り") }
                    V.ZU -> { out.add(s + "らず"); out.add(s + "るず"); out.add(s + "ないず") }
                    V.CAUSPASS -> { out.add(s + "らせられる"); out.add(s + "せられる"); out.add(s + "させれる") }
                    V.DICT -> {}
                }
            }
            Kind.SURU -> {
                val p = reading.removeSuffix("する")
                when (f) {
                    V.STEM, V.MASU -> { out.add(p + "すます"); out.add(p + "せます"); out.add(p + "すります") }
                    V.TE -> { out.add(p + "すて"); out.add(p + "しって"); out.add(p + "せて") }
                    V.TA -> { out.add(p + "すた"); out.add(p + "しった"); out.add(p + "せた") }
                    V.NAI -> { out.add(p + "すない"); out.add(p + "さない"); out.add(p + "しらない") }
                    V.NA -> { out.add(p + "すな"); out.add(p + "さな") }
                    V.VOL -> { out.add(p + "すよう"); out.add(p + "しよ"); out.add(p + "そう") }
                    V.BA -> { out.add(p + "しれば"); out.add(p + "せば"); out.add(p + "すば") }
                    V.PASS -> { out.add(p + "しられる"); out.add(p + "すられる"); out.add(p + "せられる") }
                    V.CAUS -> { out.add(p + "しさせる"); out.add(p + "すせる"); out.add(p + "させれる") }
                    V.IMP -> { out.add(p + "すれ"); out.add(p + "しれ"); out.add(p + "すろ") }
                    V.ZU -> { out.add(p + "しず"); out.add(p + "すず"); out.add(p + "さず") }
                    V.CAUSPASS -> { out.add(p + "しさせられる"); out.add(p + "すせられる"); out.add(p + "させれる") }
                    V.DICT -> {}
                }
            }
            Kind.KURU -> when (f) {
                V.STEM, V.MASU -> { out.add("くます"); out.add("こます"); out.add("くります") }
                V.TE -> { out.add("くて"); out.add("こて"); out.add("くって") }
                V.TA -> { out.add("くた"); out.add("こた"); out.add("くった") }
                V.NAI -> { out.add("くない"); out.add("きない"); out.add("こらない") }
                V.NA -> { out.add("くな"); out.add("きな") }
                V.VOL -> { out.add("くよう"); out.add("きよう"); out.add("こう") }
                V.BA -> { out.add("きれば"); out.add("こば"); out.add("これば") }
                V.PASS -> { out.add("くられる"); out.add("きられる"); out.add("これる") }
                V.CAUS -> { out.add("くさせる"); out.add("きさせる"); out.add("こさす") }
                V.IMP -> { out.add("くれ"); out.add("きろ"); out.add("ころ") }
                V.ZU -> { out.add("くず"); out.add("きず"); out.add("こなず") }
                V.CAUSPASS -> { out.add("くさせられる"); out.add("きさせられる"); out.add("こられさせる") }
                V.DICT -> {}
            }
        }
        return out.distinct()
    }

    fun learnerErrorsAdj(reading: String, f: A): List<String> {
        if (reading.length < 2) return emptyList()
        val s = reading.dropLast(1)
        return when (f) {
            A.DICT, A.STEM -> emptyList()
            A.KU -> listOf(reading + "く", s + "に", s + "で")
            A.KATTA -> listOf(reading + "かった", s + "だった", s + "かた", reading + "でした")
            A.KUTE -> listOf(reading + "くて", s + "で", s + "いて")
            A.KEREBA -> listOf(reading + "ければ", s + "ば", s + "れば", s + "なら")
        }.distinct()
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
