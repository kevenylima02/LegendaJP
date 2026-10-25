package com.keveny.legendajp

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast

/**
 * "Reportar erro" (menu ⋮ no exercício e na escolha de palavras): fica guardado no celular, com o
 * contexto (frase, palavra, exercício e as traduções que estavam na tela). A tela Reportes lista
 * os abertos; tradução da frase e da palavra podem ser corrigidas com a IA e aceitas.
 */
object Reports {

    const val SENTENCE = "frase"
    const val WORD = "palavra"
    const val EXERCISE = "exercicio"
    const val EXPLANATION = "explicacao"
    const val OTHER = "outro"

    val kinds = listOf(
        SENTENCE to "Tradução da frase",
        WORD to "Tradução / significado da palavra",
        EXERCISE to "Exercício errado (resposta ou alternativas)",
        EXPLANATION to "Explicação ou regra de gramática",
        OTHER to "Outro",
    )

    fun kindLabel(k: String) = kinds.firstOrNull { it.first == k }?.second ?: k

    data class Report(
        val id: Long, val kind: String, val sentence: String, val word: String, val reading: String, val pos: String,
        val recording: String, val screen: String, val shown: String, val comment: String,
        val created: Long, val status: String
    )

    fun createTable(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS reports (
            id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, sentence TEXT NOT NULL, word TEXT NOT NULL,
            reading TEXT NOT NULL, pos TEXT NOT NULL, recording TEXT NOT NULL, screen TEXT NOT NULL, shown TEXT NOT NULL,
            comment TEXT NOT NULL, created INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'aberto')""")
    }

    private fun db(ctx: Context) = VocabDb.get(ctx).writableDatabase

    fun add(ctx: Context, r: Report) {
        db(ctx).insert("reports", null, ContentValues().apply {
            put("kind", r.kind); put("sentence", r.sentence); put("word", r.word); put("reading", r.reading); put("pos", r.pos)
            put("recording", r.recording); put("screen", r.screen); put("shown", r.shown); put("comment", r.comment)
            put("created", System.currentTimeMillis()); put("status", "aberto")
        })
    }

    fun list(ctx: Context, open: Boolean): List<Report> =
        db(ctx).rawQuery(
            "SELECT id, kind, sentence, word, reading, pos, recording, screen, shown, comment, created, status FROM reports " +
                (if (open) "WHERE status='aberto' " else "") + "ORDER BY created DESC", null
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(Report(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4),
                    c.getString(5), c.getString(6), c.getString(7), c.getString(8), c.getString(9), c.getLong(10), c.getString(11)))
            }
        }

    fun setStatus(ctx: Context, id: Long, status: String) {
        db(ctx).execSQL("UPDATE reports SET status=? WHERE id=?", arrayOf(status, id))
    }

    fun openCount(ctx: Context): Int =
        db(ctx).rawQuery("SELECT COUNT(*) FROM reports WHERE status='aberto'", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /**
     * Diálogo de reporte. screen: onde foi (ex.: "Exercício · Lacuna com áudio"); shown: o que estava na
     * tela que pode estar errado (traduções, resposta). word: a palavra do exercício, se houver.
     */
    fun dialog(act: Activity, sentence: String, word: Jp.Word?, recording: String?, screen: String, shown: String) {
        val d = act.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val group = RadioGroup(act)
        val ids = HashMap<Int, String>()
        for ((k, label) in kinds) {
            if (k == WORD && word == null) continue
            if (k == SENTENCE && sentence.isBlank()) continue
            val rb = RadioButton(act).apply { text = label; textSize = 15f; id = android.view.View.generateViewId() }
            ids[rb.id] = k
            group.addView(rb)
        }
        group.check(group.getChildAt(0).id)
        val comment = EditText(act).apply { hint = "O que está errado? (opcional)"; textSize = 15f }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(TextView(act).apply {
                text = listOfNotNull(sentence.takeIf { it.isNotBlank() }, word?.let { "Palavra: ${it.base}" }).joinToString("\n")
                textSize = 14f
                setTextColor(act.getColor(R.color.text_secondary))
                setPadding(0, 0, 0, dp(8))
            })
            addView(group)
            addView(comment)
        }
        AlertDialog.Builder(act).setTitle("Reportar erro").setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Reportar") { _, _ ->
                val kind = ids[group.checkedRadioButtonId] ?: OTHER
                val r = Report(0, kind, sentence, word?.base ?: "", word?.reading ?: "", word?.pos ?: "", recording ?: "",
                    screen, shown, comment.text.toString().trim(), 0, "aberto")
                Thread { add(act, r) }.start()
                Toast.makeText(act, "Reportado. Veja em ⋮ → Reportes na tela principal.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
