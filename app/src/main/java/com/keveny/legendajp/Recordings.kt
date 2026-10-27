package com.keveny.legendajp

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Locale

/** Acesso aos arquivos em Download/LegendaJP via MediaStore. */
object Recordings {
    const val FOLDER = "LegendaJP"
    private val relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "/"

    data class Item(val uri: Uri, val name: String, val size: Long, val dateAdded: Long)

    fun values(name: String, mime: String) = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, mime)
        put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
    }

    fun create(ctx: Context, name: String, mime: String): Uri? =
        ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values(name, mime))

    /** Lista os arquivos da pasta com a extensão pedida, mais recentes primeiro. */
    fun list(ctx: Context, suffix: String): List<Item> {
        val out = mutableListOf<Item>()
        val proj = arrayOf(
            MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE, MediaStore.Downloads.DATE_ADDED
        )
        ctx.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj,
            "${MediaStore.Downloads.RELATIVE_PATH} = ? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
            arrayOf(relativePath, "%$suffix"),
            "${MediaStore.Downloads.DATE_ADDED} DESC"
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
            val iDate = c.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(iId))
                out.add(Item(uri, c.getString(iName), c.getLong(iSize), c.getLong(iDate)))
            }
        }
        return out
    }

    fun find(ctx: Context, name: String): Item? = list(ctx, name).firstOrNull { it.name == name }

    fun readText(ctx: Context, uri: Uri): String =
        ctx.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

    fun delete(ctx: Context, uri: Uri): Boolean =
        try { ctx.contentResolver.delete(uri, null, null) > 0 } catch (_: Exception) { false }

    /** "legenda_20261024_180135.txt" -> "24/10/2026 18:01" */
    fun prettyName(name: String): String {
        if (name.startsWith("__vocabulario__")) return "Vocabulário salvo"
        if (name.startsWith("__erros__")) return "Revisão de erros"
        val m = Regex("(?:legenda|texto)_(\\d{4})(\\d{2})(\\d{2})_(\\d{2})(\\d{2})(\\d{2})").find(name) ?: return name
        val (y, mo, d, h, mi, _) = m.destructured
        return "$d/$mo/$y $h:$mi"
    }

    fun baseName(name: String) = name.substringBeforeLast('.')

    /** Título dado pelo usuário, se houver. Lê o banco. */
    fun title(ctx: Context, name: String): String? = VocabDb.get(ctx).recordingTitle(baseName(name))

    /** Título (se houver) ou a data da gravação. */
    fun display(ctx: Context, name: String): String = title(ctx, name) ?: prettyName(name)

    /**
     * Renomear a gravação: só o título (o arquivo continua com a data). "✦ Sugerir com IA" lê a
     * transcrição e propõe um título. Vazio volta a mostrar só a data. onDone roda na thread principal.
     */
    fun renameDialog(act: android.app.Activity, name: String, onDone: () -> Unit) {
        val base = baseName(name)
        val d = act.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val input = android.widget.EditText(act).apply {
            setText(title(act, name) ?: "")
            hint = "Título (vazio = só a data)"
            textSize = 17f
            setSingleLine()
        }
        val box = android.widget.LinearLayout(act).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(android.widget.TextView(act).apply {
                text = "Gravada em ${prettyName(name)}"
                textSize = 13f
                setTextColor(act.getColor(R.color.text_secondary))
            })
            addView(input)
        }
        if (Ai.enabled(act)) {
            val btn = android.widget.Button(act).apply {
                text = "✦ Sugerir título com IA"
                isAllCaps = false
                setBackgroundResource(R.drawable.bg_button_outline)
                setTextColor(act.getColor(R.color.text))
                stateListAnimator = null
            }
            btn.setOnClickListener {
                btn.isEnabled = false
                btn.text = "✦ Lendo a transcrição…"
                Ai.suggestTitle(act, base) { t, err ->
                    btn.isEnabled = true
                    btn.text = "✦ Sugerir outro título"
                    if (t != null) { input.setText(t); input.setSelection(t.length) }
                    else android.widget.Toast.makeText(act, "Não consegui: $err", android.widget.Toast.LENGTH_LONG).show()
                }
            }
            box.addView(btn, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) })
        }
        android.app.AlertDialog.Builder(act).setTitle("Renomear").setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Salvar") { _, _ ->
                val t = input.text.toString().trim()
                Thread {
                    VocabDb.get(act).setRecordingTitle(base, t)
                    act.runOnUiThread(onDone)
                }.start()
            }
            .show()
    }

    fun prettySize(bytes: Long): String = when {
        bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format(Locale.US, "%.0f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }

    fun newBaseName(): String =
        "legenda_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())

    /** Separa "[mm:ss] texto" em (segundos, texto). Linhas sem timestamp retornam -1. */
    fun parseLine(line: String): Pair<Int, String> {
        val m = Regex("^\\[(\\d+):(\\d{2})\\]\\s*(.*)$").find(line) ?: return -1 to line
        val (mm, ss, text) = m.destructured
        return (mm.toInt() * 60 + ss.toInt()) to text
    }
}
