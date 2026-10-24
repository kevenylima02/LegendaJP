package com.keveny.legendajp

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    companion object {
        private const val REQ_PERMS = 1001
        private const val REQ_PROJECTION = 1002
    }

    private lateinit var statusView: TextView
    private lateinit var partialView: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var transcriptView: TextView
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        statusView = TextView(this).apply { textSize = 15f }
        levelBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        partialView = TextView(this).apply { textSize = 15f; alpha = 0.6f }

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(button("Verificar japonês") { checkJapanese() }, weightParams())
        row1.addView(button("Compartilhar") { share() }, weightParams())

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(button("Iniciar") { startCapture() }, weightParams())
        row2.addView(button("Parar") { stopCapture() }, weightParams())

        transcriptView = TextView(this).apply {
            textSize = 18f
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply { addView(transcriptView) }

        root.addView(statusView)
        root.addView(levelBar)
        root.addView(row1)
        root.addView(row2)
        root.addView(partialView)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        requestNeededPermissions()
    }

    override fun onResume() {
        super.onResume()
        TranscriptStore.listener = { runOnUiThread { render() } }
        render()
    }

    override fun onPause() {
        super.onPause()
        TranscriptStore.listener = null
    }

    private fun weightParams() =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun button(label: String, action: () -> Unit) =
        Button(this).apply {
            text = label
            setOnClickListener { action() }
        }

    private fun requestNeededPermissions(): Boolean {
        val needed = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), REQ_PERMS)
        return needed.isEmpty()
    }

    private fun render() {
        statusView.text = TranscriptStore.status
        levelBar.progress = TranscriptStore.level
        partialView.text = TranscriptStore.partial
        val text = TranscriptStore.lines.joinToString("\n")
        if (transcriptView.text.toString() != text) {
            transcriptView.text = text
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun setStatus(msg: String) {
        TranscriptStore.status = msg
        render()
    }

    private fun checkJapanese() {
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            setStatus("Reconhecimento on-device indisponível neste aparelho. O app vai tentar o reconhecedor padrão.")
            return
        }
        val rec = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
        setStatus("Verificando suporte a japonês...")
        rec.checkRecognitionSupport(intent, mainExecutor, object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) {
                fun hasJa(list: List<String>) = list.any { it.startsWith("ja") }
                val msg = when {
                    hasJa(support.installedOnDeviceLanguages) -> "Japonês offline instalado ✓ Pode iniciar."
                    hasJa(support.pendingOnDeviceLanguages) -> "Pacote de japonês baixando. Espere e verifique de novo."
                    hasJa(support.supportedOnDeviceLanguages) -> {
                        rec.triggerModelDownload(intent)
                        "Download do pacote de japonês solicitado. Verifique de novo em alguns minutos."
                    }
                    else -> "Japonês não suportado offline. Instalados: ${support.installedOnDeviceLanguages}"
                }
                setStatus(msg)
                rec.destroy()
            }

            override fun onError(error: Int) {
                setStatus("Erro ao verificar idioma: ${CaptureService.errorText(error)}")
                rec.destroy()
            }
        })
    }

    private fun startCapture() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestNeededPermissions()
            setStatus("Permita o acesso ao microfone (o Android exige para capturar áudio interno).")
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val captureIntent = if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        @Suppress("DEPRECATION")
        startActivityForResult(captureIntent, REQ_PROJECTION)
    }

    @Deprecated("Activity simples sem AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROJECTION) return
        if (resultCode != RESULT_OK || data == null) {
            setStatus("Captura cancelada.")
            return
        }
        val svc = Intent(this, CaptureService::class.java)
            .putExtra(CaptureService.EXTRA_CODE, resultCode)
            .putExtra(CaptureService.EXTRA_DATA, data)
        startForegroundService(svc)
    }

    private fun stopCapture() {
        startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
    }

    private fun share() {
        val text = TranscriptStore.lines.joinToString("\n")
        if (text.isBlank()) {
            setStatus("Nada transcrito ainda.")
            return
        }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, "Compartilhar transcrição"))
    }
}
