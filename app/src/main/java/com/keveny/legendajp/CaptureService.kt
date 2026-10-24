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
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
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
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.sqrt

class CaptureService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.keveny.legendajp.STOP"
        private const val CHANNEL_ID = "captura"
        private const val NOTIF_ID = 1
        private const val SAMPLE_RATE = 16000

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
    }

    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var recognizer: SpeechRecognizer? = null
    private var readSide: ParcelFileDescriptor? = null
    private var output: OutputStream? = null
    private var fileName: String = ""
    private var startTime = 0L
    @Volatile private var capturing = false
    private var finished = false
    private var errorMsg: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            return START_NOT_STICKY
        }
        if (capturing) return START_NOT_STICKY

        startForegroundNow()
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        if (data == null) {
            fail("Sem permissão de captura.")
            return START_NOT_STICKY
        }
        try {
            begin(code, data)
        } catch (e: Exception) {
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
            .setContentText("Capturando e transcrevendo áudio")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        startForeground(
            NOTIF_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
    }

    @SuppressLint("MissingPermission")
    private fun begin(code: Int, data: Intent) {
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
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuf * 4, SAMPLE_RATE))
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord não inicializou")
        }
        audioRecord = record

        TranscriptStore.lines.clear()
        TranscriptStore.partial = ""
        openOutputFile()

        val pipe = ParcelFileDescriptor.createPipe()
        readSide = pipe[0]
        val writeSide = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        startRecognizer(pipe[0])

        startTime = System.currentTimeMillis()
        record.startRecording()
        capturing = true
        setStatus("Capturando. Toque o áudio no Spotify: a barra deve se mexer. Se ficar parada, a captura está bloqueada.")

        Thread({ audioLoop(record, writeSide) }, "audio-capture").start()
    }

    private fun audioLoop(record: AudioRecord, out: OutputStream) {
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
                out.write(bytes, 0, n * 2)
                val rms = sqrt(sum / n)
                val db = if (rms < 1.0) -60.0 else 20 * log10(rms / 32768.0)
                TranscriptStore.level = (((db + 60) / 60) * 100).toInt().coerceIn(0, 100)
                TranscriptStore.notifyChanged()
            }
        } catch (e: IOException) {
            // O reconhecedor fechou o pipe
        } finally {
            try { out.close() } catch (_: Exception) {}
            TranscriptStore.level = 0
        }
    }

    private fun startRecognizer(source: ParcelFileDescriptor) {
        val onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        val rec = if (onDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else {
            SpeechRecognizer.createSpeechRecognizer(this)
        }
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                errorMsg = "Erro no reconhecimento: ${errorText(error)}"
                setStatus(errorMsg!!)
                stopCapture()
            }

            override fun onResults(results: Bundle?) {
                addResult(results)
                finish()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                TranscriptStore.partial =
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                TranscriptStore.notifyChanged()
            }

            override fun onSegmentResults(segmentResults: Bundle) {
                addResult(segmentResults)
            }

            override fun onEndOfSegmentedSession() {
                finish()
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }
        rec.startListening(intent)
        recognizer = rec
    }

    private fun addResult(bundle: Bundle?) {
        val text = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()?.trim().orEmpty()
        if (text.isEmpty()) return
        val secs = (System.currentTimeMillis() - startTime) / 1000
        val line = "[%02d:%02d] %s".format(secs / 60, secs % 60, text)
        TranscriptStore.lines.add(line)
        TranscriptStore.partial = ""
        try {
            output?.write((line + "\n").toByteArray(Charsets.UTF_8))
            output?.flush()
        } catch (_: IOException) {}
        TranscriptStore.notifyChanged()
    }

    private fun openOutputFile() {
        fileName = "legenda_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LegendaJP")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        output = uri?.let { contentResolver.openOutputStream(it) }
    }

    private fun stopCapture() {
        if (finished) return
        if (audioRecord == null) {
            finish()
            return
        }
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
        try { recognizer?.destroy() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        try { readSide?.close() } catch (_: Exception) {}
        try { output?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        val saved = if (fileName.isNotEmpty())
            "Salvo em Download/LegendaJP/$fileName (${TranscriptStore.lines.size} frases)" else ""
        val err = errorMsg
        when {
            err != null && saved.isNotEmpty() -> setStatus("$err. $saved")
            err != null -> setStatus(err)
            saved.isNotEmpty() -> setStatus(saved)
        }
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
