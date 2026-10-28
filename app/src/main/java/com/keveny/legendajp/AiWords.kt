package com.keveny.legendajp

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * ✦ IA separa um trecho em palavras inteiras (com leitura, classe e significado) e deixa salvar cada
 * uma: a que o Kuromoji já conhece é salva como palavra normal; as outras viram palavras próprias
 * (classe "ia") com o significado guardado, destacadas e tocáveis onde aparecerem. Usado no leitor
 * (trecho marcado) e na gravação (toque longo na frase), para quando o app não reconhece a palavra.
 */
object AiWords {

    fun show(act: Activity, sel: String, sentence: String, recording: String, sec: Int, onChanged: () -> Unit) {
        if (!Ai.enabled(act)) { Toast.makeText(act, "Ative a IA em Configurações", Toast.LENGTH_LONG).show(); return }
        fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()
        val label = TextView(act).apply { text = sel; textSize = 14f; setTextColor(act.getColor(R.color.text)); setPadding(dp(24), dp(16), dp(24), dp(8)) }
        val dlg = AlertDialog.Builder(act).setTitle("✦ Separando as palavras").setView(label).setCancelable(false)
            .setNegativeButton("Parar") { _, _ -> Ai.stopRequested = true }.show()
        Ai.analyzeWords(act, sel, sentence) { list, err ->
            if (dlg.isShowing) dlg.dismiss()
            if (act.isFinishing) return@analyzeWords
            if (list == null || list.isEmpty()) { Toast.makeText(act, "Não consegui: ${err ?: "sem palavras"}", Toast.LENGTH_LONG).show(); return@analyzeWords }
            val db = VocabDb.get(act)
            val known = try { Jp.wordsAll(sentence) } catch (_: Exception) { emptyList() }
            val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(12), dp(22), dp(8)) }
            box.addView(TextView(act).apply { text = sel; textSize = 18f; setTextColor(act.getColor(R.color.text)); setPadding(0, 0, 0, dp(8)) })
            for (aw in list) {
                if (!Jp.isLexical(aw.base)) continue // números, letras, símbolos: não são palavras para salvar
                // Palavra que o dicionário já conhece: salva como palavra normal (sem duplicar)
                val w = known.firstOrNull { it.base == aw.base } ?: Jp.Word(aw.surface, aw.surface, aw.base, aw.reading, aw.pos, VocabDb.AI, "", 0, 0)
                val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
                row.addView(TextView(act).apply {
                    textSize = 15f; setTextColor(act.getColor(R.color.text))
                    this.text = (if (aw.reading != aw.base) "${aw.base}（${aw.reading}）" else aw.base) + " · ${aw.pos}\n" + aw.meaning +
                        (if (w.posKey != VocabDb.AI) "" else "  ✦")
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                fun small() = Button(act).apply { textSize = 12f; isAllCaps = false; minHeight = 0; minimumHeight = 0; setPadding(dp(10), dp(4), dp(10), dp(4)) }
                val save = small()
                val kn = small()
                val kanji = if (w.base.any(StudyGen::isKanji)) small() else null
                fun refresh() { save.text = if (db.isSaved(w)) "★" else "☆"; kn.text = if (db.isKnown(w)) "✓" else "Conheço"; kanji?.text = if (db.isKnownKanji(w)) "✍✓" else "✍" }
                fun saveIt() {
                    db.save(w, sentence, recording, sec)
                    if (aw.meaning.isNotBlank()) db.putTranslation(w.base, aw.meaning)
                }
                kanji?.setOnClickListener {
                    if (db.isKnownKanji(w)) db.setKnownKanji(w, false) else { if (!db.isSaved(w)) saveIt(); db.setKnownKanji(w, true) }
                    refresh()
                    Toast.makeText(act, if (db.isKnownKanji(w)) "${w.base}: sei escrever (fora dos exercícios de kanji)" else "${w.base} volta aos exercícios de kanji", Toast.LENGTH_SHORT).show()
                }
                save.setOnClickListener { if (db.isSaved(w)) db.remove(w) else saveIt(); refresh(); onChanged() }
                kn.setOnClickListener {
                    if (db.isKnown(w)) db.setKnown(w, false) else { if (!db.isSaved(w)) saveIt(); db.setKnown(w, true) }
                    refresh(); onChanged()
                }
                refresh()
                row.addView(save, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
                row.addView(kn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
                if (kanji != null) row.addView(kanji, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
                box.addView(row)
            }
            box.addView(TextView(act).apply {
                text = "✦ = palavra que o dicionário do app não tinha: fica guardada com o significado da IA e destacada nos textos."
                textSize = 12f; setTextColor(act.getColor(R.color.text_secondary)); setPadding(0, dp(10), 0, 0)
            })
            AlertDialog.Builder(act).setTitle("✦ Palavras do trecho").setView(ScrollView(act).apply { addView(box) })
                .setNegativeButton("Fechar", null).show()
        }
    }
}
