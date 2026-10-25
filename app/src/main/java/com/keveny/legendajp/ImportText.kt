package com.keveny.legendajp

import android.content.Context

/**
 * Importar texto pronto (sem áudio) como uma gravação: formato Michi, legenda .srt ou texto simples.
 *
 * Formato Michi (uma frase por linha):
 *   # michi: 1
 *   # titulo: Pedindo um café
 *   # locutores: A=Yuki (feminino), B=Takeshi (masculino)
 *   A: いらっしゃいませ。 | Bem-vindo.
 *   B: コーヒーを一つお願いします。 | Um café, por favor.
 * Opcional: [mm:ss] no começo da linha; "X:" = locutor; " | " + tradução em português.
 */
object ImportText {

    const val TEMPLATE = """# michi: 1
# titulo: Pedindo um café
# locutores: A=Yuki (feminino), B=Takeshi (masculino)
A: いらっしゃいませ。ご注文はお決まりですか。 | Bem-vindo. Já decidiu o pedido?
B: コーヒーを一つお願いします。 | Um café, por favor.
A: ホットとアイス、どちらになさいますか。 | Quente ou gelado?
B: ホットでお願いします。 | Quente, por favor."""

    /** Instruções para pedir a outra IA um texto neste formato (copiar e colar). */
    const val PROMPT = """Converta o conteúdo abaixo para o formato de importação do app Michi (japonês).
Regras:
1. A primeira linha é: # michi: 1
2. Depois, opcionalmente: # titulo: <título curto em português>
3. Se houver mais de uma pessoa falando: # locutores: A=<nome> (feminino|masculino), B=<nome> (feminino|masculino)
   Use letras A, B, C… como identificadores.
4. Depois, uma frase em japonês por linha (no máximo uma ou duas orações; quebre frases longas).
5. Em cada linha: opcionalmente o locutor no começo ("A: "), a frase em japonês, e opcionalmente " | " e a tradução natural em português do Brasil.
6. Se souber o tempo em que a frase é dita no áudio, comece a linha com [mm:ss].
7. Sem markdown, sem comentários, sem linhas extras. Linhas que começam com # são só informação.
Exemplo:
""" + TEMPLATE

    data class PLine(val sec: Int?, val speaker: String?, val ja: String, val pt: String?)
    data class Parsed(val title: String?, val speakers: List<Speakers.Info>, val lines: List<PLine>)

    private val stamp = Regex("^\\[(\\d+):(\\d{2})(?::(\\d{2}))?]\\s*")
    private val speakerPrefix = Regex("^([A-Za-zＡ-Ｚ]{1,2}|[^:：\\s]{1,12})\\s*[:：]\\s*")
    private val srtTime = Regex("(\\d{2}):(\\d{2}):(\\d{2})[,.](\\d{3})\\s*-->")

    fun parse(text: String): Parsed {
        val raw = text.replace("\r", "").trim()
        if (srtTime.containsMatchIn(raw)) return parseSrt(raw)
        var title: String? = null
        val infos = LinkedHashMap<String, Speakers.Info>()
        val out = mutableListOf<PLine>()
        for (line0 in raw.lines()) {
            val line = line0.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                val body = line.trimStart('#').trim()
                val k = body.substringBefore(':').trim().lowercase()
                val v = body.substringAfter(':', "").trim()
                when (k) {
                    "titulo", "título", "title" -> title = v.ifBlank { null }
                    "locutores", "speakers" -> for (part in v.split(',', '、')) {
                        val id = part.substringBefore('=').trim().uppercase()
                        if (id.isEmpty()) continue
                        val rest = part.substringAfter('=', id).trim()
                        val name = rest.substringBefore('(').trim().ifEmpty { id }
                        val g = rest.lowercase()
                        infos[id] = Speakers.Info(id, name, when { "femin" in g -> true; "mascul" in g -> false; else -> null })
                    }
                }
                continue
            }
            var rest = line
            var sec: Int? = null
            stamp.find(rest)?.let { m ->
                val (a, b, c) = m.destructured
                sec = if (c.isNotEmpty()) a.toInt() * 3600 + b.toInt() * 60 + c.toInt() else a.toInt() * 60 + b.toInt()
                rest = rest.substring(m.range.last + 1)
            }
            var speaker: String? = null
            speakerPrefix.find(rest)?.let { m ->
                val id = m.groupValues[1]
                // Só vale como locutor: A, B… ou um nome declarado em "# locutores" (「注意：…」 continua sendo frase)
                if (Regex("^[A-Za-zＡ-Ｚ]{1,2}$").matches(id) || infos.values.any { it.name == id }) {
                    val key = infos.values.firstOrNull { it.name == id }?.id ?: id.uppercase()
                    speaker = key
                    if (key !in infos) infos[key] = Speakers.Info(key, key, null)
                    rest = rest.substring(m.range.last + 1)
                }
            }
            val ja = rest.substringBefore(" | ").substringBefore("｜").trim()
            val pt = if (" | " in rest) rest.substringAfter(" | ").trim() else if ("｜" in rest) rest.substringAfter("｜").trim() else null
            if (ja.isEmpty()) continue
            // Texto simples: várias frases numa linha viram linhas separadas
            val pieces = if (pt == null && sec == null) splitSentences(ja) else listOf(ja)
            for (p in pieces) out.add(PLine(sec, speaker, p, if (pieces.size == 1) pt?.ifBlank { null } else null))
        }
        return Parsed(title, infos.values.toList(), out)
    }

    private fun parseSrt(raw: String): Parsed {
        val out = mutableListOf<PLine>()
        for (block in raw.split(Regex("\\n\\s*\\n"))) {
            val ls = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val ti = ls.indexOfFirst { srtTime.containsMatchIn(it) }
            if (ti < 0) continue
            val m = srtTime.find(ls[ti])!!
            val (h, mi, s, _) = m.destructured
            val sec = h.toInt() * 3600 + mi.toInt() * 60 + s.toInt()
            val text = ls.drop(ti + 1).joinToString("").replace(Regex("<[^>]+>"), "").trim()
            if (text.isNotEmpty()) out.add(PLine(sec, null, text, null))
        }
        return Parsed(null, emptyList(), out)
    }

    /** Separa por 。！？ mantendo a pontuação. */
    fun splitSentences(s: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (c in s) {
            sb.append(c)
            if (c in "。！？!?") { val t = sb.toString().trim(); if (t.isNotEmpty()) out.add(t); sb.clear() }
        }
        sb.toString().trim().takeIf { it.isNotEmpty() }?.let { out.add(it) }
        return out
    }

    /** Cria a gravação (só o .txt), título, locutores e traduções. Devolve o nome do .txt. Chame fora da UI. */
    fun save(ctx: Context, p: Parsed): String {
        val base = Recordings.newBaseName()
        val body = p.lines.joinToString("\n") { l ->
            (if (l.sec != null) "[${CaptureService.formatTime(l.sec)}] " else "") + l.ja
        } + "\n"
        val uri = Recordings.create(ctx, "$base.txt", "text/plain") ?: throw IllegalStateException("Não consegui criar o arquivo")
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val db = VocabDb.get(ctx)
        p.title?.let { db.setRecordingTitle(base, it) }
        for (l in p.lines) if (l.pt != null) db.putAiTranslation(l.ja, l.pt)
        val byLine = p.lines.withIndex().filter { it.value.speaker != null }.associate { it.index to it.value.speaker!! }
        if (byLine.isNotEmpty()) Speakers.save(ctx, base, byLine, p.speakers)
        return "$base.txt"
    }
}
