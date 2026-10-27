package com.keveny.legendajp

import android.content.Context

/** Gera os exercícios de uma gravação (ou do vocabulário salvo) por foco. */
object StudyGen {

    enum class Focus(val label: String) { MISTO("Misto"), GRAMATICA("Gramática"), VOCAB("Vocabulário"), KANJI("Kanji") }

    /** Uma frase com início e fim (em segundos) no áudio; sec < 0 quando não há áudio. recording = gravação de origem. */
    data class Line(val sec: Int, val text: String, val endSec: Int, val recording: String? = null)

    /**
     * @param level nível JLPT do conteúdo (5 = N5 … 3 = N3 ou acima); itens do nível do usuário ou acima têm prioridade.
     * @param wordKey palavra do vocabulário a que o item se refere (para a pontuação), se houver.
     */
    sealed class Item(val focus: Focus, val kind: String, val sentence: String, val sec: Int, val endSec: Int, val level: Int, val wordKey: String?, val recording: String? = null) {
        class Order(s: Line, val chunks: List<String>, level: Int) :
            Item(Focus.GRAMATICA, "Ordenar a frase", s.text, s.sec, s.endSec, level, null, s.recording)
        class GrammarQ(s: Line, val hit: Grammar.Hit, val options: List<Grammar.Point>) :
            Item(Focus.GRAMATICA, "Identificar o padrão", s.text, s.sec, s.endSec, levelNum(hit.point.level), null, s.recording)
        class GrammarCloze(s: Line, val hit: Grammar.Hit, val options: List<String>) :
            Item(Focus.GRAMATICA, "Lacuna gramatical", s.text, s.sec, s.endSec, levelNum(hit.point.level), null, s.recording)
        class Cloze(s: Line, val word: Jp.Word, val options: List<String>) :
            Item(Focus.VOCAB, "Lacuna com áudio", s.text, s.sec, s.endSec, Jlpt.wordLevel(word.base), word.key, s.recording)
        class Choice(f: Focus, kind: String, s: Line, val prompt: String, val answer: String, val options: List<String>, val note: String, val word: Jp.Word) :
            Item(f, kind, s.text, s.sec, s.endSec, Jlpt.wordLevel(word.base), word.key, s.recording)
        class Kanji(s: Line, val word: Jp.Word, val tiles: List<String>) :
            Item(Focus.KANJI, "Montar a palavra com kanji", s.text, s.sec, s.endSec, Jlpt.wordLevel(word.base), word.key, s.recording)
        class Write(s: Line, val word: Jp.Word) :
            Item(Focus.KANJI, "Escrever a palavra à mão", s.text, s.sec, s.endSec, Jlpt.wordLevel(word.base), word.key, s.recording)
        /** Exercício de uso da gramática (GrammarDrill): múltipla escolha ligada a um padrão do catálogo. */
        class Drill(kind: String, s: Line, val pointId: String, val prompt: String, val answer: String, val options: List<String>, val note: String, val word: Jp.Word?, lvl: Int) :
            Item(Focus.GRAMATICA, kind, s.text, s.sec, s.endSec, lvl, null, s.recording)
        /** Frase livre usando um padrão, corrigida pela IA. */
        class FreeWrite(s: Line, val pointId: String, val word: Jp.Word?) :
            Item(Focus.GRAMATICA, "Escrever com o padrão (IA)", s.text, s.sec, s.endSec,
                Grammar.point(pointId)?.level?.removePrefix("N")?.toIntOrNull() ?: 5, null, s.recording)

        /** Flashcard da revisão espaçada: frente e verso; a nota (De novo…Fácil) é escolhida. */
        class Flash(val card: Srs.Card, val word: Jp.Word?, val point: Grammar.Point?, s: Line) :
            Item(
                when (card.kind) { Srs.MEANING -> Focus.VOCAB; Srs.KANJI -> Focus.KANJI; else -> Focus.GRAMATICA },
                "Flashcard (${Srs.kindLabel(card.kind)})", s.text, s.sec, s.endSec, 5,
                if (card.kind == Srs.GRAMMAR) null else card.key, s.recording
            )
        class Parts(s: Line, val word: Jp.Word, val kanji: Char, val parts: List<KanjiParts.Part>, val tiles: List<KanjiParts.Part>) :
            Item(Focus.KANJI, "Montar o kanji com as partes", s.text, s.sec, s.endSec, Jlpt.kanjiLevel(kanji), word.key, s.recording)

        /** Identidade para não repetir o mesmo exercício na sessão. */
        val identity: String get() = kind + "|" + (wordKey ?: sentence)
    }

    enum class Difficulty(val label: String) { FACIL("Fácil"), MEDIO("Médio"), DIFICIL("Difícil") }

    data class Kind(val name: String, val focus: Focus, val difficulty: Difficulty, val description: String, val trains: String)

    const val TRAINS_KANJI = "kanji"
    const val TRAINS_MEANING = "significado"
    const val TRAINS_GRAMMAR = "gramática"

    /** Todos os tipos de exercício, com foco, dificuldade e o que treinam. */
    val allKinds: List<Kind> = listOf(
        Kind("Identificar o padrão", Focus.GRAMATICA, Difficulty.FACIL, "A frase e 4 nomes de padrão gramatical.", TRAINS_GRAMMAR),
        Kind("Lacuna gramatical", Focus.GRAMATICA, Difficulty.MEDIO, "O trecho do padrão é apagado; escolha o que completa.", TRAINS_GRAMMAR),
        Kind("Ordenar a frase", Focus.GRAMATICA, Difficulty.MEDIO, "Monte a frase com os pedaços embaralhados, com a tradução como guia.", TRAINS_GRAMMAR),
        Kind("Qual forma combina", Focus.GRAMATICA, Difficulty.FACIL, "O padrão fixo; escolha a forma do verbo ou adjetivo que encaixa (食べ＿＿とき).", TRAINS_GRAMMAR),
        Kind("Português → padrão", Focus.GRAMATICA, Difficulty.FACIL, "Com a mesma palavra, qual forma tem o sentido pedido («quero …»).", TRAINS_GRAMMAR),
        Kind("Transforme com o padrão", Focus.GRAMATICA, Difficulty.MEDIO, "Aplique o padrão à palavra; as erradas são os erros comuns de conjugação.", TRAINS_GRAMMAR),
        Kind("Escrever com o padrão (IA)", Focus.GRAMATICA, Difficulty.DIFICIL, "Escreva uma frase sua usando o padrão; a IA corrige (precisa da IA ligada).", TRAINS_GRAMMAR),
        Kind("Lacuna com áudio", Focus.VOCAB, Difficulty.FACIL, "Uma palavra escondida na frase, 4 opções, com áudio.", TRAINS_MEANING),
        Kind("Tradução → palavra", Focus.VOCAB, Difficulty.FACIL, "Dada a tradução, escolha a palavra (precisa de tradução em cache).", TRAINS_MEANING),
        Kind("Palavra → tradução", Focus.VOCAB, Difficulty.FACIL, "Dada a palavra, escolha o significado (precisa de tradução em cache).", TRAINS_MEANING),
        Kind("Palavra em kanji → leitura", Focus.KANJI, Difficulty.FACIL, "Escolha a leitura em kana.", TRAINS_KANJI),
        Kind("Leitura → palavra em kanji", Focus.KANJI, Difficulty.FACIL, "Dada a leitura, escolha a palavra em kanji.", TRAINS_KANJI),
        Kind("Significado do kanji", Focus.KANJI, Difficulty.FACIL, "Dado um kanji da palavra, escolha o significado.", TRAINS_KANJI),
        Kind("Montar a palavra com kanji", Focus.KANJI, Difficulty.MEDIO, "Peças de kanji e kana; toque na ordem certa.", TRAINS_KANJI),
        Kind("Montar o kanji com as partes", Focus.KANJI, Difficulty.MEDIO, "O kanji em pedaços (KanjiVG); toque nas partes na ordem das lacunas.", TRAINS_KANJI),
        Kind("Escrever a palavra à mão", Focus.KANJI, Difficulty.DIFICIL, "Desenhe cada kanji da palavra com o dedo; o app reconhece.", TRAINS_KANJI),
    )

    val kinds: Map<Focus, List<String>> = Focus.values().filter { it != Focus.MISTO }
        .associateWith { f -> allKinds.filter { it.focus == f }.map { it.name } }

    fun trainsOf(kind: String): String? = allKinds.firstOrNull { it.name == kind }?.trains

    /** Exercícios de kanji que pedem para escrever/montar (os que sobram quando você já reconhece a palavra). */
    val writingKinds = setOf("Leitura → palavra em kanji", "Montar a palavra com kanji", "Montar o kanji com as partes", "Escrever a palavra à mão")

    /**
     * O exercício está escondido pelas marcas da palavra? "Conheço a palavra" tira os de vocabulário e os de
     * kanji de reconhecimento (leitura, significado); "sei escrever" tira todos os de kanji.
     */
    fun hiddenByKnown(item: Item): Boolean {
        if (item.wordKey == null) return false
        val w = wordOf(item) ?: return false
        return when (trainsOf(item.kind)) {
            TRAINS_KANJI -> Jp.keyIn(w, VocabDb.knownKanjiKeys) != null || (Jp.keyIn(w, VocabDb.knownKeys) != null && item.kind !in writingKinds)
            TRAINS_MEANING -> Jp.keyIn(w, VocabDb.knownKeys) != null
            else -> false
        }
    }

    fun isKanji(c: Char) = c in '一'..'鿿'
    private fun isKana(c: Char) = c in '぀'..'ヿ'
    private fun levelNum(level: String) = level.removePrefix("N").toIntOrNull() ?: 5

    private val kanaDistractors = listOf("の", "は", "を", "に", "が", "と", "い", "る", "た", "し", "く", "な")

    /** Quantos exercícios distintos cada foco consegue gerar. */
    /** O que ainda dá para estudar aqui: palavras por foco (as que o escolhedor vai mostrar), kanji novos e exercícios de gramática. */
    data class Available(val words: Map<Focus, Int>, val kanji: Int, val grammar: Int)

    /**
     * Mesmas regras do escolhedor de palavras (WordPickActivity): tipos de exercício desligados, palavras
     * marcadas Já conheço, já estudadas (cartão respondido; voltam pela Revisão) e puladas há menos de
     * 24 h ficam de fora. Gramática conta exercícios (não passa pela escolha de palavras).
     */
    fun available(ctx: Context, lines: List<Line>, restrict: Set<String>? = null): Available {
        val disabled = Prefs(ctx).disabledKinds
        val exclude = VocabDb.get(ctx).skippedKeys() + Srs.studiedKeys(ctx)
        val items = generateAll(ctx, lines, kotlin.random.Random(1), restrict, grammar = restrict == null)
            .filter { it.kind !in disabled }.distinctBy { it.identity }
        val grammar = items.count { it.focus == Focus.GRAMATICA }
        val wordItems = items.filter { it.focus != Focus.GRAMATICA }
        val words = listOf(Focus.MISTO, Focus.VOCAB, Focus.KANJI).associateWith { candidatesOf(wordItems, it, exclude).size }
        val kanji = candidatesOf(wordItems, Focus.KANJI, exclude).flatMap { c -> c.word.base.filter(::isKanji).toList() }.distinct().size
        return Available(words, kanji, grammar)
    }

    /**
     * Monta a sessão: sem repetir exercício, intercalando tipos, com prioridade para itens do nível
     * do usuário ou acima (level menor = mais difícil).
     * @param grammar inclui gramática e ordenar frase; com onlyKeys, só das frases que têm essas palavras.
     * @param cover garante pelo menos um exercício de cada palavra de onlyKeys (palavras escolhidas).
     */
    fun build(
        ctx: Context, lines: List<Line>, focus: Focus, levels: Set<Int>, max: Int, onlyKeys: Set<String>? = null,
        grammar: Boolean = onlyKeys == null, cover: Boolean = false
    ): List<Item> {
        val rnd = kotlin.random.Random(System.nanoTime())
        val disabled = Prefs(ctx).disabledKinds
        val all = generateAll(ctx, lines, rnd, onlyKeys, grammar).shuffled(rnd)
            .filter { it.kind !in disabled }
            .distinctBy { it.identity }
        val pool = if (focus == Focus.MISTO) all else all.filter { it.focus == focus }
        val out = mutableListOf<Item>()
        if (cover && onlyKeys != null) {
            for (k in onlyKeys.shuffled(rnd)) {
                if (out.size >= max) break
                val mine = pool.filter { it.wordKey == k }
                (mine.firstOrNull { it.level in levels } ?: mine.firstOrNull())?.let { out.add(it) }
            }
        }
        val picked = out.map { it.identity }.toHashSet()
        val preferred = pool.filter { it.level in levels && it.identity !in picked }
        val rest = pool.filter { it.level !in levels && it.identity !in picked }
        for (group in listOf(preferred, rest)) {
            val byKind = group.groupBy { it.kind }.values.map { it.toMutableList() }.shuffled(rnd)
            while (out.size < max && byKind.any { it.isNotEmpty() }) {
                for (q in byKind) if (q.isNotEmpty() && out.size < max) out.add(q.removeAt(0))
            }
            if (out.size >= max) break
        }
        return if (cover) out.shuffled(rnd) else out
    }

    /** Palavra a que o exercício se refere (exercícios de vocabulário e kanji). */
    fun wordOf(item: Item): Jp.Word? = when (item) {
        is Item.Cloze -> item.word
        is Item.Choice -> item.word
        is Item.Kanji -> item.word
        is Item.Write -> item.word
        is Item.Parts -> item.word
        is Item.Flash -> item.word
        is Item.Drill -> item.word
        is Item.FreeWrite -> item.word
        else -> null
    }

    // =====================================================================
    // Revisão espaçada: um exercício (ou flashcard) por cartão vencido
    // =====================================================================

    /**
     * Monta a revisão: para cada cartão, um exercício que treina aquilo (significado, kanji ou o padrão
     * de gramática) com uma frase das suas gravações, ou um flashcard.
     * @param mode "misto" (metade de cada, quando houver exercício), "exercicios" ou "flash".
     */
    fun review(ctx: Context, cards: List<Srs.Card>, mode: String): List<Item> {
        val rnd = kotlin.random.Random(System.nanoTime())
        val disabled = Prefs(ctx).disabledKinds
        val wordCards = cards.filter { it.kind != Srs.GRAMMAR }
        val keys = wordCards.map { it.key }.toSet()
        val lines = if (keys.isEmpty()) emptyList() else linesFromVocab(ctx).sortedBy { if (it.sec >= 0) 0 else 1 }
        val wordItems = if (keys.isEmpty()) emptyList() else
            generateAll(ctx, lines, rnd, keys, grammar = false).filter { it.kind !in disabled }
        // Frase (com áudio, de preferência) e a palavra como aparece nela, para os flashcards
        val lineOf = HashMap<String, Pair<Line, Jp.Word>>()
        for (l in lines) {
            if (lineOf.size == keys.size) break
            for (w in (try { Jp.words(l.text) } catch (_: Exception) { emptyList() })) {
                if (w.key in keys && w.key !in lineOf) lineOf[w.key] = l to w
            }
        }
        val saved = if (keys.size > lineOf.size) VocabDb.get(ctx).words().associateBy { it.key } else emptyMap()
        val out = mutableListOf<Item>()
        for (c in cards) {
            val exercise: Item? = when (c.kind) {
                Srs.GRAMMAR -> grammarExercise(ctx, c, rnd, disabled)
                else -> {
                    val trains = if (c.kind == Srs.KANJI) TRAINS_KANJI else TRAINS_MEANING
                    wordItems.filter { it.wordKey == c.key && trainsOf(it.kind) == trains }.randomOrNull(rnd)
                }
            }
            val flash = when (mode) {
                "flash" -> true
                "exercicios" -> exercise == null
                else -> exercise == null || rnd.nextBoolean()
            }
            val item = if (!flash) exercise else when (c.kind) {
                Srs.GRAMMAR -> Grammar.point(c.key)?.let { p -> Item.Flash(c, null, p, lineFor(ctx, c.recording, c.sentence ?: p.example, c.sec)) }
                else -> lineOf[c.key]?.let { (l, w) -> Item.Flash(c, w, null, l) }
                    ?: saved[c.key]?.let { w -> Item.Flash(c, Jp.Word(w.base, w.base, w.base, w.reading, w.pos, w.posKey, "", -1, -1), null, Line(-1, "", -1)) }
            }
            if (item != null) out.add(item)
        }
        return out.shuffled(rnd)
    }

    private fun grammarExercise(ctx: Context, c: Srs.Card, rnd: kotlin.random.Random, disabled: Set<String>): Item? {
        val s = c.sentence ?: return null
        val line = lineFor(ctx, c.recording, s, c.sec)
        val words = try { Jp.words(line.text) } catch (_: Exception) { emptyList() }
        val drills = GrammarDrill.items(ctx, line, words.filter { it.posKey in setOf("動詞", "形容詞") }, listOf(c.key),
            Prefs(ctx).levels, rnd, pointId = c.key, withAi = Ai.enabled(ctx))
        return (generateAll(ctx, listOf(line), rnd, null, grammar = true).filter {
            (it is Item.GrammarQ && it.hit.point.id == c.key) || (it is Item.GrammarCloze && it.hit.point.id == c.key)
        } + drills).filter { it.kind !in disabled }.randomOrNull(rnd)
    }

    private val txtLines = android.util.LruCache<String, List<Line>>(8)

    /** A linha da frase na gravação (para o fim do trecho de áudio); sem gravação, sem áudio. */
    private fun lineFor(ctx: Context, recording: String?, sentence: String, sec: Int): Line {
        if (recording.isNullOrBlank() || sec < 0 || recording.startsWith("__")) return Line(-1, sentence, -1)
        val ls = txtLines.get(recording) ?: run {
            val item = Recordings.find(ctx, "$recording.txt")
            val text = item?.let { try { Recordings.readText(ctx, it.uri) } catch (_: Exception) { null } } ?: return Line(-1, sentence, -1)
            if (Recordings.find(ctx, "$recording.wav") == null) return Line(-1, sentence, -1)
            lines(text, recording).also { txtLines.put(recording, it) }
        }
        return ls.firstOrNull { it.text == sentence } ?: Line(sec, sentence, sec + 6, recording)
    }

    /**
     * Palavra para a escolha antes do estudo: uma frase onde aparece, em quantas frases aparece, o nível
     * e a posição (ordem em que aparece no áudio).
     */
    data class Candidate(val word: Jp.Word, val line: Line, val sentences: Int, val level: Int, val position: Int)

    /**
     * Palavras que geram exercícios neste foco. Ficam de fora as de exclude (já conhecidas, já estudadas,
     * puladas há menos de 24 h). Primeiro as dos níveis marcados, depois as outras; dentro de cada grupo,
     * na ordem pedida (sortInGroup).
     */
    fun candidates(
        ctx: Context, lines: List<Line>, focus: Focus, levels: Set<Int>, restrict: Set<String>? = null,
        exclude: Set<String> = emptySet()
    ): List<Candidate> {
        val disabled = Prefs(ctx).disabledKinds
        val items = generateAll(ctx, lines, kotlin.random.Random(1), restrict, grammar = false).filter { it.kind !in disabled }
        return candidatesOf(items, focus, exclude)
    }

    private fun candidatesOf(all: List<Item>, focus: Focus, exclude: Set<String>): List<Candidate> {
        val items = all.filter {
            it.wordKey != null && it.wordKey !in exclude && (focus == Focus.MISTO || it.focus == focus) &&
                wordOf(it)?.let { w -> Jp.keyIn(w, exclude) } == null
        }
        return items.groupBy { it.wordKey!! }.entries.mapIndexedNotNull { i, (_, its) ->
            val first = its.first()
            val w = wordOf(first) ?: return@mapIndexedNotNull null
            Candidate(w, Line(first.sec, first.sentence, first.endSec, first.recording), its.map { it.sentence }.distinct().size, Jlpt.wordLevel(w.base), i)
        }
    }

    /**
     * Ordena para a escolha: níveis marcados primeiro; dentro de cada grupo "aleatoria", "frequentes"
     * (mais frases primeiro, empate aleatório) ou "audio" (ordem em que aparecem; no vocabulário salvo,
     * a lista já vem das mais recentes).
     */
    fun sortCandidates(list: List<Candidate>, levels: Set<Int>, order: String): List<Candidate> {
        val rnd = kotlin.random.Random(System.nanoTime())
        fun sortGroup(g: List<Candidate>): List<Candidate> = when (order) {
            "frequentes" -> g.shuffled(rnd).sortedByDescending { it.sentences }
            "audio" -> g.sortedBy { it.position }
            else -> g.shuffled(rnd)
        }
        val (mine, rest) = list.partition { it.level in levels }
        return sortGroup(mine) + sortGroup(rest)
    }

    private fun generateAll(ctx: Context, lines: List<Line>, rnd: kotlin.random.Random, onlyKeys: Set<String>?, grammar: Boolean): List<Item> {
        val sentences = lines.filter { it.text.length >= 4 }
        if (sentences.isEmpty()) return emptyList()
        val analyzed = sentences.map { it to (try { Jp.words(it.text) } catch (_: Exception) { emptyList() }) }
        val allWords = analyzed.flatMap { it.second }
            .filter { it.posKey in setOf("名詞", "動詞", "形容詞", "副詞") }
            .distinctBy { it.key }
        val kanjiPool = allWords.flatMap { w -> w.base.filter(::isKanji).toList() }.distinct()
        val saved = VocabDb.savedKeys
        val hitsBySentence = sentences.map { it to (try { Grammar.find(it.text) } catch (_: Exception) { emptyList() }) }
        val allHits = hitsBySentence.flatMap { (l, hs) -> hs.map { l to it } }
        val out = mutableListOf<Item>()
        val singleWord = onlyKeys != null && onlyKeys.size == 1
        val known = VocabDb.knownKeys
        val knownKanji = VocabDb.knownKanjiKeys
        fun chosen(w: Jp.Word) = onlyKeys == null || w.key in onlyKeys
        /** Vocabulário: fora as "conheço a palavra". */
        fun eligible(w: Jp.Word) = chosen(w) && (singleWord || Jp.keyIn(w, known) == null)
        /** Kanji: fora as "sei escrever"; as "conheço a palavra" ficam só com os de escrita (hiddenByKnown, no fim). */
        fun eligibleKanji(w: Jp.Word) = chosen(w) && w.base.any(::isKanji) && (singleWord || Jp.keyIn(w, knownKanji) == null)

        for ((line, ws) in analyzed) {
            val s = line.text
            val hits = hitsBySentence.first { it.first === line }.second
            val sentenceLevel = hits.minOfOrNull { levelNum(it.point.level) } ?: 5

            // ----- Gramática (com palavras escolhidas: só nas frases que têm alguma delas) -----
            if (grammar && (onlyKeys == null || ws.any { it.key in onlyKeys })) {
                val chunks = try { Jp.chunks(s) } catch (_: Exception) { emptyList() }
                if (chunks.size in 4..9) out.add(Item.Order(line, chunks, sentenceLevel))
                val present = hits.map { it.point.id }.toSet()
                // Usar a gramática: com uma palavra da frase (verbo/adjetivo) ou um verbo comum
                val drillWords = ws.filter { it.posKey in setOf("動詞", "形容詞") && (onlyKeys == null || it.key in onlyKeys) && Jp.keyIn(it, known) == null }
                out.addAll(GrammarDrill.items(ctx, line, drillWords, present.toList(), Prefs(ctx).levels, rnd, withAi = Ai.enabled(ctx)))
                for (hit in hits) {
                    val wrong = Grammar.all.filter { it.id !in present }.shuffled(rnd).take(3)
                    out.add(Item.GrammarQ(line, hit, (wrong + hit.point).shuffled(rnd)))
                    val span = s.substring(hit.start, hit.end)
                    val others = allHits.filter { it.second.point.id != hit.point.id }
                        .map { (l, h) -> l.text.substring(h.start, h.end) }
                        .filter { it != span && it.length in 1..12 }.distinct().shuffled(rnd).take(3)
                    if (others.size == 3 && span.length in 1..12) {
                        out.add(Item.GrammarCloze(line, hit, (others + span).shuffled(rnd)))
                    }
                }
            }

            // ----- Vocabulário: todas as palavras de conteúdo da frase -----
            val content = ws.filter { it.posKey in setOf("名詞", "動詞", "形容詞") && (eligible(it) || eligibleKanji(it)) }
            for (w in content) {
                if (eligible(w)) {
                if (w.surface.length >= 2 || Jp.keyIn(w, saved) != null) {
                    val same = allWords.filter { it.key != w.key && it.posKey == w.posKey }.shuffled(rnd).map { it.surface }
                    val fallback = allWords.filter { it.key != w.key }.shuffled(rnd).map { it.surface }
                    val opts = (same + fallback).filter { it != w.surface }.distinct().take(3)
                    if (opts.size == 3) out.add(Item.Cloze(line, w, (opts + w.surface).shuffled(rnd)))
                }
                val tr = Tr.cached(ctx, w.base)
                if (tr != null) {
                    val others = allWords.filter { it.key != w.key }.shuffled(rnd).take(3)
                    if (others.size == 3) {
                        out.add(Item.Choice(Focus.VOCAB, "Tradução → palavra", line, "Qual palavra significa \"$tr\"?", w.base,
                            (others.map { it.base } + w.base).distinct().shuffled(rnd), "${w.base} (${w.reading}) · ${w.pos}", w))
                        val otherTr = allWords.filter { it.key != w.key }.mapNotNull { Tr.cached(ctx, it.base) }
                            .filter { it != tr }.distinct().shuffled(rnd).take(3)
                        if (otherTr.size == 3) {
                            out.add(Item.Choice(Focus.VOCAB, "Palavra → tradução", line, "O que significa 「${w.base}」?", tr,
                                (otherTr + tr).shuffled(rnd), "${w.base} (${w.reading}) · ${w.pos}", w))
                        }
                    }
                }

                } // eligible (vocabulário)

                // ----- Kanji -----
                if (!eligibleKanji(w)) continue
                val hasKanji = w.base.any(::isKanji)
                val simple = w.base.all { isKanji(it) || isKana(it) }
                if (hasKanji && simple && w.base.length >= 2) {
                    // Peças: todos os caracteres da palavra (kanji e kana) + kanji e kana de distração
                    val need = w.base.toList()
                    val extraKanji = kanjiPool.filter { it !in need }.shuffled(rnd).take(maxOf(0, 7 - need.size))
                    val extraKana = if (need.any(::isKana)) kanaDistractors.filter { it[0] !in need }.shuffled(rnd).take(2) else emptyList()
                    val tiles = (need.map { it.toString() } + extraKanji.map { it.toString() } + extraKana)
                    if (tiles.size >= 4) out.add(Item.Kanji(line, w, tiles.shuffled(rnd)))
                }
                if (hasKanji && simple && w.base.count(::isKanji) in 1..3) out.add(Item.Write(line, w))
                // Partes de um kanji da palavra (KanjiVG)
                if (hasKanji) {
                    val entry = w.base.filter(::isKanji).toList().shuffled(rnd).firstNotNullOfOrNull { KanjiParts.get(ctx, it) }
                    if (entry != null) {
                        val extra = KanjiParts.distractors(ctx, entry, maxOf(2, 6 - entry.parts.size), kanjiPool, rnd)
                        if (extra.size >= 2) out.add(Item.Parts(line, w, entry.char, entry.parts, (entry.parts + extra).shuffled(rnd)))
                    }
                }
                // Significado de um kanji da palavra (KANJIDIC2)
                if (hasKanji) {
                    val k = w.base.filter(::isKanji).toList().shuffled(rnd)
                        .mapNotNull { Kanjidic.get(ctx, it) }.firstOrNull { it.meanings.isNotEmpty() }
                    if (k != null) {
                        val answer = k.shortMeaning
                        val near = kanjiPool.filter { it != k.char }.mapNotNull { Kanjidic.get(ctx, it) }
                        val far = Kanjidic.sample(ctx, setOf(k.char), 8, rnd)
                        val opts = (near.shuffled(rnd) + far).map { it.shortMeaning }
                            .filter { it.isNotBlank() && it != answer }.distinct().take(3)
                        if (opts.size == 3) {
                            val reading = listOf(k.on.joinToString("、"), k.kunPretty.joinToString("、")).filter { it.isNotEmpty() }.joinToString(" · ")
                            out.add(Item.Choice(Focus.KANJI, "Significado do kanji", line, "O que significa o kanji 「${k.char}」?",
                                answer, (opts + answer).shuffled(rnd), "${k.char}: $reading (em ${w.base})", w))
                        }
                    }
                }
                if (hasKanji && w.reading != w.base) {
                    // Alternativas com o mesmo okurigana, para o final da palavra não entregar a resposta
                    val wrongReadings = ReadingOptions.readings(ctx, w, allWords, rnd)
                    if (wrongReadings.size == 3) {
                        out.add(Item.Choice(Focus.KANJI, "Palavra em kanji → leitura", line, "Como se lê 「${w.base}」?", w.reading,
                            (wrongReadings + w.reading).shuffled(rnd), "${w.base}: ${w.pos}", w))
                    }
                    val wrongWords = ReadingOptions.words(ctx, w, allWords, kanjiPool, rnd)
                    if (wrongWords.size == 3) {
                        out.add(Item.Choice(Focus.KANJI, "Leitura → palavra em kanji", line, "Qual palavra se lê 「${w.reading}」?", w.base,
                            (wrongWords + w.base).shuffled(rnd), "${w.base}: ${w.pos}", w))
                    }
                }
            }
        }
        return out.filter { (it !is Item.Choice || it.options.size == 4) && (singleWord || !hiddenByKnown(it)) }
    }

    /** Lê o .txt da gravação e monta as linhas com fim estimado (início da próxima frase). */
    fun lines(text: String, recording: String? = null): List<Line> {
        val parsed = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { Recordings.parseLine(it) }
        return parsed.mapIndexed { i, (sec, t) ->
            val next = parsed.getOrNull(i + 1)?.first ?: -1
            val end = if (next > sec) next else sec + 6
            Line(sec, t, end, recording)
        }
    }

    /**
     * Linhas a partir das frases do vocabulário salvo. Quando a mesma frase está em várias
     * gravações, escolhe uma ao acaso; o fim do trecho vem do .txt da gravação de origem.
     */
    fun linesFromVocab(ctx: Context): List<Line> {
        val rnd = kotlin.random.Random(System.nanoTime())
        val bySentence = VocabDb.get(ctx).allOccurrences().groupBy { it.sentence }
        val txtCache = HashMap<String, List<Pair<Int, String>>?>()
        fun recordingLines(rec: String): List<Pair<Int, String>>? = txtCache.getOrPut(rec) {
            val item = Recordings.find(ctx, "$rec.txt") ?: return@getOrPut null
            try {
                Recordings.readText(ctx, item.uri).lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { Recordings.parseLine(it) }
            } catch (_: Exception) { null }
        }
        return bySentence.map { (sentence, occs) ->
            val o = occs.random(rnd)
            val rl = recordingLines(o.recording)
            val hasAudio = rl != null && Recordings.find(ctx, "${o.recording}.wav") != null
            if (!hasAudio) Line(-1, sentence, -1, null) else {
                val next = rl!!.firstOrNull { it.first > o.sec }?.first ?: -1
                Line(o.sec, sentence, if (next > o.sec) next else o.sec + 6, o.recording)
            }
        }
    }
}
