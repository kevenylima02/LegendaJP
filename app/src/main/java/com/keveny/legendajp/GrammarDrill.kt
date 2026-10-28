package com.keveny.legendajp

import com.keveny.legendajp.Conj.A
import com.keveny.legendajp.Conj.V
import com.keveny.legendajp.StudyGen.Item
import com.keveny.legendajp.StudyGen.Line

/**
 * Exercícios para usar a gramática (não só reconhecer): como cada padrão do catálogo se liga a
 * verbos e adjetivos-い, e os exercícios gerados a partir disso com palavras das suas gravações.
 * - Qual forma combina: 「(食べる)＿＿とき」, escolha a forma que encaixa.
 * - Transforme com o padrão: 読む + ～ている → 読んでいる (alternativas com os erros comuns de conjugação).
 * - Português → padrão: com 読む, qual frase significa «quero …»?
 * - Escrever com o padrão (IA): frase livre, corrigida pelo Gemini.
 */
object GrammarDrill {

    /** Como o padrão se forma: com verbo e/ou adjetivo-い (forma + o que vem depois). Mais de um = todos valem. */
    class Spec(
        val id: String,
        val verb: List<Pair<V, String>>,
        val adj: List<Pair<A, String>>,
        /** Sentido curto, com … no lugar da palavra: «quero …». */
        val meaning: String
    ) {
        val point: Grammar.Point? get() = Grammar.point(id)

        /** Regra em português: "～とき: verbo na forma de dicionário ou na forma た; adjetivo-い inteiro." */
        fun rule(): String {
            val p = point?.name ?: id
            val parts = mutableListOf<String>()
            if (verb.isNotEmpty()) parts.add("verbo: " + verb.joinToString(" ou ") { (f, s) -> f.label + if (s.isNotEmpty()) " + $s" else "" })
            if (adj.isNotEmpty()) parts.add("adjetivo: " + adj.joinToString(" ou ") { (f, s) -> f.label + if (s.isNotEmpty()) " + $s" else "" })
            return "$p: " + parts.joinToString("; ") + "."
        }
    }

    private fun v(vararg x: Pair<V, String>) = x.toList()
    private fun a(vararg x: Pair<A, String>) = x.toList()

    val specs: List<Spec> = listOf(
        Spec("masu", v(V.STEM to "ます"), a(), "(forma educada) …"),
        Spec("mashita", v(V.STEM to "ました"), a(), "… (passado, educado)"),
        Spec("masen", v(V.STEM to "ません"), a(), "não … (educado)"),
        Spec("mashou", v(V.STEM to "ましょう"), a(), "vamos …!"),
        Spec("ta", v(V.TA to ""), a(A.KATTA to ""), "… (passado)"),
        Spec("nai", v(V.NAI to ""), a(A.KU to "ない"), "não …"),
        Spec("nakatta", v(V.NA to "かった"), a(A.KU to "なかった"), "não … (passado)"),
        Spec("te-iru", v(V.TE to "いる"), a(), "estar …ndo / estado de …"),
        Spec("te-kudasai", v(V.TE to "ください"), a(), "por favor, …"),
        Spec("te-mo-ii", v(V.TE to "もいい"), a(), "pode …"),
        Spec("te-wa-ikenai", v(V.TE to "はいけない"), a(), "não pode …"),
        Spec("te-kara", v(V.TE to "から"), a(), "depois de …"),
        Spec("te-miru", v(V.TE to "みる"), a(), "tentar … (para ver)"),
        Spec("te-shimau", v(V.TE to "しまう"), a(), "… por completo / sem querer"),
        Spec("te-oku", v(V.TE to "おく"), a(), "… com antecedência"),
        Spec("tai", v(V.STEM to "たい"), a(), "quero …"),
        Spec("rareru", v(V.PASS to ""), a(), "ser …do (passivo)"),
        Spec("saseru", v(V.CAUS to ""), a(), "fazer/deixar alguém …"),
        Spec("you", v(V.VOL to ""), a(), "vamos … (informal)"),
        Spec("you-to-omou", v(V.VOL to "と思う"), a(), "estou pensando em …"),
        Spec("ba", v(V.BA to ""), a(A.KEREBA to ""), "se …"),
        Spec("tara", v(V.TA to "ら"), a(A.KATTA to "ら"), "se / quando …"),
        Spec("nakereba", v(V.NA to "ければならない"), a(), "tenho que …"),
        Spec("nakute-mo-ii", v(V.NA to "くてもいい"), a(), "não precisa …"),
        Spec("naide", v(V.NAI to "で"), a(), "sem …"),
        Spec("nagara", v(V.STEM to "ながら"), a(), "enquanto …"),
        Spec("tari", v(V.TA to "りする"), a(), "fazer coisas como …"),
        Spec("koto-ga-aru", v(V.TA to "ことがある"), a(), "já … alguma vez"),
        Spec("koto-ga-dekiru", v(V.DICT to "ことができる"), a(), "consigo …"),
        Spec("koto-ni-suru", v(V.DICT to "ことにする", V.NAI to "ことにする"), a(), "decidi …"),
        Spec("you-ni-naru", v(V.DICT to "ようになる"), a(), "passei a …"),
        Spec("tsumori", v(V.DICT to "つもりだ", V.NAI to "つもりだ"), a(), "pretendo …"),
        Spec("sugiru", v(V.STEM to "すぎる"), a(A.STEM to "すぎる"), "… demais"),
        Spec("yasui", v(V.STEM to "やすい"), a(), "fácil de …"),
        Spec("hajimeru", v(V.STEM to "始める"), a(), "começar a …"),
        Spec("kata", v(V.STEM to "方"), a(), "modo de …"),
        Spec("mae-ni", v(V.DICT to "前に"), a(), "antes de …"),
        Spec("ato-de", v(V.TA to "後で"), a(), "depois de …"),
        Spec("toki", v(V.DICT to "とき", V.TA to "とき", V.NAI to "とき"), a(A.DICT to "とき", A.KATTA to "とき"), "quando …"),
        Spec("hou-ga-ii", v(V.TA to "ほうがいい", V.NAI to "ほうがいい"), a(), "é melhor … / é melhor não …"),
        Spec("temo", v(V.TE to "も"), a(A.KUTE to "も"), "mesmo que …"),
        Spec("sou-looks", v(V.STEM to "そう"), a(A.STEM to "そう"), "parece que vai … / parece …"),
        Spec("sou-hearsay", v(V.DICT to "そうだ", V.TA to "そうだ", V.NAI to "そうだ"), a(A.DICT to "そうだ", A.KATTA to "そうだ"), "dizem que …"),
        Spec("hazu", v(V.DICT to "はずだ", V.TA to "はずだ", V.NAI to "はずだ"), a(A.DICT to "はずだ", A.KATTA to "はずだ"), "deve … (é o esperado)"),
        Spec("kamoshirenai", v(V.DICT to "かもしれない", V.TA to "かもしれない", V.NAI to "かもしれない"), a(A.DICT to "かもしれない", A.KATTA to "かもしれない"), "talvez …"),
        Spec("kara", v(V.DICT to "から", V.TA to "から", V.NAI to "から"), a(A.DICT to "から", A.KATTA to "から"), "porque …"),
        Spec("to-omou", v(V.DICT to "と思う", V.TA to "と思う", V.NAI to "と思う"), a(A.DICT to "と思う", A.KATTA to "と思う"), "acho que …"),
        Spec("adj-kunai", v(), a(A.KU to "ない"), "não é …"),
        Spec("adj-katta", v(), a(A.KATTA to ""), "era …"),
        Spec("adj-ku-naru", v(), a(A.KU to "なる"), "ficar …"),
    ).filter { Grammar.point(it.id) != null }

    private val byId = specs.associateBy { it.id }

    fun spec(id: String): Spec? = byId[id]

    /** Verbos para quando a frase não tem um que sirva. */
    private val fallbackVerbs = listOf("食べる", "飲む", "行く", "見る", "読む", "書く", "話す", "待つ", "遊ぶ", "買う", "寝る", "起きる")

    /** Banco para cobrir todos os grupos: godan por terminação (く ぐ す つ ぶ む る う), ichidan, する, 来る; e adjetivos-い. */
    private val verbBank = listOf(
        "書く" to "escrever", "泳ぐ" to "nadar", "話す" to "falar", "待つ" to "esperar", "遊ぶ" to "brincar", "読む" to "ler",
        "帰る" to "voltar para casa", "買う" to "comprar", "食べる" to "comer", "見る" to "ver", "する" to "fazer", "来る" to "vir",
    )
    private val adjBank = listOf("高い" to "alto / caro", "新しい" to "novo", "楽しい" to "divertido")

    /**
     * Exercícios de uso dos padrões escolhidos com todos os grupos de verbo (e adjetivos-い, quando o padrão
     * aceita): Qual forma combina e Transforme para cada palavra do banco, Português → padrão e a frase
     * livre (IA) uma vez por padrão. A frase de apoio é a primeira da gravação que tem o padrão.
     */
    fun coverage(ctx: android.content.Context, ids: Set<String>, lines: List<Line>, rnd: kotlin.random.Random, withAi: Boolean): List<Item> {
        val out = mutableListOf<Item>()
        val db = VocabDb.get(ctx)
        for (id in ids) {
            val s = spec(id) ?: continue
            val line = lines.firstOrNull { l -> (try { Grammar.find(l.text) } catch (_: Exception) { emptyList() }).any { it.point.id == id } }
                ?: Line(-1, s.point?.example ?: "", -1)
            val bank = (if (s.verb.isNotEmpty()) verbBank.map { it to "動詞" } else emptyList()) +
                (if (s.adj.isNotEmpty()) adjBank.map { it to "形容詞" } else emptyList())
            val targets = mutableListOf<Target>()
            for ((pair, posKey) in bank) {
                val (base, pt) = pair
                if (Tr.cached(ctx, base) == null) db.putTranslation(base, pt)
                val w = Jp.Word(base, base, base, base, if (posKey == "動詞") "verbo" else "adjetivo", posKey, "", -1, -1)
                val t = target(w) ?: continue
                if (!t.fits(s) || t.results(s).isEmpty()) continue
                targets.add(t)
                form(ctx, line, t, s, rnd)?.let { out.add(it) }
                transform(ctx, line, t, s, rnd)?.let { out.add(it) }
            }
            val t = targets.randomOrNull(rnd) ?: continue
            val usable = specs.filter { t.fits(it) && t.results(it).isNotEmpty() }
            meaning(ctx, line, t, s, usable, rnd)?.let { out.add(it) }
            if (withAi) out.add(Item.FreeWrite(line, id, t.word))
        }
        return out
    }

    private fun levelOf(s: Spec) = s.point?.level?.removePrefix("N")?.toIntOrNull() ?: 5

    /** Uma palavra que dá para conjugar: verbo ou adjetivo-い, com as formas. */
    private class Target(val word: Jp.Word, val verb: Map<V, String>?, val adj: Map<A, String>?) {
        fun results(s: Spec): List<String> {
            // いい/良い + そう = よさそう/良さそう (irregular)
            if (adj != null && s.id == "sou-looks" && word.base in setOf("いい", "良い")) return listOf(adj.getValue(A.STEM) + "さそう")
            return if (verb != null) s.verb.mapNotNull { (f, suf) -> verb[f]?.plus(suf) } else s.adj.mapNotNull { (f, suf) -> adj?.get(f)?.plus(suf) }
        }
        fun fits(s: Spec) = if (verb != null) s.verb.isNotEmpty() else s.adj.isNotEmpty()
    }

    private fun target(w: Jp.Word): Target? = when (w.posKey) {
        "動詞" -> Conj.verb(w.base)?.let { Target(w, it, null) }
        "形容詞" -> Conj.adj(w.base)?.let { Target(w, null, it) }
        else -> null
    }

    private fun fallback(rnd: kotlin.random.Random): Target? {
        val base = fallbackVerbs.random(rnd)
        val w = Jp.Word(base, base, base, base, "verbo", "動詞", "", -1, -1)
        return Conj.verb(base)?.let { Target(w, it, null) }
    }

    private fun meaningOf(ctx: android.content.Context, t: Target): String =
        Tr.cached(ctx, t.word.base)?.lowercase()?.let { "${t.word.base} ($it)" } ?: t.word.base

    /**
     * Exercícios de uso para uma frase. preferIds: padrões que aparecem na frase (vêm primeiro).
     * pointId: só deste padrão (revisão espaçada). Palavras: as da frase (elegíveis) ou um verbo comum.
     */
    fun items(
        ctx: android.content.Context, line: Line, words: List<Jp.Word>, preferIds: List<String>,
        levels: Set<Int>, rnd: kotlin.random.Random, pointId: String? = null, withAi: Boolean = false
    ): List<Item> {
        val targets = words.mapNotNull { target(it) }.ifEmpty { listOfNotNull(fallback(rnd)) }
        val t = targets.random(rnd)
        val usable = specs.filter { t.fits(it) && t.results(it).isNotEmpty() }
        if (usable.isEmpty()) return emptyList()
        fun pick(exclude: Set<String>): Spec? {
            if (pointId != null) return usable.firstOrNull { it.id == pointId }
            val pool = usable.filter { it.id !in exclude }
            return pool.filter { it.id in preferIds }.randomOrNull(rnd)
                ?: pool.filter { levelOf(it) in levels }.randomOrNull(rnd)
                ?: pool.randomOrNull(rnd)
        }
        val out = mutableListOf<Item>()
        val used = HashSet<String>()
        pick(used)?.let { s -> form(ctx, line, t, s, rnd)?.let { out.add(it); used.add(s.id) } }
        pick(used)?.let { s -> transform(ctx, line, t, s, rnd)?.let { out.add(it); used.add(s.id) } }
        pick(used)?.let { s -> meaning(ctx, line, t, s, usable, rnd)?.let { out.add(it); used.add(s.id) } }
        if (withAi) pick(emptySet())?.let { s -> out.add(Item.FreeWrite(line, s.id, t.word)) }
        return out
    }

    /**
     * Formas que dão alternativas erradas de verdade. Se o padrão aceita a forma de dicionário, た e ない
     * também costumam formar frases válidas (com outro sentido), então ficam de fora; antes de ます,
     * a forma て forma o coloquial ～てます.
     */
    private fun wrongVerbForms(s: Spec, suf: String): Set<V> {
        val forms = mutableSetOf(V.DICT, V.STEM, V.TE, V.TA, V.NAI, V.VOL, V.BA)
        if (s.verb.any { it.first == V.DICT }) { forms.remove(V.TA); forms.remove(V.NAI) }
        if (suf.startsWith("ま") || suf.startsWith("い")) forms.remove(V.TE)
        return forms
    }

    /** Qual forma combina: o padrão fixo, escolha a forma da palavra que encaixa. */
    private fun form(ctx: android.content.Context, line: Line, t: Target, s: Spec, rnd: kotlin.random.Random): Item? {
        val right = t.results(s)
        val suf = if (t.verb != null) s.verb.first().second else s.adj.first().second
        val validForms = if (t.verb != null) s.verb.map { it.first.name }.toSet() else s.adj.map { it.first.name }.toSet()
        val wrongForms = wrongVerbForms(s, suf)
        val wrong = (if (t.verb != null) t.verb.filterKeys { it in wrongForms && it.name !in validForms }.values else t.adj!!.filterKeys { it.name !in validForms }.values)
            .map { it + suf }.filter { it !in right }.distinct().shuffled(rnd).take(3)
        if (wrong.size < 3) return null
        val answer = right.first()
        val name = s.point?.name ?: return null
        return Item.Drill(
            "Qual forma combina", line, s.id,
            "Qual forma de ${meaningOf(ctx, t)} combina com 「$name」 (${s.meaning})?",
            answer, (wrong + answer).shuffled(rnd),
            s.rule() + if (right.size > 1) " Também valem: " + right.drop(1).joinToString("、") + "." else "",
            t.word, levelOf(s)
        )
    }

    /** Transforme com o padrão: alternativas com os erros comuns (forma て errada, forma trocada). */
    private fun transform(ctx: android.content.Context, line: Line, t: Target, s: Spec, rnd: kotlin.random.Random): Item? {
        val right = t.results(s)
        val answer = right.first()
        val wrong = mutableListOf<String>()
        if (t.verb != null) {
            val (f, suf) = s.verb.first()
            if (f == V.TE || f == V.TA) wrong.addAll(Conj.wrongTe(t.word.base, past = f == V.TA).map { it + suf })
            wrong.addAll(wrongVerbForms(s, suf).filter { it != f && it != V.VOL && it != V.BA }.mapNotNull { t.verb[it]?.plus(suf) })
        } else {
            val (f, suf) = s.adj.first()
            wrong.addAll(A.values().filter { it != f }.mapNotNull { t.adj!![it]?.plus(suf) })
        }
        val opts = wrong.filter { it !in right }.distinct().shuffled(rnd).take(3)
        if (opts.size < 3) return null
        val name = s.point?.name ?: return null
        return Item.Drill(
            "Transforme com o padrão", line, s.id,
            "Use 「$name」 (${s.meaning}) com ${meaningOf(ctx, t)}:",
            answer, (opts + answer).shuffled(rnd), s.rule(), t.word, levelOf(s)
        )
    }

    /** Português → padrão: com a mesma palavra, qual forma tem o sentido pedido. */
    private fun meaning(ctx: android.content.Context, line: Line, t: Target, s: Spec, usable: List<Spec>, rnd: kotlin.random.Random): Item? {
        val answer = t.results(s).first()
        val others = usable.filter { it.id != s.id && it.meaning != s.meaning }.shuffled(rnd)
            .map { it to t.results(it).first() }.filter { it.second != answer }.distinctBy { it.second }.take(3)
        if (others.size < 3) return null
        val note = (others + (s to answer)).joinToString("\n") { (sp, form) -> "$form = ${sp.meaning}" }
        return Item.Drill(
            "Português → padrão", line, s.id,
            "Com ${meaningOf(ctx, t)}, qual forma significa «${s.meaning}»?",
            answer, (others.map { it.second } + answer).shuffled(rnd), note, t.word, levelOf(s)
        )
    }
}
