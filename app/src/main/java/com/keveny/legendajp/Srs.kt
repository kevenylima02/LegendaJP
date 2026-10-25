package com.keveny.legendajp

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.util.Calendar
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Revisão espaçada com o FSRS 4.5 (o algoritmo do Anki), parâmetros padrão e retenção desejada de 90%.
 * Cartões: significado de uma palavra, kanji de uma palavra e padrão de gramática.
 * - Entram sozinhos: palavras salvas (menos as "Já conheço") e padrões que aparecem nos exercícios.
 * - Toda primeira resposta conta: acertou = Bom, errou = De novo (o botão Fácil sobe para Fácil);
 *   no flashcard a nota é escolhida (De novo, Difícil, Bom, Fácil).
 */
object Srs {
    const val MEANING = "meaning"
    const val KANJI = "kanji"
    const val GRAMMAR = "grammar"

    const val NEW = 0
    const val LEARNING = 1
    const val REVIEW = 2
    const val RELEARNING = 3

    const val AGAIN = 1
    const val HARD = 2
    const val GOOD = 3
    const val EASY = 4

    private const val RETENTION = 0.9
    private const val DECAY = -0.5
    private const val FACTOR = 19.0 / 81.0
    private const val DAY = 86_400_000L
    private const val RELEARN_MS = 10 * 60_000L
    private const val MATURE_DAYS = 21.0
    private val w = doubleArrayOf(
        0.4872, 1.4003, 3.7145, 13.8206, 5.1618, 1.2298, 0.8975, 0.031, 1.6474,
        0.1367, 1.0461, 2.1072, 0.0793, 0.3246, 1.587, 0.2272, 2.8755
    )

    data class Card(
        val id: Long, val kind: String, val key: String, val label: String,
        val state: Int, val due: Long, val stability: Double, val difficulty: Double,
        val reps: Int, val lapses: Int, val last: Long, val first: Long,
        val sentence: String?, val recording: String?, val sec: Int
    )

    fun kindLabel(kind: String) = when (kind) { MEANING -> "significado"; KANJI -> "kanji"; else -> "gramática" }

    fun gradeLabel(g: Int) = when (g) { AGAIN -> "De novo"; HARD -> "Difícil"; GOOD -> "Bom"; else -> "Fácil" }

    fun gradeOf(label: String) = (AGAIN..EASY).firstOrNull { gradeLabel(it) == label } ?: AGAIN

    // =====================================================================
    // FSRS
    // =====================================================================

    /** Chance de lembrar agora (0..1); null para cartão novo. */
    fun retrievability(c: Card, now: Long = System.currentTimeMillis()): Double? {
        if (c.state == NEW || c.last == 0L || c.stability <= 0) return null
        val t = max(0.0, (now - c.last).toDouble() / DAY)
        return (1 + FACTOR * t / c.stability).pow(DECAY)
    }

    private fun d0(g: Int) = clampD(w[4] - (g - 3) * w[5])
    private fun clampD(d: Double) = min(10.0, max(1.0, d))

    /** Intervalo (dias) em que a chance de lembrar cai para a retenção desejada. */
    private fun intervalDays(s: Double) = max(1L, (s / FACTOR * (RETENTION.pow(1 / DECAY) - 1)).roundToLong())

    /** Aplica a nota e devolve o cartão com o novo estado e a nova data. */
    fun schedule(c: Card, g: Int, now: Long = System.currentTimeMillis()): Card {
        val s: Double
        val d: Double
        if (c.state == NEW || c.stability <= 0) {
            s = w[g - 1]
            d = d0(g)
        } else {
            val r = retrievability(c, now) ?: 1.0
            val dn = c.difficulty - w[6] * (g - 3)
            d = clampD(w[7] * d0(3) + (1 - w[7]) * dn)
            s = if (g == AGAIN) {
                min(c.stability, w[11] * c.difficulty.pow(-w[12]) * ((c.stability + 1).pow(w[13]) - 1) * exp(w[14] * (1 - r)))
            } else {
                val hard = if (g == HARD) w[15] else 1.0
                val easy = if (g == EASY) w[16] else 1.0
                c.stability * (exp(w[8]) * (11 - c.difficulty) * c.stability.pow(-w[9]) * (exp(w[10] * (1 - r)) - 1) * hard * easy + 1)
            }
        }
        val state: Int
        val due: Long
        var lapses = c.lapses
        if (g == AGAIN) {
            if (c.state == REVIEW) lapses++
            state = if (c.state == REVIEW || c.state == RELEARNING) RELEARNING else LEARNING
            due = now + RELEARN_MS
        } else {
            state = REVIEW
            due = now + intervalDays(s) * DAY
        }
        return c.copy(state = state, due = due, stability = s, difficulty = d, reps = c.reps + 1, lapses = lapses,
            last = now, first = if (c.first == 0L) now else c.first)
    }

    /** Texto do próximo intervalo de cada nota, para os botões do flashcard. */
    fun preview(c: Card, now: Long = System.currentTimeMillis()): Map<Int, String> =
        (AGAIN..EASY).associateWith { g -> human(schedule(c, g, now).due - now) }

    fun human(ms: Long): String {
        val min = ms / 60_000
        val days = (ms + DAY / 2) / DAY
        return when {
            min < 60 -> "${max(1, min)} min"
            days < 1 -> "${min / 60} h"
            days < 31 -> "$days d"
            days < 365 -> "${days / 30} m"
            else -> String.format("%.1f a", days / 365.0)
        }
    }

    // =====================================================================
    // Banco
    // =====================================================================

    fun createTables(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS srs (
            id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, key TEXT NOT NULL, label TEXT NOT NULL,
            state INTEGER NOT NULL DEFAULT 0, due INTEGER NOT NULL DEFAULT 0, stability REAL NOT NULL DEFAULT 0,
            difficulty REAL NOT NULL DEFAULT 0, reps INTEGER NOT NULL DEFAULT 0, lapses INTEGER NOT NULL DEFAULT 0,
            last INTEGER NOT NULL DEFAULT 0, first INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL,
            sentence TEXT, recording TEXT, sec INTEGER NOT NULL DEFAULT -1,
            UNIQUE(kind, key))""")
        db.execSQL("CREATE TABLE IF NOT EXISTS srs_log (card_id INTEGER NOT NULL, ts INTEGER NOT NULL, grade INTEGER NOT NULL, mode TEXT NOT NULL)")
    }

    private fun db(ctx: Context) = VocabDb.get(ctx).writableDatabase

    private fun read(c: Cursor) = Card(
        c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4), c.getLong(5),
        c.getDouble(6), c.getDouble(7), c.getInt(8), c.getInt(9), c.getLong(10), c.getLong(11),
        c.getString(12), c.getString(13), c.getInt(14)
    )

    private const val COLS = "id, kind, key, label, state, due, stability, difficulty, reps, lapses, last, first, sentence, recording, sec"

    fun all(ctx: Context): List<Card> =
        db(ctx).rawQuery("SELECT $COLS FROM srs ORDER BY created", null).use { c -> buildList { while (c.moveToNext()) add(read(c)) } }

    fun get(ctx: Context, kind: String, key: String): Card? =
        db(ctx).rawQuery("SELECT $COLS FROM srs WHERE kind=? AND key=?", arrayOf(kind, key)).use { c -> if (c.moveToFirst()) read(c) else null }

    /** Cria o cartão (novo) se ainda não existir; atualiza a frase de exemplo quando vier uma. */
    fun ensure(ctx: Context, kind: String, key: String, label: String, sentence: String? = null, recording: String? = null, sec: Int = -1): Card {
        val db = db(ctx)
        db.insertWithOnConflict("srs", null, ContentValues().apply {
            put("kind", kind); put("key", key); put("label", label); put("created", System.currentTimeMillis())
            if (sentence != null) { put("sentence", sentence); put("recording", recording); put("sec", sec) }
        }, SQLiteDatabase.CONFLICT_IGNORE)
        if (sentence != null) {
            db.execSQL("UPDATE srs SET sentence=?, recording=?, sec=? WHERE kind=? AND key=?", arrayOf(sentence, recording, sec, kind, key))
        }
        return get(ctx, kind, key)!!
    }

    private fun save(ctx: Context, c: Card) {
        db(ctx).update("srs", ContentValues().apply {
            put("state", c.state); put("due", c.due); put("stability", c.stability); put("difficulty", c.difficulty)
            put("reps", c.reps); put("lapses", c.lapses); put("last", c.last); put("first", c.first)
        }, "id=?", arrayOf(c.id.toString()))
    }

    /** Dá a nota e devolve o cartão como estava antes (para o botão Fácil refazer a nota). */
    fun grade(ctx: Context, card: Card, g: Int, mode: String): Card {
        save(ctx, schedule(card, g))
        db(ctx).insert("srs_log", null, ContentValues().apply {
            put("card_id", card.id); put("ts", System.currentTimeMillis()); put("grade", g); put("mode", mode)
        })
        return card
    }

    /** Refaz a última nota de um cartão a partir do estado anterior. */
    fun regrade(ctx: Context, before: Card, g: Int) {
        save(ctx, schedule(before, g))
        db(ctx).execSQL(
            "UPDATE srs_log SET grade=? WHERE rowid = (SELECT MAX(rowid) FROM srs_log WHERE card_id=?)",
            arrayOf(g, before.id)
        )
    }

    fun log(ctx: Context, cardId: Long): List<Pair<Long, Int>> =
        db(ctx).rawQuery("SELECT ts, grade FROM srs_log WHERE card_id=? ORDER BY ts DESC LIMIT 30", arrayOf(cardId.toString())).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0) to c.getInt(1)) }
        }

    // =====================================================================
    // Entrada automática e o que vale hoje
    // =====================================================================

    /** Palavras salvas (menos as conhecidas) viram cartões de significado e, se tiverem kanji, de kanji. */
    fun sync(ctx: Context) {
        val words = VocabDb.get(ctx).words()
        for (w in words) {
            if (w.known) continue
            val key = "${w.base}|${w.posKey}"
            ensure(ctx, MEANING, key, w.base)
            if (w.base.any(StudyGen::isKanji)) ensure(ctx, KANJI, key, w.base)
        }
    }

    /** O cartão ainda vale? (palavra salva e não marcada como conhecida) */
    fun active(c: Card): Boolean = c.kind == GRAMMAR || c.key in VocabDb.studyKeys

    fun startOfDay(now: Long = System.currentTimeMillis()): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    data class Today(val due: List<Card>, val fresh: List<Card>, val newLeft: Int)

    /** Vencidos até o fim de hoje + os novos que ainda cabem no limite diário. */
    fun today(ctx: Context): Today {
        val now = System.currentTimeMillis()
        val sod = startOfDay(now)
        val tomorrow = sod + DAY
        val cards = all(ctx).filter(::active)
        val due = cards.filter { it.state != NEW && it.due < tomorrow }.sortedBy { it.due }
        val introduced = cards.count { it.first >= sod }
        val left = max(0, Prefs(ctx).newPerDay - introduced)
        val fresh = cards.filter { it.state == NEW }.take(left)
        return Today(due, fresh, left)
    }

    /** Estado para a tela: novo, aprendendo, revisando, dominado (estabilidade ≥ 21 dias). */
    fun bucket(c: Card): String = when {
        c.state == NEW -> "novos"
        c.state == LEARNING || c.state == RELEARNING -> "aprendendo"
        c.stability >= MATURE_DAYS -> "dominados"
        else -> "revisando"
    }

    /** Revisões que vencem em cada um dos próximos dias (0 = hoje, com os atrasados). */
    fun forecast(ctx: Context, days: Int = 7): IntArray {
        val sod = startOfDay()
        val out = IntArray(days)
        for (c in all(ctx).filter(::active)) {
            if (c.state == NEW) continue
            val d = ((c.due - sod) / DAY).toInt().coerceAtLeast(0)
            if (d < days) out[d]++
        }
        return out
    }

    /** Dias seguidos com pelo menos uma revisão, até hoje (ou até ontem, se hoje ainda não teve). */
    fun streak(ctx: Context): Int {
        val days = db(ctx).rawQuery("SELECT DISTINCT ts FROM srs_log ORDER BY ts DESC LIMIT 2000", null).use { c ->
            buildSet { while (c.moveToNext()) add(startOfDay(c.getLong(0))) }
        }
        var day = startOfDay()
        if (day !in days) day -= DAY
        var n = 0
        while (day in days) { n++; day -= DAY }
        return n
    }
}
