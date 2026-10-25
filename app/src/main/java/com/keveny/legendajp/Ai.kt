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

    private fun cacheKey(ctx: Context, r: Request) = "${r.task}|${modelId(ctx)}|${r.sentence}|${r.target}|${r.note}"

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
                if (!fresh) cached(app, r)?.let { hit -> main.post { cb(hit) }; return@Thread }
                val prompt = prompt(app, r)
                val out = when (mode(app)) {
                    NANO -> runNano(prompt)
                    CLOUD -> runCloud(app, prompt)
                    else -> throw IllegalStateException("A IA está desligada em Configurações.")
                }
                val parsed = parse(r.task, out, modelLabel(app), cached = false)
                if (parsed.error == null) VocabDb.get(app).aiPut(cacheKey(app, r), out)
                parsed
            } catch (e: Exception) {
                Log.e(TAG, "ask", e)
                Result(emptyList(), "", e.message ?: e.javaClass.simpleName, modelLabel(app), false)
            }
            main.post { cb(res) }
        }.start()
    }

    // =====================================================================
    // Prompts
    // =====================================================================

    private fun prompt(ctx: Context, r: Request): String {
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
                    "Chaves do JSON: \"padrao\" (nome do padrão), \"significado\" (1 linha), \"como_formar\" (regra de formação, 1 linha), " +
                    "\"nesta_frase\" (o que ele faz nesta frase, 1 linha), \"exemplo_ja\" (frase nova e simples), " +
                    "\"exemplo_pt\" (tradução do exemplo), \"nivel\" (N5 a N1)."
            )
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
    )

    private fun parse(task: Task, out: String, model: String, cached: Boolean): Result {
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

    private fun runCloud(ctx: Context, prompt: String): String {
        val p = Prefs(ctx)
        val key = p.geminiKey.ifBlank { TEST_KEY }
        if (key.isBlank()) throw IllegalStateException("Sem chave do Gemini: cole a sua em Configurações → Inteligência artificial.")
        val model = p.geminiModel.ifBlank { DEFAULT_MODEL }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.3).put("maxOutputTokens", 2048))
        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 45000
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

    private fun runNano(prompt: String): String = runBlocking {
        val m = nanoClient()
        when (m.checkStatus()) {
            FeatureStatus.UNAVAILABLE -> throw IllegalStateException("O Gemini Nano não está disponível neste aparelho.")
            FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING ->
                throw IllegalStateException("O Gemini Nano ainda não foi baixado. Em Configurações, toque em Testar a IA para baixar.")
        }
        val resp = m.generateContent(generateContentRequest(TextPart(prompt)) {
            temperature = 0.3f
            maxOutputTokens = 512
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
