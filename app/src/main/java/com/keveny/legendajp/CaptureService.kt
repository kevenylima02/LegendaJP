package com.keveny.legendajp

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
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
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Captura áudio (interno, microfone ou arquivo WAV), envia para o SpeechRecognizer
 * por um pipe e grava as frases num .txt. Em modo captura/microfone também salva
 * um .wav com o áudio bruto, para diagnosticar se o áudio chegou.
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
        private const val CHANNEL_ID = "captura"
        private const val NOTIF_ID = 1
        private const val SAMPLE_RATE = 16000
        private const val MAX_RESTARTS = 200

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
            else -> "captura interna"
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var mode = MODE_CAPTURE
    private var projection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var recognizer: SpeechRecognizer? = null
    private var readSide: ParcelFileDescriptor? = null
    @Volatile private var pipeOut: OutputStream? = null
    private var output: OutputStream? = null
    private var fileName: String = ""
    private var wavTemp: File? = null
    private var wavOut: OutputStream? = null
    private var startTime = 0L
    @Volatile private var capturing = false
    @Volatile private var stopRequested = false
    private var finished = false
    private var errorMsg: String? = null

    // Estatísticas de diagnóstico
    @Volatile private var totalBytes = 0L
    @Volatile private var peakLevel = 0
    private var segments = 0
    private var partials = 0
    private var restarts = 0
    private var onDevice = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
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
            fail("Erro ao iniciar: ${e.javaClass.simpleName}: ${e.message}")
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNow() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Captura de áudio", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("LegendaJP")
            .setContentText("Transcrevendo (${modeName(mode)})")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
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
        startWithRecord(record, "Capturando áudio interno. Toque o áudio no Spotify: a barra deve se mexer.")
    }

    @SuppressLint("MissingPermission")
    private fun beginMic() {
        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(pcmFormat())
            .setBufferSizeInBytes(bufferSize())
            .build()
        startWithRecord(record, "Ouvindo pelo microfone. Fale em japonês ou toque áudio no alto-falante.")
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
            throw IllegalStateException("Arquivo não é WAV. Use um .wav gerado pelo app.")
        }
        val channels = (header[22].toInt() and 0xff) or ((header[23].toInt() and 0xff) shl 8)
        val rate = (header[24].toInt() and 0xff) or ((header[25].toInt() and 0xff) shl 8) or
            ((header[26].toInt() and 0xff) shl 16) or ((header[27].toInt() and 0xff) shl 24)
        val bits = (header[34].toInt() and 0xff) or ((header[35].toInt() and 0xff) shl 8)
        if (channels != 1 || rate != SAMPLE_RATE || bits != 16) {
            stream.close()
            throw IllegalStateException("WAV precisa ser mono, 16 kHz, 16 bits (este é ${channels}ch ${rate}Hz ${bits}bit)")
        }
        prepareSession(saveWav = false)
        startRecognizer()
        capturing = true
        setStatus("Transcrevendo arquivo...")
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
        return maxOf(minBuf * 4, SAMPLE_RATE)
    }

    private fun startWithRecord(record: AudioRecord, msg: String) {
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord não inicializou")
        }
        audioRecord = record
        prepareSession(saveWav = true)
        startRecognizer()
        record.startRecording()
        capturing = true
        setStatus(msg)
        Thread({ audioLoop(record) }, "audio-capture").start()
    }

    private fun prepareSession(saveWav: Boolean) {
        TranscriptStore.lines.clear()
        TranscriptStore.partial = ""
        startTime = System.currentTimeMillis()
        openOutputFile()
        onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        val header = "# modo=${modeName(mode)} onDevice=$onDevice " +
            "servico=${if (onDevice) Diag.onDeviceService() else Diag.defaultService(this)}"
        Log.i(TAG, header)
        writeLine(header)
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
        val buf = ByteArray(SAMPLE_RATE / 10 * 2)
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
            // reconhecedor fechou o pipe; startRecognizer() cria outro
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
            override fun onReadyForSpeech(params: Bundle?) { Log.d(TAG, "onReadyForSpeech") }
            override fun onBeginningOfSpeech() { Log.d(TAG, "onBeginningOfSpeech") }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { Log.d(TAG, "onEndOfSpeech") }
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                Log.w(TAG, "onError ${errorText(error)} capturing=$capturing stop=$stopRequested")
                if (stopRequested || !capturing) {
                    // NO_MATCH no fim é normal: o último trecho não tinha fala
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
                        setStatus(errorMsg!!)
                        stopCapture()
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                Log.d(TAG, "onResults")
                addResult(results)
                if (capturing && !stopRequested) restartRecognizer("onResults") else finish()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partials++
                TranscriptStore.partial =
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                TranscriptStore.notifyChanged()
            }

            override fun onSegmentResults(segmentResults: Bundle) {
                addResult(segmentResults)
            }

            override fun onEndOfSegmentedSession() {
                Log.d(TAG, "onEndOfSegmentedSession")
                if (capturing && !stopRequested) restartRecognizer("fim de sessão") else finish()
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }
        rec.startListening(intent)
        recognizer = rec
    }

    private fun restartRecognizer(reason: String) {
        if (restarts >= MAX_RESTARTS) {
            errorMsg = "Reconhecedor reiniciou $restarts vezes ($reason); desistindo."
            setStatus(errorMsg!!)
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
                    errorMsg = "Falha ao reiniciar reconhecedor: ${e.message}"
                    setStatus(errorMsg!!)
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
        val secs = (System.currentTimeMillis() - startTime) / 1000
        val line = "[%02d:%02d] %s".format(secs / 60, secs % 60, text)
        TranscriptStore.lines.add(line)
        TranscriptStore.partial = ""
        writeLine(line)
        TranscriptStore.notifyChanged()
    }

    private fun writeLine(line: String) {
        try {
            output?.write((line + "\n").toByteArray(Charsets.UTF_8))
            output?.flush()
        } catch (_: IOException) {}
    }

    // ---------- Arquivos ----------

    private fun openOutputFile() {
        fileName = "legenda_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, downloadValues(fileName, "text/plain"))
        output = uri?.let { contentResolver.openOutputStream(it) }
    }

    private fun downloadValues(name: String, mime: String) = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, mime)
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LegendaJP")
    }

    /** Converte o PCM temporário em WAV dentro de Download/LegendaJP. Retorna a descrição ou "". */
    private fun saveWav(): String {
        try { wavOut?.close() } catch (_: Exception) {}
        wavOut = null
        val pcm = wavTemp ?: return ""
        val dataLen = pcm.length()
        if (dataLen <= 0) { pcm.delete(); return "" }
        val wavName = fileName.removeSuffix(".txt") + ".wav"
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, downloadValues(wavName, "audio/wav"))
            ?: return ""
        try {
            contentResolver.openOutputStream(uri)!!.use { out ->
                out.write(wavHeader(dataLen))
                pcm.inputStream().use { it.copyTo(out, 64 * 1024) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "erro ao salvar wav", e)
            return ""
        } finally {
            pcm.delete()
        }
        val secs = dataLen / (SAMPLE_RATE * 2)
        return "$wavName (${secs}s)"
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
        int32(24, SAMPLE_RATE.toLong()); int32(28, (SAMPLE_RATE * 2).toLong()); int16(32, 2); int16(34, 16)
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
        if (errorMsg == null) setStatus("Finalizando...")
        try { audioRecord?.stop() } catch (_: Exception) {}
        // O fim do pipe encerra a sessão do reconhecedor; isto é só garantia
        handler.postDelayed({ finish() }, 4000)
    }

    private fun fail(msg: String) {
        errorMsg = msg
        setStatus(msg)
        finish()
    }

    private fun finish() {
        if (finished) return
        finished = true
        capturing = false
        stopRequested = true
        try { recognizer?.destroy() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        closePipe()
        try { readSide?.close() } catch (_: Exception) {}
        val wavInfo = saveWav()
        val diag = "Diagnóstico: ${modeName(mode)}, ${totalBytes / (SAMPLE_RATE * 2)}s de áudio, pico ${peakLevel}%, " +
            "$segments frases, $partials parciais, $restarts reinícios, onDevice=$onDevice"
        writeLine("# $diag")
        Log.i(TAG, diag)
        try { output?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}

        val saved = buildString {
            if (fileName.isNotEmpty()) append("Salvo em Download/LegendaJP/$fileName (${TranscriptStore.lines.size} frases)")
            if (wavInfo.isNotEmpty()) append(" e $wavInfo")
        }
        val err = errorMsg
        val msg = listOfNotNull(err, saved.ifEmpty { null }, diag).joinToString("\n")
        if (msg.isNotEmpty()) setStatus(msg)
        TranscriptStore.level = 0
        TranscriptStore.notifyChanged()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        finish()
        super.onDestroy()
    }

    private fun setStatus(msg: String) {
        TranscriptStore.status = msg
        TranscriptStore.notifyChanged()
    }
}
