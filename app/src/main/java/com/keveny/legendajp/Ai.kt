package com.keveny.legendajp

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Explicações com IA: Gemini Nano no aparelho (ML Kit GenAI / AICore) ou Gemini na nuvem (API do
 * Google AI Studio). Cada tarefa tem um prompt fixo que pede um JSON com chaves conhecidas; o app
 * mostra sempre no mesmo formato. As respostas ficam no banco (tabela ai) para não gastar de novo.
 */
object Ai {
    const val OFF = 0
    const val NANO = 1
    const val CLOUD = 2

    /**
     * Chave de teste embutida no APK na compilação (local.properties ou segredo GEMINI_API_KEY do Actions;
     * nunca no git). A versão final deve pedir a chave de cada pessoa.
     */
    val TEST_KEY: String get() = BuildConfig.GEMINI_KEY
    const val DEFAULT_MODEL = "gemini-3.5-flash-lite"

    enum class Task(val label: String) {
        TRANSLATE("Traduzir a frase com contexto"),
        WORD("Explicar a palavra nesta frase"),
        GRAMMAR("Explicar a gramática"),
        GUIDE("Escrever o material de estudo"),
    }

    /**
     * @param target palavra como aparece na frase (WORD) ou nome do padrão (GRAMMAR); vazio = ponto principal.
     * @param note detalhe do alvo: forma de dicionário e leitura, ou o trecho do padrão.
     */
    data class Request(
        val task: Task, val sentence: String, val recording: String?,
        val target: String = "", val note: String = "", val level: Int = 5
    )

    /** Campos já com rótulo, na ordem de exibição. error != null quando falhou. */
    data class Result(val fields: List<Pair<String, String>>, val raw: String, val error: String?, val model: String, val cached: Boolean)

    private const val TAG = "MichiAI"
    private val main = Handler(Looper.getMainLooper())

    fun mode(ctx: Context) = Prefs(ctx).aiMode

    fun enabled(ctx: Context) = mode(ctx) != OFF

    fun modelLabel(ctx: Context): String = when (mode(ctx)) {
        NANO -> "Gemini Nano (no aparelho)"
        CLOUD -> Prefs(ctx).geminiModel.ifBlank { DEFAULT_MODEL }
        else -> "desligada"
    }

    private fun modelId(ctx: Context) = when (mode(ctx)) {
        NANO -> "nano"
        else -> Prefs(ctx).geminiModel.ifBlank { DEFAULT_MODEL }
    }

    private fun cacheKey(ctx: Context, r: Request) = "${r.task}|${modelId(ctx)}|${r.sentence}|${r.target}|${r.note}" + (if (r.task == Task.GUIDE) "|txt" else "")

    /** Resposta guardada, se houver. Chame fora da UI. */
    fun cached(ctx: Context, r: Request): Result? {
        val out = VocabDb.get(ctx).aiGet(cacheKey(ctx, r)) ?: return null
        return parse(r.task, out, modelLabel(ctx), cached = true)
    }

    /** Pergunta à IA (ou devolve o cache, se fresh = false). O callback roda na thread principal. */
    fun ask(ctx: Context, r: Request, fresh: Boolean, cb: (Result) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val res = try {
                if (!fresh) cached(app, r)?.let { hit ->
                    if (r.task == Task.TRANSLATE) keepTranslation(app, r.sentence, hit.raw)
                    main.post { cb(hit) }
                    return@Thread
                }
                val prompt = prompt(app, r)
                val out = when (mode(app)) {
                    NANO -> runNano(prompt)
                    CLOUD -> if (r.task == Task.GUIDE) runCloud(app, prompt, 8192, json = false) else runCloud(app, prompt)
                    else -> throw IllegalStateException("A IA está desligada em Configurações.")
                }
                val parsed = parse(r.task, out, modelLabel(app), cached = false)
                if (parsed.error == null) {
                    VocabDb.get(app).aiPut(cacheKey(app, r), out)
                    if (r.task == Task.TRANSLATE) keepTranslation(app, r.sentence, out)
                }
                parsed
            } catch (e: Exception) {
                Log.e(TAG, "ask", e)
                Result(emptyList(), "", e.message ?: e.javaClass.simpleName, modelLabel(app), false)
            }
            main.post { cb(res) }
        }.start()
    }

    /** Palavra montada pela IA: como apareceu, forma de dicionário, leitura, classe e significado. */
    data class AiWord(val surface: String, val base: String, val reading: String, val pos: String, val meaning: String)

    /**
     * Separa um trecho em palavras como um dicionário faria (順番 inteiro, e não 順+番), com leitura,
     * classe e significado, para salvar o que o Kuromoji não conhece ou corta errado. Guardado em cache.
     */
    fun analyzeWords(ctx: Context, text: String, sentence: String, cb: (List<AiWord>?, String?) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            var list: List<AiWord>? = null
            val err = try {
                val key = "words|${modelId(app)}|$text|$sentence"
                val out = VocabDb.get(app).aiGet(key) ?: run {
                    val prompt = buildString {
                        append("Frase em japonês: ").append(sentence).append('\n')
                        if (text != sentence) append("Trecho de interesse: ").append(text).append('\n')
                        append("Separe o trecho em palavras como um dicionário japonês-português faria: palavras compostas inteiras ")
                        append("(順番, e não 順 + 番), verbos com a forma de dicionário, partículas e auxiliares também contam. ")
                        append("Números, letras latinas e símbolos não são palavras: não os inclua e não os cole na palavra seguinte (3室 → só 室, 10時 → só 時). ")
                        append("Para cada palavra: forma (como aparece), base (forma de dicionário), leitura (só hiragana), ")
                        append("classe (substantivo, verbo (godan), verbo (ichidan), verbo (suru), adjetivo-i, adjetivo-na, advérbio, partícula, auxiliar, expressão, nome próprio…) ")
                        append("e significado curto em português do Brasil, no sentido usado na frase. ")
                        append("Responda somente com JSON: {\"palavras\": [{\"forma\": \"\", \"base\": \"\", \"leitura\": \"\", \"classe\": \"\", \"significado\": \"\"}]}")
                    }
                    val res = when (mode(app)) {
                        NANO -> runNano(prompt, 768)
                        CLOUD -> runCloud(app, prompt)
                        else -> throw IllegalStateException("A IA está desligada em Configurações.")
                    }
                    VocabDb.get(app).aiPut(key, res)
                    res
                }
                val o = JSONObject(out.substring(out.indexOf('{'), out.lastIndexOf('}') + 1))
                val arr = o.optJSONArray("palavras") ?: JSONArray()
                list = (0 until arr.length()).mapNotNull { i ->
                    val w = arr.optJSONObject(i) ?: return@mapNotNull null
                    // Tira números, letras e símbolos colados (3室 → 室); se sobrou nada, não é palavra
                    fun lex(s: String) = s.trim().trimStart { !Jp.isLexicalChar(it) }.trimEnd { !Jp.isLexicalChar(it) }
                    val raw = w.optString("base").trim().ifEmpty { w.optString("forma").trim() }
                    val base = lex(raw)
                    val reading0 = Jp.hiragana(w.optString("leitura").trim().ifEmpty { base })
                    val reading = if (base == raw) reading0 else try {
                        Jp.hiragana(Jp.tokenize(base).joinToString("") { t -> if (t.reading == "*" || t.reading.isNullOrEmpty()) t.surface else t.reading })
                    } catch (_: Exception) { base }
                    if (base.isEmpty()) null else AiWord(lex(w.optString("forma")).ifEmpty { base }, base,
                        reading, w.optString("classe").trim().ifEmpty { "palavra" }, w.optString("significado").trim())
                }
                null
            } catch (e: Exception) {
                Log.e(TAG, "analyzeWords", e)
                e.message ?: e.javaClass.simpleName
            }
            val l = list
            main.post { cb(l, err) }
        }.start()
    }

    /** A "traducao" da resposta vira a tradução do Gemini da frase (ao lado da do ML Kit). */
    private fun keepTranslation(ctx: Context, sentence: String, out: String) {
        val a = out.indexOf('{'); val b = out.lastIndexOf('}')
        val t = try { if (a >= 0 && b > a) JSONObject(out.substring(a, b + 1)).optString("traducao").trim() else "" } catch (_: Exception) { "" }
        if (t.isNotEmpty()) VocabDb.get(ctx).putAiTranslation(sentence, t)
    }

    // =====================================================================
    // Traduzir a gravação inteira
    // =====================================================================

    @Volatile var stopRequested = false

    /**
     * Proposta de correção para um reporte de tradução (palavra ou frase): (tradução, observação) ou erro.
     * O callback roda na thread principal.
     */
    fun fixTranslation(ctx: Context, r: Reports.Report, cb: (Pair<String, String>?, String?) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            var res: Pair<String, String>? = null
            var err: String? = null
            try {
                val sb = StringBuilder("Você corrige traduções japonês → português do Brasil de um app de estudo. ")
                if (r.kind == Reports.WORD) {
                    sb.append("Dê o significado da palavra 「${r.word}」 (leitura ${r.reading}, ${r.pos})")
                    if (r.sentence.isNotBlank()) sb.append(" como usada na frase 「${r.sentence}」")
                    sb.append(". Tradução atual (reportada como errada): ${Tr.cached(app, r.word) ?: "(nenhuma)"}.\n")
                } else {
                    val context = contextLines(app, r.recording, r.sentence)
                    if (context.isNotEmpty()) {
                        sb.append("Transcrição da gravação (a frase marcada com >>):\n")
                        for (l in context) sb.append(l).append('\n')
                    }
                    sb.append("Traduza a frase 「${r.sentence}」. Tradução atual (reportada como errada): ")
                    sb.append(Tr.aiCached(app, r.sentence) ?: Tr.cached(app, r.sentence) ?: "(nenhuma)").append(".\n")
                }
                if (r.comment.isNotBlank()) sb.append("Comentário de quem reportou: ${r.comment}\n")
                sb.append("Nada de inglês na resposta. Responda somente com JSON, sem markdown: {\"traducao\": \"")
                sb.append(if (r.kind == Reports.WORD) "1 a 4 palavras, estilo dicionário, sem artigo" else "tradução natural")
                sb.append("\", \"observacao\": \"uma linha: outros sentidos ou o que estava errado\"}")
                val out = when (mode(app)) {
                    NANO -> runNano(sb.toString())
                    CLOUD -> runCloud(app, sb.toString())
                    else -> throw IllegalStateException("A IA está desligada em Configurações.")
                }
                val a = out.indexOf('{'); val b = out.lastIndexOf('}')
                val o = JSONObject(out.substring(a, b + 1))
                val t = o.optString("traducao").trim()
                if (t.isNotEmpty()) res = t to o.optString("observacao").trim() else err = "resposta vazia"
            } catch (e: Exception) {
                Log.e(TAG, "fixTranslation", e)
                err = e.message ?: e.javaClass.simpleName
            }
            val r2 = res; val e2 = err
            main.post { cb(r2, e2) }
        }.start()
    }

    /**
     * Locutores: manda o áudio (em partes de 6 min) com as frases numeradas e pede quem fala cada uma.
     * Os ids continuam os mesmos entre as partes (o pedido leva as vozes já identificadas).
     * progress(feitas, total) e done(nº de locutores, erro) rodam na thread principal.
     */
    fun identifySpeakers(ctx: Context, recording: String, progress: (Int, Int) -> Unit, done: (Int, String?) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            var count = 0
            val err = try {
                if (mode(app) != CLOUD) throw IllegalStateException("Precisa do Gemini na nuvem (Configurações → IA → Nuvem): o Nano não ouve áudio.")
                val txt = Recordings.find(app, "$recording.txt") ?: throw IllegalStateException("Transcrição não encontrada")
                val wavItem = Recordings.find(app, "$recording.wav") ?: throw IllegalStateException("Esta gravação não tem áudio")
                val lines = StudyGen.lines(Recordings.readText(app, txt.uri))
                if (lines.none { it.sec >= 0 }) throw IllegalStateException("As frases não têm tempo no áudio")
                val pcm = app.contentResolver.openInputStream(wavItem.uri)!!.use { AudioGen.readWav(it.readBytes()) }
                val totalSec = pcm.size / 16000
                val chunk = 6 * 60
                val windows = (0 until maxOf(1, (totalSec + chunk - 1) / chunk)).map { it * chunk }
                val bySpeaker = HashMap<Int, String>()
                val descr = LinkedHashMap<String, String>()
                for ((w, from) in windows.withIndex()) {
                    val idx = lines.indices.filter { lines[it].sec >= from && lines[it].sec < from + chunk }
                    main.post { progress(w, windows.size) }
                    if (idx.isEmpty()) continue
                    val a = (maxOf(0, from - 1) * 16000).coerceAtMost(pcm.size)
                    val endSec = minOf(totalSec, (lines.getOrNull(idx.last() + 1)?.sec ?: totalSec) + 1)
                    val b = (endSec * 16000).coerceIn(a, pcm.size)
                    val part = pcm.copyOfRange(a, b)
                    val bytes = ByteArray(part.size * 2)
                    for (i in part.indices) { bytes[i * 2] = (part[i].toInt() and 0xff).toByte(); bytes[i * 2 + 1] = (part[i].toInt() shr 8).toByte() }
                    val wav = AudioGen.wavHeader(bytes.size.toLong()) + bytes
                    val sb = StringBuilder("O áudio é um trecho de uma gravação em japonês. Frases do trecho (número global, segundo em que começa no trecho, texto):\n")
                    for (i in idx) sb.append('[').append(i + 1).append("] (").append(lines[i].sec - maxOf(0, from - 1)).append("s) ").append(lines[i].text).append('\n')
                    if (descr.isNotEmpty()) {
                        sb.append("Vozes já identificadas nas partes anteriores (use os mesmos ids se for a mesma pessoa): ")
                        sb.append(descr.entries.joinToString("; ") { "${it.key} = ${it.value}" }).append('\n')
                    }
                    sb.append("Identifique pela voz quem fala cada frase. Use ids A, B, C… Se só uma pessoa fala, use A em todas. ")
                    sb.append("Responda somente com JSON: {\"locutores\": [{\"id\": \"A\", \"descricao\": \"voz feminina, aguda\"}], \"frases\": [{\"n\": 1, \"l\": \"A\"}]}")
                    val body = JSONObject()
                        .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                            .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/wav")
                                .put("data", android.util.Base64.encodeToString(wav, android.util.Base64.NO_WRAP))))
                            .put(JSONObject().put("text", sb.toString())))))
                        .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.1))
                    val out = runCloudBody(app, body)
                    val o = JSONObject(out.substring(out.indexOf('{'), out.lastIndexOf('}') + 1))
                    o.optJSONArray("locutores")?.let { arr ->
                        for (k in 0 until arr.length()) arr.optJSONObject(k)?.let { s ->
                            val id = s.optString("id").trim().uppercase()
                            if (id.isNotEmpty() && id !in descr) descr[id] = s.optString("descricao")
                        }
                    }
                    o.optJSONArray("frases")?.let { arr ->
                        for (k in 0 until arr.length()) arr.optJSONObject(k)?.let { f ->
                            val n = f.optInt("n", 0) - 1
                            val l = f.optString("l").trim().uppercase()
                            if (n in idx && l.isNotEmpty()) bySpeaker[n] = l
                        }
                    }
                }
                // Ids 1, 2… viram A, B… na ordem em que aparecem
                val order = bySpeaker.toSortedMap().values.distinct()
                val rename = order.withIndex().associate { (i, id) -> id to if (id.length == 1 && id[0] in 'A'..'Z') id else ('A' + i).toString() }
                val finalLines = bySpeaker.mapValues { rename.getValue(it.value) }
                val old = Speakers.infos(app, recording).associateBy { it.id }
                val infos = order.map { id ->
                    val nid = rename.getValue(id)
                    val d = descr[id] ?: ""
                    Speakers.Info(nid, old[nid]?.name?.takeIf { it != nid } ?: "Personagem ${nid[0] - 'A' + 1}",
                        when { "femin" in d.lowercase() -> true; "mascul" in d.lowercase() -> false; else -> null })
                }
                Speakers.save(app, recording, finalLines, infos)
                count = infos.size
                null
            } catch (e: Exception) {
                Log.e(TAG, "identifySpeakers", e)
                e.message ?: e.javaClass.simpleName
            }
            val n = count
            main.post { done(n, err) }
        }.start()
    }

    /** Texto japonês de uma foto (ordem de leitura, sem furigana). O callback roda na thread principal. */
    fun readImage(ctx: Context, uri: android.net.Uri, cb: (String?, String?) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            var text: String? = null
            var err: String? = null
            try {
                // Reduz para ~1600 px no lado maior (JPEG)
                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                app.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
                var sample = 1
                while (maxOf(opts.outWidth, opts.outHeight) / sample > 1600) sample *= 2
                val bmp = app.contentResolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                } ?: throw IllegalStateException("Não consegui abrir a imagem")
                val bytes = java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
                val prompt = "Transcreva todo o texto em japonês desta imagem mantendo o layout da página, em Markdown leve: " +
                    "títulos e subtítulos com # e ##; tabelas como tabelas Markdown (| célula | célula |, com a linha |---|---| depois do cabeçalho; " +
                    "célula vazia fica vazia); listas e exercícios numerados com \"- \"; parágrafos separados por uma linha vazia e, dentro do parágrafo, " +
                    "uma frase por linha. Mantenha a ordem de leitura (texto vertical: colunas da direita para a esquerda; mangá: balões na ordem japonesa) " +
                    "e a ordem dos blocos como estão na página. Ignore furigana (a leitura pequena ao lado dos kanji), números de página e ruído. " +
                    "Não traduza nem comente; responda só com o Markdown, sem cerca de código."
                val body = JSONObject()
                    .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                        .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg")
                            .put("data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))))
                        .put(JSONObject().put("text", prompt)))))
                    .put("generationConfig", JSONObject().put("temperature", 0.1))
                text = runCloudBody(app, body).trim().removePrefix("```markdown").removePrefix("```").removeSuffix("```").trim().ifEmpty { null }
                if (text == null) err = "resposta vazia"
            } catch (e: Exception) {
                Log.e(TAG, "readImage", e)
                err = e.message ?: e.javaClass.simpleName
            }
            val t = text; val e2 = err
            main.post { cb(t, e2) }
        }.start()
    }

    /** Título curto em português para a gravação, a partir da transcrição (e das traduções que houver). */
    fun suggestTitle(ctx: Context, recording: String, cb: (String?, String?) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            var title: String? = null
            var err: String? = null
            try {
                val item = Recordings.find(app, "$recording.txt") ?: throw IllegalStateException("Transcrição não encontrada")
                val lines = StudyGen.lines(Recordings.readText(app, item.uri)).map { it.text }
                val limit = if (mode(app) == NANO) 1200 else 6000
                val sb = StringBuilder()
                for (l in lines) {
                    if (sb.length > limit) break
                    sb.append(l)
                    Tr.aiCached(app, l)?.let { sb.append("  (").append(it).append(")") }
                    sb.append('\n')
                }
                val prompt = "Abaixo está a transcrição automática (em japonês, às vezes com tradução) de um áudio. " +
                    "Crie um título curto em português do Brasil, de 3 a 7 palavras, que diga do que o áudio trata " +
                    "(ex.: \"Conversa sobre o trabalho em Osaka\"). Sem aspas, sem data.\n\n$sb\n" +
                    "Responda somente com JSON, sem markdown: {\"titulo\": \"...\"}"
                val out = when (mode(app)) {
                    NANO -> runNano(prompt, 128)
                    CLOUD -> runCloud(app, prompt)
                    else -> throw IllegalStateException("A IA está desligada em Configurações.")
                }
                val a = out.indexOf('{'); val b = out.lastIndexOf('}')
                title = JSONObject(out.substring(a, b + 1)).optString("titulo").trim().trim('"', '「', '」').ifEmpty { null }
                if (title == null) err = "resposta vazia"
            } catch (e: Exception) {
                Log.e(TAG, "suggestTitle", e)
                err = e.message ?: e.javaClass.simpleName
            }
            val t = title; val e2 = err
            main.post { cb(t, e2) }
        }.start()
    }

    /** Correção de uma frase escrita pelo aluno com um padrão. error != null quando falhou. */
    data class Check(val correct: Boolean, val corrected: String, val explanation: String, val translation: String, val error: String?)

    /** Corrige a frase livre do exercício "Escrever com o padrão". O callback roda na thread principal. */
    fun check(ctx: Context, pointId: String, text: String, hintWord: String?, level: Int, cb: (Check) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val res = try {
                val p = Grammar.point(pointId) ?: throw IllegalStateException("Padrão desconhecido")
                val prompt = "Você é professor de japonês de um brasileiro que estuda para o JLPT N$level. " +
                    "Ele precisava escrever uma frase usando o padrão 「${p.name}」 (${p.explanation})" +
                    (if (!hintWord.isNullOrBlank()) ", podendo usar a palavra 「$hintWord」" else "") + ".\n" +
                    "Frase do aluno: 「$text」\n" +
                    "Avalie se está gramaticalmente correta, natural e se usa o padrão corretamente. " +
                    "Seja tolerante com kana no lugar de kanji e com pontuação, mas a frase precisa estar em japonês: " +
                    "se vier em romaji, marque correta como false e mostre em \"corrigida\" a versão em japonês. " +
                    "Responda somente com JSON, sem markdown: {\"correta\": true ou false, " +
                    "\"corrigida\": \"a frase corrigida (igual, se já estiver certa)\", " +
                    "\"explicacao\": \"1 a 2 linhas em português: o que estava errado ou por que está certo\", " +
                    "\"traducao\": \"tradução da frase corrigida\"}"
                val out = when (mode(app)) {
                    NANO -> runNano(prompt)
                    CLOUD -> runCloud(app, prompt)
                    else -> throw IllegalStateException("A IA está desligada em Configurações.")
                }
                val a = out.indexOf('{'); val b = out.lastIndexOf('}')
                val o = JSONObject(out.substring(a, b + 1))
                Check(o.optBoolean("correta", false), o.optString("corrigida").trim(), o.optString("explicacao").trim(),
                    o.optString("traducao").trim(), null)
            } catch (e: Exception) {
                Log.e(TAG, "check", e)
                Check(false, "", "", "", e.message ?: e.javaClass.simpleName)
            }
            main.post { cb(res) }
        }.start()
    }

    /**
     * Traduz as frases da gravação com a conversa como contexto, em partes (80 frases na nuvem, 15 no
     * Nano), guardando cada tradução como "Gemini". onlyMissing: só as que ainda não têm.
     * progress(feitas, total) e done(traduzidas, erro) rodam na thread principal.
     */
    fun translateRecording(
        ctx: Context, recording: String, onlyMissing: Boolean,
        progress: (Int, Int) -> Unit, done: (Int, String?) -> Unit
    ) {
        val app = ctx.applicationContext
        stopRequested = false
        Thread {
            var count = 0
            val err = try {
                val item = Recordings.find(app, "$recording.txt") ?: throw IllegalStateException("Transcrição não encontrada.")
                val lines = StudyGen.lines(Recordings.readText(app, item.uri), recording)
                txtCache.put(recording, lines)
                val db = VocabDb.get(app)
                val todo = lines.indices.filter { !onlyMissing || db.aiTranslation(lines[it].text) == null }
                val nano = mode(app) == NANO
                val chunk = if (nano) 15 else 80
                val total = lines.lastOrNull()?.endSec ?: 0
                main.post { progress(0, todo.size) }
                for (part in todo.chunked(chunk)) {
                    if (stopRequested) break
                    val first = part.first(); val last = part.last()
                    // Contexto: a conversa inteira na nuvem (até 60 min); senão, um pedaço em volta
                    val ctxRange = when {
                        nano -> maxOf(0, first - 5)..minOf(lines.size - 1, last + 5)
                        total <= FULL_MAX_SEC -> lines.indices
                        else -> maxOf(0, first - 40)..minOf(lines.size - 1, last + 15)
                    }
                    val sb = StringBuilder()
                    sb.append("Você traduz transcrições automáticas de áudio em japonês para o português do Brasil. ")
                    sb.append("A transcrição pode ter erros; use o contexto da conversa para escolher o sentido certo.\n")
                    sb.append("Transcrição (cada frase com seu número):\n")
                    for (i in ctxRange) sb.append('[').append(i + 1).append("] ").append(lines[i].text).append('\n')
                    sb.append("\nTraduza somente as frases de número: ").append(part.joinToString(",") { (it + 1).toString() })
                    sb.append(". Tradução natural, fiel ao sentido, sem explicações.\n")
                    sb.append("Responda somente com JSON, sem markdown: {\"traducoes\": [{\"n\": número, \"pt\": \"tradução\"}, ...]}")
                    val out = if (nano) runNano(sb.toString(), 1024) else runCloud(app, sb.toString(), 16384)
                    val a = out.indexOf('{'); val b = out.lastIndexOf('}')
                    val arr = JSONObject(out.substring(a, b + 1)).optJSONArray("traducoes") ?: JSONArray()
                    for (k in 0 until arr.length()) {
                        val o = arr.optJSONObject(k) ?: continue
                        val i = o.optInt("n", 0) - 1
                        val pt = o.optString("pt").trim()
                        if (i in part && pt.isNotEmpty()) { db.putAiTranslation(lines[i].text, pt); count++ }
                    }
                    val n = count
                    main.post { progress(n, todo.size) }
                }
                null
            } catch (e: Exception) {
                Log.e(TAG, "translateRecording", e)
                e.message ?: e.javaClass.simpleName
            }
            val n = count
            main.post { done(n, err) }
        }.start()
    }

    // =====================================================================
    // Prompts
    // =====================================================================

    private fun prompt(ctx: Context, r: Request): String {
        if (r.task == Task.GUIDE) return guidePrompt(r)
        val context = contextLines(ctx, r.recording, r.sentence)
        val sb = StringBuilder()
        sb.append("Você é professor de japonês de um brasileiro que estuda para o JLPT N${r.level}. ")
        sb.append("A frase veio de uma transcrição automática de áudio e pode ter erros.\n")
        if (context.isNotEmpty()) {
            sb.append("Transcrição da gravação (em ordem; a frase em estudo está marcada com >>):\n")
            for (l in context) sb.append(l).append('\n')
        }
        sb.append("Frase em estudo: 「").append(r.sentence).append("」\n")
        when (r.task) {
            Task.TRANSLATE -> sb.append(
                "Traduza a frase em estudo para o português do Brasil, usando o contexto para escolher o sentido certo. " +
                    "Chaves do JSON: \"traducao\" (tradução natural), \"literal\" (tradução literal curta), " +
                    "\"observacao\" (uma linha: ambiguidade, nuance ou possível erro de transcrição; \"\" se não houver)."
            )
            Task.WORD -> sb.append(
                "Explique a palavra 「${r.target}」" + (if (r.note.isNotBlank()) " (${r.note})" else "") + " como usada nesta frase. " +
                    "Chaves do JSON: \"leitura\" (em hiragana), \"significado_aqui\" (o sentido nesta frase, 1 linha), " +
                    "\"outros_significados\" (lista com até 3 outros sentidos comuns), " +
                    "\"forma\" (como aparece na frase e por quê: conjugação, partícula; 1 linha), " +
                    "\"exemplo_ja\" (frase nova, curta e simples), \"exemplo_pt\" (tradução do exemplo)."
            )
            Task.GRAMMAR -> sb.append(
                (if (r.target.isNotBlank()) "Explique o padrão gramatical 「${r.target}」" + (if (r.note.isNotBlank()) " (trecho: ${r.note})" else "") + " como usado nesta frase. "
                else "Explique o ponto gramatical principal desta frase. ") +
                    "Chaves do JSON: \"padrao\" (nome do padrão), \"significado\" (1 linha), " +
                    "\"como_formar\" (a regra de formação completa, pode ter várias linhas: se liga a verbos, a regra para o grupo 1 (godan), " +
                    "o grupo 2 (ichidan) e o grupo 3 (する e 来る), cada um com um exemplo em hiragana; se liga a adjetivos-い, adjetivos-な ou " +
                    "substantivos, como cada um se liga; e as exceções importantes), " +
                    "\"nesta_frase\" (o que ele faz nesta frase, 1 linha), \"exemplo_ja\" (frase nova e simples), " +
                    "\"exemplo_pt\" (tradução do exemplo), \"nivel\" (N5 a N1)."
            )
            Task.GUIDE -> {}
        }
        sb.append("\nResponda somente com um objeto JSON, sem markdown. Textos curtos, em português do Brasil (o japonês em japonês).")
        return sb.toString()
    }

    private val txtCache = android.util.LruCache<String, List<StudyGen.Line>>(6)

    /** Na nuvem, a transcrição inteira até este tamanho (≈ 10 mil tokens); acima, uma janela de ±5 min. */
    private const val FULL_MAX_SEC = 60 * 60
    private const val CLOUD_WINDOW_SEC = 5 * 60
    /** No Nano (limite de ~4 mil tokens), ±2 min em volta da frase. */
    private const val NANO_WINDOW_SEC = 2 * 60

    /**
     * Contexto da gravação, em ordem, com a frase em estudo marcada com >>. Nuvem: a transcrição inteira
     * (até 60 min); Nano: ±2 min. Lê o .txt; chame fora da UI.
     */
    fun contextLines(ctx: Context, recording: String?, sentence: String): List<String> {
        if (recording.isNullOrBlank() || recording.startsWith("__")) return emptyList()
        val lines = txtCache.get(recording) ?: run {
            val item = Recordings.find(ctx, "$recording.txt") ?: return emptyList()
            val text = try { Recordings.readText(ctx, item.uri) } catch (_: Exception) { return emptyList() }
            StudyGen.lines(text).also { txtCache.put(recording, it) }
        }
        val i = lines.indexOfFirst { it.text == sentence }
        if (i < 0) return emptyList()
        val at = lines[i].sec
        val total = lines.lastOrNull()?.endSec ?: 0
        val window = when {
            mode(ctx) == NANO -> NANO_WINDOW_SEC
            total <= FULL_MAX_SEC -> Int.MAX_VALUE
            else -> CLOUD_WINDOW_SEC
        }
        return lines.withIndex().filter { (j, l) ->
            j == i || window == Int.MAX_VALUE || at < 0 || l.sec < 0 || kotlin.math.abs(l.sec - at) <= window
        }.map { (j, l) -> (if (j == i) ">> " else "") + l.text }
    }

    // =====================================================================
    // Resposta → campos
    // =====================================================================

    /** Material de estudo de um nível (dicionário de gramática): r.sentence = "N5", r.note = padrões, um por linha. */
    private fun guidePrompt(r: Request): String =
        "Você é professor de japonês de um brasileiro que estuda para o JLPT N${r.level}. " +
            "Escreva um material de estudo, em português do Brasil, sobre a gramática do nível ${r.sentence}, cobrindo estes padrões " +
            "(um por linha: nome — resumo):\n${r.note}\n" +
            "Formato: texto corrido, sem JSON e sem markdown (nada de asteriscos, # ou tabelas). " +
            "Comece com a linha VISÃO GERAL e 3 a 5 linhas sobre o que a gramática desse nível permite dizer e por onde começar. " +
            "Depois, uma seção por padrão, na ordem dada: o nome do padrão numa linha própria; depois \"Significado:\"; " +
            "\"Formação:\" (para verbos, grupo 1 godan, grupo 2 ichidan e grupo 3 する／来る, cada um com exemplo em hiragana; " +
            "para adjetivos e substantivos, como se ligam); e \"Exemplo:\" com uma frase simples e a tradução. Uma linha em branco entre as seções. " +
            "Termine com a linha DICAS E ERROS COMUNS: padrões parecidos e como diferenciar, e os erros mais comuns de brasileiros. " +
            "O japonês em japonês, com a leitura em hiragana entre parênteses quando houver kanji."

    private val labels = mapOf(
        Task.TRANSLATE to listOf("traducao" to "Tradução (IA)", "literal" to "Literal", "observacao" to "Obs."),
        Task.WORD to listOf(
            "leitura" to "Leitura", "significado_aqui" to "Aqui significa", "outros_significados" to "Outros sentidos",
            "forma" to "Na frase", "exemplo_ja" to "Exemplo", "exemplo_pt" to ""
        ),
        Task.GRAMMAR to listOf(
            "padrao" to "Padrão", "nivel" to "Nível", "significado" to "Significado", "como_formar" to "Como se forma",
            "nesta_frase" to "Nesta frase", "exemplo_ja" to "Exemplo", "exemplo_pt" to ""
        ),
        Task.GUIDE to listOf("introducao" to "Visão geral", "material" to "Padrões", "dicas" to "Dicas e erros comuns"),
    )

    private fun parse(task: Task, out: String, model: String, cached: Boolean): Result {
        if (task == Task.GUIDE) return Result(emptyList(), out.trim(), if (out.isBlank()) "Resposta vazia" else null, model, cached)
        val a = out.indexOf('{')
        val b = out.lastIndexOf('}')
        val o = try { if (a >= 0 && b > a) JSONObject(out.substring(a, b + 1)) else null } catch (_: Exception) { null }
            ?: return Result(emptyList(), out.trim(), if (out.isBlank()) "Resposta vazia" else null, model, cached)
        val fields = mutableListOf<Pair<String, String>>()
        for ((k, label) in labels.getValue(task)) {
            val v = o.opt(k) ?: continue
            val text = when (v) {
                is JSONArray -> (0 until v.length()).map { v.optString(it) }.filter { it.isNotBlank() }.joinToString("; ")
                else -> v.toString()
            }.trim()
            if (text.isNotEmpty() && text != "null") fields.add(label to text)
        }
        return Result(fields, out.trim(), null, model, cached)
    }

    // =====================================================================
    // Gemini na nuvem (REST)
    // =====================================================================

    /** Pedido com corpo pronto (ex.: áudio + texto) ao modelo da nuvem; devolve o texto da resposta. */
    private fun runCloudBody(ctx: Context, body: JSONObject): String {
        val p = Prefs(ctx)
        val key = p.geminiKey.ifBlank { TEST_KEY }
        if (key.isBlank()) throw IllegalStateException("Sem chave do Gemini: cole a sua em Configurações → Inteligência artificial.")
        val model = p.geminiModel.ifBlank { DEFAULT_MODEL }
        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"; conn.connectTimeout = 15000; conn.readTimeout = 180000; conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", key)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = try { JSONObject(text).getJSONObject("error").optString("message") } catch (_: Exception) { text.take(200) }
                throw RuntimeException("Gemini respondeu $code: $msg")
            }
            val parts = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
            return (0 until parts.length()).map { parts.getJSONObject(it) }.filter { !it.optBoolean("thought", false) }.joinToString("") { it.optString("text") }
        } finally {
            conn.disconnect()
        }
    }

    private fun runCloud(ctx: Context, prompt: String, maxTokens: Int = 2048, json: Boolean = true): String {
        val p = Prefs(ctx)
        val key = p.geminiKey.ifBlank { TEST_KEY }
        if (key.isBlank()) throw IllegalStateException("Sem chave do Gemini: cole a sua em Configurações → Inteligência artificial.")
        val model = p.geminiModel.ifBlank { DEFAULT_MODEL }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("responseMimeType", if (json) "application/json" else "text/plain").put("temperature", 0.3).put("maxOutputTokens", maxTokens))
        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = if (maxTokens > 4096) 180000 else 45000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", key)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = try { JSONObject(text).getJSONObject("error").optString("message") } catch (_: Exception) { text.take(200) }
                throw RuntimeException("Gemini respondeu $code: $msg")
            }
            val parts = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
            return (0 until parts.length()).map { parts.getJSONObject(it) }
                .filter { !it.optBoolean("thought", false) }.joinToString("") { it.optString("text") }
        } finally {
            conn.disconnect()
        }
    }

    // =====================================================================
    // Gemini Nano (no aparelho)
    // =====================================================================

    @Volatile private var nano: GenerativeModel? = null

    private fun nanoClient(): GenerativeModel = nano ?: synchronized(this) { nano ?: Generation.getClient().also { nano = it } }

    private fun runNano(prompt: String, maxTokens: Int = 512): String = runBlocking {
        val m = nanoClient()
        when (m.checkStatus()) {
            FeatureStatus.UNAVAILABLE -> throw IllegalStateException("O Gemini Nano não está disponível neste aparelho.")
            FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING ->
                throw IllegalStateException("O Gemini Nano ainda não foi baixado. Em Configurações, toque em Testar a IA para baixar.")
        }
        val resp = m.generateContent(generateContentRequest(TextPart(prompt)) {
            temperature = 0.3f
            maxOutputTokens = maxTokens
        })
        resp.candidates.firstOrNull()?.text ?: ""
    }

    /**
     * Teste das Configurações: estado do modelo e uma pergunta curta. No Nano, baixa o modelo se
     * precisar (progress mostra o andamento). Os callbacks rodam na thread principal.
     */
    fun test(ctx: Context, progress: (String) -> Unit, done: (String) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val t0 = System.currentTimeMillis()
            val msg = try {
                when (mode(app)) {
                    NANO -> runBlocking {
                        val m = nanoClient()
                        var status = m.checkStatus()
                        if (status == FeatureStatus.UNAVAILABLE) return@runBlocking "Gemini Nano indisponível neste aparelho (AICore sem suporte)."
                        if (status != FeatureStatus.AVAILABLE) {
                            main.post { progress("Baixando o Gemini Nano…") }
                            var total = 0L
                            var failed: String? = null
                            m.download().collect { s ->
                                when (s) {
                                    is DownloadStatus.DownloadStarted -> total = s.bytesToDownload
                                    is DownloadStatus.DownloadProgress -> {
                                        val mb = s.totalBytesDownloaded / 1_000_000
                                        main.post { progress("Baixando o Gemini Nano… $mb MB" + if (total > 0) " de ${total / 1_000_000} MB" else "") }
                                    }
                                    is DownloadStatus.DownloadFailed -> failed = s.e.message
                                    else -> {}
                                }
                            }
                            if (failed != null) return@runBlocking "Falha ao baixar o Gemini Nano: $failed"
                            status = m.checkStatus()
                        }
                        main.post { progress("Perguntando ao Gemini Nano…") }
                        val out = m.generateContent("日本語の「雪」はポルトガル語で何ですか？一言で答えてください。").candidates.firstOrNull()?.text ?: ""
                        "Gemini Nano OK em ${System.currentTimeMillis() - t0} ms.\nPergunta: o que é 雪 em português?\nResposta: ${out.trim()}"
                    }
                    CLOUD -> {
                        main.post { progress("Perguntando ao ${modelLabel(app)}…") }
                        val out = runCloud(app, "Responda só com um JSON {\"resposta\": ...}: o que significa 雪 em português?")
                        "${modelLabel(app)} OK em ${System.currentTimeMillis() - t0} ms.\nResposta: ${out.trim()}"
                    }
                    else -> "A IA está desligada."
                }
            } catch (e: Exception) {
                Log.e(TAG, "test", e)
                "Erro: ${e.message ?: e.javaClass.simpleName}"
            }
            main.post { done(msg) }
        }.start()
    }
}
