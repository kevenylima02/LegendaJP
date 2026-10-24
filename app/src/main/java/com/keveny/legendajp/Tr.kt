package com.keveny.legendajp

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * Tradução japonês → português offline (ML Kit). O modelo (~30 MB) é baixado uma vez,
 * na primeira tradução. Resultados ficam em cache no banco, então cada frase só é
 * traduzida uma vez.
 */
object Tr {
    private val options = TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.JAPANESE)
        .setTargetLanguage(TranslateLanguage.PORTUGUESE)
        .build()
    private val translator: Translator by lazy { Translation.getClient(options) }

    @Volatile private var modelReady = false
    @Volatile private var downloading = false
    private val waiting = mutableListOf<(Boolean) -> Unit>()

    fun cached(ctx: Context, text: String): String? = VocabDb.get(ctx).translation(text)

    /** Garante o modelo baixado; avisa o usuário na primeira vez. */
    private fun ensureModel(ctx: Context, cb: (Boolean) -> Unit) {
        if (modelReady) { cb(true); return }
        synchronized(waiting) { waiting.add(cb) }
        if (downloading) return
        downloading = true
        Toast.makeText(ctx, "Preparando a tradução offline (baixa ~30 MB na primeira vez)…", Toast.LENGTH_LONG).show()
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                modelReady = true
                downloading = false
                flush(true)
            }
            .addOnFailureListener { e ->
                Log.e(CaptureService.TAG, "modelo de tradução", e)
                downloading = false
                Toast.makeText(ctx, "Não consegui baixar o modelo de tradução: ${e.message}", Toast.LENGTH_LONG).show()
                flush(false)
            }
    }

    private fun flush(ok: Boolean) {
        val list = synchronized(waiting) { val l = waiting.toList(); waiting.clear(); l }
        for (cb in list) cb(ok)
    }

    /** Traduz (usando o cache quando houver). O callback roda na thread principal. */
    fun translate(ctx: Context, text: String, cb: (String?) -> Unit) {
        val app = ctx.applicationContext
        cached(app, text)?.let { cb(it); return }
        ensureModel(app) { ok ->
            if (!ok) { cb(null); return@ensureModel }
            translator.translate(text)
                .addOnSuccessListener { out ->
                    val t = out.trim()
                    if (t.isNotEmpty()) VocabDb.get(app).putTranslation(text, t)
                    cb(t.ifEmpty { null })
                }
                .addOnFailureListener { e ->
                    Log.e(CaptureService.TAG, "traduzir", e)
                    cb(null)
                }
        }
    }
}
