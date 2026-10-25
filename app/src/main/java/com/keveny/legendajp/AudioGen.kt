package com.keveny.legendajp

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
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
 * - Celular: TextToSpeech do Android (grátis, offline), uma frase por vez; voz masculina ou feminina
 *   conforme o locutor (o tom muda quando o celular não tem a voz certa).
 * - Gemini: um pedido por par de locutores (até 20 frases), o áudio é dividido nas pausas. Plano grátis:
 *   3 pedidos por minuto e 10 por dia em cada modelo; o app espera entre os pedidos e, se um modelo
 *   esgota no dia, passa para o outro.
 * Cada frase gerada fica guardada no celular: se parar no meio, gerar de novo continua de onde parou,
 * e dá para completar o que faltou com a voz do celular.
 */
object AudioGen {
    const val PHONE = "celular"
    const val GEMINI = "gemini"
    private const val SR = 16000
    private const val TAG = "MichiAudio"
    private val geminiModels = listOf("gemini-3.8-flash-lite-tts", "gemini-3.8-flash-tts")
    private val femaleVoices = listOf("Kore", "Aoede", "Leda", "Zephyr")
    private val maleVoices = listOf("Puck", "Charon", "Orus", "Fenrir")
    private val main = Handler(Looper.getMainLooper())

    /** Modelo → até quando está sem cota (limite diário). */
    private val exhaustedUntil = ConcurrentHashMap<String, Long>()
    @Volatile private var lastGeminiCall = 0L

    @Volatile var stopRequested = false

    /** Parou com parte das frases geradas (elas ficam guardadas). */
    class Partial(msg: String, val done: Int, val total: Int) : Exception(msg)

    /**
     * progress(texto, feitas, total); done(erro ou null, frases prontas, total). Rodam na thread principal.
     * allowOther: aproveita frases já geradas por outro meio (ex.: completar o Gemini com a voz do celular).
     */
    fun generate(
        ctx: Context, txtName: String, engine: String, allowOther: Boolean,
        progress: (String, Int, Int) -> Unit, done: (String?, Int, Int) -> Unit
    ) {
        val app = ctx.applicationContext
        stopRequested = false
        Thread {
            var err: String? = null
            var ok = 0
            var total = 0
            try {
                total = run(app, txtName, engine, allowOther) { msg, a, b -> main.post { progress(msg, a, b) } }
                ok = total
            } catch (e: Partial) {
                err = e.message; ok = e.done; total = e.total
            } catch (e: Exception) {
                Log.e(TAG, "generate", e)
                err = e.message ?: e.javaClass.simpleName
            }
            val (e2, o2, t2) = Triple(err, ok, total)
            main.post { done(e2, o2, t2) }
        }.start()
    }

    // =====================================================================
    // Montagem
    // =====================================================================

    private fun run(ctx: Context, txtName: String, engine: String, allowOther: Boolean, progress: (String, Int, Int) -> Unit): Int {
        val base = Recordings.baseName(txtName)
        val item = Recordings.find(ctx, "$base.txt") ?: throw IllegalStateException("Transcrição não encontrada")
        val texts = Recordings.readText(ctx, item.uri).lines().filter { it.isNotBlank() && !it.startsWith("#") }
            .map { Recordings.parseLine(it).second.trim() }.filter { it.isNotEmpty() }
        if (texts.isEmpty()) throw IllegalStateException("A transcrição está vazia")
        val bySpeaker = Speakers.lines(ctx, base)
        val infos = Speakers.infos(ctx, base).associateBy { it.id }
        val ids = bySpeaker.values.distinct().sorted().ifEmpty { listOf("A") }
        fun speakerOf(i: Int) = bySpeaker[i] ?: ids.first()
        fun female(id: String) = infos[id]?.female ?: (ids.indexOf(id) % 2 == 0)

        // Frases já geradas (guardadas) entram direto
        val dir = File(ctx.filesDir, "audiogen/$base").apply { mkdirs() }
        val segs = arrayOfNulls<ShortArray>(texts.size)
        val voiceKey: (Int) -> String = if (engine == GEMINI) { i -> geminiVoice(speakerOf(i), ids, ::female) } else { i -> speakerOf(i) + female(speakerOf(i)) + Prefs(ctx).ttsPackage + Prefs(ctx).voiceGenders.size }
        fun file(i: Int, eng: String) = File(dir, "$i-$eng-${texts[i].hashCode().toUInt()}-${voiceKey(i).hashCode().toUInt()}.pcm")
        for (i in texts.indices) {
            val f = file(i, engine).takeIf { it.exists() }
                ?: if (allowOther) dir.listFiles()?.firstOrNull { it.name.startsWith("$i-") && it.name.contains("-${texts[i].hashCode().toUInt()}-") } else null
            if (f != null) segs[i] = readPcm(f)
        }
        val store: (Int, ShortArray) -> Unit = { i, s -> segs[i] = s; writePcm(file(i, engine), s) }

        val before = segs.count { it != null }
        if (before < texts.size) {
            try {
                if (engine == GEMINI) gemini(ctx, texts, segs, ::speakerOf, ids, ::female, store, progress)
                else phone(ctx, texts, segs, ::speakerOf, ids, ::female, store, progress)
            } catch (e: Exception) {
                val got = segs.count { it != null }
                throw Partial((e.message ?: "erro") + " · $got de ${texts.size} frases prontas (guardadas)", got, texts.size)
            }
        }
        if (stopRequested) { val got = segs.count { it != null }; throw Partial("Parado · $got de ${texts.size} frases prontas (guardadas)", got, texts.size) }

        // Monta o WAV: cada frase começa num segundo exato (com pelo menos 0,35 s de pausa antes)
        progress("Montando o áudio…", texts.size, texts.size)
        val out = ByteArrayOutputStream()
        var pos = 0L
        val secs = IntArray(texts.size)
        for ((i, seg) in segs.withIndex()) {
            val start = ceil((pos + SR * 0.35) / SR).toLong() * SR
            writeSilence(out, (start - pos).toInt()); pos = start
            secs[i] = (start / SR).toInt()
            writeSamples(out, seg!!); pos += seg.size
        }
        writeSilence(out, SR * 6 / 10)
        val pcm = out.toByteArray()

        Recordings.find(ctx, "$base.wav")?.let { Recordings.delete(ctx, it.uri) }
        val wavUri = Recordings.create(ctx, "$base.wav", "audio/wav") ?: throw IllegalStateException("Não consegui criar o WAV")
        ctx.contentResolver.openOutputStream(wavUri)!!.use { it.write(wavHeader(pcm.size.toLong())); it.write(pcm) }
        val body = texts.indices.joinToString("\n") { "[${CaptureService.formatTime(secs[it])}] ${texts[it]}" } + "\n"
        ctx.contentResolver.openOutputStream(item.uri, "wt")!!.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        VocabDb.get(ctx).deleteTiming(base)
        return texts.size
    }

    // =====================================================================
    // Celular (TextToSpeech)
    // =====================================================================

    /** Abre o TTS (motor escolhido em Configurações ou o padrão). Chame fora da UI; feche com shutdown(). */
    fun openTts(ctx: Context): TextToSpeech {
        val ready = CountDownLatch(1)
        var status = TextToSpeech.ERROR
        val pkg = Prefs(ctx).ttsPackage.ifBlank { null }
        val tts = TextToSpeech(ctx, { st -> status = st; ready.countDown() }, pkg)
        ready.await(15, TimeUnit.SECONDS)
        if (status != TextToSpeech.SUCCESS) { tts.shutdown(); throw IllegalStateException("O leitor de texto do celular não abriu") }
        val lang = tts.setLanguage(Locale.JAPAN)
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.shutdown()
            throw IllegalStateException("Falta a voz japonesa: Configurações do Android → Saída de texto para voz → instale japonês")
        }
        return tts
    }

    /** Vozes japonesas offline do motor. */
    fun japaneseVoices(tts: TextToSpeech): List<Voice> =
        tts.voices?.filter { it.locale.language == "ja" && !it.isNetworkConnectionRequired }?.sortedBy { it.name } ?: emptyList()

    /**
     * A voz é feminina? Primeiro o que você marcou em Configurações → Vozes do celular; senão pelo nome
     * (Samsung: SMTf/SMTm; Google: htm/jab femininas, jac/jad masculinas). null = não sei.
     */
    fun isFemale(ctx: Context, v: Voice): Boolean? {
        Prefs(ctx).voiceGenders[v.name]?.let { return it }
        val n = v.name.lowercase()
        return when {
            "smtf" in n || "-x-htm" in n || "-x-jab" in n || "female" in n -> true
            "smtm" in n || "-x-jac" in n || "-x-jad" in n || "male" in n -> false
            else -> null
        }
    }

    private fun phone(
        ctx: Context, texts: List<String>, segs: Array<ShortArray?>, speakerOf: (Int) -> String, ids: List<String>,
        female: (String) -> Boolean, store: (Int, ShortArray) -> Unit, progress: (String, Int, Int) -> Unit
    ) {
        val tts = openTts(ctx)
        try {
            val voices = japaneseVoices(tts)
            // Cada locutor: uma voz do gênero certo (sem repetir enquanto der); sem voz certa, muda o tom
            val used = HashMap<Voice, Int>()
            val choice = ids.associateWith { id ->
                val want = female(id)
                val sameGender = voices.filter { isFemale(ctx, it) == want }
                val unknown = voices.filter { isFemale(ctx, it) == null }
                val v = (sameGender.firstOrNull { it !in used } ?: unknown.firstOrNull { it !in used }
                    ?: sameGender.minByOrNull { used[it] ?: 0 } ?: voices.minByOrNull { used[it] ?: 0 })
                val n = if (v != null) used.getOrDefault(v, 0) else 0
                if (v != null) used[v] = n + 1
                val g = v?.let { isFemale(ctx, it) }
                val pitch = when {
                    g == want -> if (n == 0) 1.0f else if (n % 2 == 1) 0.92f else 1.08f
                    want -> 1.15f   // queria feminina, só tem masculina/desconhecida
                    else -> 0.78f   // queria masculina
                }
                v to pitch
            }
            val latches = ConcurrentHashMap<String, CountDownLatch>()
            val failed = ConcurrentHashMap.newKeySet<String>()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {}
                override fun onDone(utteranceId: String) { latches[utteranceId]?.countDown() }
                @Deprecated("API antiga") override fun onError(utteranceId: String) { failed.add(utteranceId); latches[utteranceId]?.countDown() }
                override fun onError(utteranceId: String, errorCode: Int) { failed.add(utteranceId); latches[utteranceId]?.countDown() }
            })
            for ((i, text) in texts.withIndex()) {
                if (stopRequested) break
                if (segs[i] != null) continue
                progress("Gerando a voz (celular)…", segs.count { it != null }, texts.size)
                val (voice, pitch) = choice.getValue(speakerOf(i))
                if (voice != null) tts.voice = voice
                tts.setPitch(pitch)
                val f = File(ctx.cacheDir, "tts_$i.wav")
                val id = "michi_$i"
                val latch = CountDownLatch(1)
                latches[id] = latch
                tts.synthesizeToFile(text, Bundle(), f, id)
                latch.await(60, TimeUnit.SECONDS)
                if (id in failed || !f.exists()) throw IllegalStateException("O celular não conseguiu gerar a frase ${i + 1}")
                store(i, readWav(f.readBytes()))
                f.delete()
            }
        } finally {
            tts.shutdown()
        }
    }

    // =====================================================================
    // Gemini TTS
    // =====================================================================

    private fun geminiVoice(id: String, ids: List<String>, female: (String) -> Boolean): String {
        val f = female(id)
        val sameBefore = ids.take(ids.indexOf(id).coerceAtLeast(0)).count { female(it) == f }
        return (if (f) femaleVoices else maleVoices)[sameBefore % 4]
    }

    private fun gemini(
        ctx: Context, texts: List<String>, segs: Array<ShortArray?>, speakerOf: (Int) -> String, ids: List<String>,
        female: (String) -> Boolean, store: (Int, ShortArray) -> Unit, progress: (String, Int, Int) -> Unit
    ) {
        // Poucos pedidos: um por par de locutores (até 20 frases cada), só com as frases que faltam
        val pairs = ids.chunked(2)
        for (pair in pairs) {
            val lines = texts.indices.filter { segs[it] == null && speakerOf(it) in pair }
            for (chunk in lines.chunked(20)) {
                if (stopRequested) return
                val sps = chunk.map(speakerOf)
                val distinct = sps.distinct()
                val genCfg = JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
                val parts = JSONArray()
                if (distinct.size >= 2) {
                    for ((k, i) in chunk.withIndex()) parts.put(JSONObject().put("text", texts[i]).put("speechMetadata", JSONObject().put("speaker", sps[k])))
                    val cfgs = JSONArray()
                    for (s in distinct) cfgs.put(JSONObject().put("speaker", s).put("voiceConfig",
                        JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", geminiVoice(s, ids, female)))))
                    genCfg.put("speechConfig", JSONObject().put("multiSpeakerVoiceConfig", JSONObject().put("speakerVoiceConfigs", cfgs)))
                } else {
                    parts.put(JSONObject().put("text", chunk.joinToString("\n") { texts[it] }))
                    genCfg.put("speechConfig", JSONObject().put("voiceConfig",
                        JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", geminiVoice(distinct.first(), ids, female)))))
                }
                val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", parts))).put("generationConfig", genCfg)
                val done = segs.count { it != null }
                val wav = geminiCall(ctx, body) { msg -> progress(msg, done, texts.size) }
                val pieces = splitByPauses(readWav(wav), chunk.map { weight(texts[it]) })
                for ((k, i) in chunk.withIndex()) store(i, pieces[k])
                progress("Gerando a voz (Gemini)…", segs.count { it != null }, texts.size)
            }
        }
    }

    /** Segundos do "retry in 20h56m4.3s" / "retry in 29.9s" da resposta 429. */
    private fun retrySeconds(text: String): Int? {
        val m = Regex("retry in (?:(\\d+)h)?(?:(\\d+)m)?([0-9.]+)s").find(text) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: 0
        val mi = m.groupValues[2].toIntOrNull() ?: 0
        return h * 3600 + mi * 60 + (m.groupValues[3].toDoubleOrNull()?.toInt() ?: 0) + 1
    }

    /**
     * POST ao TTS: espera 21 s entre pedidos (3 por minuto no plano grátis); limite por minuto → espera
     * e tenta de novo; limite do dia → passa para o outro modelo; sem modelo com cota → erro claro.
     */
    private fun geminiCall(ctx: Context, body: JSONObject, status: (String) -> Unit): ByteArray {
        val key = Prefs(ctx).geminiKey.ifBlank { Ai.TEST_KEY }
        if (key.isBlank()) throw IllegalStateException("Sem chave do Gemini (Configurações → IA)")
        var attempts = 0
        while (true) {
            if (stopRequested) throw IllegalStateException("Parado")
            val now = System.currentTimeMillis()
            val model = geminiModels.firstOrNull { (exhaustedUntil[it] ?: 0L) < now }
                ?: throw IllegalStateException("A cota grátis do Gemini TTS acabou por hoje (10 pedidos por dia em cada modelo). Volta em algumas horas; dá para completar com a voz do celular")
            val wait = 21_000L - (now - lastGeminiCall)
            if (wait > 0) countdown((wait / 1000).toInt() + 1, status, "Aguardando o limite do plano grátis (3 por minuto)")
            lastGeminiCall = System.currentTimeMillis()
            val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection
            try {
                status("Gerando a voz (Gemini · $model)…")
                conn.requestMethod = "POST"; conn.connectTimeout = 15000; conn.readTimeout = 180000; conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-goog-api-key", key)
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                if (code == 429) {
                    val secs = retrySeconds(text) ?: 30
                    if ("PerDay" in text || secs > 300) {
                        exhaustedUntil[model] = System.currentTimeMillis() + secs * 1000L
                        Log.i(TAG, "$model sem cota diária por ${secs / 3600} h")
                        continue // tenta o outro modelo
                    }
                    if (++attempts > 4) throw IllegalStateException("O Gemini TTS continuou no limite por minuto")
                    countdown(secs, status, "Limite por minuto do plano grátis")
                    continue
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
    }

    private fun countdown(secs: Int, status: (String) -> Unit, what: String) {
        for (s in secs downTo 1) {
            if (stopRequested) throw IllegalStateException("Parado")
            status("$what: ${s}s…")
            Thread.sleep(1000)
        }
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
    // WAV e PCM guardado
    // =====================================================================

    private fun writePcm(f: File, s: ShortArray) {
        val buf = ByteArray(s.size * 2)
        for (i in s.indices) { buf[i * 2] = (s[i].toInt() and 0xff).toByte(); buf[i * 2 + 1] = (s[i].toInt() shr 8).toByte() }
        f.writeBytes(buf)
    }

    private fun readPcm(f: File): ShortArray {
        val b = f.readBytes()
        return ShortArray(b.size / 2) { i -> ((b[i * 2 + 1].toInt() shl 8) or (b[i * 2].toInt() and 0xff)).toShort() }
    }

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
