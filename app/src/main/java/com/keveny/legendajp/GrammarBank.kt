package com.keveny.legendajp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Banco de gramática que vai dentro do app (assets/grammar_bank.json; formato em tools/grammar/FORMATO.md):
 * todos os padrões de N5 a N1, Fora do JLPT e Kansai-ben, cada um com explicação em português,
 * como se forma, exemplos com o trecho do padrão marcado, alternativas erradas para as lacunas,
 * padrões parecidos, erros comuns, o exercício de conjugação (quando o padrão é forma + sufixo) e os
 * textos que indicam o padrão numa frase. Escrito uma vez; não usa IA nem internet no celular.
 *
 * Identidade: uma ficha ligada a um padrão do catálogo do app ("app") usa o id do catálogo; as
 * outras usam o próprio id ("bp:123"), e viram Grammar.Point para o resto do app (Grammar.point).
 */
object GrammarBank {

    class Ex(val jp: String, val pt: String, val target: String, val wrong: List<String>)
    class Similar(val pattern: String, val diff: String)

    class Entry(
        val id: String, val title: String, val reading: String, val romaji: String, val level: String, val order: Int,
        /** id do padrão do catálogo do app (com regras próprias de reconhecimento e exercícios), se houver. */
        val app: String?,
        val summary: String, val meaning: String, val attaches: List<String>, val formation: List<String>, val usage: String,
        val examples: List<Ex>, val similar: List<Similar>, val errors: List<String>,
        val drill: GrammarDrill.Spec?, val detect: List<String>
    ) {
        /** Id usado no resto do app (Srs, exercícios, reconhecimento). */
        val key: String get() = app ?: id
        /** Nome para mostrar: o do catálogo do app, se ligado; senão o título. */
        val name: String get() = app?.let { Grammar.point(it)?.name } ?: title
    }

    /** Rótulos dos códigos de "liga_a". */
    val ATTACH = mapOf(
        "radical_masu" to "radical da forma ます", "dicionario" to "forma de dicionário", "te" to "forma て", "ta" to "forma た",
        "nai" to "forma ない", "volitiva" to "forma volitiva", "ba" to "forma ば", "tara" to "forma たら", "passiva" to "forma passiva",
        "causativa" to "forma causativa", "potencial" to "forma potencial", "imperativa" to "forma imperativa",
        "forma_simples" to "forma simples (dicionário, た, ない)", "substantivo" to "substantivo", "adjetivo_i" to "adjetivo-い",
        "adjetivo_na" to "adjetivo-な", "frase" to "frase ou oração", "inicio_de_frase" to "início da frase", "sozinho" to "usado sozinho",
    )

    private class Data(val list: List<Entry>, val byKey: Map<String, Entry>, val points: Map<String, Grammar.Point>, val detectable: List<Entry>,
                       val translations: Map<String, String>)

    @Volatile private var data: Data? = null
    @Volatile private var app: Context? = null

    /** Guarda o contexto (MichiApp) para carregar sob demanda. */
    fun init(ctx: Context) { app = ctx.applicationContext }

    private fun data(): Data? {
        data?.let { return it }
        val ctx = app ?: return null
        synchronized(this) {
            data?.let { return it }
            val d = try { load(ctx) } catch (e: Exception) {
                android.util.Log.e("MichiBank", "load", e); Data(emptyList(), emptyMap(), emptyMap(), emptyList(), emptyMap())
            }
            data = d
            return d
        }
    }

    fun all(): List<Entry> = data()?.list ?: emptyList()

    /** Ficha pelo id usado no app (id do catálogo ou "bp:123"). */
    fun entry(key: String): Entry? = data()?.byKey?.get(key)

    /** Padrão (Grammar.Point) de uma ficha que não está no catálogo do app. */
    fun point(id: String): Grammar.Point? = if (id.startsWith("bp:")) data()?.points?.get(id) else null

    /** Tradução de uma frase de exemplo do banco, se for uma delas. */
    fun translation(sentence: String): String? = data()?.translations?.get(sentence)

    fun spec(id: String): GrammarDrill.Spec? = if (id.startsWith("bp:")) entry(id)?.drill else null

    /**
     * Padrões do banco (os que não estão no catálogo) achados pelos textos de "detectar": o trecho
     * precisa começar onde começa uma palavra (posições dos tokens) e terminar onde uma termina, ou num
     * kanji (へ行 de へ行く): ていた não conta dentro de いただいて.
     */
    fun find(sentence: String, tokenStarts: Set<Int>, seen: MutableSet<String>): List<Grammar.Hit> {
        fun endsOk(i: Int, n: Int): Boolean {
            val e = i + n
            return e >= sentence.length || e in tokenStarts || Character.UnicodeBlock.of(sentence[e - 1]) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
        }
        val d = data() ?: return emptyList()
        val out = mutableListOf<Grammar.Hit>()
        for (e in d.detectable) {
            if (e.key in seen) continue
            var best = -1; var len = 0
            for (s in e.detect) {
                var i = sentence.indexOf(s)
                while (i >= 0 && (i !in tokenStarts || !endsOk(i, s.length))) i = sentence.indexOf(s, i + 1)
                if (i >= 0 && (best < 0 || i < best || (i == best && s.length > len))) { best = i; len = s.length }
            }
            if (best >= 0) {
                val p = d.points[e.key] ?: continue
                out.add(Grammar.Hit(p, best, best + len))
            }
        }
        // Um achado dentro de outro maior não conta: どうして dentro de どうしても
        val kept = out.filter { h -> out.none { o -> o !== h && o.start <= h.start && o.end >= h.end && (o.end - o.start) > (h.end - h.start) } }
        for (h in kept) seen.add(h.point.id)
        return kept
    }

    /**
     * Lacunas com os exemplos do banco (o trecho do padrão vira ＿＿＿; alternativas: as erradas escritas
     * para aquela frase). A tradução vem do banco (translation).
     */
    fun clozes(ctx: Context, keys: Set<String>, rnd: kotlin.random.Random): List<StudyGen.Item> {
        val out = mutableListOf<StudyGen.Item>()
        for (k in keys) {
            val e = entry(k) ?: continue
            val p = Grammar.point(e.key) ?: continue
            for (x in e.examples) {
                val at = x.jp.indexOf(x.target)
                if (at < 0 || x.wrong.size < 3) continue
                out.add(StudyGen.Item.GrammarCloze(StudyGen.Line(-1, x.jp, -1), Grammar.Hit(p, at, at + x.target.length),
                    (x.wrong.take(3) + x.target).shuffled(rnd)))
            }
        }
        return out
    }

    // ---------- leitura do arquivo ----------

    private fun strs(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() }

    private fun load(ctx: Context): Data {
        val text = ctx.assets.open("grammar_bank.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = JSONObject(text).getJSONArray("points")
        val list = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.getString("id")
            val appId = o.optString("app").ifBlank { null }?.takeIf { Grammar.isCatalog(it) }
            val key = appId ?: id
            val ex = o.optJSONArray("exemplos")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { x -> Ex(x.optString("jp"), x.optString("pt"), x.optString("alvo"), strs(x.optJSONArray("erradas"))) }
            } ?: emptyList()
            val sim = o.optJSONArray("parecidos")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Similar(it.optString("padrao"), it.optString("diferenca")) }
            } ?: emptyList()
            list.add(Entry(
                id, o.optString("titulo"), o.optString("leitura"), o.optString("romaji"), o.optString("nivel"), o.optInt("ordem"), appId,
                o.optString("resumo"), o.optString("significado"), strs(o.optJSONArray("liga_a")), strs(o.optJSONArray("como_formar")),
                o.optString("uso"), ex, sim, strs(o.optJSONArray("erros_comuns")),
                if (appId == null) o.optJSONObject("drill")?.let { drill(key, it) } else null,
                if (appId == null) strs(o.optJSONArray("detectar")).filter { it.length >= 2 } else emptyList()
            ))
        }
        val byKey = HashMap<String, Entry>()
        for (e in list) { byKey.putIfAbsent(e.key, e); byKey.putIfAbsent(e.id, e) }
        val points = list.filter { it.app == null }.associate {
            it.id to Grammar.Point(it.id, it.title, it.level, it.meaning.ifBlank { it.summary }, it.examples.firstOrNull()?.jp ?: "")
        }
        // Textos mais longos primeiro: ～わけにはいかない antes de ～わけ
        val detectable = list.filter { it.detect.isNotEmpty() }.sortedByDescending { e -> e.detect.maxOf { it.length } }
        val tr = HashMap<String, String>()
        for (e in list) for (x in e.examples) tr.putIfAbsent(x.jp, x.pt)
        return Data(list, byKey, points, detectable, tr)
    }

    /** Exercício de conjugação da ficha, só se o resultado bate com o conjugador do app (campo "teste"). */
    private fun drill(key: String, o: JSONObject): GrammarDrill.Spec? = try {
        fun pairs(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONArray(it) }.map { it.getString(0) to it.optString(1) }
        val verb = pairs(o.optJSONArray("verbo")).map { (f, s) -> Conj.V.valueOf(f) to s }
        val adj = pairs(o.optJSONArray("adj")).map { (f, s) -> Conj.A.valueOf(f) to s }
        val test = o.optJSONObject("teste") ?: JSONObject()
        var ok = verb.isNotEmpty() || adj.isNotEmpty()
        if (verb.isNotEmpty()) {
            val (f, suf) = verb.first()
            for ((base, k) in listOf("かく" to Conj.Kind.GODAN, "たべる" to Conj.Kind.ICHIDAN, "する" to Conj.Kind.SURU, "くる" to Conj.Kind.KURU)) {
                val got = Conj.verb(base, k)[f]?.plus(suf)
                if (got == null || got != test.optString(base)) ok = false
            }
        }
        if (adj.isNotEmpty()) {
            val (f, suf) = adj.first()
            if (Conj.adjForms("たかい")[f]?.plus(suf) != test.optString("たかい")) ok = false
        }
        if (ok) GrammarDrill.Spec(key, verb, adj, o.optString("sentido").ifBlank { "…" }) else null
    } catch (_: Exception) { null }
}
