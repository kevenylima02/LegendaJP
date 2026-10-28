package com.keveny.legendajp

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Textos para leitura (colados, compartilhados ou tirados de foto). Ficam na mesma pasta das
 * gravações, como "texto_AAAAMMDD_HHMMSS.txt": uma frase por linha; a estrutura da página vai em
 * linhas com # (que o resto do app ignora, então estudo, IA, áudio e reportes funcionam igual):
 *   #p            separa parágrafos
 *   #h1 Título    título (#h2, #h3 menores)
 *   #li           o parágrafo seguinte é um item de lista
 *   #table [header] … #row … #cell … #endtable   tabela: linhas, células, e as frases dentro da célula
 *   #rb 0-2=かんじ;…   leitura dada pelo autor (ruby) para trechos da frase seguinte
 * O usuário escreve (ou o Gemini devolve) Markdown leve — # título, | tabela |, - item, linha vazia
 * entre parágrafos — e [toLines] converte para esse formato. Ruby no estilo Aozora (｜漢字《かんじ》 ou
 * 漢字《かんじ》) e HTML (<ruby>漢字<rt>かんじ</rt></ruby>) vira #rb; notas ［＃…］ do Aozora são removidas.
 */
object Texts {
    const val PREFIX = "texto_"
    const val PARAGRAPH = "#p"

    fun isText(name: String) = Recordings.baseName(name).startsWith(PREFIX)

    fun newBaseName(): String = PREFIX + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())

    /** Uma frase (com o segundo no áudio, -1 sem áudio) e as leituras dadas pelo autor (ruby), se houver. */
    data class Sentence(val sec: Int, val text: String, val ruby: List<Furigana.Ruby> = emptyList())

    /** Blocos da página, na ordem. */
    sealed class Block {
        class Para(val sentences: List<Sentence>, val bullet: Boolean = false) : Block()
        class Heading(val text: String, val level: Int, val ruby: List<Furigana.Ruby> = emptyList()) : Block()
        /** rows → células → frases; header = a primeira linha é cabeçalho. */
        class Table(val rows: List<List<List<Sentence>>>, val header: Boolean) : Block()
    }

    private val headingRe = Regex("^(#{1,3})\\s+(.+)$")
    private val bulletRe = Regex("^([-*・•]|\\d+[.)])\\s*(.+)$")

    private fun sentences(s: String): List<String> = ImportText.splitSentences(s).map { it.trim() }.filter { it.isNotEmpty() }

    private val rubyHtml = Regex("<ruby>(.*?)<rt>(.*?)</rt></ruby>", RegexOption.IGNORE_CASE)
    private val rubyBar = Regex("[｜|]([^《｜|]+)《([^》]+)》")
    private val rubyKanji = Regex("([\\u3400-\\u9FFF々〆ヶ]+)《([^》]+)》")
    private val aozoraNote = Regex("［＃[^］]*］")

    /** Tira o ruby do texto e devolve o texto limpo com as leituras (posições no texto limpo). */
    fun extractRuby(s: String): Pair<String, List<Furigana.Ruby>> {
        var t = aozoraNote.replace(s, "")
        t = rubyHtml.replace(t) { m -> "｜" + m.groupValues[1].replace(Regex("<rp>.*?</rp>", RegexOption.IGNORE_CASE), "") + "《" + m.groupValues[2] + "》" }
        if (!t.contains('《')) return t to emptyList()
        val out = mutableListOf<Furigana.Ruby>()
        val sb = StringBuilder()
        var i = 0
        val all = (rubyBar.findAll(t).map { it to true } + rubyKanji.findAll(t).map { it to false })
            .sortedBy { it.first.range.first }
        var last = -1
        for ((m, _) in all) {
            if (m.range.first <= last) continue   // sobreposição (o mesmo trecho pelas duas regras)
            sb.append(t, i, m.range.first)
            val base = m.groupValues[1]
            val a = sb.length
            sb.append(base)
            out.add(Furigana.Ruby(a, sb.length, m.groupValues[2].trim()))
            i = m.range.last + 1
            last = m.range.last
        }
        sb.append(t, i, t.length)
        // 《…》 que sobrou sem base vira nada
        return sb.toString().replace(Regex("《[^》]*》"), "") to out
    }

    /** Linhas do arquivo para uma frase: "#rb …" (se tiver ruby) e a frase limpa. */
    private fun sentenceLines(raw: String): List<String> {
        val (clean, ruby) = extractRuby(raw)
        if (clean.isBlank()) return emptyList()
        return if (ruby.isEmpty()) listOf(clean) else listOf("#rb " + ruby.joinToString(";") { "${it.start}-${it.end}=${it.reading}" }, clean)
    }

    private fun parseRb(t: String): List<Furigana.Ruby> = t.removePrefix("#rb").trim().split(";").mapNotNull { part ->
        val m = Regex("^(\\d+)-(\\d+)=(.+)$").find(part.trim()) ?: return@mapNotNull null
        Furigana.Ruby(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3])
    }

    /**
     * Texto bruto (colado, OCR ou Markdown leve do Gemini) → linhas do arquivo. Linhas vazias separam
     * parágrafos; dentro do parágrafo, quebras simples (de foto ou PDF) não separam frases; 。！？ separam.
     */
    fun toLines(raw: String): List<String> {
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        var bullet = false
        val table = mutableListOf<String>()
        fun flush() {
            if (buf.isNotBlank()) {
                if (out.isNotEmpty() && out.last() != PARAGRAPH) out.add(PARAGRAPH)
                if (bullet) out.add("#li")
                for (s in sentences(buf.toString())) out.addAll(sentenceLines(s))
            }
            buf.clear(); bullet = false
        }
        fun flushTable() { if (table.isNotEmpty()) { out.addAll(tableLines(table)); table.clear() } }
        for (line in raw.replace("\r", "").lines()) {
            val t = line.trim()
            if (t.startsWith("|")) { flush(); table.add(t); continue } else flushTable()
            val h = headingRe.find(t)
            val b = bulletRe.find(t)
            when {
                t.isEmpty() -> flush()
                h != null -> {
                    flush()
                    val (clean, ruby) = extractRuby(h.groupValues[2].trim())
                    if (ruby.isNotEmpty()) out.add("#rb " + ruby.joinToString(";") { "${it.start}-${it.end}=${it.reading}" })
                    out.add("#h${h.groupValues[1].length} $clean")
                }
                b != null && !bullet -> { flush(); bullet = true; buf.append(b.groupValues[2]) }
                b != null -> { flush(); bullet = true; buf.append(b.groupValues[2]) }
                else -> buf.append(t)
            }
        }
        flush(); flushTable()
        return out
    }

    private fun tableLines(rows: List<String>): List<String> {
        val cellsRows = mutableListOf<List<String>>()
        var header = false
        for ((i, r) in rows.withIndex()) {
            val cells = r.removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
            val separator = cells.any { it.contains("-") } && cells.all { c -> c.all { it == '-' || it == ':' || it == ' ' } }
            if (separator) { if (i == 1) header = true; continue }
            cellsRows.add(cells)
        }
        if (cellsRows.isEmpty()) return emptyList()
        val out = mutableListOf(if (header) "#table header" else "#table")
        for (cells in cellsRows) {
            out.add("#row")
            for (c in cells) { out.add("#cell"); for (s in sentences(c)) out.addAll(sentenceLines(s)) }
        }
        out.add("#endtable")
        return out
    }

    /** Cria o texto; devolve o nome do .txt. Chame fora da UI. */
    fun save(ctx: Context, title: String?, lines: List<String>): String {
        val base = newBaseName()
        val body = lines.joinToString("\n") + "\n"
        val uri = Recordings.create(ctx, "$base.txt", "text/plain") ?: throw IllegalStateException("Não consegui criar o arquivo")
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        if (!title.isNullOrBlank()) VocabDb.get(ctx).setRecordingTitle(base, title)
        return "$base.txt"
    }

    /** Lê os blocos da página (títulos, parágrafos, listas e tabelas). */
    fun readBlocks(ctx: Context, name: String): List<Block> {
        val item = Recordings.find(ctx, "${Recordings.baseName(name)}.txt") ?: return emptyList()
        val blocks = mutableListOf<Block>()
        var para = mutableListOf<Sentence>()
        var bullet = false
        var table: MutableList<MutableList<MutableList<Sentence>>>? = null
        var header = false
        var pendingRuby: List<Furigana.Ruby> = emptyList()
        fun closePara() {
            if (para.isNotEmpty()) blocks.add(Block.Para(para.toList(), bullet))
            para = mutableListOf(); bullet = false
        }
        for (line in Recordings.readText(ctx, item.uri).lines()) {
            val t = line.trim()
            when {
                t == PARAGRAPH -> closePara()
                t.startsWith("#table") -> { closePara(); table = mutableListOf(); header = t.endsWith("header") }
                t == "#row" -> table?.add(mutableListOf())
                t == "#cell" -> table?.lastOrNull()?.add(mutableListOf())
                t == "#endtable" -> {
                    table?.let { tb -> if (tb.isNotEmpty()) blocks.add(Block.Table(tb.map { r -> r.map { c -> c.toList() } }, header)) }
                    table = null
                }
                t.startsWith("#h") && t.length > 3 && t[2].isDigit() -> {
                    closePara(); blocks.add(Block.Heading(t.substring(3).trim(), t[2] - '0', pendingRuby)); pendingRuby = emptyList()
                }
                t == "#li" -> { closePara(); bullet = true }
                t.startsWith("#rb ") -> pendingRuby = parseRb(t)
                t.isEmpty() || t.startsWith("#") -> {}
                else -> {
                    val (sec, s) = Recordings.parseLine(t)
                    val sent = Sentence(sec, s, pendingRuby.filter { it.end <= s.length })
                    pendingRuby = emptyList()
                    val tb = table
                    if (tb != null) tb.lastOrNull()?.lastOrNull()?.add(sent) else para.add(sent)
                }
            }
        }
        closePara()
        return blocks
    }

    /** Só as frases, em grupos (parágrafos e células), na ordem em que aparecem. */
    fun groups(blocks: List<Block>): List<List<Sentence>> = blocks.flatMap { b ->
        when (b) {
            is Block.Para -> listOf(b.sentences)
            is Block.Heading -> emptyList()
            is Block.Table -> b.rows.flatten().filter { it.isNotEmpty() }
        }
    }

    /** Lê os parágrafos (e células) de frases. */
    fun read(ctx: Context, name: String): List<List<Sentence>> = groups(readBlocks(ctx, name))
}
