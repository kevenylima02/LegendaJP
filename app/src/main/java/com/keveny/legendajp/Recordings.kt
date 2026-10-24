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
        val m = Regex("legenda_(\\d{4})(\\d{2})(\\d{2})_(\\d{2})(\\d{2})(\\d{2})").find(name) ?: return name
        val (y, mo, d, h, mi, _) = m.destructured
        return "$d/$mo/$y $h:$mi"
    }

    fun baseName(name: String) = name.substringBeforeLast('.')

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
