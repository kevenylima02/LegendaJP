package com.keveny.legendajp

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Pastas para organizar gravações e textos ("Exercícios da escola", "Anime"…). Só no banco: os
 * arquivos continuam em Download/LegendaJP. As mesmas pastas valem para Gravações e Textos; cada
 * tela mostra só os itens dela. "Tudo" e "Sem pasta" são fixas. Uma pasta inteira pode ser estudada
 * (as frases de todos os itens juntas): StudyMenuActivity com o nome "pasta:<id>".
 */
object Folders {
    const val ALL = -1L
    const val NONE = 0L
    const val STUDY_PREFIX = "pasta:"

    data class Folder(val id: Long, val name: String)

    fun createTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS folder (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS rec_folder (recording TEXT PRIMARY KEY, folder_id INTEGER NOT NULL)")
    }

    private fun db(ctx: Context) = VocabDb.get(ctx).writableDatabase

    fun all(ctx: Context): List<Folder> =
        db(ctx).rawQuery("SELECT id, name FROM folder ORDER BY name COLLATE NOCASE", null).use { c ->
            buildList { while (c.moveToNext()) add(Folder(c.getLong(0), c.getString(1))) }
        }

    fun name(ctx: Context, id: Long): String? = when (id) {
        ALL -> "Tudo"
        NONE -> "Sem pasta"
        else -> db(ctx).rawQuery("SELECT name FROM folder WHERE id=?", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }

    fun create(ctx: Context, name: String): Long =
        db(ctx).insert("folder", null, ContentValues().apply { put("name", name.trim()); put("created", System.currentTimeMillis()) })

    fun rename(ctx: Context, id: Long, name: String) {
        db(ctx).update("folder", ContentValues().apply { put("name", name.trim()) }, "id=?", arrayOf(id.toString()))
    }

    /** Apaga a pasta; os itens voltam para "Sem pasta" (nenhum arquivo é apagado). */
    fun delete(ctx: Context, id: Long) {
        db(ctx).delete("rec_folder", "folder_id=?", arrayOf(id.toString()))
        db(ctx).delete("folder", "id=?", arrayOf(id.toString()))
    }

    /** Gravação/texto (nome base) → pasta. */
    fun map(ctx: Context): Map<String, Long> =
        db(ctx).rawQuery("SELECT recording, folder_id FROM rec_folder", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getLong(1)) }
        }

    fun move(ctx: Context, bases: Collection<String>, folderId: Long) {
        val d = db(ctx)
        d.beginTransaction()
        try {
            for (b in bases) {
                if (folderId == NONE) d.delete("rec_folder", "recording=?", arrayOf(b))
                else d.insertWithOnConflict("rec_folder", null, ContentValues().apply { put("recording", b); put("folder_id", folderId) }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            d.setTransactionSuccessful()
        } finally { d.endTransaction() }
    }

    fun matches(folder: Long, base: String, map: Map<String, Long>): Boolean =
        folder == ALL || (folder == NONE && base !in map) || map[base] == folder

    // ---------- UI ----------

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    /**
     * Linha de fichas: Tudo · N, Sem pasta · N, as pastas e "+ Nova". Toque escolhe; toque longo numa
     * pasta abre Renomear / Apagar / Estudar. counts(id) conta os itens da tela naquela pasta.
     */
    fun bind(act: Activity, row: LinearLayout, current: Long, counts: (Long) -> Int, onSelect: (Long) -> Unit, onChanged: () -> Unit) {
        row.removeAllViews()
        val items = listOf(Folder(ALL, "Tudo"), Folder(NONE, "Sem pasta")) + all(act)
        fun chip(label: String, on: Boolean, onClick: () -> Unit, onLong: (() -> Unit)? = null) {
            val tv = TextView(act).apply {
                text = label; textSize = 13f
                setPadding(dp(act, 12), dp(act, 6), dp(act, 12), dp(act, 6))
                background = GradientDrawable().apply {
                    cornerRadius = dp(act, 16).toFloat()
                    setColor(act.getColor(if (on) R.color.accent_soft else R.color.card))
                    setStroke(dp(act, 1), act.getColor(if (on) R.color.accent else R.color.outline))
                }
                setTextColor(act.getColor(if (on) R.color.accent else R.color.text))
                setOnClickListener { onClick() }
                if (onLong != null) setOnLongClickListener { onLong(); true }
            }
            row.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(act, 6) })
        }
        for (f in items) {
            chip("${f.name} · ${counts(f.id)}", f.id == current, { onSelect(f.id) },
                if (f.id > 0) ({ folderMenu(act, f, onChanged) }) else null)
        }
        chip("+ Nova pasta", false, { newFolderDialog(act) { id -> onSelect(id); onChanged() } })
    }

    fun newFolderDialog(act: Activity, onCreated: (Long) -> Unit) {
        val input = EditText(act).apply { hint = "Nome da pasta"; setSingleLine() }
        val box = LinearLayout(act).apply { setPadding(dp(act, 20), dp(act, 8), dp(act, 20), 0); addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        AlertDialog.Builder(act).setTitle("Nova pasta").setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Criar") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isEmpty()) return@setPositiveButton
                onCreated(create(act, n))
            }.show()
        input.requestFocus()
    }

    /** Mover vários itens: escolhe a pasta, "Sem pasta" ou cria uma nova. */
    fun moveDialog(act: Activity, bases: Collection<String>, onDone: () -> Unit) {
        val folders = all(act)
        val labels = folders.map { it.name } + "Sem pasta" + "+ Nova pasta…"
        val what = if (bases.size == 1) "1 item" else "${bases.size} itens"
        AlertDialog.Builder(act).setTitle("Mover $what para")
            .setItems(labels.toTypedArray()) { _, i ->
                when {
                    i < folders.size -> { move(act, bases, folders[i].id); Toast.makeText(act, "Movido para ${folders[i].name}", Toast.LENGTH_SHORT).show(); onDone() }
                    i == folders.size -> { move(act, bases, NONE); onDone() }
                    else -> newFolderDialog(act) { id -> move(act, bases, id); onDone() }
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    fun folderMenu(act: Activity, f: Folder, onChanged: () -> Unit) {
        AlertDialog.Builder(act).setTitle(f.name)
            .setItems(arrayOf("Estudar a pasta inteira", "Renomear", "Apagar a pasta")) { _, i ->
                when (i) {
                    0 -> act.startActivity(studyIntent(act, f.id))
                    1 -> {
                        val input = EditText(act).apply { setText(f.name); setSingleLine() }
                        val box = LinearLayout(act).apply { setPadding(dp(act, 20), dp(act, 8), dp(act, 20), 0); addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
                        AlertDialog.Builder(act).setTitle("Renomear pasta").setView(box)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton("Salvar") { _, _ -> val n = input.text.toString().trim(); if (n.isNotEmpty()) { rename(act, f.id, n); onChanged() } }.show()
                    }
                    2 -> AlertDialog.Builder(act).setMessage("Apagar a pasta \"${f.name}\"? Os itens voltam para \"Sem pasta\"; nada é excluído.")
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton("Apagar") { _, _ -> delete(act, f.id); onChanged() }.show()
                }
            }.show()
    }

    // ---------- estudar a pasta inteira ----------

    fun studyIntent(ctx: Context, id: Long): Intent =
        Intent(ctx, StudyMenuActivity::class.java).putExtra(StudyActivity.EXTRA_NAME, "$STUDY_PREFIX$id")

    fun isStudyName(name: String) = name.startsWith(STUDY_PREFIX)

    fun studyTitle(ctx: Context, name: String): String = "pasta " + (name(ctx, name.removePrefix(STUDY_PREFIX).toLongOrNull() ?: NONE) ?: "?")

    /** Frases de todos os .txt da pasta (gravações e textos), cada uma com a gravação de origem. */
    fun lines(ctx: Context, name: String): List<StudyGen.Line> {
        val id = name.removePrefix(STUDY_PREFIX).toLongOrNull() ?: return emptyList()
        val m = map(ctx)
        return Recordings.list(ctx, ".txt").filter { matches(id, Recordings.baseName(it.name), m) }.flatMap { item ->
            val text = try { Recordings.readText(ctx, item.uri) } catch (_: Exception) { "" }
            StudyGen.lines(text, Recordings.baseName(item.name))
        }
    }
}
