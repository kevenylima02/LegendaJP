package com.keveny.legendajp

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase

/**
 * Quem fala cada frase de uma gravação (A, B, C…), com nome e voz (feminina/masculina) de cada
 * locutor. Vem do texto importado ("A: frase") ou da identificação pela IA. A frase é a posição dela
 * na transcrição (linhas sem # e não vazias, em ordem).
 */
object Speakers {

    data class Info(val id: String, val name: String, val female: Boolean?)

    /** Cores das etiquetas A, B, C… (claras o bastante para os dois temas). */
    val colors = intArrayOf(0xFFE57373.toInt(), 0xFF64B5F6.toInt(), 0xFF81C784.toInt(), 0xFFFFB74D.toInt(), 0xFFBA68C8.toInt(), 0xFF4DB6AC.toInt())

    fun colorOf(id: String, all: List<String>): Int = colors[(all.indexOf(id).coerceAtLeast(0)) % colors.size]

    fun createTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS line_speaker (recording TEXT NOT NULL, idx INTEGER NOT NULL, speaker TEXT NOT NULL, PRIMARY KEY(recording, idx))")
        db.execSQL("CREATE TABLE IF NOT EXISTS rec_speaker (recording TEXT NOT NULL, speaker TEXT NOT NULL, name TEXT NOT NULL, female INTEGER, PRIMARY KEY(recording, speaker))")
    }

    private fun db(ctx: Context) = VocabDb.get(ctx).writableDatabase

    /** Locutor de cada frase (índice → id). */
    fun lines(ctx: Context, recording: String): Map<Int, String> =
        db(ctx).rawQuery("SELECT idx, speaker FROM line_speaker WHERE recording=?", arrayOf(recording)).use { c ->
            buildMap { while (c.moveToNext()) put(c.getInt(0), c.getString(1)) }
        }

    fun infos(ctx: Context, recording: String): List<Info> =
        db(ctx).rawQuery("SELECT speaker, name, female FROM rec_speaker WHERE recording=? ORDER BY speaker", arrayOf(recording)).use { c ->
            buildList { while (c.moveToNext()) add(Info(c.getString(0), c.getString(1), if (c.isNull(2)) null else c.getInt(2) == 1)) }
        }

    /** Grava tudo de uma vez (substitui o que havia para a gravação). */
    fun save(ctx: Context, recording: String, byLine: Map<Int, String>, infos: List<Info>) {
        val db = db(ctx)
        db.beginTransaction()
        try {
            db.delete("line_speaker", "recording=?", arrayOf(recording))
            db.delete("rec_speaker", "recording=?", arrayOf(recording))
            for ((i, s) in byLine) db.insert("line_speaker", null, ContentValues().apply { put("recording", recording); put("idx", i); put("speaker", s) })
            for (inf in infos) db.insert("rec_speaker", null, ContentValues().apply {
                put("recording", recording); put("speaker", inf.id); put("name", inf.name)
                if (inf.female != null) put("female", if (inf.female) 1 else 0)
            })
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun rename(ctx: Context, recording: String, id: String, name: String, female: Boolean?) {
        db(ctx).insertWithOnConflict("rec_speaker", null, ContentValues().apply {
            put("recording", recording); put("speaker", id); put("name", name.ifBlank { id })
            if (female != null) put("female", if (female) 1 else 0) else putNull("female")
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Diálogo para dar nome e tipo de voz a cada locutor. */
    fun editDialog(act: android.app.Activity, recording: String, onDone: () -> Unit) {
        val list = infos(act, recording)
        if (list.isEmpty()) { android.widget.Toast.makeText(act, "Esta gravação ainda não tem locutores", android.widget.Toast.LENGTH_SHORT).show(); return }
        val d = act.resources.displayMetrics.density
        val box = android.widget.LinearLayout(act).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0) }
        val fields = list.map { inf ->
            val name = android.widget.EditText(act).apply { setText(inf.name); hint = "Nome de ${inf.id}"; setSingleLine() }
            val fem = android.widget.CheckBox(act).apply { text = "voz feminina (para gerar o áudio)"; isChecked = inf.female == true }
            box.addView(android.widget.TextView(act).apply { text = "Locutor ${inf.id}"; setTextColor(colorOf(inf.id, list.map { it.id })); textSize = 14f })
            box.addView(name); box.addView(fem)
            Triple(inf, name, fem)
        }
        android.app.AlertDialog.Builder(act).setTitle("Locutores").setView(android.widget.ScrollView(act).apply { addView(box) })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Salvar") { _, _ ->
                Thread {
                    for ((inf, name, fem) in fields) rename(act, recording, inf.id, name.text.toString().trim(), fem.isChecked)
                    act.runOnUiThread(onDone)
                }.start()
            }.show()
    }
}
