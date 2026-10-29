package com.keveny.legendajp

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gemini para o leitor de mangá: lê só o balão tocado (recorte da página) ou a página inteira (todos
 * os balões na ordem de leitura, com a posição). Nada é pedido sem toque; as respostas ficam guardadas
 * (VocabDb.aiGet) por página e região, então o mesmo balão não é pedido duas vezes.
 */
object MangaAi {

    /** Texto de um balão; x e y = centro do balão como fração da página (−1 quando não se sabe). */
    data class Bubble(val text: String, val translation: String, val x: Float = -1f, val y: Float = -1f)

    private const val TAG = "MichiManga"
    private val main = Handler(Looper.getMainLooper())

    private fun model(ctx: Context) = Prefs(ctx).geminiModel.ifBlank { Ai.DEFAULT_MODEL }
    private fun cacheKey(ctx: Context, key: String) = "manga|${model(ctx)}|$key"

    private fun jpeg(bmp: Bitmap, maxSide: Int): String {
        val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        val b = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
        val bytes = java.io.ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        if (b !== bmp) b.recycle()
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    private fun ask(ctx: Context, image: String, prompt: String, maxTokens: Int): String {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", image)))
                .put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.1).put("maxOutputTokens", maxTokens))
        return Ai.runCloudBody(ctx, body)
    }

    private fun obj(out: String): JSONObject {
        val a = out.indexOf('{'); val b = out.lastIndexOf('}')
        if (a < 0 || b <= a) throw IllegalStateException("resposta sem JSON")
        return JSONObject(out.substring(a, b + 1))
    }

    private fun clean(s: String) = s.replace("\n", "").replace(" ", "").trim()

    /**
     * Lê o balão no centro do recorte (marcado com um círculo vermelho). [key] identifica página e
     * região para o cache.
     */
    fun bubble(ctx: Context, crop: Bitmap, key: String, cb: (Bubble?, String?) -> Unit) {
        val app = ctx.applicationContext
        val image = jpeg(crop, 1200)
        Thread {
            var res: Bubble? = null
            val err = try {
                val k = cacheKey(app, "bubble|$key")
                val out = VocabDb.get(app).aiGet(k) ?: run {
                    val prompt = "Esta imagem é um recorte de uma página de mangá. Transcreva o texto japonês do balão de fala " +
                        "(ou legenda, ou placa) que está marcado pelo círculo vermelho; se o círculo não estiver sobre texto, use o balão mais perto dele. " +
                        "Só esse balão, inteiro. Ignore a furigana pequena ao lado dos kanji, onomatopeias desenhadas fora dos balões e texto cortado na borda. " +
                        "Texto vertical: leia as colunas de cima para baixo, da direita para a esquerda. Junte as linhas numa frase só. " +
                        "Responda somente com JSON: {\"texto\": \"o texto japonês\", \"traducao\": \"tradução natural para o português do Brasil\"}. " +
                        "Se não houver texto japonês, responda {\"texto\": \"\", \"traducao\": \"\"}."
                    ask(app, image, prompt, 1024).also { VocabDb.get(app).aiPut(k, it) }
                }
                val o = obj(out)
                val t = clean(o.optString("texto"))
                if (t.isNotEmpty()) res = Bubble(t, o.optString("traducao").trim())
                if (t.isEmpty()) "não achei texto nesse ponto" else null
            } catch (e: Exception) {
                Log.e(TAG, "bubble", e); e.message ?: e.javaClass.simpleName
            }
            val r = res
            main.post { cb(r, err) }
        }.start()
    }

    /** Página já lida pelo ✦ (do cache), ou null. Chame fora da UI. */
    fun cachedPage(ctx: Context, key: String): List<Bubble>? =
        VocabDb.get(ctx).aiGet(cacheKey(ctx, "page2|$key"))?.let { try { parsePage(it) } catch (_: Exception) { null } }

    /** Coordenada em fração (0..1): aceita 0..1, 0..1000 (formato do Gemini) ou −1. */
    private fun frac(v: Double): Float = when {
        v.isNaN() || v < 0 -> -1f
        v <= 1.0 -> v.toFloat()
        v <= 1000.0 -> (v / 1000.0).toFloat()
        else -> -1f
    }

    private fun parsePage(out: String): List<Bubble> {
        val arr = obj(out).optJSONArray("baloes") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val b = arr.optJSONObject(i) ?: return@mapNotNull null
            val t = clean(b.optString("texto"))
            if (t.isEmpty() || !Jp.isLexical(t)) return@mapNotNull null
            // Caixa [ymin, xmin, ymax, xmax] de 0 a 1000 (formato nativo do Gemini); senão x, y soltos
            val box = b.optJSONArray("caixa")
            val (x, y) = if (box != null && box.length() == 4) {
                frac((box.optDouble(1) + box.optDouble(3)) / 2) to frac((box.optDouble(0) + box.optDouble(2)) / 2)
            } else frac(b.optDouble("x", -1.0)) to frac(b.optDouble("y", -1.0))
            Bubble(t, b.optString("traducao").trim(), x, y)
        }
    }

    /** Todos os balões da página, na ordem de leitura, com tradução e posição. */
    fun page(ctx: Context, page: Bitmap, key: String, cb: (List<Bubble>?, String?) -> Unit) {
        val app = ctx.applicationContext
        val image = jpeg(page, 1600)
        Thread {
            var res: List<Bubble>? = null
            val err = try {
                val k = cacheKey(app, "page2|$key")
                val out = VocabDb.get(app).aiGet(k) ?: run {
                    val prompt = "Esta imagem é uma página de mangá. Liste todos os balões de fala, legendas e placas com texto japonês, " +
                        "na ordem de leitura japonesa: quadros da direita para a esquerda e de cima para baixo; dentro do quadro, balões da direita para a esquerda. " +
                        "Ignore a furigana pequena, os números de página e as onomatopeias desenhadas fora dos balões. Texto vertical: colunas de cima para baixo, " +
                        "da direita para a esquerda; junte as linhas do balão numa frase só. " +
                        "Para cada balão: \"texto\" (japonês), \"traducao\" (português do Brasil, natural) e \"caixa\" (a caixa do balão " +
                        "como [ymin, xmin, ymax, xmax], de 0 a 1000). " +
                        "Responda somente com JSON: {\"baloes\": [{\"texto\": \"\", \"traducao\": \"\", \"caixa\": [0, 0, 0, 0]}]}."
                    ask(app, image, prompt, 6144).also { VocabDb.get(app).aiPut(k, it) }
                }
                res = parsePage(out)
                null
            } catch (e: Exception) {
                Log.e(TAG, "page", e); e.message ?: e.javaClass.simpleName
            }
            val r = res
            main.post { cb(r, err) }
        }.start()
    }
}
