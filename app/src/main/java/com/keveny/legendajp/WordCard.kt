package com.keveny.legendajp

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/** Cartão da palavra: forma de dicionário, leitura, classe, conjugação e Salvar/Remover. */
object WordCard {

    /** Preenche um word_card.xml já inflado. onPlay = null esconde o botão Ouvir. */
    fun bind(
        ctx: Context, view: View, w: Jp.Word, sentence: String, recording: String, sec: Int,
        onPlay: (() -> Unit)?, onChanged: () -> Unit, light: Boolean
    ) {
        val db = VocabDb.get(ctx)
        val base = view.findViewById<TextView>(R.id.cardBase)
        val reading = view.findViewById<TextView>(R.id.cardReading)
        val pos = view.findViewById<TextView>(R.id.cardPos)
        val conj = view.findViewById<TextView>(R.id.cardConj)
        val tr = view.findViewById<TextView>(R.id.cardTr)
        val save = view.findViewById<Button>(R.id.cardSave)
        val play = view.findViewById<Button>(R.id.cardPlay)

        base.text = w.base
        reading.text = if (w.reading != w.base) w.reading else ""
        pos.text = w.pos
        if (w.conjugation.isNotEmpty() && (w.surface != w.base)) {
            conj.visibility = View.VISIBLE
            conj.text = "${w.surface} = ${w.base} + ${w.conjugation}"
        } else if (w.conjugation.isNotEmpty() && w.conjugation != "forma de dicionário") {
            conj.visibility = View.VISIBLE
            conj.text = w.conjugation
        } else {
            conj.visibility = View.GONE
        }
        val fg = if (light) 0xFFFFFFFF.toInt() else ctx.getColor(R.color.text)
        val fg2 = if (light) 0xB3FFFFFF.toInt() else ctx.getColor(R.color.text_secondary)
        base.setTextColor(fg); reading.setTextColor(fg2); pos.setTextColor(fg2); conj.setTextColor(fg); tr.setTextColor(fg)

        // Tradução da forma de dicionário (cache primeiro; senão traduz e mostra quando chegar)
        tr.visibility = View.GONE
        val cachedTr = Tr.cached(ctx, w.base)
        if (cachedTr != null) {
            tr.text = cachedTr; tr.visibility = View.VISIBLE
        } else {
            tr.text = "Traduzindo…"; tr.visibility = View.VISIBLE; tr.alpha = 0.6f
            Tr.translate(ctx, w.base) { t ->
                if (view.findViewById<TextView>(R.id.cardBase).text != w.base) return@translate
                tr.alpha = 1f
                if (t != null) tr.text = t else tr.visibility = View.GONE
            }
        }

        fun refresh() {
            val saved = db.isSaved(w)
            save.text = if (saved) "Remover" else "Salvar"
            save.setBackgroundResource(if (saved) R.drawable.bg_button_stop else R.drawable.bg_button_primary)
        }
        refresh()
        save.setOnClickListener {
            if (db.isSaved(w)) {
                db.remove(w)
                Toast.makeText(ctx, "${w.base} removida do vocabulário", Toast.LENGTH_SHORT).show()
            } else {
                db.save(w, sentence, recording, sec)
                Toast.makeText(ctx, "${w.base} salva no vocabulário", Toast.LENGTH_SHORT).show()
            }
            refresh()
            onChanged()
        }
        if (onPlay != null) {
            play.visibility = View.VISIBLE
            play.setOnClickListener { onPlay() }
        } else {
            play.visibility = View.GONE
        }
    }

    /** Diálogo dentro do app. */
    fun show(
        activity: Activity, w: Jp.Word, sentence: String, recording: String, sec: Int,
        onPlay: (() -> Unit)? = null, onChanged: () -> Unit = {}
    ) {
        val view = LayoutInflater.from(activity).inflate(R.layout.word_card, null)
        val dialog = AlertDialog.Builder(activity).setView(view).create()
        bind(activity, view, w, sentence, recording, sec, onPlay, { onChanged(); dialog.dismiss() }, light = false)
        dialog.show()
    }
}
