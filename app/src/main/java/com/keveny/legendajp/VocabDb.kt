package com.keveny.legendajp

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Vocabulário salvo: palavras (forma de dicionário) e as frases onde apareceram. */
class VocabDb private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "michi.db", null, 2) {

    companion object {
        @Volatile private var instance: VocabDb? = null
        fun get(ctx: Context): VocabDb =
            instance ?: synchronized(this) { instance ?: VocabDb(ctx.applicationContext).also { instance = it } }

        /** Chaves (base|classe) das palavras salvas, em memória, para destacar sem tocar no banco. */
        @Volatile var savedKeys: Set<String> = emptySet()
            private set
    }

    data class Word(
        val id: Long, val base: String, val reading: String, val pos: String, val posKey: String,
        val created: Long, val count: Int
    ) {
        val key get() = "$base|$posKey"
    }

    data class Occurrence(
        val id: Long, val sentence: String, val surface: String, val recording: String,
        val sec: Int, val created: Long
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE words (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                base TEXT NOT NULL, reading TEXT NOT NULL, pos TEXT NOT NULL, pos_key TEXT NOT NULL,
                created INTEGER NOT NULL, UNIQUE(base, pos_key))"""
        )
        db.execSQL(
            """CREATE TABLE occ (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                word_id INTEGER NOT NULL REFERENCES words(id) ON DELETE CASCADE,
                sentence TEXT NOT NULL, surface TEXT NOT NULL, recording TEXT NOT NULL,
                sec INTEGER NOT NULL, created INTEGER NOT NULL,
                UNIQUE(word_id, sentence, recording))"""
        )
        createTr(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createTr(db)
    }

    private fun createTr(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS tr (text TEXT PRIMARY KEY, translation TEXT NOT NULL, created INTEGER NOT NULL)")
    }

    fun translation(text: String): String? =
        readableDatabase.rawQuery("SELECT translation FROM tr WHERE text=?", arrayOf(text)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    fun putTranslation(text: String, translation: String) {
        writableDatabase.insertWithOnConflict("tr", null, ContentValues().apply {
            put("text", text); put("translation", translation); put("created", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun onOpen(db: SQLiteDatabase) {
        db.execSQL("PRAGMA foreign_keys = ON")
    }

    fun reloadKeys() {
        val keys = HashSet<String>()
        readableDatabase.rawQuery("SELECT base, pos_key FROM words", null).use { c ->
            while (c.moveToNext()) keys.add(c.getString(0) + "|" + c.getString(1))
        }
        savedKeys = keys
    }

    fun isSaved(w: Jp.Word) = w.key in savedKeys

    /** Salva a palavra (se nova) e registra a frase de origem. */
    fun save(w: Jp.Word, sentence: String, recording: String, sec: Int): Long {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        var id = findId(db, w.base, w.posKey)
        if (id < 0) {
            id = db.insert("words", null, ContentValues().apply {
                put("base", w.base); put("reading", w.reading); put("pos", w.pos); put("pos_key", w.posKey)
                put("created", now)
            })
        }
        db.insertWithOnConflict("occ", null, ContentValues().apply {
            put("word_id", id); put("sentence", sentence); put("surface", w.surface)
            put("recording", recording); put("sec", sec); put("created", now)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        reloadKeys()
        return id
    }

    fun remove(w: Jp.Word) {
        val db = writableDatabase
        db.delete("occ", "word_id IN (SELECT id FROM words WHERE base=? AND pos_key=?)", arrayOf(w.base, w.posKey))
        db.delete("words", "base=? AND pos_key=?", arrayOf(w.base, w.posKey))
        reloadKeys()
    }

    fun removeById(id: Long) {
        val db = writableDatabase
        db.delete("occ", "word_id=?", arrayOf(id.toString()))
        db.delete("words", "id=?", arrayOf(id.toString()))
        reloadKeys()
    }

    private fun findId(db: SQLiteDatabase, base: String, posKey: String): Long =
        db.rawQuery("SELECT id FROM words WHERE base=? AND pos_key=?", arrayOf(base, posKey)).use { c ->
            if (c.moveToFirst()) c.getLong(0) else -1L
        }

    /** Registra automaticamente as palavras salvas que aparecem numa frase nova. */
    fun recordOccurrences(sentence: String, recording: String, sec: Int) {
        if (savedKeys.isEmpty()) return
        val words = try { Jp.words(sentence) } catch (_: Exception) { return }
        val hits = words.filter { it.key in savedKeys }
        if (hits.isEmpty()) return
        val db = writableDatabase
        val now = System.currentTimeMillis()
        for (w in hits) {
            val id = findId(db, w.base, w.posKey)
            if (id < 0) continue
            db.insertWithOnConflict("occ", null, ContentValues().apply {
                put("word_id", id); put("sentence", sentence); put("surface", w.surface)
                put("recording", recording); put("sec", sec); put("created", now)
            }, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    /** Desloca os tempos das ocorrências de uma gravação (quando o começo do áudio foi cortado). */
    fun shiftOccurrences(recording: String, offsetSec: Int) {
        if (offsetSec <= 0) return
        writableDatabase.execSQL(
            "UPDATE occ SET sec = MAX(0, sec - ?) WHERE recording = ?",
            arrayOf(offsetSec, recording)
        )
    }

    fun words(query: String = "", byDate: Boolean = true): List<Word> {
        val out = mutableListOf<Word>()
        val order = if (byDate) "w.created DESC" else "w.reading ASC"
        val where = if (query.isBlank()) "" else "WHERE w.base LIKE ? OR w.reading LIKE ?"
        val args = if (query.isBlank()) null else arrayOf("%$query%", "%$query%")
        readableDatabase.rawQuery(
            """SELECT w.id, w.base, w.reading, w.pos, w.pos_key, w.created,
                      (SELECT COUNT(*) FROM occ o WHERE o.word_id = w.id) AS n
               FROM words w $where ORDER BY $order""", args
        ).use { c ->
            while (c.moveToNext()) out.add(
                Word(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getLong(5), c.getInt(6))
            )
        }
        return out
    }

    fun word(id: Long): Word? =
        readableDatabase.rawQuery(
            """SELECT w.id, w.base, w.reading, w.pos, w.pos_key, w.created,
                      (SELECT COUNT(*) FROM occ o WHERE o.word_id = w.id) FROM words w WHERE w.id=?""",
            arrayOf(id.toString())
        ).use { c ->
            if (c.moveToFirst()) Word(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getLong(5), c.getInt(6)) else null
        }

    fun occurrences(wordId: Long): List<Occurrence> {
        val out = mutableListOf<Occurrence>()
        readableDatabase.rawQuery(
            "SELECT id, sentence, surface, recording, sec, created FROM occ WHERE word_id=? ORDER BY created DESC",
            arrayOf(wordId.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(
                Occurrence(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4), c.getLong(5))
            )
        }
        return out
    }

    /** CSV para o Anki: palavra, leitura, classe, frase de exemplo, gravação. */
    fun exportCsv(): String {
        fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val sb = StringBuilder("palavra,leitura,classe,frase,gravacao\n")
        for (w in words(byDate = false)) {
            val occ = occurrences(w.id).firstOrNull()
            sb.append(listOf(w.base, w.reading, w.pos, occ?.sentence ?: "", occ?.recording ?: "").joinToString(",") { q(it) })
            sb.append('\n')
        }
        return sb.toString()
    }
}
