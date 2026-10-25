package com.keveny.legendajp

import com.keveny.legendajp.StudyGen.Item
import org.json.JSONArray
import org.json.JSONObject

/** Serializa exercícios em JSON para a fila de erros (o exercício volta exatamente igual). */
object StudyCodec {

    private fun wordJson(w: Jp.Word) = JSONObject()
        .put("surface", w.surface).put("head", w.head).put("base", w.base).put("reading", w.reading)
        .put("pos", w.pos).put("posKey", w.posKey).put("conj", w.conjugation).put("start", w.start).put("end", w.end)

    private fun word(o: JSONObject) = Jp.Word(
        o.getString("surface"), o.getString("head"), o.getString("base"), o.getString("reading"),
        o.getString("pos"), o.getString("posKey"), o.getString("conj"), o.getInt("start"), o.getInt("end")
    )

    private fun hitJson(h: Grammar.Hit) = JSONObject().put("id", h.point.id).put("start", h.start).put("end", h.end)

    private fun hit(o: JSONObject): Grammar.Hit? =
        Grammar.point(o.getString("id"))?.let { Grammar.Hit(it, o.getInt("start"), o.getInt("end")) }

    private fun strings(a: JSONArray) = (0 until a.length()).map { a.getString(it) }

    fun encode(item: Item): String {
        val o = JSONObject()
            .put("sentence", item.sentence).put("sec", item.sec).put("endSec", item.endSec)
            .put("level", item.level).put("recording", item.recording ?: JSONObject.NULL)
        when (item) {
            is Item.Order -> o.put("t", "order").put("chunks", JSONArray(item.chunks))
            is Item.GrammarQ -> o.put("t", "gq").put("hit", hitJson(item.hit)).put("options", JSONArray(item.options.map { it.id }))
            is Item.GrammarCloze -> o.put("t", "gc").put("hit", hitJson(item.hit)).put("options", JSONArray(item.options))
            is Item.Cloze -> o.put("t", "cloze").put("word", wordJson(item.word)).put("options", JSONArray(item.options))
            is Item.Choice -> o.put("t", "choice").put("focus", item.focus.name).put("kind", item.kind)
                .put("prompt", item.prompt).put("answer", item.answer).put("options", JSONArray(item.options))
                .put("note", item.note).put("word", wordJson(item.word))
            is Item.Kanji -> o.put("t", "kanji").put("word", wordJson(item.word)).put("tiles", JSONArray(item.tiles))
            is Item.Write -> o.put("t", "write").put("word", wordJson(item.word))
            is Item.Flash -> o.put("t", "flash") // flashcards não vão para a fila de erros
            is Item.Parts -> o.put("t", "parts").put("word", wordJson(item.word)).put("kanji", item.kanji.toString())
                .put("parts", JSONArray(item.parts.map { it.encode() })).put("tiles", JSONArray(item.tiles.map { it.encode() }))
        }
        return o.toString()
    }

    fun decode(s: String): Item? {
        return try {
            val o = JSONObject(s)
            val line = StudyGen.Line(
                o.getInt("sec"), o.getString("sentence"), o.getInt("endSec"),
                if (o.isNull("recording")) null else o.getString("recording")
            )
            when (o.getString("t")) {
                "order" -> Item.Order(line, strings(o.getJSONArray("chunks")), o.getInt("level"))
                "gq" -> {
                    val h = hit(o.getJSONObject("hit")) ?: return null
                    Item.GrammarQ(line, h, strings(o.getJSONArray("options")).mapNotNull { Grammar.point(it) })
                }
                "gc" -> {
                    val h = hit(o.getJSONObject("hit")) ?: return null
                    Item.GrammarCloze(line, h, strings(o.getJSONArray("options")))
                }
                "cloze" -> Item.Cloze(line, word(o.getJSONObject("word")), strings(o.getJSONArray("options")))
                "choice" -> Item.Choice(
                    StudyGen.Focus.valueOf(o.getString("focus")), o.getString("kind"), line, o.getString("prompt"),
                    o.getString("answer"), strings(o.getJSONArray("options")), o.getString("note"), word(o.getJSONObject("word"))
                )
                "kanji" -> Item.Kanji(line, word(o.getJSONObject("word")), strings(o.getJSONArray("tiles")))
                "write" -> Item.Write(line, word(o.getJSONObject("word")))
                "parts" -> Item.Parts(
                    line, word(o.getJSONObject("word")), o.getString("kanji")[0],
                    strings(o.getJSONArray("parts")).mapNotNull { KanjiParts.decode(it) },
                    strings(o.getJSONArray("tiles")).mapNotNull { KanjiParts.decode(it) }
                )
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}
