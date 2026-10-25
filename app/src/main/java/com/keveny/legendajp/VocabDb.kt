package com.keveny.legendajp

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Vocabulário salvo: palavras (forma de dicionário) e as frases onde apareceram. */
class VocabDb private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "michi.db", null, 10) {

    companion object {
        @Volatile private var instance: VocabDb? = null
        fun get(ctx: Context): VocabDb =
            instance ?: synchronized(this) { instance ?: VocabDb(ctx.applicationContext).also { instance = it } }

        /** Chaves (base|classe) das palavras salvas, em memória, para destacar sem tocar no banco. */
        @Volatile var savedKeys: Set<String> = emptySet()
            private set

        /** Palavras salvas marcadas como "já conheço": fora dos exercícios e sem destaque. */
        @Volatile var knownKeys: Set<String> = emptySet()
            private set

        /** Salvas e ainda em estudo (destacadas nas legendas). */
        @Volatile var studyKeys: Set<String> = emptySet()
            private set

        /** Muda a cada recarga das chaves (para as telas saberem quando redesenhar). */
        @Volatile var keysVersion = 0
            private set
    }

    data class Word(
        val id: Long, val base: String, val reading: String, val pos: String, val posKey: String,
        val created: Long, val count: Int, val kanjiScore: Int = 0, val meaningScore: Int = 0,
        val known: Boolean = false
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
                created INTEGER NOT NULL, kanji_score INTEGER NOT NULL DEFAULT 0, meaning_score INTEGER NOT NULL DEFAULT 0,
                known INTEGER NOT NULL DEFAULT 0,
                UNIQUE(base, pos_key))"""
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
        createStudy(db)
        createMistakes(db)
        createTimingAndAi(db)
        Srs.createTables(db)
        createTrAi(db)
        createSkip(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createTr(db)
        if (oldVersion < 3) createStudy(db)
        if (oldVersion < 4) addScores(db)
        if (oldVersion < 5) createMistakes(db)
        if (oldVersion < 6) db.execSQL("ALTER TABLE words ADD COLUMN known INTEGER NOT NULL DEFAULT 0")
        if (oldVersion < 7) createTimingAndAi(db)
        if (oldVersion < 8) Srs.createTables(db)
        if (oldVersion < 9) createTrAi(db)
        if (oldVersion < 10) createSkip(db)
    }

    /** Marca/desmarca "já conheço". Ao marcar, tira a palavra da fila de erros. */
    fun setKnown(key: String, known: Boolean) {
        val (base, posKey) = key.split("|").let { it[0] to it.getOrElse(1) { "" } }
        val db = writableDatabase
        db.execSQL("UPDATE words SET known = ? WHERE base = ? AND pos_key = ?", arrayOf(if (known) 1 else 0, base, posKey))
        if (known) db.delete("mistakes", "identity LIKE ?", arrayOf("%|$key"))
        reloadKeys()
    }

    fun isKnown(key: String) = key in knownKeys

    // ---------- fila de erros ----------

    private fun createMistakes(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS mistakes (
            identity TEXT PRIMARY KEY, json TEXT NOT NULL, kind TEXT NOT NULL, prompt TEXT NOT NULL,
            answer TEXT NOT NULL, sentence TEXT NOT NULL, created INTEGER NOT NULL)""")
    }

    data class Mistake(val identity: String, val json: String, val kind: String, val prompt: String,
                       val answer: String, val sentence: String, val created: Long)

    fun addMistake(m: Mistake) {
        writableDatabase.insertWithOnConflict("mistakes", null, ContentValues().apply {
            put("identity", m.identity); put("json", m.json); put("kind", m.kind); put("prompt", m.prompt)
            put("answer", m.answer); put("sentence", m.sentence); put("created", m.created)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun removeMistake(identity: String) {
        writableDatabase.delete("mistakes", "identity=?", arrayOf(identity))
    }

    fun mistakes(): List<Mistake> {
        val out = mutableListOf<Mistake>()
        readableDatabase.rawQuery(
            "SELECT identity, json, kind, prompt, answer, sentence, created FROM mistakes ORDER BY created DESC", null
        ).use { c ->
            while (c.moveToNext()) out.add(Mistake(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getLong(6)))
        }
        return out
    }

    fun mistakeCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM mistakes", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    private fun addScores(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE words ADD COLUMN kanji_score INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE words ADD COLUMN meaning_score INTEGER NOT NULL DEFAULT 0")
    }

    /** Atualiza a barrinha de kanji ou de significado da palavra (acerto +10, erro -5, 0..1000). */
    fun bumpScore(key: String, kanji: Boolean, correct: Boolean) {
        val (base, posKey) = key.split("|").let { it[0] to it.getOrElse(1) { "" } }
        val col = if (kanji) "kanji_score" else "meaning_score"
        val delta = if (correct) Score.GAIN else -Score.LOSS
        writableDatabase.execSQL(
            "UPDATE words SET $col = MIN(${Score.MAX}, MAX(0, $col + ?)) WHERE base=? AND pos_key=?",
            arrayOf(delta, base, posKey)
        )
    }

    /** Todas as ocorrências do vocabulário salvo (frase, gravação, segundo). */
    fun allOccurrences(): List<Occurrence> {
        val out = mutableListOf<Occurrence>()
        readableDatabase.rawQuery(
            "SELECT id, sentence, surface, recording, sec, created FROM occ ORDER BY created DESC", null
        ).use { c ->
            while (c.moveToNext()) out.add(
                Occurrence(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4), c.getLong(5))
            )
        }
        return out
    }

    private fun createStudy(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS study_session (
            id INTEGER PRIMARY KEY AUTOINCREMENT, recording TEXT NOT NULL, focus TEXT NOT NULL,
            total INTEGER NOT NULL, correct INTEGER NOT NULL, created INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS study_item (
            id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER NOT NULL REFERENCES study_session(id) ON DELETE CASCADE,
            kind TEXT NOT NULL, prompt TEXT NOT NULL, answer TEXT NOT NULL, given TEXT NOT NULL,
            correct INTEGER NOT NULL, sentence TEXT NOT NULL)""")
    }

    data class StudySession(val id: Long, val recording: String, val focus: String, val total: Int, val correct: Int, val created: Long)
    data class StudyItem(val kind: String, val prompt: String, val answer: String, val given: String, val correct: Boolean, val sentence: String)

    fun startSession(recording: String, focus: String, total: Int): Long =
        writableDatabase.insert("study_session", null, ContentValues().apply {
            put("recording", recording); put("focus", focus); put("total", total); put("correct", 0)
            put("created", System.currentTimeMillis())
        })

    fun addStudyItem(sessionId: Long, it: StudyItem) {
        val db = writableDatabase
        db.insert("study_item", null, ContentValues().apply {
            put("session_id", sessionId); put("kind", it.kind); put("prompt", it.prompt); put("answer", it.answer)
            put("given", it.given); put("correct", if (it.correct) 1 else 0); put("sentence", it.sentence)
        })
        if (it.correct) db.execSQL("UPDATE study_session SET correct = correct + 1 WHERE id = ?", arrayOf(sessionId))
        db.execSQL("UPDATE study_session SET total = (SELECT COUNT(*) FROM study_item WHERE session_id = ?) WHERE id = ?",
            arrayOf(sessionId, sessionId))
    }

    fun sessions(recording: String): List<StudySession> {
        val out = mutableListOf<StudySession>()
        readableDatabase.rawQuery(
            "SELECT id, recording, focus, total, correct, created FROM study_session WHERE recording=? ORDER BY created DESC",
            arrayOf(recording)
        ).use { c -> while (c.moveToNext()) out.add(StudySession(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3), c.getInt(4), c.getLong(5))) }
        return out
    }

    fun studyItems(sessionId: Long): List<StudyItem> {
        val out = mutableListOf<StudyItem>()
        readableDatabase.rawQuery(
            "SELECT kind, prompt, answer, given, correct, sentence FROM study_item WHERE session_id=? ORDER BY id",
            arrayOf(sessionId.toString())
        ).use { c -> while (c.moveToNext()) out.add(StudyItem(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4) == 1, c.getString(5))) }
        return out
    }

    fun deleteSession(id: Long) {
        val db = writableDatabase
        db.delete("study_item", "session_id=?", arrayOf(id.toString()))
        db.delete("study_session", "id=?", arrayOf(id.toString()))
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

    // ---------- tempo das palavras (reconhecedor) e respostas da IA ----------

    private fun createTimingAndAi(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS timing (recording TEXT NOT NULL, sentence TEXT NOT NULL, words TEXT NOT NULL, PRIMARY KEY(recording, sentence))")
        db.execSQL("CREATE TABLE IF NOT EXISTS ai (k TEXT PRIMARY KEY, out TEXT NOT NULL, created INTEGER NOT NULL)")
    }

    /** words: WordTiming.encode (trechos da frase com o ms em que começam no WAV). */
    fun putTiming(recording: String, sentence: String, words: String) {
        writableDatabase.insertWithOnConflict("timing", null, ContentValues().apply {
            put("recording", recording); put("sentence", sentence); put("words", words)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun timing(recording: String, sentence: String): String? =
        readableDatabase.rawQuery("SELECT words FROM timing WHERE recording=? AND sentence=?", arrayOf(recording, sentence)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    /** Traduções feitas pelo Gemini; ficam ao lado das do ML Kit (tabela tr), sem substituir. */
    private fun createTrAi(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS tr_ai (text TEXT PRIMARY KEY, translation TEXT NOT NULL, created INTEGER NOT NULL)")
    }

    /** "Pular" na escolha de palavras: a palavra some da escolha até "until". */
    private fun createSkip(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS pick_skip (key TEXT PRIMARY KEY, until INTEGER NOT NULL)")
    }

    fun skipWord(key: String, until: Long) {
        writableDatabase.insertWithOnConflict("pick_skip", null, ContentValues().apply {
            put("key", key); put("until", until)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun unskipWord(key: String) {
        writableDatabase.delete("pick_skip", "key=?", arrayOf(key))
    }

    fun skippedKeys(now: Long = System.currentTimeMillis()): Set<String> =
        readableDatabase.rawQuery("SELECT key FROM pick_skip WHERE until > ?", arrayOf(now.toString())).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }

    fun aiTranslation(text: String): String? =
        readableDatabase.rawQuery("SELECT translation FROM tr_ai WHERE text=?", arrayOf(text)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    fun putAiTranslation(text: String, translation: String) {
        writableDatabase.insertWithOnConflict("tr_ai", null, ContentValues().apply {
            put("text", text); put("translation", translation); put("created", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun aiGet(k: String): String? =
        readableDatabase.rawQuery("SELECT out FROM ai WHERE k=?", arrayOf(k)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun aiPut(k: String, out: String) {
        writableDatabase.insertWithOnConflict("ai", null, ContentValues().apply {
            put("k", k); put("out", out); put("created", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun onOpen(db: SQLiteDatabase) {
        db.execSQL("PRAGMA foreign_keys = ON")
    }

    fun reloadKeys() {
        val keys = HashSet<String>()
        val known = HashSet<String>()
        readableDatabase.rawQuery("SELECT base, pos_key, known FROM words", null).use { c ->
            while (c.moveToNext()) {
                val k = c.getString(0) + "|" + c.getString(1)
                keys.add(k)
                if (c.getInt(2) == 1) known.add(k)
            }
        }
        savedKeys = keys
        knownKeys = known
        studyKeys = keys - known
        keysVersion++
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
                      (SELECT COUNT(*) FROM occ o WHERE o.word_id = w.id) AS n, w.kanji_score, w.meaning_score, w.known
               FROM words w $where ORDER BY w.known ASC, $order""", args
        ).use { c ->
            while (c.moveToNext()) out.add(
                Word(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getLong(5), c.getInt(6), c.getInt(7), c.getInt(8), c.getInt(9) == 1)
            )
        }
        return out
    }

    fun word(id: Long): Word? =
        readableDatabase.rawQuery(
            """SELECT w.id, w.base, w.reading, w.pos, w.pos_key, w.created,
                      (SELECT COUNT(*) FROM occ o WHERE o.word_id = w.id), w.kanji_score, w.meaning_score, w.known FROM words w WHERE w.id=?""",
            arrayOf(id.toString())
        ).use { c ->
            if (c.moveToFirst()) Word(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getLong(5), c.getInt(6), c.getInt(7), c.getInt(8), c.getInt(9) == 1) else null
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
