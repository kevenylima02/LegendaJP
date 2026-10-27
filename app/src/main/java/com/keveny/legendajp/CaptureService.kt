package com.keveny.legendajp

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Captura áudio (interno, microfone ou arquivo WAV), envia para o SpeechRecognizer
 * por um pipe e grava as frases num .txt com timestamp relativo ao áudio. Em modo
 * captura/microfone salva também o .wav, para o visualizador tocar cada frase.
 * Nenhum arquivo é criado se nada foi transcrito.
 */
class CaptureService : Service() {

    companion object {
        const val TAG = "LegendaJP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_MODE = "mode"
        const val EXTRA_FILE = "file"
        const val MODE_CAPTURE = 0
        const val MODE_MIC = 1
        const val MODE_FILE = 2
        const val ACTION_STOP = "com.keveny.legendajp.STOP"
        const val ACTION_OVERLAY = "com.keveny.legendajp.OVERLAY"
        private const val CHANNEL_ID = "captura"
        private const val NOTIF_ID = 1
        const val SAMPLE_RATE = 16000
        private const val BYTES_PER_SEC = SAMPLE_RATE * 2
        private const val MAX_RESTARTS = 500

        fun errorText(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
            SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
            SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "ERROR_LANGUAGE_NOT_SUPPORTED"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "ERROR_LANGUAGE_UNAVAILABLE"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "ERROR_SERVER_DISCONNECTED"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "ERROR_TOO_MANY_REQUESTS"
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "ERROR_CANNOT_CHECK_SUPPORT"
            else -> "erro $code"
        }

        fun modeName(mode: Int) = when (mode) {
            MODE_MIC -> "microfone"
            MODE_FILE -> "arquivo"
            else -> "áudio interno"
        }

        fun formatTime(sec: Int) = "%02d:%02d".format(sec / 60, sec % 60)
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private var overlay: Overlay? = null
    private var mode = MODE_CAPTURE
    private var projection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var recognizer: SpeechRecognizer? = null
    private var readSide: ParcelFileDescriptor? = null
    @Volatile private var pipeOut: OutputStream? = null

    private var baseName = ""
    private var txtOut: OutputStream? = null
    private var txtUri: Uri? = null
    private var wavTemp: File? = null
    private var wavOut: OutputStream? = null

    @Volatile private var capturing = false
    @Volatile private var stopRequested = false
    private var finished = false
    private var errorMsg: String? = null

    // Posição no áudio (em bytes PCM) e início da frase em andamento
    @Volatile private var totalBytes = 0L
    private var phraseStartSec = -1
    /** Posição do áudio (ms) em que a sessão atual do reconhecedor começou: base dos tempos das palavras. */
    private var sessionStartMs = 0L
    /** Tempo das palavras de cada frase (ms no áudio antes do corte), gravado no banco no fim. */
    private val pendingTimings = mutableListOf<Pair<String, List<WordTiming.Seg>>>()
    private var firstPhraseSec = -1
    private var lastPhraseEndSec = -1
    @Volatile private var peakLevel = 0
    private var segments = 0
    private var restarts = 0
    private var silentRestarts = 0
    private var onDevice = false
    @Volatile private var gotCallback = false
    private val watchdog = Runnable {
        if (!capturing || stopRequested) return@Runnable
        if (gotCallback) { silentRestarts = 0; return@Runnable }
        silentRestarts++
        Log.w(TAG, "reconhecedor sem resposta (#$silentRestarts)")
        if (silentRestarts >= 3) {
            errorMsg = "O reconhecedor não respondeu. Verifique o pacote de idioma offline."
            stopCapture()
        } else {
            restartRecognizer("sem resposta")
        }
    }

    private val audioSec: Int get() = (totalBytes / BYTES_PER_SEC).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        prefs = Prefs(this)
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_OVERLAY) {
            // Botão "Legenda" da notificação: mostra ou esconde a legenda flutuante
            if (capturing) {
                if (overlay == null) { prefs.overlay = true; showOverlay() }
                else { overlay?.hide(); overlay = null }
            }
            return START_NOT_STICKY
        }
        if (capturing) return START_NOT_STICKY

        mode = intent?.getIntExtra(EXTRA_MODE, MODE_CAPTURE) ?: MODE_CAPTURE
        startForegroundNow()
        try {
            when (mode) {
                MODE_CAPTURE -> {
                    val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
                    val data = intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                    if (data == null) {
                        fail("Sem permissão de captura.")
                        return START_NOT_STICKY
                    }
                    beginCapture(code, data)
                }
                MODE_MIC -> beginMic()
                MODE_FILE -> {
                    val uri = intent?.getParcelableExtra(EXTRA_FILE, Uri::class.java)
                    if (uri == null) {
                        fail("Nenhum arquivo escolhido.")
                        return START_NOT_STICKY
                    }
                    beginFile(uri)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "erro ao iniciar", e)
            fail("Erro ao iniciar: ${e.message}")
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNow() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val overlayIntent = PendingIntent.getService(
            this, 3, Intent(this, CaptureService::class.java).setAction(ACTION_OVERLAY),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Transcrevendo (${modeName(mode)})")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stopIntent).build())
            .addAction(Notification.Action.Builder(null, "Legenda", overlayIntent).build())
            .setOngoing(true)
            .build()
        val type = if (mode == MODE_CAPTURE)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        else
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        startForeground(NOTIF_ID, notification, type)
    }

    // ---------- Fontes de áudio ----------

    @SuppressLint("MissingPermission")
    private fun beginCapture(code: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(code, data)
            ?: throw IllegalStateException("MediaProjection nula")
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post { stopCapture() }
            }
        }, handler)
        projection = proj

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val record = AudioRecord.Builder()
            .setAudioFormat(pcmFormat())
            .setBufferSizeInBytes(bufferSize())
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()
        startWithRecord(record, "Capturando áudio interno")
    }

    @SuppressLint("MissingPermission")
    private fun beginMic() {
        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(pcmFormat())
            .setBufferSizeInBytes(bufferSize())
            .build()
        startWithRecord(record, "Ouvindo pelo microfone")
    }

    private fun beginFile(uri: Uri) {
        val stream = contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Não consegui abrir o arquivo")
        val header = ByteArray(44)
        var got = 0
        while (got < 44) {
            val n = stream.read(header, got, 44 - got)
            if (n < 0) break
            got += n
        }
        val isWav = got == 44 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF"
        if (!isWav) {
            stream.close()
            throw IllegalStateException("O arquivo não é WAV. Use um .wav gerado pelo app.")
        }
        val channels = (header[22].toInt() and 0xff) or ((header[23].toInt() and 0xff) shl 8)
        val rate = (header[24].toInt() and 0xff) or ((header[25].toInt() and 0xff) shl 8) or
            ((header[26].toInt() and 0xff) shl 16) or ((header[27].toInt() and 0xff) shl 24)
        val bits = (header[34].toInt() and 0xff) or ((header[35].toInt() and 0xff) shl 8)
        if (channels != 1 || rate != SAMPLE_RATE || bits != 16) {
            stream.close()
            throw IllegalStateException("O WAV precisa ser mono, 16 kHz, 16 bits (este é ${channels}ch ${rate}Hz ${bits}bit)")
        }
        prepareSession(saveWav = false)
        startRecognizer()
        capturing = true
        setStatus("Transcrevendo arquivo")
        Thread({ fileLoop(stream) }, "file-feed").start()
    }

    private fun pcmFormat() = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(SAMPLE_RATE)
        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
        .build()

    private fun bufferSize(): Int {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        return maxOf(minBuf * 4, BYTES_PER_SEC)
    }

    private fun startWithRecord(record: AudioRecord, msg: String) {
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord não inicializou")
        }
        audioRecord = record
        prepareSession(saveWav = prefs.saveWav)
        startRecognizer()
        record.startRecording()
        capturing = true
        setStatus(msg)
        showOverlay()
        Thread({ audioLoop(record) }, "audio-capture").start()
    }

    private fun showOverlay() {
        if (!prefs.overlay || !Overlay.canDraw(this)) return
        try {
            overlay = Overlay(this, prefs).also { it.show() }
        } catch (e: Exception) {
            Log.e(TAG, "overlay", e)
        }
    }

    private fun prepareSession(saveWav: Boolean) {
        TranscriptStore.reset(mode)
        baseName = Recordings.newBaseName()
        TranscriptStore.recording = baseName
        onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        Log.i(TAG, "modo=${modeName(mode)} onDevice=$onDevice servico=${Diag.onDeviceService()}")
        if (saveWav) {
            wavTemp = File(cacheDir, "captura.pcm").also { it.delete() }
            wavOut = FileOutputStream(wavTemp)
        }
    }

    // ---------- Loops de áudio ----------

    private fun audioLoop(record: AudioRecord) {
        val samples = ShortArray(SAMPLE_RATE / 10) // 100 ms
        val bytes = ByteArray(samples.size * 2)
        try {
            while (capturing) {
                val n = record.read(samples, 0, samples.size)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) {
                    val s = samples[i].toInt()
                    sum += (s * s).toDouble()
                    bytes[2 * i] = (s and 0xff).toByte()
                    bytes[2 * i + 1] = ((s shr 8) and 0xff).toByte()
                }
                feed(bytes, n * 2)
                try { wavOut?.write(bytes, 0, n * 2) } catch (_: IOException) {}
                val rms = sqrt(sum / n)
                val db = if (rms < 1.0) -60.0 else 20 * log10(rms / 32768.0)
                val level = (((db + 60) / 60) * 100).toInt().coerceIn(0, 100)
                if (level > peakLevel) peakLevel = level
                TranscriptStore.level = level
                TranscriptStore.notifyChanged()
            }
        } finally {
            closePipe()
            TranscriptStore.level = 0
            TranscriptStore.notifyChanged()
        }
    }

    private fun fileLoop(stream: InputStream) {
        val buf = ByteArray(BYTES_PER_SEC / 10)
        try {
            while (capturing) {
                val n = stream.read(buf)
                if (n < 0) break
                feed(buf, n)
                TranscriptStore.level = 50
                TranscriptStore.notifyChanged()
            }
        } catch (_: IOException) {
        } finally {
            try { stream.close() } catch (_: Exception) {}
            closePipe()
            TranscriptStore.level = 0
            TranscriptStore.notifyChanged()
            // Fim do arquivo: o reconhecedor encerra quando o pipe fecha
            handler.postDelayed({ if (!stopRequested) stopCapture() }, 8000)
        }
    }

    /** Escreve no pipe do reconhecedor atual; ignora se ele estiver fechado (reinício em andamento). */
    private fun feed(bytes: ByteArray, len: Int) {
        totalBytes += len
        val out = pipeOut ?: return
        try {
            out.write(bytes, 0, len)
        } catch (_: IOException) {
        }
    }

    private fun closePipe() {
        val out = pipeOut
        pipeOut = null
        try { out?.close() } catch (_: Exception) {}
    }

    // ---------- Reconhecedor ----------

    private fun startRecognizer() {
        try { recognizer?.destroy() } catch (_: Exception) {}
        closePipe()
        try { readSide?.close() } catch (_: Exception) {}

        val pipe = ParcelFileDescriptor.createPipe()
        readSide = pipe[0]
        pipeOut = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])

        val rec = if (onDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else {
            SpeechRecognizer.createSpeechRecognizer(this)
        }
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { gotCallback = true }
            override fun onBeginningOfSpeech() {
                gotCallback = true
                if (phraseStartSec < 0) phraseStartSec = audioSec
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                gotCallback = true
                Log.w(TAG, "onError ${errorText(error)} capturing=$capturing stop=$stopRequested")
                if (stopRequested || !capturing) {
                    finish()
                    return
                }
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    SpeechRecognizer.ERROR_SERVER,
                    SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
                    SpeechRecognizer.ERROR_CLIENT,
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> restartRecognizer(errorText(error))
                    else -> {
                        errorMsg = "Erro no reconhecimento: ${errorText(error)}"
                        stopCapture()
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                addResult(results)
                if (capturing && !stopRequested) restartRecognizer("onResults") else finish()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                gotCallback = true
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                if (text.isNotBlank() && phraseStartSec < 0) {
                    // O parcial chega com ~1-2 s de atraso em relação ao áudio
                    phraseStartSec = maxOf(0, audioSec - 2)
                }
                TranscriptStore.partial = text
                TranscriptStore.notifyChanged()
            }

            override fun onSegmentResults(segmentResults: Bundle) {
                gotCallback = true
                addResult(segmentResults)
            }

            override fun onEndOfSegmentedSession() {
                if (capturing && !stopRequested) restartRecognizer("fim de sessão") else finish()
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, prefs.language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            // Android 14+: pede o tempo de cada palavra (nem todo reconhecedor devolve)
            putExtra(RecognizerIntent.EXTRA_REQUEST_WORD_TIMING, true)
        }
        sessionStartMs = totalBytes * 1000 / BYTES_PER_SEC
        gotCallback = false
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, 15000)
        rec.startListening(intent)
        recognizer = rec
    }

    private fun restartRecognizer(reason: String) {
        if (restarts >= MAX_RESTARTS) {
            errorMsg = "O reconhecedor reiniciou $restarts vezes ($reason)."
            stopCapture()
            return
        }
        restarts++
        Log.i(TAG, "reiniciando reconhecedor ($reason), #$restarts")
        closePipe()
        handler.postDelayed({
            if (capturing && !stopRequested) {
                try {
                    startRecognizer()
                } catch (e: Exception) {
                    errorMsg = "Falha ao reiniciar o reconhecedor: ${e.message}"
                    stopCapture()
                }
            }
        }, 300)
    }

    private fun addResult(bundle: Bundle?) {
        val text = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()?.trim().orEmpty()
        if (text.isEmpty()) return
        segments++
        var start = if (phraseStartSec >= 0) phraseStartSec else maxOf(0, audioSec - 3)
        phraseStartSec = -1
        val segs = bundle?.let { wordTimes(it, text) } ?: emptyList()
        if (segs.isNotEmpty()) {
            // Com o tempo das palavras, o início da frase fica exato
            val exact = (segs.first().ms / 1000).toInt()
            if (kotlin.math.abs(exact - start) <= 5) start = exact
            pendingTimings.add(text to segs)
        }
        if (firstPhraseSec < 0) firstPhraseSec = start
        lastPhraseEndSec = audioSec
        val line = "[${formatTime(start)}] $text"
        TranscriptStore.lines.add(line)
        TranscriptStore.partial = ""
        writeLine(line)
        TranscriptStore.notifyChanged()
        val rec = baseName
        Thread { try { VocabDb.get(this).recordOccurrences(text, rec, start) } catch (_: Exception) {} }.start()
    }

    /**
     * Tempo de cada palavra (RecognitionPart), como trechos da frase com o ms no áudio. Vazio quando o
     * reconhecedor não informa ou quando os números não fazem sentido.
     */
    private fun wordTimes(bundle: Bundle, text: String): List<WordTiming.Seg> {
        val parts = try {
            bundle.getParcelableArrayList(SpeechRecognizer.RECOGNITION_PARTS, android.speech.RecognitionPart::class.java)
        } catch (_: Exception) { null }
        if (parts.isNullOrEmpty()) return emptyList()
        Log.i(TAG, "partes: ${parts.size}, início da sessão ${sessionStartMs} ms, primeiras: " +
            parts.take(4).joinToString { "${it.formattedText ?: it.rawText}@${it.timestampMillis}" })
        val audioMs = totalBytes * 1000 / BYTES_PER_SEC
        val segs = mutableListOf<WordTiming.Seg>()
        var cursor = 0
        for (p in parts) {
            val cands = listOfNotNull(p.formattedText, p.rawText).map { it.trim() }.filter { it.isNotEmpty() }
            var idx = -1
            var len = 0
            for (c in cands) { idx = text.indexOf(c, cursor); if (idx >= 0) { len = c.length; break } }
            if (idx < 0) continue
            val ms = sessionStartMs + p.timestampMillis
            if (p.timestampMillis <= 0 && segs.isNotEmpty()) continue
            if (ms < 0 || ms > audioMs + 2000) return emptyList()
            segs.add(WordTiming.Seg(idx, idx + len, ms))
            cursor = idx + len
        }
        // Tempos sempre em ordem e espalhados (todos iguais = o reconhecedor não informou)
        if (segs.size < 2 || segs.zipWithNext().any { (a, b) -> b.ms < a.ms } || segs.first().ms == segs.last().ms) return emptyList()
        return segs
    }

    /** Cria o .txt na primeira frase; assim nenhuma gravação vazia vai para a pasta. */
    private fun writeLine(line: String) {
        try {
            if (txtOut == null) {
                txtUri = Recordings.create(this, "$baseName.txt", "text/plain")
                txtOut = txtUri?.let { contentResolver.openOutputStream(it) }
            }
            txtOut?.write((line + "\n").toByteArray(Charsets.UTF_8))
            txtOut?.flush()
        } catch (e: IOException) {
            Log.e(TAG, "erro ao gravar txt", e)
        }
    }

    // ---------- WAV ----------

    /** Converte o PCM temporário em WAV dentro de Download/LegendaJP. Retorna a descrição ou "". */
    private fun saveWav(keep: Boolean, fromSec: Int = 0, toSec: Int = Int.MAX_VALUE): String {
        try { wavOut?.close() } catch (_: Exception) {}
        wavOut = null
        val pcm = wavTemp ?: return ""
        val total = pcm.length()
        if (!keep || total <= 0) { pcm.delete(); return "" }
        val from = (fromSec.toLong() * BYTES_PER_SEC).coerceIn(0, total)
        val to = if (toSec == Int.MAX_VALUE) total else (toSec.toLong() * BYTES_PER_SEC).coerceIn(from, total)
        val dataLen = to - from
        if (dataLen <= 0) { pcm.delete(); return "" }
        val wavName = "$baseName.wav"
        val uri = Recordings.create(this, wavName, "audio/wav") ?: return ""
        try {
            contentResolver.openOutputStream(uri)!!.use { out ->
                out.write(wavHeader(dataLen))
                pcm.inputStream().use { input ->
                    var skipped = 0L
                    while (skipped < from) { val n = input.skip(from - skipped); if (n <= 0) break; skipped += n }
                    val buf = ByteArray(64 * 1024)
                    var left = dataLen
                    while (left > 0) {
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) break
                        out.write(buf, 0, n)
                        left -= n
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "erro ao salvar wav", e)
            return ""
        } finally {
            pcm.delete()
        }
        return "áudio ${Recordings.prettySize(dataLen + 44)}"
    }

    /** Reescreve o .txt com os tempos deslocados (quando o começo do áudio foi cortado). */
    private fun shiftTranscript(offsetSec: Int) {
        if (offsetSec <= 0) return
        val shifted = TranscriptStore.lines.map { line ->
            val (sec, text) = Recordings.parseLine(line)
            if (sec < 0) line else "[${formatTime(maxOf(0, sec - offsetSec))}] $text"
        }
        TranscriptStore.lines.clear()
        TranscriptStore.lines.addAll(shifted)
        val uri = txtUri ?: return
        try {
            contentResolver.openOutputStream(uri, "wt")!!.use { out ->
                out.write((shifted.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            Log.e(TAG, "erro ao reescrever txt", e)
        }
        val rec = baseName
        Thread { try { VocabDb.get(this).shiftOccurrences(rec, offsetSec) } catch (_: Exception) {} }.start()
    }

    private fun wavHeader(dataLen: Long): ByteArray {
        val h = ByteArray(44)
        fun str(off: Int, s: String) { s.toByteArray(Charsets.US_ASCII).copyInto(h, off) }
        fun int32(off: Int, v: Long) {
            h[off] = (v and 0xff).toByte(); h[off + 1] = ((v shr 8) and 0xff).toByte()
            h[off + 2] = ((v shr 16) and 0xff).toByte(); h[off + 3] = ((v shr 24) and 0xff).toByte()
        }
        fun int16(off: Int, v: Int) { h[off] = (v and 0xff).toByte(); h[off + 1] = ((v shr 8) and 0xff).toByte() }
        str(0, "RIFF"); int32(4, 36 + dataLen); str(8, "WAVE")
        str(12, "fmt "); int32(16, 16); int16(20, 1); int16(22, 1)
        int32(24, SAMPLE_RATE.toLong()); int32(28, BYTES_PER_SEC.toLong()); int16(32, 2); int16(34, 16)
        str(36, "data"); int32(40, dataLen)
        return h
    }

    // ---------- Encerramento ----------

    private fun stopCapture() {
        if (finished) return
        if (!capturing) {
            finish()
            return
        }
        stopRequested = true
        capturing = false
        TranscriptStore.state = TranscriptStore.State.STOPPING
        setStatus("Finalizando")
        try { audioRecord?.stop() } catch (_: Exception) {}
        // O fim do pipe encerra a sessão do reconhecedor; isto é só garantia
        handler.postDelayed({ finish() }, 4000)
    }

    private fun fail(msg: String) {
        errorMsg = msg
        finish()
    }

    private fun finish() {
        if (finished) return
        finished = true
        capturing = false
        stopRequested = true
        handler.removeCallbacks(watchdog)
        overlay?.hide()
        overlay = null
        try { recognizer?.destroy() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        closePipe()
        try { readSide?.close() } catch (_: Exception) {}
        try { txtOut?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}

        val hasText = TranscriptStore.lines.isNotEmpty()
        // Corta o silêncio antes da primeira frase e depois da última (com folga)
        var trimStart = 0
        var trimEnd = Int.MAX_VALUE
        if (hasText && mode != MODE_FILE && firstPhraseSec >= 0) {
            trimStart = maxOf(0, firstPhraseSec - 2)
            trimEnd = minOf(audioSec, lastPhraseEndSec + 3)
            if (trimEnd <= trimStart) { trimStart = 0; trimEnd = Int.MAX_VALUE }
        }
        val wavInfo = saveWav(keep = hasText, fromSec = trimStart, toSec = trimEnd)
        if (wavInfo.isNotEmpty()) shiftTranscript(trimStart)
        if (wavInfo.isNotEmpty() && pendingTimings.isNotEmpty()) {
            val offsetMs = trimStart * 1000L
            val rec = baseName
            val list = pendingTimings.toList()
            Thread {
                val db = VocabDb.get(this)
                for ((sentence, segs) in list) {
                    db.putTiming(rec, sentence, WordTiming.encode(segs.map { it.copy(ms = maxOf(0, it.ms - offsetMs)) }))
                }
            }.start()
        }
        val diag = "${modeName(mode)} · ${formatTime(audioSec)} de áudio · pico $peakLevel% · $restarts reinícios"
        Log.i(TAG, diag)

        val status = when {
            errorMsg != null -> errorMsg!!
            hasText -> "Salvo: ${TranscriptStore.lines.size} frases" + (if (wavInfo.isNotEmpty()) " + $wavInfo" else "")
            peakLevel < 3 && mode != MODE_FILE -> "Nada transcrito: o áudio chegou em silêncio"
            else -> "Nada transcrito"
        }
        TranscriptStore.state = TranscriptStore.State.IDLE
        TranscriptStore.status = status
        TranscriptStore.detail = if (hasText) "Download/$FOLDER/$baseName.txt · $diag" else diag
        TranscriptStore.level = 0
        TranscriptStore.partial = ""
        TranscriptStore.notifyChanged()
        if (hasText && wavInfo.isNotEmpty() && TranscriptStore.lines.size >= 2 && Prefs(this).autoSpeakers && Ai.mode(this) == Ai.CLOUD) {
            // Quem fala cada frase (Personagem 1, 2, 3…), em segundo plano; o visualizador mostra ao abrir
            val app = applicationContext
            Ai.identifySpeakers(app, baseName, progress = { _, _ -> }, done = { n, err ->
                if (err != null) Log.w(TAG, "locutores: $err")
                else android.widget.Toast.makeText(app, if (n <= 1) "✦ Uma pessoa fala na gravação" else "✦ $n personagens identificados na gravação", android.widget.Toast.LENGTH_LONG).show()
            })
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private val FOLDER get() = Recordings.FOLDER

    override fun onDestroy() {
        finish()
        super.onDestroy()
    }

    private fun setStatus(msg: String) {
        TranscriptStore.status = msg
        TranscriptStore.notifyChanged()
    }
}
