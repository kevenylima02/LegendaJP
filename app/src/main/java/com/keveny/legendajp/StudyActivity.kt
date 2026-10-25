package com.keveny.legendajp

import android.app.Activity
import android.app.AlertDialog
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.keveny.legendajp.StudyGen.Item
import java.util.concurrent.Executors

/**
 * Sessão de exercícios em três fases:
 * 1. MAIN: os itens da sessão; cada primeira resposta vai para o histórico, as barrinhas e a fila de erros.
 *    "Anterior" mostra itens já respondidos, só para leitura.
 * 2. REVIEW: os itens errados voltam até serem acertados (escrita à mão: 3 passagens, com guia,
 *    com botão Lembrar e sem ajuda). Nada disso vai para o histórico.
 * 3. DONE: resumo, Refazer (mesmos itens) e Fechar.
 */
class StudyActivity : Activity() {

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_NAME = "name"
        const val EXTRA_FOCUS = "focus"
        const val EXTRA_SAVED = "saved"
        const val EXTRA_MISTAKES = "mistakes"
        const val EXTRA_WORD_KEY = "wordKey"
        const val EXTRA_WORD_LABEL = "wordLabel"
        const val SAVED_RECORDING = "__vocabulario__"
        const val MISTAKES_RECORDING = "__erros__"
        private const val MAX_ITEMS = 12
        private const val MISTAKES_PER_SESSION = 10
    }

    private enum class Phase { LOADING, MAIN, REVIEW, DONE }

    private data class Result(val given: String, val ok: Boolean)
    private data class ReviewEntry(val item: Item, val pass: Int) // pass: 1..3 para escrita; 0 para os outros

    // ---------- views ----------
    private lateinit var progress: TextView
    private lateinit var prompt: TextView
    private lateinit var sentenceView: TextView
    private lateinit var built: TextView
    private lateinit var btnAudio: Button
    private lateinit var btnUndo: Button
    private lateinit var choices: FlowLayout
    private lateinit var feedback: TextView
    private lateinit var btnNext: Button
    private lateinit var btnPrev: Button
    private lateinit var writeBox: View
    private lateinit var slots: FlowLayout
    private lateinit var draw: DrawView
    private lateinit var btnRecognize: Button
    private lateinit var btnClear: Button
    private lateinit var btnRemember: Button
    private lateinit var strokeBox: View
    private lateinit var strokeRow: android.widget.LinearLayout
    private val slotViews = mutableListOf<TextView>()
    private val slotValues = mutableListOf<String>()
    private var activeSlot = 0

    // ---------- estado ----------
    private var phase = Phase.LOADING
    private var items: List<Item> = emptyList()
    private val results = HashMap<Int, Result>()
    private var frontier = 0          // item atual (ainda não passou dele)
    private var viewIndex = 0         // item mostrado (≤ frontier)
    private var current: Item? = null // item na tela
    private var answered = false      // o item na tela já tem resposta
    private val review = ArrayDeque<ReviewEntry>()
    private var reviewEntry: ReviewEntry? = null
    private var reviewedCount = 0
    private val builtParts = mutableListOf<String>()
    private val usedTiles = mutableListOf<Button>()

    // ---------- sessão / dados ----------
    private val dbExec = Executors.newSingleThreadExecutor()
    @Volatile private var sessionId = -1L
    private var recording = ""
    private var focusLabel = ""
    private var multiRecording = false // vocabulário salvo ou erros: cada item pode vir de uma gravação

    // ---------- áudio ----------
    private var player: MediaPlayer? = null
    private var prepared = false
    private var playerRecording: String? = null
    private val ui = Handler(Looper.getMainLooper())
    private var stopAt = Runnable {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_study)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        progress = findViewById(R.id.progress)
        prompt = findViewById(R.id.prompt)
        sentenceView = findViewById(R.id.sentence)
        built = findViewById(R.id.built)
        btnAudio = findViewById(R.id.btnAudio)
        btnUndo = findViewById(R.id.btnUndo)
        choices = findViewById(R.id.choices)
        feedback = findViewById(R.id.feedback)
        btnNext = findViewById(R.id.btnNext)
        btnPrev = findViewById(R.id.btnPrev)
        writeBox = findViewById(R.id.writeBox)
        slots = findViewById(R.id.slots)
        draw = findViewById(R.id.draw)
        btnRecognize = findViewById(R.id.btnRecognize)
        btnClear = findViewById(R.id.btnClear)
        btnRemember = findViewById(R.id.btnRemember)
        strokeBox = findViewById(R.id.strokeBox)
        strokeRow = findViewById(R.id.strokeRow)
        btnClear.setOnClickListener { draw.clear() }
        btnRecognize.setOnClickListener { recognizeSlot() }
        btnRemember.setOnClickListener { draw.flash(700) }
        draw.setStrokeColor(getColor(R.color.text))
        btnUndo.setOnClickListener { undoTile() }
        btnNext.setOnClickListener { onNextClicked() }
        btnPrev.setOnClickListener { onPrevClicked() }

        val mistakes = intent.getBooleanExtra(EXTRA_MISTAKES, false)
        val saved = intent.getBooleanExtra(EXTRA_SAVED, false)
        val uri = intent.getParcelableExtra(EXTRA_URI, Uri::class.java)
        if (!mistakes && !saved && uri == null) { finish(); return }
        val name = intent.getStringExtra(EXTRA_NAME) ?: ""
        val focus = try { StudyGen.Focus.valueOf(intent.getStringExtra(EXTRA_FOCUS) ?: "MISTO") } catch (_: Exception) { StudyGen.Focus.MISTO }
        val wordKey = intent.getStringExtra(EXTRA_WORD_KEY)
        val wordLabel = intent.getStringExtra(EXTRA_WORD_LABEL) ?: ""
        multiRecording = mistakes || saved
        recording = when {
            mistakes -> MISTAKES_RECORDING
            saved -> SAVED_RECORDING
            else -> Recordings.baseName(name)
        }
        focusLabel = when {
            mistakes -> "Erros"
            wordKey != null -> "Palavra: $wordLabel"
            else -> focus.label
        }
        title = when {
            mistakes -> "Revisão de erros"
            wordKey != null -> "Estudar · $wordLabel"
            else -> "Estudar · ${focus.label}"
        }
        prompt.text = "Preparando os exercícios…"
        btnNext.isEnabled = false

        Thread {
            val built: List<Item>
            var wav: Uri? = null
            if (mistakes) {
                built = VocabDb.get(this).mistakes().shuffled()
                    .mapNotNull { StudyCodec.decode(it.json) }
                    .filter { it.wordKey == null || it.wordKey !in VocabDb.knownKeys }
                    .take(MISTAKES_PER_SESSION)
            } else {
                val lines = if (saved) StudyGen.linesFromVocab(this) else {
                    val text = try { Recordings.readText(this, uri!!) } catch (_: Exception) { "" }
                    StudyGen.lines(text, recording)
                }
                if (!saved) wav = Recordings.find(this, "$recording.wav")?.uri
                val only = when {
                    wordKey != null -> setOf(wordKey)
                    saved -> VocabDb.savedKeys
                    else -> null
                }
                built = StudyGen.build(this, lines, focus, Prefs(this).level, MAX_ITEMS, only)
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (wav != null) setupPlayer(wav)
                if (built.isEmpty()) {
                    prompt.text = if (mistakes) "Nenhum erro acumulado." else "Esta gravação não tem material suficiente para este foco."
                    btnNext.text = "Fechar"
                    btnNext.isEnabled = true
                    btnNext.setOnClickListener { finish() }
                } else {
                    items = built
                    startMain()
                }
            }
        }.start()
    }

    // =====================================================================
    // Fluxo
    // =====================================================================

    private fun startMain() {
        phase = Phase.MAIN
        results.clear()
        review.clear()
        reviewEntry = null
        reviewedCount = 0
        frontier = 0
        viewIndex = 0
        sessionId = -1L // a sessão só é criada no histórico na primeira resposta
        prompt.textSize = 14f
        showMainItem()
    }

    private val correctCount get() = results.values.count { it.ok }

    /** Mostra o item viewIndex: respondido (só leitura) ou novo. */
    private fun showMainItem() {
        val item = items[viewIndex]
        render(item)
        val r = results[viewIndex]
        if (r != null) {
            applyResult(item, r.given, r.ok)
            answered = true
        }
        progress.text = "${viewIndex + 1} de ${items.size} · $correctCount certas · ${item.kind}" +
            if (viewIndex < frontier) " · revendo" else ""
        updateButtons()
    }

    private fun updateButtons() {
        when (phase) {
            Phase.MAIN -> {
                btnPrev.visibility = if (viewIndex > 0) View.VISIBLE else View.GONE
                btnPrev.text = "Anterior"
                btnNext.isEnabled = true
                btnNext.text = when {
                    viewIndex < frontier -> "Avançar"
                    !answered && current.needsVerify() -> "Verificar"
                    !answered -> "Escolha uma opção"
                    frontier == items.size - 1 -> "Concluir"
                    else -> "Próxima"
                }
                if (!answered && viewIndex == frontier && !current.needsVerify()) btnNext.isEnabled = false
            }
            Phase.REVIEW -> {
                btnPrev.visibility = View.GONE
                btnNext.isEnabled = answered || current.needsVerify()
                btnNext.text = if (answered) "Continuar" else if (current.needsVerify()) "Verificar" else "Escolha uma opção"
            }
            else -> {}
        }
    }

    private fun Item?.needsVerify() = this is Item.Order || this is Item.Kanji || this is Item.Write

    private fun onNextClicked() {
        when (phase) {
            Phase.MAIN -> {
                if (viewIndex < frontier) {
                    viewIndex++
                    showMainItem()
                    return
                }
                if (!answered) { verifyBuilt(); return }
                // respondido: segue
                frontier++
                viewIndex = frontier
                if (frontier >= items.size) startReview() else showMainItem()
            }
            Phase.REVIEW -> {
                if (!answered) { verifyBuilt(); return }
                nextReview()
            }
            Phase.DONE -> redo()
            Phase.LOADING -> {}
        }
    }

    private fun onPrevClicked() {
        when (phase) {
            Phase.MAIN -> if (viewIndex > 0) { viewIndex--; showMainItem() }
            Phase.DONE -> finish()
            else -> {}
        }
    }

    // ---------- revisão no fim ----------

    private fun startReview() {
        val wrong = items.indices.filter { results[it]?.ok == false }.map { items[it] }
        if (wrong.isEmpty()) { summary(); return }
        phase = Phase.REVIEW
        review.clear()
        for (it in wrong) review.addLast(ReviewEntry(it, if (it is Item.Write) 1 else 0))
        Toast.makeText(this, "Revisão: refaça os que errou até acertar", Toast.LENGTH_LONG).show()
        nextReview()
    }

    private fun nextReview() {
        val e = review.removeFirstOrNull()
        if (e == null) { summary(); return }
        reviewEntry = e
        render(e.item, reviewPass = e.pass)
        val left = review.size + 1
        progress.text = "Revisão · faltam $left · ${e.item.kind}" +
            if (e.pass > 0) " · passo ${e.pass} de 3" else ""
        updateButtons()
    }

    // ---------- fim ----------

    private fun summary() {
        phase = Phase.DONE
        stopAudio()
        current = null
        clearExerciseViews()
        progress.text = ""
        prompt.textSize = 18f
        val reviewed = items.indices.count { results[it]?.ok == false }
        prompt.text = "Sessão concluída: $correctCount de ${items.size} certas de primeira." +
            (if (reviewed > 0) "\nVocê revisou e acertou os $reviewed que tinha errado." else "") +
            "\nO resultado está no histórico." +
            (if (reviewed > 0) " Os erros também estão na fila de Revisar erros." else "")
        btnNext.isEnabled = true
        btnNext.text = "Refazer"
        btnPrev.visibility = View.VISIBLE
        btnPrev.text = "Fechar"
    }

    /** Refaz exatamente os mesmos exercícios, em outra ordem. */
    private fun redo() {
        items = items.shuffled()
        startMain()
    }

    // =====================================================================
    // Renderização de um item
    // =====================================================================

    private fun clearExerciseViews() {
        builtParts.clear()
        usedTiles.clear()
        choices.removeAllViews()
        feedback.visibility = View.GONE
        built.visibility = View.GONE
        built.text = ""
        btnUndo.visibility = View.GONE
        writeBox.visibility = View.GONE
        sentenceView.visibility = View.GONE
        btnAudio.visibility = View.GONE
        btnRemember.visibility = View.GONE
        strokeBox.visibility = View.GONE
        strokeRow.removeAllViews()
        draw.guide = null
        draw.showGuide = false
    }

    private var reviewPass = 0

    private fun render(item: Item, reviewPass: Int = 0) {
        stopAudio()
        current = item
        answered = false
        this.reviewPass = reviewPass
        clearExerciseViews()
        val hasAudio = item.sec >= 0 && (if (multiRecording) item.recording != null else prepared)
        btnAudio.visibility = if (hasAudio) View.VISIBLE else View.GONE
        btnAudio.setOnClickListener { if (multiRecording) playFromRecording(item) else play(item) }
        val pre = if (phase == Phase.REVIEW) "Revisão · " else ""

        when (item) {
            is Item.Order -> {
                prompt.text = pre + "Monte a frase na ordem certa (ouça o áudio se precisar)."
                built.visibility = View.VISIBLE
                btnUndo.visibility = View.VISIBLE
                for (c in item.chunks.shuffled()) addTile(c)
            }
            is Item.Cloze -> {
                prompt.text = pre + "Qual palavra completa a frase?"
                sentenceView.visibility = View.VISIBLE
                sentenceView.text = item.sentence.replaceRange(item.word.start, item.word.end, "＿＿＿")
                for (o in item.options) addChoice(o)
            }
            is Item.GrammarCloze -> {
                prompt.text = pre + "O que completa a frase? (padrão gramatical)"
                sentenceView.visibility = View.VISIBLE
                sentenceView.text = item.sentence.replaceRange(item.hit.start, item.hit.end, "＿＿＿")
                for (o in item.options) addChoice(o)
            }
            is Item.Kanji -> {
                val tr = Tr.cached(this, item.word.base)
                prompt.text = pre + "Monte a palavra 「${item.word.reading}」" + (if (tr != null) " ($tr)" else "") + " com as peças (kanji e kana)."
                built.visibility = View.VISIBLE
                btnUndo.visibility = View.VISIBLE
                for (t in item.tiles) addTile(t)
            }
            is Item.Choice -> {
                prompt.text = pre + item.prompt
                for (o in item.options) addChoice(o)
            }
            is Item.GrammarQ -> {
                prompt.text = pre + "Qual padrão gramatical aparece nesta frase?"
                sentenceView.visibility = View.VISIBLE
                sentenceView.text = item.sentence
                for (o in item.options) addChoice(o.name)
            }
            is Item.Write -> {
                val tr = Tr.cached(this, item.word.base)
                val help = when (reviewPass) {
                    1 -> " Trace por cima do kanji em cinza."
                    2 -> " Sem o modelo; toque em Lembrar para vê-lo por um instante."
                    3 -> " Agora sem ajuda."
                    else -> ""
                }
                prompt.text = pre + "Escreva 「${item.word.reading}」" + (if (tr != null) " ($tr)" else "") +
                    ": toque numa lacuna, desenhe o kanji e toque em Reconhecer." + help
                setupSlots(item)
                writeBox.visibility = View.VISIBLE
                btnRemember.visibility = if (reviewPass == 2) View.VISIBLE else View.GONE
                draw.showGuide = reviewPass == 1
                InkJp.ensureModel(this) { }
            }
        }
    }

    // ---------- respostas ----------

    private fun answerOf(item: Item): String = when (item) {
        is Item.Order -> item.chunks.joinToString("")
        is Item.Kanji -> item.word.base
        is Item.Write -> item.word.base.filter(StudyGen::isKanji)
        is Item.Cloze -> item.word.surface
        is Item.GrammarCloze -> item.sentence.substring(item.hit.start, item.hit.end)
        is Item.Choice -> item.answer
        is Item.GrammarQ -> item.hit.point.name
    }

    /** Verificar para exercícios de montar/escrever. */
    private fun verifyBuilt() {
        val item = current ?: return
        if (!item.needsVerify()) { Toast.makeText(this, "Escolha uma alternativa", Toast.LENGTH_SHORT).show(); return }
        val given = if (item is Item.Write) {
            if (slotValues.any { it.isEmpty() }) { Toast.makeText(this, "Preencha todas as lacunas", Toast.LENGTH_SHORT).show(); return }
            slotValues.joinToString("")
        } else builtParts.joinToString("")
        onAnswered(item, given)
    }

    private fun onAnswered(item: Item, given: String) {
        val answer = answerOf(item)
        val ok = given == answer
        answered = true
        stopAudio()
        applyResult(item, given, ok)
        when (phase) {
            Phase.MAIN -> {
                results[frontier] = Result(given, ok)
                record(item, ok, given, answer)
                progress.text = "${viewIndex + 1} de ${items.size} · $correctCount certas · ${item.kind}"
            }
            Phase.REVIEW -> {
                val e = reviewEntry!!
                if (ok) {
                    reviewedCount++
                    if (e.item is Item.Write && e.pass in 1..2) review.addFirst(ReviewEntry(e.item, e.pass + 1))
                } else {
                    review.addLast(e) // volta para o fim até acertar
                }
            }
            else -> {}
        }
        updateButtons()
    }

    /** Pinta a resposta dada e a certa, e mostra o feedback. Usado ao responder e ao rever. */
    private fun applyResult(item: Item, given: String, ok: Boolean) {
        val answer = answerOf(item)
        when (item) {
            is Item.Order, is Item.Kanji -> {
                val sb = SpannableStringBuilder(given.ifEmpty { "(vazio)" })
                sb.setSpan(ForegroundColorSpan(getColor(if (ok) R.color.ok else R.color.stop)), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                built.text = sb
            }
            is Item.Write -> {
                for ((i, v) in slotViews.withIndex()) {
                    val g = given.getOrNull(i)?.toString() ?: ""
                    v.text = g
                    v.setBackgroundResource(if (g == answer.getOrNull(i)?.toString()) R.drawable.bg_slot_right else R.drawable.bg_slot_wrong)
                }
                draw.showGuide = false
            }
            else -> {
                for (i in 0 until choices.childCount) {
                    val c = choices.getChildAt(i) as Button
                    val t = c.text.toString()
                    when {
                        t == answer -> { c.setBackgroundResource(R.drawable.bg_choice_right); c.setTextColor(0xFFFFFFFF.toInt()) }
                        t == given -> { c.setBackgroundResource(R.drawable.bg_button_stop); c.setTextColor(0xFFFFFFFF.toInt()) }
                    }
                }
            }
        }
        showFeedback(item, ok, given, answer)
    }

    private fun record(item: Item, ok: Boolean, given: String, answer: String) {
        val promptText = when (item) {
            is Item.Choice -> item.prompt
            is Item.GrammarQ -> "Padrão da frase"
            is Item.GrammarCloze -> "Lacuna gramatical"
            is Item.Cloze -> "Palavra que completa a frase"
            is Item.Kanji -> "Montar 「${item.word.reading}」"
            is Item.Write -> "Escrever 「${item.word.reading}」"
            is Item.Order -> "Ordenar a frase"
        }
        val key = item.wordKey
        val trains = StudyGen.trainsOf(item.kind)
        val identity = item.identity
        val json = StudyCodec.encode(item)
        dbExec.execute {
            val db = VocabDb.get(this)
            if (sessionId < 0) sessionId = db.startSession(recording, focusLabel, 0)
            db.addStudyItem(sessionId, VocabDb.StudyItem(item.kind, promptText, answer, given, ok, item.sentence))
            if (key != null && key in VocabDb.savedKeys && trains != null && trains != StudyGen.TRAINS_GRAMMAR) {
                db.bumpScore(key, kanji = trains == StudyGen.TRAINS_KANJI, correct = ok)
            }
            // Fila de erros: errou entra; acertou de primeira sai
            if (ok) db.removeMistake(identity)
            else db.addMistake(VocabDb.Mistake(identity, json, item.kind, promptText, answer, item.sentence, System.currentTimeMillis()))
        }
    }

    private fun wordOf(item: Item): Jp.Word? = when (item) {
        is Item.Cloze -> item.word
        is Item.Choice -> item.word
        is Item.Kanji -> item.word
        is Item.Write -> item.word
        else -> null
    }

    /**
     * Feedback depois de responder: certo/errado, a palavra (leitura, classe, conjugação, significado),
     * o padrão gramatical quando for o caso, e a frase com a tradução. Traduções que ainda não
     * estão no cache são pedidas e o feedback é redesenhado quando chegam.
     */
    private fun showFeedback(item: Item, ok: Boolean, given: String, answer: String) {
        val okColor = getColor(R.color.ok)
        val badColor = getColor(R.color.stop)
        val sec = getColor(R.color.text_secondary)
        val sb = SpannableStringBuilder()
        fun colored(text: String, color: Int) {
            val a = sb.length; sb.append(text)
            sb.setSpan(ForegroundColorSpan(color), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        fun bold(text: String) {
            val a = sb.length; sb.append(text)
            sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (ok) colored("✓ Certo!", okColor) else {
            colored("✗ Sua resposta: ${given.ifEmpty { "(vazio)" }}", badColor)
            sb.append("\n")
            colored("Certa: $answer", okColor)
            if (phase == Phase.REVIEW) sb.append("\nEste volta mais tarde na revisão.")
        }
        val pending = mutableListOf<String>()

        // Palavra
        val w = wordOf(item)
        if (w != null) {
            sb.append("\n\n")
            bold(w.base)
            if (w.reading != w.base) sb.append("  ").append(w.reading)
            sb.append("\n")
            colored(w.pos, sec)
            if (w.conjugation.isNotEmpty() && w.surface != w.base) {
                sb.append("\n")
                colored("Na frase: ${w.surface} = ${w.base} + ${w.conjugation}", sec)
            }
            val meaning = Tr.cached(this, w.base)
            sb.append("\nSignificado: ")
            if (meaning != null) sb.append(meaning) else { colored("traduzindo…", sec); pending.add(w.base) }

            // Cada kanji: leituras on/kun e significado
            val kanji = Kanjidic.describe(this, w.base)
            if (kanji.isNotEmpty()) {
                sb.append("\n\n")
                colored("Kanji", sec)
                for ((c, text) in kanji) {
                    sb.append("\n")
                    bold(c.toString())
                    sb.append("  ").append(text)
                }
            }
        }

        // Gramática
        when (item) {
            is Item.GrammarCloze -> {
                sb.append("\n\n"); bold("${item.hit.point.name} · ${item.hit.point.level}")
                sb.append("\n").append(item.hit.point.explanation)
            }
            is Item.GrammarQ -> {
                sb.append("\n\n"); bold("${item.hit.point.name} · ${item.hit.point.level}")
                sb.append("\n").append(item.hit.point.explanation).append("\nEx.: ").append(item.hit.point.example)
            }
            else -> {}
        }

        // Frase e tradução
        sb.append("\n\n")
        colored("Frase", sec)
        sb.append("\n").append(item.sentence)
        val trSentence = Tr.cached(this, item.sentence)
        sb.append("\n")
        if (trSentence != null) colored(trSentence, sec) else { colored("traduzindo…", sec); pending.add(item.sentence) }

        feedback.text = sb
        feedback.visibility = View.VISIBLE

        // Pede o que falta; quando chegar, redesenha se o item ainda estiver na tela
        for (text in pending) {
            Tr.translate(this, text) { _ ->
                if (current === item && answered) showFeedback(item, ok, given, answer)
            }
        }

        // Ordem dos traços para exercícios de kanji
        if (w != null && w.base.any(StudyGen::isKanji)) showStrokes(w.base)
    }

    private var strokesFor = ""

    private fun showStrokes(word: String) {
        val kanji = word.filter(StudyGen::isKanji).toList()
        if (kanji.isEmpty()) return
        if (strokesFor == word && strokeRow.childCount > 0) { strokeBox.visibility = View.VISIBLE; return }
        strokesFor = word
        val item = current
        Thread {
            Strokes.load(this, kanji.toSet())
            val paths = kanji.map { Strokes.get(this, it) }
            runOnUiThread {
                if (current !== item) return@runOnUiThread
                strokeRow.removeAllViews()
                for (p in paths) if (p != null) strokeRow.addView(StrokeView(this).also { it.setStrokes(p) })
                strokeBox.visibility = if (strokeRow.childCount > 0) View.VISIBLE else View.GONE
            }
        }.start()
    }

    // ---------- peças, alternativas, escrita ----------

    private fun addTile(label: String) {
        val b = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 20f
            setBackgroundResource(R.drawable.bg_button_outline)
            setTextColor(getColor(R.color.text))
            minHeight = 0; minimumHeight = 0
            setPadding(dp(14), dp(10), dp(14), dp(10))
            stateListAnimator = null
        }
        b.setOnClickListener {
            if (answered) return@setOnClickListener
            builtParts.add(label)
            usedTiles.add(b)
            b.visibility = View.INVISIBLE
            built.text = builtParts.joinToString("")
        }
        choices.addView(b)
    }

    private fun undoTile() {
        if (answered || builtParts.isEmpty()) return
        builtParts.removeAt(builtParts.size - 1)
        usedTiles.removeAt(usedTiles.size - 1).visibility = View.VISIBLE
        built.text = builtParts.joinToString("")
    }

    private fun addChoice(label: String) {
        val b = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 18f
            setBackgroundResource(R.drawable.bg_button_outline)
            setTextColor(getColor(R.color.text))
            minHeight = 0; minimumHeight = 0
            setPadding(dp(16), dp(12), dp(16), dp(12))
            stateListAnimator = null
        }
        b.setOnClickListener {
            val item = current ?: return@setOnClickListener
            if (answered) return@setOnClickListener
            onAnswered(item, label)
        }
        choices.addView(b)
    }

    private fun setupSlots(item: Item.Write) {
        slots.removeAllViews()
        slotViews.clear()
        slotValues.clear()
        draw.clear()
        activeSlot = -1
        for (c in item.word.base) {
            val isK = StudyGen.isKanji(c)
            val tv = TextView(this).apply {
                textSize = 30f
                gravity = android.view.Gravity.CENTER
                minWidth = dp(64); minHeight = dp(64)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                if (isK) {
                    text = ""
                    setBackgroundResource(R.drawable.bg_slot)
                    setTextColor(getColor(R.color.text))
                } else {
                    text = c.toString()
                    setTextColor(getColor(R.color.text_secondary))
                }
            }
            if (isK) {
                val idx = slotViews.size
                slotViews.add(tv)
                slotValues.add("")
                tv.setOnClickListener { if (!answered) selectSlot(idx) }
            }
            slots.addView(tv)
        }
        if (slotViews.isNotEmpty()) selectSlot(0)
    }

    private fun selectSlot(idx: Int) {
        activeSlot = idx
        for ((i, v) in slotViews.withIndex()) {
            v.setBackgroundResource(if (i == idx) R.drawable.bg_slot_active else R.drawable.bg_slot)
        }
        draw.clear()
        // Na revisão, o guia é o kanji desta lacuna
        val item = current as? Item.Write
        draw.guide = if (item != null && reviewPass in 1..2) item.word.base.filter(StudyGen::isKanji).getOrNull(idx)?.toString() else null
    }

    private fun recognizeSlot() {
        val item = current as? Item.Write ?: return
        if (answered || activeSlot < 0 || !draw.hasStrokes) return
        val expected = item.word.base.filter(StudyGen::isKanji).getOrNull(activeSlot)?.toString()
        btnRecognize.isEnabled = false
        btnRecognize.text = "Reconhecendo…"
        InkJp.recognize(this, draw.ink(), null) { cands ->
            btnRecognize.isEnabled = true
            btnRecognize.text = "Reconhecer"
            if (current !== item || answered) return@recognize
            if (cands.isEmpty()) {
                Toast.makeText(this, "Não reconheci o traçado; tente de novo", Toast.LENGTH_SHORT).show()
                return@recognize
            }
            // Se o kanji esperado estiver entre os 3 primeiros candidatos, aceita (tolerância ao traço)
            val top3 = cands.take(3).map { it.take(1) }
            val chosen = if (expected != null && expected in top3) expected else cands[0].take(1)
            slotValues[activeSlot] = chosen
            slotViews[activeSlot].text = chosen
            draw.clear()
            val nextEmpty = slotValues.indexOfFirst { it.isEmpty() }
            if (nextEmpty >= 0) selectSlot(nextEmpty)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // =====================================================================
    // Sair no meio
    // =====================================================================

    private fun inProgress() = phase == Phase.MAIN || phase == Phase.REVIEW

    private fun confirmExit() {
        if (!inProgress()) { finish(); return }
        val msg = when {
            results.isEmpty() -> "Sair do exercício? Nada foi respondido ainda."
            phase == Phase.REVIEW -> "Sair da revisão? O que você respondeu já está no histórico e os erros ficam na fila de Revisar erros."
            else -> "Sair do exercício? O que você já respondeu fica no histórico."
        }
        AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton("Sair") { _, _ -> finish() }
            .setNegativeButton("Continuar", null)
            .show()
    }

    @Deprecated("Activity simples sem AndroidX")
    override fun onBackPressed() {
        if (inProgress()) confirmExit() else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { confirmExit(); return true }
        return super.onOptionsItemSelected(item)
    }

    // =====================================================================
    // Áudio
    // =====================================================================

    private fun setupPlayer(uri: Uri) {
        val p = MediaPlayer()
        p.setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        )
        p.setOnPreparedListener {
            prepared = true
            val it = current
            if (it != null && it.sec >= 0 && (phase == Phase.MAIN || phase == Phase.REVIEW)) btnAudio.visibility = View.VISIBLE
        }
        p.setOnErrorListener { _, _, _ -> true }
        try {
            p.setDataSource(this, uri)
            p.prepareAsync()
            player = p
        } catch (_: Exception) { p.release() }
    }

    /** Toca só o trecho da frase: do início dela até o início da próxima. */
    private fun play(item: Item) {
        val p = player ?: return
        if (!prepared || item.sec < 0) return
        try {
            val startMs = maxOf(0, item.sec * 1000 - 500)
            val endMs = maxOf(startMs + 1500, item.endSec * 1000 - 300)
            p.seekTo(startMs)
            p.start()
            ui.removeCallbacks(stopAt)
            stopAt = Runnable { try { if (p.isPlaying) p.pause() } catch (_: IllegalStateException) {} }
            ui.postDelayed(stopAt, (endMs - startMs).toLong())
        } catch (_: IllegalStateException) {}
    }

    /** Vocabulário salvo / erros: cada frase pode vir de uma gravação diferente; abre o WAV certo sob demanda. */
    private fun playFromRecording(item: Item) {
        val rec = item.recording ?: return
        if (playerRecording == rec && prepared) { play(item); return }
        stopAudio()
        player?.release(); player = null; prepared = false
        btnAudio.isEnabled = false
        Thread {
            val uri = Recordings.find(this, "$rec.wav")?.uri
            val p = MediaPlayer()
            var ok = false
            try {
                if (uri != null) {
                    p.setAudioAttributes(
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                    )
                    p.setDataSource(this, uri)
                    p.prepare()
                    ok = true
                }
            } catch (_: Exception) {}
            runOnUiThread {
                btnAudio.isEnabled = true
                if (ok) {
                    player = p; prepared = true; playerRecording = rec
                    if (current === item) play(item)
                } else {
                    p.release()
                    Toast.makeText(this, "Áudio dessa frase não está mais na pasta", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun stopAudio() {
        ui.removeCallbacks(stopAt)
        try { if (prepared && player?.isPlaying == true) player?.pause() } catch (_: IllegalStateException) {}
    }

    override fun onPause() {
        super.onPause()
        stopAudio()
    }

    override fun onDestroy() {
        ui.removeCallbacks(stopAt)
        player?.release()
        player = null
        dbExec.shutdown()
        super.onDestroy()
    }
}
