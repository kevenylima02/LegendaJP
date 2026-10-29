package com.keveny.legendajp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lista de gramática do Bunpro, guardada só no armazenamento do app (files/bunpro_grammar.json).
 * Nada dela está no código: vem do arquivo gerado por tools/bunpro/build_grammar.py (importar) ou
 * de um download quando você toca (uma requisição à página pública). Depois disso o app só lê o arquivo.
 */
object BunproData {

    data class Point(
        val id: Int, val slug: String, val title: String, val furigana: String, val meaning: String,
        val level: String, val keywords: String, val order: Int, val lesson: Int
    )

    const val PAGE = "https://bunpro.jp/grammar_points"
    /** Níveis na ordem dos filtros. */
    val LEVELS = listOf("N5", "N4", "N3", "N2", "N1", "Fora do JLPT", "Kansai-ben")
    private val levelMap = mapOf("JLPT5" to "N5", "JLPT4" to "N4", "JLPT3" to "N3", "JLPT2" to "N2", "JLPT1" to "N1",
        "Non-JLPT" to "Fora do JLPT", "関西弁" to "Kansai-ben")

    @Volatile private var cache: List<Point>? = null

    private fun file(ctx: Context) = File(ctx.filesDir, "bunpro_grammar.json")

    fun has(ctx: Context) = file(ctx).exists()

    /** Data em que a lista foi baixada (do arquivo), ou "". */
    fun fetched(ctx: Context): String = try { JSONObject(file(ctx).readText()).optString("fetched") } catch (_: Exception) { "" }

    /** Todos os pontos (vazio se ainda não importou). Chame fora da UI na primeira vez. */
    fun all(ctx: Context): List<Point> {
        cache?.let { return it }
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        val list = try { parse(JSONObject(f.readText())) } catch (_: Exception) { emptyList() }
        cache = list
        return list
    }

    private fun parse(o: JSONObject): List<Point> {
        val arr = o.optJSONArray("points") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val p = arr.optJSONObject(i) ?: return@mapNotNull null
            val title = p.optString("title").trim()
            if (title.isEmpty()) null else Point(
                p.optInt("id"), p.optString("slug"), title, p.optString("furigana").ifBlank { title }, p.optString("meaning"),
                p.optString("level").let { levelMap[it] ?: it }, p.optString("keywords"), p.optInt("order"), p.optInt("lesson")
            )
        }.sortedBy { it.order }
    }

    private fun save(ctx: Context, o: JSONObject): Int {
        val list = parse(o)
        if (list.isEmpty()) throw IllegalStateException("o arquivo não tem pontos de gramática")
        file(ctx).writeText(o.toString())
        cache = list
        linkCache = null
        return list.size
    }

    /** Importa o bunpro_grammar.json gerado pelo script. Devolve quantos pontos. */
    fun import(ctx: Context, input: InputStream): Int =
        save(ctx, JSONObject(input.bufferedReader(Charsets.UTF_8).use { it.readText() }))

    /** Baixa de novo da página pública (uma requisição). Chame fora da UI. */
    fun download(ctx: Context): Int {
        val conn = URL(PAGE).openConnection() as HttpURLConnection
        val html = try {
            conn.connectTimeout = 15000; conn.readTimeout = 60000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Michi, uso pessoal)")
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { conn.disconnect() }
        val tag = "<script id=\"__NEXT_DATA__\" type=\"application/json\">"
        val a = html.indexOf(tag); val b = if (a >= 0) html.indexOf("</script>", a) else -1
        if (a < 0 || b < 0) throw IllegalStateException("a página do Bunpro mudou de formato")
        val raw = JSONObject(html.substring(a + tag.length, b)).getJSONObject("props").getJSONObject("pageProps").optJSONArray("grammarPoints")
            ?: throw IllegalStateException("a página não trouxe a lista")
        val pts = JSONArray()
        for (i in 0 until raw.length()) {
            val g = raw.optJSONObject(i) ?: continue
            val title = g.optString("title").trim()
            if (title.isEmpty()) continue
            pts.put(JSONObject().put("id", g.optInt("id")).put("slug", g.optString("slug")).put("title", title)
                .put("furigana", g.optString("furigana").ifBlank { title }).put("meaning", g.optString("meaning"))
                .put("level", levelMap[g.optString("level")] ?: g.optString("level")).put("keywords", g.optString("metadata"))
                .put("order", g.optInt("grammar_order")).put("lesson", g.optInt("lesson_id")))
        }
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        return save(ctx, JSONObject().put("source", "bunpro.jp/grammar_points").put("fetched", today).put("points", pts))
    }

    // ---------- ligação com o catálogo do app ----------

    private fun norm(s: String): String {
        var t = s
        for (c in "～~〜 　①②③④⑤⑥⑦⑧⑨") t = t.replace(c.toString(), "")
        return t.trim()
    }

    /** Nomes pelos quais um ponto do Bunpro pode ser achado: título, variantes com ・, sem だ final, Verb［…］. */
    private fun keys(p: Point): List<String> {
        val out = mutableListOf<String>()
        val parts = mutableListOf(p.title)
        parts.addAll(p.title.split("・"))
        // "Verb［れる・られる］": só o que está entre colchetes, quando o título é só isso
        Regex("^[A-Za-z\\-]*[［\\[](.*)[］\\]]$").find(p.title.trim())?.let { m -> parts.addAll(m.groupValues[1].split("・")) }
        for (x in parts) {
            val n = norm(x)
            if (n.isEmpty()) continue
            out.add(n)
            if (n.endsWith("だ") && n.length > 2) out.add(n.dropLast(1))
        }
        out.add(norm(p.slug))
        return out
    }

    /** Padrões do app com nome diferente no Bunpro (id do app → slug do Bunpro). */
    private val alias = mapOf(
        "mada-te-inai" to "まだ-ていません", "mou-ta" to "もう", "rareru" to "Verb[passive]", "saseru" to "causative",
        "you" to "よう-おう", "adj-kunai" to "negative-い-adjectives", "imperative" to "命令形", "hou-ga" to "より-のほうが",
        "te-kuremasenka" to "ていただけませんか", "hou-ga-ii" to "たほうがいい", "tokoro" to "るところだ", "aida-ni" to "の間に",
        "tara-dou" to "たらどう", "nai" to "うverb--ない",
    )

    @Volatile private var linkCache: Map<String, Point>? = null

    /**
     * Para cada padrão do catálogo do app (que o app reconhece nas frases), o ponto do Bunpro
     * equivalente: "～なければならない／いけない" → なければならない. Chame fora da UI.
     */
    fun links(ctx: Context): Map<String, Point> {
        linkCache?.let { if (cache != null) return it }
        val list = all(ctx)
        if (list.isEmpty()) return emptyMap()
        val byKey = HashMap<String, Point>()
        for (p in list) for (k in keys(p)) byKey.putIfAbsent(k, p)
        val bySlug = list.associateBy { it.slug }
        val used = HashSet<Int>()
        val out = HashMap<String, Point>()
        for (c in Grammar.all) {
            var hit = alias[c.id]?.let { bySlug[it] }
            if (hit == null) {
                val name = c.name.substringBefore(" (").substringBefore("（")
                val alts = name.split("／").map { norm(it) }.filter { it.isNotEmpty() }
                hit = alts.firstNotNullOfOrNull { a -> byKey[a]?.takeIf { it.id !in used } }
            }
            if (hit != null && hit.id !in used) { out[c.id] = hit; used.add(hit.id) }
        }
        linkCache = out
        return out
    }
}
