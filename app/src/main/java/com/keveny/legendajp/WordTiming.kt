package com.keveny.legendajp

import android.content.Context
import kotlin.math.sqrt

/**
 * Quando cada palavra da frase é dita no áudio, para a legenda que acende a palavra falada.
 * - Exato: gravações feitas desde a 0.19 guardam o tempo que o reconhecedor do Android informa
 *   (tabela timing), quando ele informa.
 * - Estimado: nas outras, acha o trecho com voz no WAV e reparte o tempo pelo tamanho da leitura
 *   de cada palavra (Kuromoji).
 */
object WordTiming {

    /** Trecho [start, end) da frase dito a partir de ms (no WAV). */
    data class Seg(val start: Int, val end: Int, val ms: Long)

    /** fromMs..toMs: trecho para tocar só a frase. */
    class Timing(val segs: List<Seg>, val exact: Boolean, val fromMs: Long, val toMs: Long)

    private const val BYTES_PER_MS = 32 // 16 kHz, 16 bits, mono
    private const val WAV_HEADER = 44

    fun encode(segs: List<Seg>) = segs.joinToString(",") { "${it.start}-${it.end}@${it.ms}" }

    fun decode(s: String): List<Seg> = s.split(',').mapNotNull { p ->
        val at = p.indexOf('@'); val dash = p.indexOf('-')
        if (at < 0 || dash < 0 || dash > at) null
        else try { Seg(p.substring(0, dash).toInt(), p.substring(dash + 1, at).toInt(), p.substring(at + 1).toLong()) } catch (_: Exception) { null }
    }

    /** Tempo das palavras da frase (sec/endSec: início e fim aproximados, em segundos). Chame fora da UI. */
    fun get(ctx: Context, recording: String?, sentence: String, sec: Int, endSec: Int): Timing? {
        if (sec < 0) return null
        val stored = recording?.let { VocabDb.get(ctx).timing(it, sentence) }?.let { decode(it) }
        if (!stored.isNullOrEmpty()) {
            val last = stored.last()
            val lastDur = 160L * (last.end - last.start) + 250
            return Timing(stored, true, maxOf(0, stored.first().ms - 300), last.ms + lastDur + 400)
        }
        return estimate(ctx, recording, sentence, sec, endSec)
    }

    private fun estimate(ctx: Context, recording: String?, sentence: String, sec: Int, endSec: Int): Timing {
        val winFrom = maxOf(0L, sec * 1000L - 700)
        val winTo = maxOf(winFrom + 1500, endSec * 1000L)
        val voice = recording?.let { try { speech(ctx, it, winFrom, winTo) } catch (_: Exception) { null } }
        val (from, to) = voice ?: (winFrom + 500 to winTo - 300)

        // Peso de cada pedaço: tamanho da leitura (≈ moras); pontuação vale uma pausa curta
        data class Piece(val start: Int, val end: Int, val weight: Double, val punct: Boolean)
        val pieces = mutableListOf<Piece>()
        var pos = 0
        val tokens = try { Jp.tokenize(sentence) } catch (_: Exception) { emptyList() }
        for (t in tokens) {
            val surf = t.surface
            val start = sentence.indexOf(surf, pos).takeIf { it >= 0 } ?: pos
            val end = minOf(sentence.length, start + surf.length)
            pos = end
            val punct = t.partOfSpeechLevel1 == "記号"
            val reading = t.reading?.takeIf { it != "*" } ?: surf
            val w = if (punct) 1.0 else reading.count { it !in "ャュョァィゥェォッ" }.coerceAtLeast(1).toDouble()
            pieces.add(Piece(start, end, w, punct))
        }
        if (pieces.isEmpty()) pieces.add(Piece(0, sentence.length, 1.0, false))
        val total = pieces.sumOf { it.weight }
        val dur = (to - from).coerceAtLeast(500)
        var acc = 0.0
        val segs = mutableListOf<Seg>()
        for (p in pieces) {
            if (!p.punct) segs.add(Seg(p.start, p.end, from + (dur * acc / total).toLong()))
            acc += p.weight
        }
        return Timing(segs, false, maxOf(0, from - 250), to + 250)
    }

    /** Começo e fim da voz entre from e to (ms), pela energia em janelas de 20 ms. */
    private fun speech(ctx: Context, recording: String, from: Long, to: Long): Pair<Long, Long>? {
        val item = Recordings.find(ctx, "$recording.wav") ?: return null
        val frame = 20 * BYTES_PER_MS
        val rms = ArrayList<Double>()
        ctx.contentResolver.openInputStream(item.uri)?.use { input ->
            var skip = WAV_HEADER + from * BYTES_PER_MS
            while (skip > 0) { val n = input.skip(skip); if (n <= 0) break; skip -= n }
            val buf = ByteArray(frame)
            var left = (to - from) * BYTES_PER_MS
            while (left >= frame) {
                var got = 0
                while (got < frame) { val n = input.read(buf, got, frame - got); if (n < 0) break; got += n }
                if (got < frame) break
                var sum = 0.0
                var i = 0
                while (i < frame) {
                    val v = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort().toDouble()
                    sum += v * v
                    i += 2
                }
                rms.add(sqrt(sum / (frame / 2)))
                left -= frame
            }
        } ?: return null
        if (rms.isEmpty()) return null
        val max = rms.max()
        val thr = maxOf(max * 0.12, 250.0)
        val first = rms.indexOfFirst { it >= thr }
        val last = rms.indexOfLast { it >= thr }
        if (first < 0 || last <= first) return null
        return (from + first * 20L) to (from + (last + 1) * 20L)
    }
}
