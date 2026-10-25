package com.keveny.legendajp

import android.app.Activity
import android.app.AlertDialog
import android.speech.tts.TextToSpeech
import android.widget.Toast

/**
 * Configurações → vozes do celular: escolher o motor de voz (Google, Samsung…) e ouvir cada voz
 * japonesa, marcando se é feminina ou masculina (o app usa isso para dar a voz certa a cada locutor).
 */
object PhoneVoices {

    /** Lista os motores instalados e grava o escolhido. */
    fun chooseEngine(act: Activity, onDone: () -> Unit) {
        Thread {
            val engines = try {
                val ready = java.util.concurrent.CountDownLatch(1)
                val t = TextToSpeech(act) { ready.countDown() }
                ready.await(10, java.util.concurrent.TimeUnit.SECONDS)
                val list = t.engines.map { it.name to it.label }
                t.shutdown()
                list
            } catch (_: Exception) { emptyList() }
            act.runOnUiThread {
                val names = listOf("" to "Padrão do Android") + engines
                val current = Prefs(act).ttsPackage
                AlertDialog.Builder(act).setTitle("Motor de voz do celular")
                    .setSingleChoiceItems(names.map { it.second }.toTypedArray(), names.indexOfFirst { it.first == current }.coerceAtLeast(0)) { d, which ->
                        Prefs(act).ttsPackage = names[which].first
                        d.dismiss(); onDone()
                    }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
        }.start()
    }

    /** Lista as vozes japonesas: tocar ouve um exemplo; "Feminina/Masculina" grava o gênero da voz. */
    fun voicesDialog(act: Activity) {
        Thread {
            val tts = try { AudioGen.openTts(act) } catch (e: Exception) {
                act.runOnUiThread { Toast.makeText(act, e.message, Toast.LENGTH_LONG).show() }; return@Thread
            }
            val voices = AudioGen.japaneseVoices(tts)
            act.runOnUiThread {
                if (voices.isEmpty()) {
                    tts.shutdown()
                    Toast.makeText(act, "Este motor não tem vozes japonesas offline", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                fun label(i: Int): String {
                    val g = AudioGen.isFemale(act, voices[i])
                    return voices[i].name + "  ·  " + when (g) { true -> "feminina"; false -> "masculina"; else -> "?" }
                }
                val labels = Array(voices.size) { label(it) }
                var selected = 0
                val dlg = AlertDialog.Builder(act).setTitle("Vozes japonesas (toque para ouvir)")
                    .setSingleChoiceItems(labels, 0) { _, which ->
                        selected = which
                        tts.voice = voices[which]
                        tts.setPitch(1f)
                        tts.speak("こんにちは。私の声はこんな感じです。", TextToSpeech.QUEUE_FLUSH, null, "amostra")
                    }
                    .setNegativeButton("Fechar", null)
                    .setNeutralButton("Masculina", null)
                    .setPositiveButton("Feminina", null)
                    .setOnDismissListener { tts.shutdown() }
                    .show()
                fun mark(female: Boolean) {
                    val p = Prefs(act)
                    p.voiceGenders = p.voiceGenders + (voices[selected].name to female)
                    labels[selected] = label(selected)
                    (dlg.listView.adapter as? android.widget.ArrayAdapter<*>)?.notifyDataSetChanged()
                    dlg.listView.invalidateViews()
                    Toast.makeText(act, "${voices[selected].name}: ${if (female) "feminina" else "masculina"}", Toast.LENGTH_SHORT).show()
                }
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { mark(true) }
                dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { mark(false) }
            }
        }.start()
    }
}
