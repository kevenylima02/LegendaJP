package com.keveny.legendajp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Onde cada padrão aparece nas suas frases (gravações e textos). O resultado de cada arquivo fica em
 * files/grammar_index/<nome>.json (chave: tamanho do texto e versão do catálogo + banco), para não
 * refazer o Kuromoji; o último resultado fica também na memória, para a tela do padrão abrir na hora.
 */
object GrammarIndex {

    /** Quantas frases por padrão ficam guardadas para a ficha e para a sessão de estudo. */
    const val KEEP = 30

    class Mine(var count: Int, val lines: MutableList<Pair<StudyGen.Line, Grammar.Hit>>)

    @Volatile var last: Map<String, Mine>? = null
        private set

    private fun version() = "${Grammar.all.size}/${GrammarBank.all().size}/2"

    /** Chame fora da UI. */
    fun scan(ctx: Context): Map<String, Mine> {
        val byPoint = HashMap<String, Mine>()
        val dir = File(ctx.filesDir, "grammar_index").apply { mkdirs() }
        val seen = HashSet<String>()
        val v = version()
        for (item in (try { Recordings.list(ctx, ".txt") } catch (_: Exception) { emptyList() })) {
            val base = Recordings.baseName(item.name)
            val text = try { Recordings.readText(ctx, item.uri) } catch (_: Exception) { continue }
            val lines = StudyGen.lines(text, base)
            val f = File(dir, base.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".json")
            seen.add(f.name)
            var hits: JSONArray? = null
            if (f.exists()) try {
                val o = JSONObject(f.readText())
                if (o.optInt("len", -1) == text.length && o.optString("v") == v) hits = o.optJSONArray("hits")
            } catch (_: Exception) {}
            if (hits == null) {
                hits = JSONArray()
                for ((i, l) in lines.withIndex()) {
                    if (l.text.length < 4) continue
                    for (h in (try { Grammar.find(l.text) } catch (_: Exception) { emptyList() }))
                        hits.put(JSONArray().put(i).put(h.point.id).put(h.start).put(h.end))
                }
                try { f.writeText(JSONObject().put("len", text.length).put("v", v).put("hits", hits).toString()) } catch (_: Exception) {}
            }
            for (k in 0 until hits.length()) {
                val a = hits.optJSONArray(k) ?: continue
                val l = lines.getOrNull(a.optInt(0)) ?: continue
                val p = Grammar.point(a.optString(1)) ?: continue
                val m = byPoint.getOrPut(p.id) { Mine(0, mutableListOf()) }
                m.count++
                if (m.lines.size < KEEP) m.lines.add(l to Grammar.Hit(p, a.optInt(2), a.optInt(3)))
            }
        }
        dir.listFiles()?.forEach { if (it.name !in seen) it.delete() }
        last = byPoint
        return byPoint
    }
}
