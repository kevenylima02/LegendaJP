package com.keveny.legendajp

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Textos para leitura (colados, compartilhados ou tirados de foto). Ficam na mesma pasta das
 * gravações, como "texto_AAAAMMDD_HHMMSS.txt": uma frase por linha e "#p" entre parágrafos (linhas
 * com # são ignoradas pelo resto do app). Assim estudo, IA, reportes e áudio funcionam igual.
 */
object Texts {
    const val PREFIX = "texto_"
    const val PARAGRAPH = "#p"

    fun isText(name: String) = Recordings.baseName(name).startsWith(PREFIX)

    fun newBaseName(): String = PREFIX + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())

    /** Um parágrafo: frases (com o segundo no áudio, -1 sem áudio). */
    data class Sentence(val sec: Int, val text: String)

    /** Texto bruto → parágrafos de frases (linhas vazias separam parágrafos; 。！？ separam frases). */
    fun split(raw: String): List<List<String>> {
        val paragraphs = raw.replace("\r", "").split(Regex("\\n\\s*\\n"))
        return paragraphs.map { p ->
            // Dentro do parágrafo, quebras de linha simples (de foto ou PDF) não separam frases
            val joined = p.lines().joinToString("") { it.trim() }
            ImportText.splitSentences(joined).map { it.trim() }.filter { it.isNotEmpty() }
        }.filter { it.isNotEmpty() }
    }

    /** Cria o texto; devolve o nome do .txt. Chame fora da UI. */
    fun save(ctx: Context, title: String?, paragraphs: List<List<String>>): String {
        val base = newBaseName()
        val body = paragraphs.joinToString("\n$PARAGRAPH\n") { it.joinToString("\n") } + "\n"
        val uri = Recordings.create(ctx, "$base.txt", "text/plain") ?: throw IllegalStateException("Não consegui criar o arquivo")
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        if (!title.isNullOrBlank()) VocabDb.get(ctx).setRecordingTitle(base, title)
        return "$base.txt"
    }

    /** Lê os parágrafos (o índice da frase no arquivo é a ordem em que aparecem). */
    fun read(ctx: Context, name: String): List<List<Sentence>> {
        val item = Recordings.find(ctx, "${Recordings.baseName(name)}.txt") ?: return emptyList()
        val out = mutableListOf<MutableList<Sentence>>(mutableListOf())
        for (line in Recordings.readText(ctx, item.uri).lines()) {
            val t = line.trim()
            when {
                t == PARAGRAPH -> if (out.last().isNotEmpty()) out.add(mutableListOf())
                t.isEmpty() || t.startsWith("#") -> {}
                else -> { val (sec, s) = Recordings.parseLine(t); out.last().add(Sentence(sec, s)) }
            }
        }
        return out.filter { it.isNotEmpty() }
    }
}
