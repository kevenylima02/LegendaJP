package com.keveny.legendajp

import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.util.LruCache
import android.view.View
import com.atilika.kuromoji.ipadic.Token
import com.atilika.kuromoji.ipadic.Tokenizer

/**
 * Análise morfológica com Kuromoji (IPADIC): separa a frase em "palavras" (um token de
 * conteúdo + seus auxiliares), dá forma de dicionário, leitura, classe e um rótulo de conjugação.
 */
object Jp {

    /** Uma palavra tocável na frase. */
    data class Word(
        val surface: String,      // como apareceu, incluindo auxiliares: 変わりました
        val head: String,         // só o token principal: 変わり
        val base: String,         // forma de dicionário: 変わる
        val reading: String,      // leitura em hiragana da forma de dicionário
        val pos: String,          // classe (rótulo em português)
        val posKey: String,       // chave estável para o banco: 動詞, 名詞...
        val conjugation: String,  // "passado polido" ou "" para não conjugado
        val start: Int,           // posição na frase
        val end: Int
    ) {
        val key get() = "$base|$posKey"
    }

    /** Momento do último toque numa palavra, para o clique "fora da palavra" não disparar junto. */
    @Volatile var lastWordTap = 0L
    /** Toques em palavras são ignorados até este instante (depois de um toque longo). */
    @Volatile var suppressTapUntil = 0L

    @Volatile private var tokenizer: Tokenizer? = null
    private val cache = LruCache<String, List<Word>>(300)

    val isReady get() = tokenizer != null

    /** Carrega o dicionário (1 a 2 s na primeira vez). Chame numa thread de fundo. */
    @Synchronized
    fun warmUp() {
        if (tokenizer == null) tokenizer = Tokenizer()
    }

    /** Tokens brutos do Kuromoji (para gramática e exercícios). */
    fun tokenize(sentence: String): List<Token> {
        val tk = tokenizer ?: run { warmUp(); tokenizer!! }
        return tk.tokenize(sentence)
    }

    /** Pedaços da frase para o exercício de ordenar: palavra de conteúdo + auxiliares, partículas soltas. */
    fun chunks(sentence: String): List<String> {
        val tokens = tokenize(sentence)
        val ws = words(sentence)
        val out = mutableListOf<String>()
        var pos = 0
        var wi = 0
        for (t in tokens) {
            if (t.partOfSpeechLevel1 == "記号") continue
            if (t.position < pos) continue
            while (wi < ws.size && ws[wi].start < t.position) wi++
            val w = ws.getOrNull(wi)
            if (w != null && t.position == w.start) {
                out.add(w.surface); pos = w.end; wi++
            } else {
                out.add(t.surface); pos = t.position + t.surface.length
            }
        }
        return out
    }

    fun words(sentence: String): List<Word> {
        cache.get(sentence)?.let { return it }
        val tk = tokenizer ?: run { warmUp(); tokenizer!! }
        val tokens = tk.tokenize(sentence)
        val result = mutableListOf<Word>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (!isContent(t)) { i++; continue }
            var j = i + 1
            val aux = mutableListOf<Token>()
            while (j < tokens.size && isAuxOf(tokens[j], aux)) { aux.add(tokens[j]); j++ }
            val last = if (aux.isEmpty()) t else aux.last()
            val start = t.position
            val end = last.position + last.surface.length
            result.add(
                Word(
                    surface = sentence.substring(start, end),
                    head = t.surface,
                    base = baseOf(t),
                    reading = hiragana(if (t.reading == "*") baseOf(t) else readingOfBase(t)),
                    pos = posLabel(t),
                    posKey = t.partOfSpeechLevel1,
                    conjugation = conjugationLabel(t, aux),
                    start = start,
                    end = end
                )
            )
            i = j
        }
        cache.put(sentence, result)
        return result
    }

    // ---------- classificação ----------

    private fun isContent(t: Token): Boolean {
        val p1 = t.partOfSpeechLevel1
        val p2 = t.partOfSpeechLevel2
        return when (p1) {
            "名詞" -> p2 != "非自立" && p2 != "接尾" && p2 != "数" && p2 != "特殊"
            "動詞" -> p2 == "自立"
            "形容詞" -> p2 == "自立"
            "副詞", "連体詞", "接続詞", "感動詞", "接頭詞" -> true
            else -> false
        }
    }

    /** Auxiliares que se grudam ao token de conteúdo: ます, た, ない, て, いる, れる... */
    private fun isAuxOf(t: Token, soFar: List<Token>): Boolean {
        val p1 = t.partOfSpeechLevel1
        val p2 = t.partOfSpeechLevel2
        return when {
            p1 == "助動詞" -> true
            p1 == "動詞" && (p2 == "非自立" || p2 == "接尾") -> true
            p1 == "形容詞" && p2 == "非自立" -> true
            p1 == "助詞" && p2 == "接続助詞" && t.surface in setOf("て", "で", "ば", "ながら", "たり", "ちゃ") -> true
            p1 == "助詞" && p2 == "接続助詞" && t.surface == "ら" && soFar.lastOrNull()?.baseForm == "た" -> true
            p1 == "名詞" && p2 == "接尾" && soFar.isEmpty() -> false
            else -> false
        }
    }

    private fun baseOf(t: Token): String = if (t.baseForm == "*" || t.baseForm.isNullOrEmpty()) t.surface else t.baseForm

    /** Leitura da forma de dicionário: para verbos/adjetivos a leitura do token é da forma conjugada. */
    private fun readingOfBase(t: Token): String {
        val reading = t.reading
        val base = baseOf(t)
        if (base == "来る") return "クル"
        if (base == "する") return "スル"
        if (t.surface == base) return reading
        // Troca o sufixo conjugado pelo da forma de dicionário quando o radical coincide
        val common = t.surface.commonPrefixWith(base).length
        if (common == 0) return reading
        val surfaceSuffix = t.surface.length - common
        if (reading.length < surfaceSuffix) return reading
        val stemReading = reading.substring(0, reading.length - surfaceSuffix)
        return stemReading + base.substring(common)
    }

    fun hiragana(s: String): String = buildString {
        for (c in s) append(if (c in 'ァ'..'ヶ') (c - 0x60) else c)
    }

    private fun posLabel(t: Token): String = when (t.partOfSpeechLevel1) {
        "名詞" -> when (t.partOfSpeechLevel2) {
            "固有名詞" -> "nome próprio"
            "代名詞" -> "pronome"
            "サ変接続" -> "substantivo (verbo com する)"
            "形容動詞語幹" -> "adjetivo-na"
            "副詞可能" -> "substantivo/advérbio"
            else -> "substantivo"
        }
        "動詞" -> "verbo" + verbGroup(t)
        "形容詞" -> "adjetivo-i"
        "副詞" -> "advérbio"
        "連体詞" -> "pré-nominal"
        "接続詞" -> "conjunção"
        "感動詞" -> "interjeição"
        "接頭詞" -> "prefixo"
        else -> t.partOfSpeechLevel1
    }

    private fun verbGroup(t: Token): String {
        val type = t.conjugationType ?: return ""
        return when {
            type.startsWith("五段") -> " (godan)"
            type.startsWith("一段") -> " (ichidan)"
            type.startsWith("サ変") -> " (suru)"
            type.startsWith("カ変") -> " (kuru)"
            else -> ""
        }
    }

    // ---------- conjugação ----------

    private fun conjugationLabel(head: Token, aux: List<Token>): String {
        val p1 = head.partOfSpeechLevel1
        if (p1 != "動詞" && p1 != "形容詞" && aux.isEmpty()) return ""
        val parts = mutableListOf<String>()
        var negative = false
        var past = false
        var polite = false
        var i = 0
        while (i < aux.size) {
            val a = aux[i]
            val b = if (a.baseForm == "*") a.surface else a.baseForm
            when {
                b == "ます" -> polite = true
                b == "です" -> polite = true
                b == "た" || b == "だ" -> {
                    if (i + 1 < aux.size && aux[i + 1].surface == "ら") { parts.add("condicional (tara)"); i++ }
                    else past = true
                }
                b == "ない" || b == "ぬ" || b == "ん" -> negative = true
                b == "て" || b == "で" -> {
                    val next = aux.getOrNull(i + 1)
                    val nb = next?.let { if (it.baseForm == "*") it.surface else it.baseForm }
                    val teAux = when (nb) {
                        "いる", "る" -> "progressivo/estado (te-iru)"
                        "ある" -> "resultado (te-aru)"
                        "おく" -> "preparação (te-oku)"
                        "しまう", "ちゃう" -> "conclusão (te-shimau)"
                        "みる" -> "tentar (te-miru)"
                        "くる", "来る" -> "vir (te-kuru)"
                        "いく", "行く" -> "ir (te-iku)"
                        "くれる", "あげる", "もらう", "くださる", "いただく" -> "favor ($nb)"
                        "ほしい" -> "pedido (te-hoshii)"
                        else -> null
                    }
                    if (teAux != null) { parts.add(teAux); i++ } else parts.add("forma-te")
                }
                b == "れる" || b == "られる" -> parts.add("passivo/potencial")
                b == "せる" || b == "させる" -> parts.add("causativo")
                b == "たい" -> parts.add("desejo (tai)")
                b == "う" || b == "よう" -> parts.add("volitivo")
                b == "ば" -> {
                    // なければならない / なければいけない = obrigação
                    val n1 = aux.getOrNull(i + 1)?.baseForm
                    val n2 = aux.getOrNull(i + 2)?.baseForm
                    if (negative && (n1 == "なる" || n1 == "いける") && n2 == "ない") {
                        negative = false
                        parts.add("obrigação (nakereba naranai)")
                        i += 2
                    } else parts.add("condicional (ba)")
                }
                b == "ながら" -> parts.add("enquanto (nagara)")
                b == "たり" -> parts.add("enumeração (tari)")
                b == "らしい" -> parts.add("parece (rashii)")
                b == "そう" -> parts.add("parece (sou)")
                b == "べき" -> parts.add("dever (beki)")
                b == "まい" -> parts.add("negativo volitivo")
                b == "なら" -> parts.add("condicional (nara)")
                b == "すぎる" -> parts.add("excesso (sugiru)")
                b == "やすい" -> parts.add("fácil de (yasui)")
                b == "にくい" -> parts.add("difícil de (nikui)")
                b == "ちゃ" -> parts.add("forma-te (coloquial)")
                else -> parts.add(b)
            }
            i++
        }
        val tense = buildString {
            if (past) append("passado")
            if (negative) { if (isNotEmpty()) append(" "); append("negativo") }
            if (polite) { if (isNotEmpty()) append(" "); append("polido") }
        }
        if (tense.isNotEmpty()) parts.add(tense)
        if (parts.isEmpty()) {
            return when (head.conjugationForm) {
                "基本形" -> if (p1 == "動詞" || p1 == "形容詞") "forma de dicionário" else ""
                "連用形" -> "radical (forma masu)"
                "未然形" -> "radical (forma nai)"
                "仮定形" -> "condicional"
                "命令ｅ", "命令ｉ", "命令ｒｏ", "命令ｙｏ" -> "imperativo"
                "連用タ接続" -> "radical"
                "連用テ接続" -> "radical (te)"
                else -> ""
            }
        }
        return parts.joinToString(" + ")
    }

    // ---------- texto tocável ----------

    /**
     * Devolve a frase com cada palavra de conteúdo tocável e as salvas em cor própria.
     * O TextView precisa de movementMethod = LinkMovementMethod.
     */
    fun decorate(
        sentence: String,
        saved: Set<String>,
        savedColor: Int,
        onTap: (Word) -> Unit
    ): CharSequence {
        val sb = SpannableStringBuilder(sentence)
        val list = try { words(sentence) } catch (_: Exception) { return sentence }
        for (w in list) {
            val isSaved = w.key in saved
            sb.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now < suppressTapUntil) return
                    lastWordTap = now
                    onTap(w)
                }
                override fun updateDrawState(ds: TextPaint) {
                    ds.isUnderlineText = false
                    if (isSaved) ds.color = savedColor
                }
            }, w.start, w.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (isSaved) sb.setSpan(ForegroundColorSpan(savedColor), w.start, w.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    /** Só o destaque das salvas, sem toque (lista de vocabulário etc.). */
    fun highlight(sentence: String, saved: Set<String>, savedColor: Int): CharSequence {
        val sb = SpannableStringBuilder(sentence)
        val list = try { words(sentence) } catch (_: Exception) { return sentence }
        for (w in list) if (w.key in saved) {
            sb.setSpan(ForegroundColorSpan(savedColor), w.start, w.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    const val OVERLAY_SAVED_COLOR = 0xFFC5CBFF.toInt()
    val TRANSPARENT = Color.TRANSPARENT
}
