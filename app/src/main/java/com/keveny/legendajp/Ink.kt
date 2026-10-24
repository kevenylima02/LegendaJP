package com.keveny.legendajp

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext

/** Reconhecimento de escrita à mão em japonês (ML Kit Digital Ink), offline após baixar o modelo. */
object InkJp {
    private val model: DigitalInkRecognitionModel by lazy {
        DigitalInkRecognitionModel.builder(DigitalInkRecognitionModelIdentifier.JA).build()
    }
    private val recognizer: DigitalInkRecognizer by lazy {
        DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
    }
    @Volatile var modelReady = false
        private set
    @Volatile private var downloading = false
    private val waiting = mutableListOf<(Boolean) -> Unit>()

    fun ensureModel(ctx: Context, cb: (Boolean) -> Unit) {
        if (modelReady) { cb(true); return }
        synchronized(waiting) { waiting.add(cb) }
        if (downloading) return
        downloading = true
        val mgr = RemoteModelManager.getInstance()
        mgr.isModelDownloaded(model).addOnSuccessListener { has ->
            if (has) { modelReady = true; downloading = false; flush(true); return@addOnSuccessListener }
            Toast.makeText(ctx, "Baixando o modelo de escrita à mão (~20 MB, só na primeira vez)…", Toast.LENGTH_LONG).show()
            mgr.download(model, DownloadConditions.Builder().build())
                .addOnSuccessListener { modelReady = true; downloading = false; flush(true) }
                .addOnFailureListener { e ->
                    Log.e(CaptureService.TAG, "modelo de escrita", e)
                    downloading = false
                    Toast.makeText(ctx, "Não consegui baixar o modelo de escrita: ${e.message}", Toast.LENGTH_LONG).show()
                    flush(false)
                }
        }.addOnFailureListener { downloading = false; flush(false) }
    }

    private fun flush(ok: Boolean) {
        val list = synchronized(waiting) { val l = waiting.toList(); waiting.clear(); l }
        for (cb in list) cb(ok)
    }

    /** Candidatos (melhor primeiro) para o traçado. Callback na thread principal. */
    fun recognize(ctx: Context, ink: Ink, expected: String?, cb: (List<String>) -> Unit) {
        ensureModel(ctx) { ok ->
            if (!ok) { cb(emptyList()); return@ensureModel }
            val rc = RecognitionContext.builder().setPreContext(expected ?: "").build()
            recognizer.recognize(ink, rc)
                .addOnSuccessListener { r -> cb(r.candidates.map { it.text.trim() }.filter { it.isNotEmpty() }) }
                .addOnFailureListener { e -> Log.e(CaptureService.TAG, "reconhecer escrita", e); cb(emptyList()) }
        }
    }
}
