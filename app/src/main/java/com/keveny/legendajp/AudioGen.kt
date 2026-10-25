package com.keveny.legendajp

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Gera o áudio de uma gravação que só tem texto (importada) e também serve para refazer o áudio.
 * - Celular: TextToSpeech do Android (grátis, offline), uma frase por vez, uma voz por locutor.
 * - Gemini: gemini-3.8-flash-lite-tts, blocos de até 12 frases (até 2 vozes por bloco); o áudio do
 *   bloco é dividido nas pausas. O plano grátis aceita 3 pedidos por minuto: o app espera e tenta de novo.
 * Cada frase começa num segundo exato e o .txt ganha os tempos [mm:ss].
 */
object AudioGen {
    const val PHONE = "celular"
    const val GEMINI = "gemini"
    private const val SR = 16000
    private const val TAG = "MichiAudio"
    private const val GEMINI_TTS = "gemini-3.8-flash-lite-tts"
    private val femaleVoices = listOf("Kore", "Aoede", "Leda", "Zephyr")
    private val maleVoices = listOf("Puck", "Charon", "Orus", "Fenrir")
    private val main = Handler(Looper.getMainLooper())

    @Volatile var stopRequested = false

    /**
     * progress(texto, feitas, total) e done(erro ou null) rodam na thread principal.
     */
    fun generate(ctx: Context, txtName: String, engine: String, progress: (String, Int, Int) -> Unit, done: (String?) -> Unit) {
        val app = ctx.applicationContext
        stopRequested = false
        Thread {
            val err = try {
                run(app, txtName, engine) { msg, a, b -> main.post { progress(msg, a, b) } }
                null
            } catch (e: Exception) {
                Log.e(TAG, "generate", e)
                e.message ?: e.javaClass.simpleName
            }
            main.post { done(err) }
        }.start()
    }

    private fun run(ctx: Context, txtName: String, engine: String, progress: (String, Int, Int) -> Unit) {
        val base = Recordings.baseName(txtName)
        val item = Recordings.find(ctx, "$base.txt") ?: throw IllegalStateException("Transcrição não encontrada")
        val texts = Recordings.readText(ctx, item.uri).lines().filter { it.isNotBlank() && !it.startsWith("#") }
            .map { Recordings.parseLine(it).second.trim() }.filter { it.isNotEmpty() }
        if (texts.isEmpty()) throw IllegalStateException("A transcrição está vazia")
        val bySpeaker = Speakers.lines(ctx, base)
        val infos = Speakers.infos(ctx, base).associateBy { it.id }
        val ids = (bySpeaker.values.distinct().sorted()).ifEmpty { listOf("A") }

        val segs: List<ShortArray> = if (engine == GEMINI) gemini(ctx, texts, bySpeaker, infos, ids, progress)
            else phone(ctx, texts, bySpeaker, ids, progress)
        if (stopRequested) throw IllegalStateException("Parado")

        // Monta o WAV: cada frase começa num segundo exato (com pelo menos 0,35 s de pausa antes)
        progress("Montando o áudio…", texts.size, texts.size)
        val out = ByteArrayOutputStream()
        var pos = 0L
        val secs = IntArray(texts.size)
        for ((i, seg) in segs.withIndex()) {
            val start = ceil((pos + SR * 0.35) / SR).toLong() * SR
            writeSilence(out, (start - pos).toInt()); pos = start
            secs[i] = (start / SR).toInt()
            writeSamples(out, seg); pos += seg.size
        }
        writeSilence(out, SR * 6 / 10)
        val pcm = out.toByteArray()

        Recordings.find(ctx, "$base.wav")?.let { Recordings.delete(ctx, it.uri) }
        val wavUri = Recordings.create(ctx, "$base.wav", "audio/wav") ?: throw IllegalStateException("Não consegui criar o WAV")
        ctx.contentResolver.openOutputStream(wavUri)!!.use { it.write(wavHeader(pcm.size.toLong())); it.write(pcm) }
        val body = texts.indices.joinToString("\n") { "[${CaptureService.formatTime(secs[it])}] ${texts[it]}" } + "\n"
        ctx.contentResolver.openOutputStream(item.uri, "wt")!!.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        VocabDb.get(ctx).deleteTiming(base)
    }

    // =====================================================================
    // Celular (TextToSpeech)
    // =====================================================================

    private fun phone(ctx: Context, texts: List<String>, bySpeaker: Map<Int, String>, ids: List<String>, progress: (String, Int, Int) -> Unit): List<ShortArray> {
        val ready = CountDownLatch(1)
        var status = TextToSpeech.ERROR
        val tts = TextToSpeech(ctx) { st -> status = st; ready.countDown() }
        try {
            ready.await(15, TimeUnit.SECONDS)
            if (status != TextToSpeech.SUCCESS) throw IllegalStateException("O leitor de texto do celular não abriu")
            val lang = tts.setLanguage(Locale.JAPAN)
            if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                throw IllegalStateException("Falta a voz japonesa: Configurações do Android → Saída de texto para voz → instale japonês")
            }
            val voices = tts.voices?.filter { it.locale.language == "ja" && !it.isNetworkConnectionRequired }?.sortedBy { it.name } ?: emptyList()
            val latches = ConcurrentHashMap<String, CountDownLatch>()
            val failed = ConcurrentHashMap.newKeySet<String>()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {}
                override fun onDone(utteranceId: String) { latches[utteranceId]?.countDown() }
                @Deprecated("API antiga") override fun onError(utteranceId: String) { failed.add(utteranceId); latches[utteranceId]?.countDown() }
                override fun onError(utteranceId: String, errorCode: Int) { failed.add(utteranceId); latches[utteranceId]?.countDown() }
            })
            val out = mutableListOf<ShortArray>()
            for ((i, text) in texts.withIndex()) {
                if (stopRequested) break
                progress("Gerando a voz (celular)…", i, texts.size)
                val sp = ids.indexOf(bySpeaker[i] ?: ids.first()).coerceAtLeast(0)
                // Uma voz por locutor; se o celular tiver menos vozes, muda o tom
                if (voices.isNotEmpty()) tts.voice = voices[sp % voices.size]
                tts.setPitch(if (voices.size > sp) 1.0f else if (sp % 2 == 0) 0.9f else 1.15f)
                val f = File(ctx.cacheDir, "tts_$i.wav")
                val id = "michi_$i"
                val latch = CountDownLatch(1)
                latches[id] = latch
                tts.synthesizeToFile(text, Bundle(), f, id)
                latch.await(60, TimeUnit.SECONDS)
                if (id in failed || !f.exists()) throw IllegalStateException("O celular não conseguiu gerar a frase ${i + 1}")
                out.add(readWav(f.readBytes()))
                f.delete()
            }
            return out
        } finally {
            tts.shutdown()
        }
    }

    // =====================================================================
    // Gemini TTS
    // =====================================================================

    private fun voiceFor(id: String, ids: List<String>, infos: Map<String, Speakers.Info>): String {
        val i = ids.indexOf(id).coerceAtLeast(0)
        val female = infos[id]?.female ?: (i % 2 == 0)
        val sameGender = ids.take(i).count { (infos[it]?.female ?: (ids.indexOf(it) % 2 == 0)) == female }
        return (if (female) femaleVoices else maleVoices)[sameGender % 4]
    }

    private fun gemini(
        ctx: Context, texts: List<String>, bySpeaker: Map<Int, String>, infos: Map<String, Speakers.Info>, ids: List<String>,
        progress: (String, Int, Int) -> Unit
    ): List<ShortArray> {
        // Blocos de frases seguidas com no máximo 2 locutores
        val groups = mutableListOf<MutableList<Int>>()
        for (i in texts.indices) {
            val g = groups.lastOrNull()
            val sp = bySpeaker[i] ?: ids.first()
            val inG = g?.map { bySpeaker[it] ?: ids.first() }?.toSet()
            if (g == null || g.size >= 12 || (inG != null && sp !in inG && inG.size >= 2)) groups.add(mutableListOf(i)) else g.add(i)
        }
        val out = arrayOfNulls<ShortArray>(texts.size)
        var done = 0
        for (g in groups) {
            if (stopRequested) break
            progress("Gerando a voz (Gemini)…", done, texts.size)
            val sps = g.map { bySpeaker[it] ?: ids.first() }
            val distinct = sps.distinct()
            val genCfg = JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
            val parts = JSONArray()
            if (distinct.size >= 2) {
                for ((k, i) in g.withIndex()) parts.put(JSONObject().put("text", texts[i]).put("speechMetadata", JSONObject().put("speaker", sps[k])))
                val cfgs = JSONArray()
                for (s in distinct) cfgs.put(JSONObject().put("speaker", s).put("voiceConfig",
                    JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voiceFor(s, ids, infos)))))
                genCfg.put("speechConfig", JSONObject().put("multiSpeakerVoiceConfig", JSONObject().put("speakerVoiceConfigs", cfgs)))
            } else {
                parts.put(JSONObject().put("text", g.joinToString("\n") { texts[it] }))
                genCfg.put("speechConfig", JSONObject().put("voiceConfig",
                    JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voiceFor(distinct.first(), ids, infos)))))
            }
            val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", parts))).put("generationConfig", genCfg)
            val wav = geminiCall(ctx, body) { wait -> progress("Limite do plano grátis: aguardando ${wait}s…", done, texts.size) }
            val pcm = readWav(wav)
            val pieces = splitByPauses(pcm, g.map { weight(texts[it]) })
            for ((k, i) in g.withIndex()) out[i] = pieces[k]
            done += g.size
        }
        return out.map { it ?: ShortArray(0) }
    }

    /** POST com espera e nova tentativa quando o plano grátis estoura (429). Devolve os bytes do áudio. */
    private fun geminiCall(ctx: Context, body: JSONObject, waiting: (Int) -> Unit): ByteArray {
        val p = Prefs(ctx)
        val key = p.geminiKey.ifBlank { Ai.TEST_KEY }
        if (key.isBlank()) throw IllegalStateException("Sem chave do Gemini (Configurações → IA)")
        repeat(8) {
            if (stopRequested) throw IllegalStateException("Parado")
            val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_TTS:generateContent").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"; conn.connectTimeout = 15000; conn.readTimeout = 180000; conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-goog-api-key", key)
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                if (code == 429) {
                    val secs = Regex("retry in ([0-9.]+)s").find(text)?.groupValues?.get(1)?.toDoubleOrNull()?.toInt()?.plus(2) ?: 25
                    waiting(secs)
                    Thread.sleep(secs * 1000L)
                    return@repeat
                }
                if (code !in 200..299) {
                    val msg = try { JSONObject(text).getJSONObject("error").optString("message") } catch (_: Exception) { text.take(200) }
                    throw IllegalStateException("Gemini TTS respondeu $code: $msg")
                }
                val data = JSONObject(text).getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
                    .getJSONArray("parts").getJSONObject(0).getJSONObject("inlineData").getString("data")
                return Base64.decode(data, Base64.DEFAULT)
            } finally {
                conn.disconnect()
            }
        }
        throw IllegalStateException("O plano grátis do Gemini TTS continuou no limite; tente mais tarde")
    }

    /** Peso da frase (≈ duração): caracteres sem pontuação. */
    private fun weight(s: String) = s.count { it !in "。、！？!?「」『』（）()…・ 　" }.coerceAtLeast(1).toDouble()

    /**
     * Divide o áudio de um bloco em n frases: escolhe n-1 pausas (≥ 0,2 s) perto de onde cada frase
     * deveria terminar pelo tamanho do texto, preferindo as pausas mais longas.
     */
    private fun splitByPauses(pcm: ShortArray, weights: List<Double>): List<ShortArray> {
        val n = weights.size
        if (n == 1) return listOf(pcm)
        val fr = SR / 50 // 20 ms
        val rms = DoubleArray(pcm.size / fr) { f ->
            var s = 0.0
            for (k in 0 until fr) { val v = pcm[f * fr + k].toDouble(); s += v * v }
            sqrt(s / fr)
        }
        val mx = rms.maxOrNull() ?: 0.0
        val thr = maxOf(mx * 0.05, 150.0)
        val first = rms.indexOfFirst { it >= thr }.coerceAtLeast(0)
        val last = rms.indexOfLast { it >= thr }.coerceAtLeast(first)
        data class Gap(val center: Int, val len: Int)
        val gaps = mutableListOf<Gap>()
        var run = 0
        for (f in first..last) {
            if (rms[f] < thr) run++ else { if (run >= 10) gaps.add(Gap(f - run / 2, run)); run = 0 }
        }
        val total = weights.sum()
        val span = (last - first).coerceAtLeast(1)
        val expected = (1 until n).map { j -> first + span * weights.take(j).sum() / total }
        val cuts: List<Int> = if (gaps.size < n - 1) {
            expected.map { it.toInt() }
        } else {
            // Programação dinâmica: corte j usa a pausa g, em ordem crescente
            val k = n - 1
            val maxLen = gaps.maxOf { it.len }.toDouble()
            fun cost(j: Int, g: Int) = abs(gaps[g].center - expected[j]) / span - 0.15 * gaps[g].len / maxLen
            val best = Array(k) { DoubleArray(gaps.size) { Double.MAX_VALUE } }
            val prev = Array(k) { IntArray(gaps.size) { -1 } }
            for (g in gaps.indices) best[0][g] = cost(0, g)
            for (j in 1 until k) for (g in j until gaps.size) {
                for (h in j - 1 until g) {
                    val c = best[j - 1][h] + cost(j, g)
                    if (c < best[j][g]) { best[j][g] = c; prev[j][g] = h }
                }
            }
            var g = (k - 1 until gaps.size).minByOrNull { best[k - 1][it] } ?: return listOf(pcm) + List(n - 1) { ShortArray(0) }
            val pick = IntArray(k)
            for (j in k - 1 downTo 0) { pick[j] = gaps[g].center; if (j > 0) g = prev[j][g] }
            pick.toList()
        }
        val bounds = listOf(first) + cuts + listOf(last + 1)
        return (0 until n).map { j -> pcm.copyOfRange((bounds[j] * fr).coerceIn(0, pcm.size), (bounds[j + 1] * fr).coerceIn(0, pcm.size)) }
    }

    // =====================================================================
    // WAV
    // =====================================================================

    /** Lê WAV (PCM 16 bits, qualquer taxa, mono ou estéreo) e devolve mono 16 kHz. */
    fun readWav(b: ByteArray): ShortArray {
        var rate = 24000; var channels = 1; var bits = 16
        var dataOff = 44; var dataLen = b.size - 44
        if (b.size >= 12 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF") {
            var p = 12
            while (p + 8 <= b.size) {
                val id = String(b, p, 4, Charsets.US_ASCII)
                val len = (b[p + 4].toInt() and 0xff) or ((b[p + 5].toInt() and 0xff) shl 8) or ((b[p + 6].toInt() and 0xff) shl 16) or ((b[p + 7].toInt() and 0xff) shl 24)
                if (id == "fmt ") {
                    channels = (b[p + 10].toInt() and 0xff) or ((b[p + 11].toInt() and 0xff) shl 8)
                    rate = (b[p + 12].toInt() and 0xff) or ((b[p + 13].toInt() and 0xff) shl 8) or ((b[p + 14].toInt() and 0xff) shl 16)
                    bits = (b[p + 22].toInt() and 0xff) or ((b[p + 23].toInt() and 0xff) shl 8)
                }
                if (id == "data") { dataOff = p + 8; dataLen = if (len <= 0 || len > b.size - dataOff) b.size - dataOff else len; break }
                p += 8 + len + (len and 1)
            }
        } else { dataOff = 0; dataLen = b.size }
        if (bits != 16) throw IllegalStateException("Áudio em formato inesperado ($bits bits)")
        val frames = dataLen / (2 * channels)
        val mono = ShortArray(frames) { f ->
            var s = 0
            for (c in 0 until channels) { val o = dataOff + (f * channels + c) * 2; s += ((b[o + 1].toInt() shl 8) or (b[o].toInt() and 0xff)).toShort().toInt() }
            (s / channels).toShort()
        }
        if (rate == SR) return mono
        val outLen = (frames.toLong() * SR / rate).toInt()
        return ShortArray(outLen) { i ->
            val x = i.toDouble() * rate / SR
            val a = x.toInt().coerceAtMost(frames - 1)
            val bIdx = (a + 1).coerceAtMost(frames - 1)
            val t = x - a
            (mono[a] * (1 - t) + mono[bIdx] * t).toInt().toShort()
        }
    }

    private fun writeSamples(out: ByteArrayOutputStream, s: ShortArray) {
        val buf = ByteArray(s.size * 2)
        for (i in s.indices) { buf[i * 2] = (s[i].toInt() and 0xff).toByte(); buf[i * 2 + 1] = (s[i].toInt() shr 8).toByte() }
        out.write(buf)
    }

    private fun writeSilence(out: ByteArrayOutputStream, samples: Int) { if (samples > 0) out.write(ByteArray(samples * 2)) }

    fun wavHeader(dataLen: Long): ByteArray {
        val h = ByteArray(44)
        fun str(off: Int, s: String) { s.toByteArray(Charsets.US_ASCII).copyInto(h, off) }
        fun int32(off: Int, v: Long) { for (k in 0..3) h[off + k] = ((v shr (8 * k)) and 0xff).toByte() }
        fun int16(off: Int, v: Int) { h[off] = (v and 0xff).toByte(); h[off + 1] = ((v shr 8) and 0xff).toByte() }
        str(0, "RIFF"); int32(4, 36 + dataLen); str(8, "WAVE"); str(12, "fmt "); int32(16, 16); int16(20, 1); int16(22, 1)
        int32(24, SR.toLong()); int32(28, SR * 2L); int16(32, 2); int16(34, 16); str(36, "data"); int32(40, dataLen)
        return h
    }
}
