package com.keveny.legendajp

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val REQ_PERMS = 1001
        private const val REQ_PROJECTION = 1002
        private const val REQ_FILE = 1003
    }

    private lateinit var prefs: Prefs
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var statusDetail: TextView
    private lateinit var timer: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var sourceGroup: RadioGroup
    private lateinit var transcript: TextView
    private lateinit var partial: TextView
    private lateinit var scroll: ScrollView
    private lateinit var btnMain: Button

    private var renderedKey = ""
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (TranscriptStore.state == TranscriptStore.State.RUNNING) {
                val sec = ((System.currentTimeMillis() - TranscriptStore.startedAt) / 1000).toInt()
                timer.text = CaptureService.formatTime(sec)
                ui.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        statusDetail = findViewById(R.id.statusDetail)
        timer = findViewById(R.id.timer)
        levelBar = findViewById(R.id.levelBar)
        sourceGroup = findViewById(R.id.sourceGroup)
        transcript = findViewById(R.id.transcript)
        partial = findViewById(R.id.partial)
        scroll = findViewById(R.id.scroll)
        btnMain = findViewById(R.id.btnMain)

        sourceGroup.check(
            when (prefs.source) {
                CaptureService.MODE_MIC -> R.id.sourceMic
                CaptureService.MODE_FILE -> R.id.sourceFile
                else -> R.id.sourceInternal
            }
        )
        sourceGroup.setOnCheckedChangeListener { _, id ->
            prefs.source = when (id) {
                R.id.sourceMic -> CaptureService.MODE_MIC
                R.id.sourceFile -> CaptureService.MODE_FILE
                else -> CaptureService.MODE_CAPTURE
            }
        }

        btnMain.setOnClickListener { onMainButton() }
        findViewById<Button>(R.id.btnRecordings).setOnClickListener {
            startActivity(Intent(this, RecordingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnVocab).setOnClickListener {
            startActivity(Intent(this, VocabActivity::class.java))
        }
        transcript.movementMethod = LinkMovementMethod.getInstance()
        transcript.highlightColor = Color.TRANSPARENT
        findViewById<Button>(R.id.btnSpotify).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/")))
        }

        if (TranscriptStore.status.isEmpty()) TranscriptStore.status = getString(R.string.status_ready)
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
        ui.removeCallbacks(tick)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_save_wav).isChecked = prefs.saveWav
        menu.findItem(R.id.action_overlay).isChecked = prefs.overlay && Overlay.canDraw(this)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_share -> share()
            R.id.action_check -> checkJapanese()
            R.id.action_overlay -> {
                if (item.isChecked) {
                    prefs.overlay = false
                    item.isChecked = false
                } else {
                    prefs.overlay = true
                    prefs.overlayAsked = true
                    if (Overlay.canDraw(this)) item.isChecked = true else openOverlaySettings()
                }
            }
            R.id.action_save_wav -> {
                prefs.saveWav = !prefs.saveWav
                item.isChecked = prefs.saveWav
                Toast.makeText(
                    this,
                    if (prefs.saveWav) "O .wav será salvo junto do .txt" else "Só o .txt será salvo (sem player nas frases)",
                    Toast.LENGTH_SHORT
                ).show()
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ---------- UI ----------

    private fun render() {
        val state = TranscriptStore.state
        ui.removeCallbacks(tick)
        if (state == TranscriptStore.State.RUNNING) ui.post(tick)
        statusText.text = TranscriptStore.status
        statusDetail.text = TranscriptStore.detail
        statusDetail.visibility = if (TranscriptStore.detail.isEmpty()) View.GONE else View.VISIBLE
        levelBar.progress = TranscriptStore.level

        val dotColor = when (state) {
            TranscriptStore.State.RUNNING -> getColor(R.color.ok)
            TranscriptStore.State.STOPPING -> getColor(R.color.warn)
            TranscriptStore.State.IDLE -> getColor(R.color.text_secondary)
        }
        (statusDot.background.mutate() as GradientDrawable).setColor(dotColor)

        val running = state != TranscriptStore.State.IDLE
        btnMain.text = getString(if (running) R.string.stop else R.string.start)
        btnMain.setBackgroundResource(if (running) R.drawable.bg_button_stop else R.drawable.bg_button_primary)
        btnMain.isEnabled = state != TranscriptStore.State.STOPPING
        for (i in 0 until sourceGroup.childCount) sourceGroup.getChildAt(i).isEnabled = !running
        if (!running) timer.text = CaptureService.formatTime(
            if (TranscriptStore.startedAt == 0L) 0
            else ((System.currentTimeMillis() - TranscriptStore.startedAt) / 1000).toInt()
        )

        val lines = TranscriptStore.lines
        val key = lines.joinToString("\n") + "\u0000" + VocabDb.savedKeys.size
        if (lines.isEmpty()) {
            transcript.text = getString(R.string.empty_transcript)
            transcript.setTextColor(getColor(R.color.text_secondary))
            renderedKey = ""
        } else if (renderedKey != key) {
            renderedKey = key
            val sb = SpannableStringBuilder()
            for ((i, line) in lines.withIndex()) {
                val (sec, sentence) = Recordings.parseLine(line)
                if (i > 0) sb.append("\n")
                sb.append(Jp.decorate(sentence, VocabDb.savedKeys, getColor(R.color.saved_word)) { w ->
                    WordCard.show(this, w, sentence, TranscriptStore.recording, maxOf(0, sec),
                        onChanged = { renderedKey = ""; render() })
                })
            }
            transcript.text = sb
            transcript.setTextColor(getColor(R.color.text))
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
        val p = TranscriptStore.partial
        partial.text = if (p.isEmpty() && running) getString(R.string.partial_hint) else p
        partial.visibility = if (running) View.VISIBLE else View.GONE
        if (running && p.isNotEmpty()) scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun setStatus(msg: String, detail: String = "") {
        TranscriptStore.status = msg
        TranscriptStore.detail = detail
        render()
    }

    // ---------- Ações ----------

    private fun onMainButton() {
        if (TranscriptStore.state != TranscriptStore.State.IDLE) {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
            return
        }
        if (prefs.source != CaptureService.MODE_FILE && prefs.overlay && !Overlay.canDraw(this) && !prefs.overlayAsked) {
            askOverlayPermission()
            return
        }
        startSelectedSource()
    }

    private fun startSelectedSource() {
        when (prefs.source) {
            CaptureService.MODE_MIC -> startMic()
            CaptureService.MODE_FILE -> pickFile()
            else -> startCapture()
        }
    }

    private fun askOverlayPermission() {
        AlertDialog.Builder(this)
            .setTitle(R.string.overlay_dialog_title)
            .setMessage(R.string.overlay_dialog_text)
            .setPositiveButton(R.string.overlay_allow) { _, _ ->
                prefs.overlayAsked = true
                openOverlaySettings()
            }
            .setNegativeButton(R.string.overlay_later) { _, _ ->
                prefs.overlayAsked = true
                prefs.overlay = false
                startSelectedSource()
            }
            .show()
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
        Toast.makeText(this, "Ative \"Exibir sobre outros apps\" para o Michi e volte", Toast.LENGTH_LONG).show()
    }

    private fun requestNeededPermissions(): Boolean {
        val needed = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), REQ_PERMS)
        return needed.isEmpty()
    }

    private fun hasMic() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startCapture() {
        if (!hasMic()) {
            requestNeededPermissions()
            setStatus("Permita o microfone", "O Android exige essa permissão para capturar áudio interno.")
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

    private fun startMic() {
        if (!hasMic()) {
            requestNeededPermissions()
            setStatus("Permita o microfone")
            return
        }
        startForegroundService(
            Intent(this, CaptureService::class.java)
                .putExtra(CaptureService.EXTRA_MODE, CaptureService.MODE_MIC)
        )
    }

    private fun pickFile() {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("audio/*")
        @Suppress("DEPRECATION")
        startActivityForResult(pick, REQ_FILE)
    }

    @Deprecated("Activity simples sem AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_PROJECTION -> {
                if (resultCode != RESULT_OK || data == null) {
                    setStatus("Captura cancelada")
                    return
                }
                startForegroundService(
                    Intent(this, CaptureService::class.java)
                        .putExtra(CaptureService.EXTRA_MODE, CaptureService.MODE_CAPTURE)
                        .putExtra(CaptureService.EXTRA_CODE, resultCode)
                        .putExtra(CaptureService.EXTRA_DATA, data)
                )
            }
            REQ_FILE -> {
                val uri = data?.data
                if (resultCode != RESULT_OK || uri == null) {
                    setStatus("Nenhum arquivo escolhido")
                    return
                }
                startForegroundService(
                    Intent(this, CaptureService::class.java)
                        .putExtra(CaptureService.EXTRA_MODE, CaptureService.MODE_FILE)
                        .putExtra(CaptureService.EXTRA_FILE, uri)
                )
            }
        }
    }

    private fun share() {
        val text = TranscriptStore.lines.joinToString("\n")
        if (text.isBlank()) {
            Toast.makeText(this, "Nada transcrito ainda", Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, getString(R.string.share)))
    }

    private fun checkJapanese() {
        val diag = Diag.summary(this)
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            setStatus("Reconhecimento on-device indisponível", diag)
            return
        }
        val rec = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, prefs.language)
        setStatus("Verificando japonês offline")
        rec.checkRecognitionSupport(intent, mainExecutor, object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) {
                fun hasJa(list: List<String>) = list.any { it.startsWith("ja") }
                val msg = when {
                    hasJa(support.installedOnDeviceLanguages) -> "Japonês offline instalado ✓"
                    hasJa(support.pendingOnDeviceLanguages) -> "Pacote de japonês baixando, verifique de novo em instantes"
                    hasJa(support.supportedOnDeviceLanguages) -> {
                        rec.triggerModelDownload(intent)
                        "Download do pacote de japonês solicitado"
                    }
                    else -> "Japonês não suportado offline (instalados: ${support.installedOnDeviceLanguages})"
                }
                setStatus(msg, diag)
                rec.destroy()
            }

            override fun onError(error: Int) {
                setStatus("Erro ao verificar idioma: ${CaptureService.errorText(error)}", diag)
                rec.destroy()
            }
        })
    }
}
