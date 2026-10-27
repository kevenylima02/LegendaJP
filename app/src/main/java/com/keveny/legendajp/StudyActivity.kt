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
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
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
        /** Palavras escolhidas antes do estudo (WordPickActivity). */
        const val EXTRA_KEYS = "keys"
        /** Revisão espaçada de hoje (ReviewActivity); EXTRA_SRS_MODE: misto, exercicios ou flash. */
        const val EXTRA_SRS = "srs"
        const val EXTRA_SRS_MODE = "srsMode"
        const val SRS_RECORDING = "__revisao__"
        private const val MAX_REVIEW = 60
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
    private lateinit var partsBox: android.widget.FrameLayout
    private lateinit var buildView: KanjiBuildView
    private lateinit var subtitle: TextView
    private lateinit var timingInfo: TextView
    private lateinit var btnSubs: Button
    private lateinit var aiBox: android.widget.LinearLayout
    private var aiPanel: AiPanel? = null
    private lateinit var btnEasy: Button
    private lateinit var trLine: TextView
    private lateinit var freeInput: android.widget.EditText
    /** Correções da IA das frases livres (por item). */
    private val freeChecks = HashMap<Item, Ai.Check>()

    // ---------- revisão espaçada ----------
    private var flashRevealed = false
    private var flashGrade = 0
    /** Cartão como estava antes da última nota (para o botão "Foi fácil" refazer como Fácil). */
    private var lastSrs: Srs.Card? = null
    private var lastSrsItem: Item? = null

    // ---------- legenda com a palavra falada ----------
    private var subsOn = false                       // começa escondida; vale para a sessão toda
    private var timing: WordTiming.Timing? = null    // tempo das palavras do item na tela
    private val timingCache = HashMap<String, WordTiming.Timing?>()
    private val karaoke = Runnable { tickKaraoke() }
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
    private val usedTiles = mutableListOf<View>()
    private val builtPartList = mutableListOf<KanjiParts.Part>()

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
        partsBox = findViewById(R.id.partsBox)
        buildView = KanjiBuildView(this)
        partsBox.addView(buildView, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.CENTER_HORIZONTAL))
        btnClear.setOnClickListener { draw.clear() }
        btnRecognize.setOnClickListener { recognizeSlot() }
        btnRemember.setOnClickListener { draw.flash(700) }
        draw.setStrokeColor(getColor(R.color.text))
        btnUndo.setOnClickListener { undoTile() }
        btnNext.setOnClickListener { onNextClicked() }
        btnPrev.setOnClickListener { onPrevClicked() }
        subtitle = findViewById(R.id.subtitle)
        timingInfo = findViewById(R.id.timingInfo)
        btnSubs = findViewById(R.id.btnSubs)
        aiBox = findViewById(R.id.aiBox)
        btnEasy = findViewById(R.id.btnEasy)
        trLine = findViewById(R.id.trLine)
        freeInput = findViewById(R.id.freeInput)
        btnEasy.setOnClickListener {
            val before = lastSrs ?: return@setOnClickListener
            btnEasy.isEnabled = false
            btnEasy.text = "Fácil ✓"
            dbExec.execute { Srs.regrade(this, before, Srs.EASY) }
        }
        btnSubs.setOnClickListener { subsOn = !subsOn; updateSubs() }
        timingInfo.setOnClickListener {
            val msg = if (timing?.exact == true)
                "Tempo exato: o reconhecedor do Android informou quando cada palavra foi dita nesta gravação."
            else
                "Tempo estimado: esta gravação não tem o tempo de cada palavra. O destaque é calculado pela parte do áudio com voz e pelo tamanho de cada palavra, então pode adiantar ou atrasar um pouco."
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }

        val mistakes = intent.getBooleanExtra(EXTRA_MISTAKES, false)
        val srs = intent.getBooleanExtra(EXTRA_SRS, false)
        val srsMode = intent.getStringExtra(EXTRA_SRS_MODE) ?: "misto"
        val saved = intent.getBooleanExtra(EXTRA_SAVED, false)
        val uri = intent.getParcelableExtra(EXTRA_URI, Uri::class.java)
        if (!mistakes && !saved && !srs && uri == null) { finish(); return }
        val name = intent.getStringExtra(EXTRA_NAME) ?: ""
        val focus = try { StudyGen.Focus.valueOf(intent.getStringExtra(EXTRA_FOCUS) ?: "MISTO") } catch (_: Exception) { StudyGen.Focus.MISTO }
        val wordKey = intent.getStringExtra(EXTRA_WORD_KEY)
        val wordLabel = intent.getStringExtra(EXTRA_WORD_LABEL) ?: ""
        val keys = intent.getStringArrayListExtra(EXTRA_KEYS)
        multiRecording = mistakes || saved || srs
        recording = when {
            srs -> SRS_RECORDING
            mistakes -> MISTAKES_RECORDING
            saved -> SAVED_RECORDING
            else -> Recordings.baseName(name)
        }
        focusLabel = when {
            srs -> "Revisão"
            mistakes -> "Erros"
            wordKey != null -> "Palavra: $wordLabel"
            keys != null -> "${focus.label} · ${keys.size} palavras"
            else -> focus.label
        }
        title = when {
            srs -> "Revisão de hoje"
            mistakes -> "Revisão de erros"
            wordKey != null -> "Estudar · $wordLabel"
            else -> "Estudar · ${focus.label}"
        }
        prompt.text = "Preparando os exercícios…"
        btnNext.isEnabled = false

        Thread {
            val built: List<Item>
            var wav: Uri? = null
            if (srs) {
                Srs.sync(this)
                val t = Srs.today(this)
                built = StudyGen.review(this, (t.due + t.fresh).take(MAX_REVIEW), srsMode)
            } else if (mistakes) {
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
                    keys != null -> keys.toSet()
                    saved -> VocabDb.savedKeys
                    else -> null
                }
                val max = if (keys != null) maxOf(MAX_ITEMS, keys.size * 2) else MAX_ITEMS
                built = StudyGen.build(this, lines, focus, Prefs(this).levels, max, only,
                    grammar = !saved && wordKey == null, cover = keys != null)
            }
            // Traços das peças de "Montar o kanji" (uma passada pelo arquivo)
            Strokes.load(this, built.filterIsInstance<Item.Parts>().flatMap { it.tiles.map { p -> p.src } }.toSet())
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (wav != null) setupPlayer(wav)
                if (built.isEmpty()) {
                    prompt.text = when {
                        srs -> "Nada para revisar agora. Volte amanhã ou estude uma gravação: as palavras salvas e os padrões que aparecem nos exercícios entram na revisão."
                        mistakes -> "Nenhum erro acumulado."
                        else -> "Esta gravação não tem material suficiente para este foco."
                    }
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
                    !answered && current is Item.Flash -> if (flashRevealed) "Escolha a nota" else "Mostrar resposta"
                    !answered && current is Item.FreeWrite -> "Corrigir com IA"
                    !answered && current.needsVerify() -> "Verificar"
                    !answered -> "Escolha uma opção"
                    frontier == items.size - 1 -> "Concluir"
                    else -> "Próxima"
                }
                if (!answered && viewIndex == frontier && !current.needsVerify()) btnNext.isEnabled = false
                if (!answered && current is Item.Flash && flashRevealed) btnNext.isEnabled = false
            }
            Phase.REVIEW -> {
                btnPrev.visibility = View.GONE
                btnNext.isEnabled = answered || current.needsVerify()
                btnNext.text = if (answered) "Continuar" else if (current is Item.FreeWrite) "Corrigir com IA" else if (current is Item.Flash) (if (flashRevealed) "Escolha a nota" else "Mostrar resposta")
                    else if (current.needsVerify()) "Verificar" else "Escolha uma opção"
                if (!answered && current is Item.Flash && flashRevealed) btnNext.isEnabled = false
            }
            else -> {}
        }
    }

    private fun Item?.needsVerify() = this is Item.Order || this is Item.Kanji || this is Item.Write || this is Item.Parts || this is Item.Flash || this is Item.FreeWrite

    private fun onNextClicked() {
        when (phase) {
            Phase.MAIN -> {
                if (viewIndex < frontier) {
                    viewIndex++
                    showMainItem()
                    return
                }
                if (!answered && current is Item.Flash) { revealFlash(); return }
                if (!answered) { verifyBuilt(); return }
                // respondido: segue
                frontier++
                viewIndex = frontier
                if (frontier >= items.size) startReview() else showMainItem()
            }
            Phase.REVIEW -> {
                if (!answered && current is Item.Flash) { revealFlash(); return }
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
        builtPartList.clear()
        partsBox.visibility = View.GONE
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
        ui.removeCallbacks(karaoke)
        timing = null
        subtitle.visibility = View.GONE
        timingInfo.visibility = View.GONE
        btnSubs.visibility = View.GONE
        aiPanel?.hide()
        aiPanel = null
        btnEasy.visibility = View.GONE
        trLine.visibility = View.GONE
        freeInput.visibility = View.GONE
        freeInput.isEnabled = true
        flashRevealed = false
        flashGrade = 0
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
        if (hasAudio) loadTiming(item)
        updateSubs()
        val pre = if (phase == Phase.REVIEW) "Revisão · " else ""

        when (item) {
            is Item.Order -> {
                prompt.text = pre + "Monte a frase na ordem certa, guiando-se pela tradução (ouça o áudio se precisar)."
                // A tradução guia a ordem (a frase em japonês fica escondida)
                sentenceView.visibility = View.VISIBLE
                Furigana.style(sentenceView, false)
                TrSwitch.bind(this, sentenceView, item.sentence, "", scale = 0.75f)
                built.visibility = View.VISIBLE
                btnUndo.visibility = View.VISIBLE
                for (c in item.chunks.shuffled()) addTile(c)
            }
            is Item.Drill -> {
                prompt.text = pre + item.prompt
                for (o in item.options) addChoice(o)
            }
            is Item.FreeWrite -> {
                val p = Grammar.point(item.pointId)
                prompt.text = pre + "Escreva uma frase sua usando 「${p?.name ?: item.pointId}」" +
                    (if (p != null) " — ${p.explanation}" else "") +
                    (if (item.word != null) "\nSe quiser, use 「${item.word.base}」." else "") +
                    (if (p != null) "\nEx.: ${p.example}" else "") +
                    "\nDepois toque em Corrigir com IA."
                freeInput.visibility = View.VISIBLE
                freeInput.isEnabled = true
                freeInput.setText("")
            }
            is Item.Cloze -> {
                prompt.text = pre + "Qual palavra completa a frase?"
                sentenceView.visibility = View.VISIBLE
                val masked = item.sentence.replaceRange(item.word.start, item.word.end, "＿＿＿")
                Furigana.set(this, sentenceView, masked, masked)
                for (o in item.options) addChoice(o)
            }
            is Item.GrammarCloze -> {
                prompt.text = pre + "O que completa a frase? (padrão gramatical; use a tradução e, se precisar, o áudio)"
                sentenceView.visibility = View.VISIBLE
                val masked = item.sentence.replaceRange(item.hit.start, item.hit.end, "＿＿＿")
                val (pre, on) = Furigana.apply(this, masked, masked)
                Furigana.style(sentenceView, on)
                TrSwitch.bind(this, sentenceView, item.sentence, SpannableStringBuilder(pre).append("\n"), scale = 0.65f)
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
            is Item.Flash -> renderFlashFront(item, pre)
            is Item.Parts -> {
                val tr = Tr.cached(this, item.word.base)
                val masked = item.word.base.replace(item.kanji, '□')
                val k = Kanjidic.get(this, item.kanji)
                val info = if (k == null) "" else
                    listOf(k.on.joinToString("、"), k.kunPretty.joinToString("、")).filter { it.isNotEmpty() }.joinToString(" · ") +
                        if (k.meanings.isNotEmpty()) " — ${k.shortMeaning}" else ""
                prompt.text = pre + "Monte o kanji que falta em 「$masked」 (${item.word.reading}" + (if (tr != null) " · $tr" else "") + ")." +
                    (if (info.isNotBlank()) "\nKanji: $info" else "") + "\nToque nas partes na ordem das lacunas."
                partsBox.visibility = View.VISIBLE
                btnUndo.visibility = View.VISIBLE
                buildView.setSlots(item.parts.map { KanjiParts.paths(this, it) })
                for (p in item.tiles) addPartTile(item, p)
            }
            is Item.GrammarQ -> {
                prompt.text = pre + "Qual padrão gramatical aparece nesta frase?"
                sentenceView.visibility = View.VISIBLE
                Furigana.set(this, sentenceView, item.sentence, item.sentence)
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
        is Item.Parts -> item.parts.joinToString(" + ") { it.element }
        is Item.Flash -> Srs.gradeLabel(Srs.GOOD)
        is Item.Drill -> item.answer
        is Item.FreeWrite -> freeChecks[item]?.corrected ?: ""
        is Item.Cloze -> item.word.surface
        is Item.GrammarCloze -> item.sentence.substring(item.hit.start, item.hit.end)
        is Item.Choice -> item.answer
        is Item.GrammarQ -> item.hit.point.name
    }

    /** Verificar para exercícios de montar/escrever. */
    private fun verifyBuilt() {
        val item = current ?: return
        if (!item.needsVerify()) { Toast.makeText(this, "Escolha uma alternativa", Toast.LENGTH_SHORT).show(); return }
        if (item is Item.FreeWrite) { checkFree(item); return }
        val given = when (item) {
            is Item.Write -> {
                if (slotValues.any { it.isEmpty() }) { Toast.makeText(this, "Preencha todas as lacunas", Toast.LENGTH_SHORT).show(); return }
                slotValues.joinToString("")
            }
            is Item.Parts -> {
                if (builtPartList.size < item.parts.size) { Toast.makeText(this, "Coloque uma parte em cada lacuna", Toast.LENGTH_SHORT).show(); return }
                builtPartList.joinToString(" + ") { it.element }
            }
            else -> builtParts.joinToString("")
        }
        onAnswered(item, given)
    }

    private fun onAnswered(item: Item, given: String) {
        val answer = answerOf(item)
        val ok = when (item) {
            is Item.Flash -> Srs.gradeOf(given) >= Srs.HARD
            is Item.FreeWrite -> freeChecks[item]?.correct == true
            else -> given == answer
        }
        answered = true
        stopAudio()
        applyResult(item, given, ok)
        updateSubs()
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
            is Item.FreeWrite -> {
                freeInput.visibility = View.VISIBLE
                freeInput.setText(given)
                freeInput.isEnabled = false
            }
            is Item.Flash -> {
                if (!flashRevealed) revealFlash()
                for (i in 0 until choices.childCount) {
                    val c = choices.getChildAt(i) as? Button ?: continue
                    if (c.tag == given) { c.setBackgroundResource(if (ok) R.drawable.bg_choice_right else R.drawable.bg_button_stop); c.setTextColor(0xFFFFFFFF.toInt()) }
                }
                showFlashBack(item)
                setupAi(item)
                return
            }
            is Item.Parts -> {
                val els = given.split(" + ")
                val ps = els.map { e -> item.tiles.firstOrNull { it.element == e } }
                buildView.setFilled(
                    ps.map { if (it == null) emptyList() else KanjiParts.paths(this, it) },
                    els.mapIndexed { i, e -> e == item.parts.getOrNull(i)?.element }
                )
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
        setupAi(item)
    }

    private fun record(item: Item, ok: Boolean, given: String, answer: String) {
        val promptText = when (item) {
            is Item.Choice -> item.prompt
            is Item.GrammarQ -> "Padrão da frase"
            is Item.GrammarCloze -> "Lacuna gramatical"
            is Item.Cloze -> "Palavra que completa a frase"
            is Item.Kanji -> "Montar 「${item.word.reading}」"
            is Item.Write -> "Escrever 「${item.word.reading}」"
            is Item.Parts -> "Partes do kanji ${item.kanji} (${item.word.base})"
            is Item.Flash -> "Flashcard (${Srs.kindLabel(item.card.kind)}): ${item.card.label}"
            is Item.Drill -> item.prompt
            is Item.FreeWrite -> "Escrever com ${Grammar.point(item.pointId)?.name ?: item.pointId}"
            is Item.Order -> "Ordenar a frase"
        }
        val key = item.wordKey
        val trains = StudyGen.trainsOf(item.kind)
        val identity = item.identity
        val json = StudyCodec.encode(item)
        val target = srsTarget(item)
        val grade = if (item is Item.Flash) Srs.gradeOf(given) else if (ok) Srs.GOOD else Srs.AGAIN
        dbExec.execute {
            // Revisão espaçada: toda primeira resposta conta
            if (target != null) {
                try {
                    val card = Srs.ensure(this, target.kind, target.key, target.label, target.sentence, target.recording, target.sec)
                    val before = Srs.grade(this, card, grade, if (item is Item.Flash) "flash" else "exercicio")
                    runOnUiThread {
                        lastSrs = before
                        lastSrsItem = item
                        if (ok && item !is Item.Flash && current === item && phase == Phase.MAIN) {
                            btnEasy.visibility = View.VISIBLE
                            btnEasy.isEnabled = true
                            btnEasy.text = "Foi fácil"
                        }
                    }
                } catch (e: Exception) { android.util.Log.e("Michi", "srs", e) }
            }
            val db = VocabDb.get(this)
            if (sessionId < 0) sessionId = db.startSession(recording, focusLabel, 0)
            db.addStudyItem(sessionId, VocabDb.StudyItem(item.kind, promptText, answer, given, ok, item.sentence))
            if (key != null && key in VocabDb.savedKeys && trains != null && trains != StudyGen.TRAINS_GRAMMAR) {
                db.bumpScore(key, kanji = trains == StudyGen.TRAINS_KANJI, correct = ok)
            }
            // Fila de erros: errou entra; acertou de primeira sai (flashcards ficam de fora)
            if (item is Item.Flash) return@execute
            if (ok) db.removeMistake(identity)
            else db.addMistake(VocabDb.Mistake(identity, json, item.kind, promptText, answer, item.sentence, System.currentTimeMillis()))
        }
    }

    private fun wordOf(item: Item): Jp.Word? = when (item) {
        is Item.Cloze -> item.word
        is Item.Choice -> item.word
        is Item.Kanji -> item.word
        is Item.Write -> item.word
        is Item.Parts -> item.word
        else -> null
    }

    /**
     * Feedback depois de responder: certo/errado, a palavra (leitura, classe, conjugação, significado),
     * o padrão gramatical quando for o caso, e a frase com a tradução. Traduções que ainda não
     * estão no cache são pedidas e o feedback é redesenhado quando chegam.
     */
    private fun showFeedback(item: Item, ok: Boolean, given: String, answer: String) {
        if (item is Item.Flash) { showFlashBack(item); return }
        if (item is Item.FreeWrite) { showFreeFeedback(item, given); return }
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

        // Partes do kanji
        if (item is Item.Parts) {
            sb.append("\n\n")
            colored("Partes", sec)
            sb.append("\n")
            bold(item.kanji.toString())
            sb.append(" = ").append(item.parts.joinToString(" + ") { p ->
                val m = p.element.firstOrNull()?.let { Kanjidic.get(this, it) }?.shortMeaning
                p.element + if (!m.isNullOrBlank()) " ($m)" else ""
            })
        }

        // Gramática
        when (item) {
            is Item.GrammarCloze -> {
                sb.append("\n\n"); bold("${item.hit.point.name} · ${item.hit.point.level}")
                sb.append("\n").append(item.hit.point.explanation)
            }
            is Item.Drill -> Grammar.point(item.pointId)?.let { p ->
                sb.append("\n\n"); bold("${p.name} · ${p.level}")
                sb.append("\n").append(p.explanation)
                if (item.note.isNotBlank()) { sb.append("\n"); colored(item.note, sec) }
            }
            is Item.GrammarQ -> {
                sb.append("\n\n"); bold("${item.hit.point.name} · ${item.hit.point.level}")
                sb.append("\n").append(item.hit.point.explanation).append("\nEx.: ").append(item.hit.point.example)
            }
            else -> {}
        }

        feedback.text = sb
        feedback.visibility = View.VISIBLE
        showSentenceTr(item.sentence)

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

    private fun addPartTile(item: Item.Parts, p: KanjiParts.Part) {
        val t = PartTileView(this)
        t.set(KanjiParts.paths(this, p), p.element)
        t.setOnClickListener {
            if (answered || current !== item || builtPartList.size >= item.parts.size) return@setOnClickListener
            builtPartList.add(p)
            usedTiles.add(t)
            t.visibility = View.INVISIBLE
            buildView.setFilled(builtPartList.map { KanjiParts.paths(this, it) })
        }
        choices.addView(t)
    }

    private fun undoTile() {
        if (answered) return
        if (current is Item.Parts) {
            if (builtPartList.isEmpty()) return
            builtPartList.removeAt(builtPartList.size - 1)
            usedTiles.removeAt(usedTiles.size - 1).visibility = View.VISIBLE
            buildView.setFilled(builtPartList.map { KanjiParts.paths(this, it) })
            return
        }
        if (builtParts.isEmpty()) return
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
    // Escrever com o padrão (correção pela IA)
    // =====================================================================

    private fun checkFree(item: Item.FreeWrite) {
        val text = freeInput.text.toString().trim()
        if (text.isEmpty()) { Toast.makeText(this, "Escreva uma frase primeiro", Toast.LENGTH_SHORT).show(); return }
        if (!Ai.enabled(this)) { Toast.makeText(this, "Ative a IA em Configurações", Toast.LENGTH_LONG).show(); return }
        btnNext.isEnabled = false
        btnNext.text = "Corrigindo…"
        Ai.check(this, item.pointId, text, item.word?.base, Prefs(this).level) { res ->
            if (current !== item || answered) return@check
            if (res.error != null) {
                Toast.makeText(this, "Não consegui corrigir: ${res.error}", Toast.LENGTH_LONG).show()
                updateButtons()
                return@check
            }
            freeChecks[item] = res
            onAnswered(item, text)
        }
    }

    private fun showFreeFeedback(item: Item.FreeWrite, given: String) {
        val res = freeChecks[item]
        val sb = SpannableStringBuilder()
        fun colored(t: String, c: Int) { val a = sb.length; sb.append(t); sb.setSpan(ForegroundColorSpan(c), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        fun bold(t: String) { val a = sb.length; sb.append(t); sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        val sec = getColor(R.color.text_secondary)
        if (res == null) {
            colored("Sua frase: $given", sec)
        } else {
            if (res.correct) colored("✓ Correta!", getColor(R.color.ok)) else colored("✗ Precisa de ajuste", getColor(R.color.stop))
            sb.append("\n\n"); bold("Sua frase: "); sb.append(given)
            if (res.corrected.isNotBlank() && res.corrected != given) { sb.append("\n"); bold("Corrigida: "); colored(res.corrected, getColor(R.color.ok)) }
            if (res.translation.isNotBlank()) { sb.append("\n"); bold("Tradução: "); sb.append(res.translation) }
            if (res.explanation.isNotBlank()) { sb.append("\n"); bold("Por quê: "); sb.append(res.explanation) }
            sb.append("\n"); colored("Correção: ${Ai.modelLabel(this)}", sec)
        }
        Grammar.point(item.pointId)?.let { p ->
            sb.append("\n\n"); bold("${p.name} · ${p.level}")
            sb.append("\n").append(p.explanation).append("\nEx.: ").append(p.example)
            GrammarDrill.spec(p.id)?.let { sb.append("\n"); colored(it.rule(), sec) }
        }
        feedback.text = sb
        feedback.visibility = View.VISIBLE
        trLine.visibility = View.GONE
    }

    /** "Frase" + a frase + a tradução (ML Kit ou Gemini; tocar alterna). */
    private fun showSentenceTr(sentence: String) {
        if (sentence.isBlank()) { trLine.visibility = View.GONE; return }
        val pre = SpannableStringBuilder()
        val a = pre.length
        pre.append("Frase")
        pre.setSpan(ForegroundColorSpan(getColor(R.color.text_secondary)), a, pre.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        pre.append("\n")
        val off = pre.length
        pre.append(sentence)
        val on = Furigana.decorate(this, pre, off, sentence)
        pre.append("\n")
        Furigana.style(trLine, on)
        trLine.visibility = View.VISIBLE
        TrSwitch.bind(this, trLine, sentence, pre)
    }

    // =====================================================================
    // Revisão espaçada: alvo da nota e flashcards
    // =====================================================================

    private class SrsTarget(val kind: String, val key: String, val label: String, val sentence: String? = null, val recording: String? = null, val sec: Int = -1)

    /** Que cartão esta resposta avalia: palavra salva (significado ou kanji) ou padrão de gramática. */
    private fun srsTarget(item: Item): SrsTarget? = when (item) {
        is Item.Flash -> SrsTarget(item.card.kind, item.card.key, item.card.label)
        is Item.GrammarQ -> SrsTarget(Srs.GRAMMAR, item.hit.point.id, item.hit.point.name, item.sentence, item.recording, item.sec)
        is Item.GrammarCloze -> SrsTarget(Srs.GRAMMAR, item.hit.point.id, item.hit.point.name, item.sentence, item.recording, item.sec)
        is Item.Drill -> Grammar.point(item.pointId)?.let { SrsTarget(Srs.GRAMMAR, it.id, it.name, item.sentence, item.recording, item.sec) }
        is Item.FreeWrite -> Grammar.point(item.pointId)?.let { SrsTarget(Srs.GRAMMAR, it.id, it.name, item.sentence, item.recording, item.sec) }
        else -> {
            val w = StudyGen.wordOf(item)
            val key = item.wordKey
            when {
                w == null || key == null || key !in VocabDb.studyKeys -> null
                StudyGen.trainsOf(item.kind) == StudyGen.TRAINS_KANJI ->
                    if (w.base.any(StudyGen::isKanji)) SrsTarget(Srs.KANJI, key, w.base) else null
                StudyGen.trainsOf(item.kind) == StudyGen.TRAINS_MEANING -> SrsTarget(Srs.MEANING, key, w.base)
                else -> null
            }
        }
    }

    private fun bigSpan(sb: SpannableStringBuilder, text: String, size: Float) {
        val a = sb.length
        sb.append(text)
        sb.setSpan(RelativeSizeSpan(size), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** Frente do flashcard: significado mostra a palavra; kanji mostra a leitura; gramática mostra a frase. */
    private fun renderFlashFront(item: Item.Flash, pre: String) {
        flashRevealed = false
        sentenceView.visibility = View.VISIBLE
        val sb = SpannableStringBuilder()
        val accent = getColor(R.color.accent)
        val w = item.word
        val s = item.sentence
        var furi = false
        fun sentenceWith(mask: Boolean) {
            if (s.isEmpty()) return
            sb.append("\n\n")
            val a = sb.length
            if (w != null && w.start >= 0 && w.end <= s.length && w.start < w.end) {
                sb.append(s.substring(0, w.start))
                val b = sb.length
                sb.append(if (mask) "＿".repeat(w.end - w.start) else s.substring(w.start, w.end))
                sb.setSpan(ForegroundColorSpan(accent), b, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append(s.substring(w.end))
            } else sb.append(s)
            sb.setSpan(RelativeSizeSpan(0.8f), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            // Num cartão de kanji a palavra já está escondida; a leitura dela nunca aparece
            val shown = sb.subSequence(a, sb.length).toString()
            if (Furigana.decorate(this, sb, a, shown, skip = if (mask && w != null) w.start until w.end else null)) furi = true
        }
        when (item.card.kind) {
            Srs.MEANING -> {
                prompt.text = pre + "Flashcard · o que significa? Pense na resposta e toque em Mostrar resposta."
                bigSpan(sb, item.card.label, 1.8f)
                sentenceWith(mask = false)
            }
            Srs.KANJI -> {
                prompt.text = pre + "Flashcard · como se escreve em kanji? Imagine (ou escreva no ar) e toque em Mostrar resposta."
                bigSpan(sb, w?.reading ?: item.card.label, 1.5f)
                Tr.cached(this, item.card.label)?.let { sb.append("\n").append(it) }
                sentenceWith(mask = true)
            }
            else -> {
                prompt.text = pre + "Flashcard · que padrão gramatical está em destaque e o que ele significa?"
                val hit = try { Grammar.find(s).firstOrNull { it.point.id == item.card.key } } catch (_: Exception) { null }
                if (hit != null) {
                    sb.append(s.substring(0, hit.start))
                    val b = sb.length
                    sb.append(s.substring(hit.start, hit.end))
                    sb.setSpan(ForegroundColorSpan(accent), b, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), b, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.append(s.substring(hit.end))
                } else sb.append(s)
                if (Furigana.decorate(this, sb, 0, s)) furi = true
            }
        }
        Furigana.style(sentenceView, furi)
        sentenceView.text = sb
        updateButtons()
    }

    /** Vira o cartão: mostra o verso e os 4 botões de nota com o próximo intervalo de cada um. */
    private fun revealFlash() {
        val item = current as? Item.Flash ?: return
        flashRevealed = true
        if (item.card.kind == Srs.KANJI) {
            // No verso a palavra aparece inteira
            Furigana.style(sentenceView, false)
            sentenceView.text = SpannableStringBuilder().also { bigSpan(it, item.card.label, 1.8f) }
        }
        showFlashBack(item)
        choices.removeAllViews()
        val card = Srs.get(this, item.card.kind, item.card.key) ?: item.card
        val pv = Srs.preview(card)
        for (g in Srs.AGAIN..Srs.EASY) {
            val label = Srs.gradeLabel(g)
            val b = Button(this).apply {
                text = "$label\n${pv[g]}"
                tag = label
                isAllCaps = false
                textSize = 15f
                setBackgroundResource(R.drawable.bg_button_outline)
                setTextColor(getColor(if (g == Srs.AGAIN) R.color.stop else R.color.text))
                minHeight = 0; minimumHeight = 0
                setPadding(dp(14), dp(8), dp(14), dp(8))
                stateListAnimator = null
                setOnClickListener {
                    if (answered || current !== item) return@setOnClickListener
                    flashGrade = g
                    onAnswered(item, label)
                }
            }
            choices.addView(b)
        }
        updateButtons()
    }

    /** Verso do flashcard (o mesmo template para os três tipos de cartão). */
    private fun showFlashBack(item: Item.Flash) {
        val sec = getColor(R.color.text_secondary)
        val sb = SpannableStringBuilder()
        fun grey(t: String) { val a = sb.length; sb.append(t); sb.setSpan(ForegroundColorSpan(sec), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        fun bold(t: String) { val a = sb.length; sb.append(t); sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        val pending = mutableListOf<String>()
        val p = item.point
        if (p != null) {
            bold("${p.name} · ${p.level}")
            sb.append("\n").append(p.explanation).append("\nEx.: ").append(p.example)
        } else {
            val w = item.word
            bold(item.card.label)
            if (w != null && w.reading != w.base) sb.append("  ").append(w.reading)
            if (w != null) { sb.append("\n"); grey(w.pos) }
            val meaning = Tr.cached(this, item.card.label)
            sb.append("\nSignificado: ")
            if (meaning != null) sb.append(meaning) else { grey("traduzindo…"); pending.add(item.card.label) }
            val kanji = Kanjidic.describe(this, item.card.label)
            if (kanji.isNotEmpty()) {
                sb.append("\n\n"); grey("Kanji")
                for ((c, text) in kanji) {
                    sb.append("\n"); bold(c.toString()); sb.append("  ").append(text)
                    KanjiParts.get(this, c)?.let { e -> sb.append("\n   "); grey("partes: " + e.parts.joinToString(" + ") { it.element }) }
                }
            }
        }
        val card = Srs.get(this, item.card.kind, item.card.key)
        if (card != null && card.reps > 0) {
            sb.append("\n\n")
            val r = Srs.retrievability(card)
            grey("Revisões: ${card.reps} · erros: ${card.lapses}" + (if (r != null) " · lembrava ${(r * 100).toInt()}%" else ""))
        }
        feedback.text = sb
        feedback.visibility = View.VISIBLE
        showSentenceTr(item.sentence)
        for (t in pending) Tr.translate(this, t) { _ -> if (current === item) showFlashBack(item) }
        if (item.card.kind != Srs.GRAMMAR && item.card.label.any(StudyGen::isKanji)) showStrokes(item.card.label)
    }

    // =====================================================================
    // Legenda com a palavra falada
    // =====================================================================

    private fun itemRecording(item: Item): String? = item.recording ?: if (multiRecording) null else recording

    private fun loadTiming(item: Item) {
        val rec = itemRecording(item)
        val key = "$rec|${item.sec}|${item.sentence}"
        if (timingCache.containsKey(key)) { timing = timingCache[key]; updateSubs(); return }
        Thread {
            val t = try { WordTiming.get(this, rec, item.sentence, item.sec, item.endSec) } catch (_: Exception) { null }
            runOnUiThread {
                timingCache[key] = t
                if (current === item) { timing = t; updateSubs() }
            }
        }.start()
    }

    /** Botão Legenda, a legenda e o aviso de tempo exato/estimado. No "Ordenar a frase" só depois de responder. */
    private fun updateSubs() {
        val item = current
        val can = item != null && item.sec >= 0 && btnAudio.visibility == View.VISIBLE &&
            (phase == Phase.MAIN || phase == Phase.REVIEW) && !(item is Item.Order && !answered)
        btnSubs.visibility = if (can) View.VISIBLE else View.GONE
        btnSubs.text = if (subsOn) "Ocultar legenda" else "Legenda"
        val show = can && subsOn
        subtitle.visibility = if (show) View.VISIBLE else View.GONE
        val t = timing
        timingInfo.visibility = if (show && t != null) View.VISIBLE else View.GONE
        timingInfo.text = if (t?.exact == true) "ⓘ tempo exato das palavras" else "ⓘ tempo estimado"
        if (show) renderSubtitle(playingPos())
    }

    /** Antes de responder, a palavra (ou o trecho) do exercício vira ＿ na legenda. */
    private fun maskOf(item: Item): IntRange? = when (item) {
        is Item.GrammarCloze -> item.hit.start until item.hit.end
        is Item.GrammarQ, is Item.Order -> null
        else -> StudyGen.wordOf(item)?.let { w ->
            if (w.start >= 0 && w.start < w.end && w.end <= item.sentence.length) w.start until w.end else null
        }
    }

    private fun renderSubtitle(pos: Long?) {
        val item = current ?: return
        val s = item.sentence
        val mask = if (answered) null else maskOf(item)
        val text = if (mask != null) s.replaceRange(mask.first, mask.last + 1, "＿".repeat(mask.last + 1 - mask.first)) else s
        val sb = SpannableStringBuilder(text)
        val t = timing
        if (t != null && pos != null && pos in t.fromMs..t.toMs) {
            val seg = t.segs.lastOrNull { it.ms <= pos }
            if (seg != null && seg.start < seg.end && seg.end <= text.length) {
                sb.setSpan(ForegroundColorSpan(getColor(R.color.accent)), seg.start, seg.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), seg.start, seg.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(RelativeSizeSpan(1.2f), seg.start, seg.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        Furigana.style(subtitle, Furigana.decorate(this, sb, 0, text, skip = mask))
        subtitle.text = sb
    }

    private fun playingPos(): Long? = try {
        if (prepared && player?.isPlaying == true) player!!.currentPosition.toLong() else null
    } catch (_: IllegalStateException) { null }

    private fun tickKaraoke() {
        val pos = playingPos()
        if (subtitle.visibility == View.VISIBLE) renderSubtitle(pos)
        if (pos != null) ui.postDelayed(karaoke, 60)
    }

    // =====================================================================
    // ✦ IA no fim do retorno
    // =====================================================================

    private fun setupAi(item: Item) {
        aiPanel?.hide()
        aiPanel = null
        if (!Ai.enabled(this)) return
        val rec = itemRecording(item)
        val level = Prefs(this).level
        val reqs = mutableListOf(Ai.Request(Ai.Task.TRANSLATE, item.sentence, rec, level = level))
        StudyGen.wordOf(item)?.let { w ->
            reqs.add(Ai.Request(Ai.Task.WORD, item.sentence, rec, w.surface, "forma de dicionário ${w.base}, leitura ${w.reading}, ${w.pos}", level))
        }
        when (item) {
            is Item.GrammarQ -> reqs.add(Ai.Request(Ai.Task.GRAMMAR, item.sentence, rec, item.hit.point.name, item.sentence.substring(item.hit.start, item.hit.end), level))
            is Item.GrammarCloze -> reqs.add(Ai.Request(Ai.Task.GRAMMAR, item.sentence, rec, item.hit.point.name, item.sentence.substring(item.hit.start, item.hit.end), level))
            is Item.Order -> reqs.add(Ai.Request(Ai.Task.GRAMMAR, item.sentence, rec, level = level))
            is Item.Drill -> Grammar.point(item.pointId)?.let { reqs.add(Ai.Request(Ai.Task.GRAMMAR, item.sentence, rec, it.name, "", level)) }
            is Item.FreeWrite -> Grammar.point(item.pointId)?.let { reqs.add(Ai.Request(Ai.Task.GRAMMAR, item.sentence, rec, it.name, "", level)) }
            else -> {}
        }
        aiPanel = AiPanel(this, aiBox, reqs).also { it.show() }
    }

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

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(0, 101, 0, "Reportar erro")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { confirmExit(); return true }
        if (item.itemId == 101) { reportCurrent(); return true }
        return super.onOptionsItemSelected(item)
    }

    /** Reporta o item na tela com o que ele mostrava (traduções, resposta certa, alternativas). */
    private fun reportCurrent() {
        val it = current ?: run { Toast.makeText(this, "Nada na tela para reportar", Toast.LENGTH_SHORT).show(); return }
        val w = StudyGen.wordOf(it)
        val shown = buildList {
            Tr.cached(this@StudyActivity, it.sentence)?.let { t -> add("frase (ML Kit): $t") }
            Tr.aiCached(this@StudyActivity, it.sentence)?.let { t -> add("frase (Gemini): $t") }
            if (w != null) Tr.cached(this@StudyActivity, w.base)?.let { t -> add("palavra: $t") }
            if (it !is Item.Flash && it !is Item.FreeWrite) add("resposta certa: ${answerOf(it)}")
            when (it) {
                is Item.Choice -> add("alternativas: " + it.options.joinToString(" / "))
                is Item.Drill -> add("alternativas: " + it.options.joinToString(" / "))
                is Item.Cloze -> add("alternativas: " + it.options.joinToString(" / "))
                is Item.GrammarCloze -> add("alternativas: " + it.options.joinToString(" / "))
                else -> {}
            }
        }.joinToString(" · ")
        Reports.dialog(this, it.sentence, w, itemRecording(it), "Exercício · ${it.kind}", shown)
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
            if (it != null && it.sec >= 0 && (phase == Phase.MAIN || phase == Phase.REVIEW)) {
                btnAudio.visibility = View.VISIBLE
                loadTiming(it)
                updateSubs()
            }
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
            // Com o tempo das palavras, toca só a parte com voz
            val t = timing
            val startMs = if (t != null) t.fromMs.toInt() else maxOf(0, item.sec * 1000 - 500)
            val endMs = if (t != null) maxOf(startMs + 1000, t.toMs.toInt()) else maxOf(startMs + 1500, item.endSec * 1000 - 300)
            p.seekTo(startMs)
            p.start()
            ui.removeCallbacks(karaoke)
            ui.postDelayed(karaoke, 80)
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
