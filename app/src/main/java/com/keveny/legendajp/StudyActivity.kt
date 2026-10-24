package com.keveny.legendajp

import android.app.Activity
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

/** Sessão de exercícios de uma gravação (foco escolhido no menu Estudar). */
class StudyActivity : Activity() {

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_NAME = "name"
        const val EXTRA_FOCUS = "focus"
        private const val MAX_ITEMS = 12
    }

    private lateinit var progress: TextView
    private lateinit var prompt: TextView
    private lateinit var sentenceView: TextView
    private lateinit var built: TextView
    private lateinit var btnAudio: Button
    private lateinit var btnUndo: Button
    private lateinit var choices: FlowLayout
    private lateinit var feedback: TextView
    private lateinit var btnNext: Button

    private var items: List<Item> = emptyList()
    private var index = -1
    private var correct = 0
    private var answered = false
    private val builtParts = mutableListOf<String>()
    private val usedTiles = mutableListOf<Button>()
    private var player: MediaPlayer? = null
    private var prepared = false
    private val ui = Handler(Looper.getMainLooper())
    private var sessionId = -1L
    private var recording = ""
    private var focus = StudyGen.Focus.MISTO

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

        val uri = intent.getParcelableExtra(EXTRA_URI, Uri::class.java) ?: run { finish(); return }
        val name = intent.getStringExtra(EXTRA_NAME) ?: ""
        focus = try { StudyGen.Focus.valueOf(intent.getStringExtra(EXTRA_FOCUS) ?: "MISTO") } catch (_: Exception) { StudyGen.Focus.MISTO }
        recording = Recordings.baseName(name)
        title = "Estudar · ${focus.label}"
        btnUndo.setOnClickListener { undoTile() }
        btnNext.setOnClickListener { onNext() }
        prompt.text = "Preparando os exercícios…"

        Thread {
            val text = try { Recordings.readText(this, uri) } catch (_: Exception) { "" }
            val lines = StudyGen.lines(text)
            val wav = Recordings.find(this, recording + ".wav")?.uri
            val built = StudyGen.build(this, lines, focus, Prefs(this).level, MAX_ITEMS)
            runOnUiThread {
                if (wav != null) setupPlayer(wav)
                if (built.isEmpty()) {
                    prompt.text = "Esta gravação não tem material suficiente para este foco."
                    btnNext.text = "Fechar"
                    btnNext.setOnClickListener { finish() }
                } else {
                    items = built
                    sessionId = VocabDb.get(this).startSession(recording, focus.label, built.size)
                    next()
                }
            }
        }.start()
    }

    // ---------- apresentação ----------

    private fun next() {
        stopAudio()
        index++
        if (index >= items.size) { summary(); return }
        val item = items[index]
        answered = false
        builtParts.clear()
        usedTiles.clear()
        choices.removeAllViews()
        feedback.visibility = View.GONE
        built.visibility = View.GONE
        built.text = ""
        btnUndo.visibility = View.GONE
        sentenceView.visibility = View.GONE
        btnAudio.visibility = if (prepared) View.VISIBLE else View.GONE
        btnAudio.setOnClickListener { play(item) }
        progress.text = "${index + 1} de ${items.size} · $correct certas · ${item.kind}"
        btnNext.text = "Verificar"
        btnNext.isEnabled = true

        when (item) {
            is Item.Order -> {
                prompt.text = "Monte a frase na ordem certa (ouça o áudio se precisar)."
                built.visibility = View.VISIBLE
                btnUndo.visibility = View.VISIBLE
                for (c in item.chunks.shuffled()) addTile(c)
            }
            is Item.Cloze -> {
                prompt.text = "Qual palavra completa a frase?"
                sentenceView.visibility = View.VISIBLE
                sentenceView.text = item.sentence.replaceRange(item.word.start, item.word.end, "＿＿＿")
                for (o in item.options) addChoice(o, item.word.surface)
                btnNext.text = "Próxima"; btnNext.isEnabled = false
            }
            is Item.GrammarCloze -> {
                prompt.text = "O que completa a frase? (padrão gramatical)"
                sentenceView.visibility = View.VISIBLE
                sentenceView.text = item.sentence.replaceRange(item.hit.start, item.hit.end, "＿＿＿")
                for (o in item.options) addChoice(o, item.sentence.substring(item.hit.start, item.hit.end))
                btnNext.text = "Próxima"; btnNext.isEnabled = false
            }
            is Item.Kanji -> {
                val tr = Tr.cached(this, item.word.base)
                prompt.text = "Monte a palavra 「${item.word.reading}」" + (if (tr != null) " ($tr)" else "") + " com os kanji."
                built.visibility = View.VISIBLE
                btnUndo.visibility = View.VISIBLE
                for (t in item.tiles) addTile(t)
            }
            is Item.Choice -> {
                prompt.text = item.prompt
                for (o in item.options) addChoice(o, item.answer)
                btnNext.text = "Próxima"; btnNext.isEnabled = false
            }
            is Item.GrammarQ -> {
                prompt.text = "Qual padrão gramatical aparece nesta frase?"
                sentenceView.visibility = View.VISIBLE
                sentenceView.text = item.sentence
                for (o in item.options) addChoice(o.name, item.hit.point.name)
                btnNext.text = "Próxima"; btnNext.isEnabled = false
            }
        }
    }

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

    private fun addChoice(label: String, answer: String) {
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
            if (answered) return@setOnClickListener
            answered = true
            stopAudio()
            val ok = label == answer
            if (ok) correct++
            for (i in 0 until choices.childCount) {
                val c = choices.getChildAt(i) as Button
                when {
                    c.text.toString() == answer -> { c.setBackgroundResource(R.drawable.bg_choice_right); c.setTextColor(0xFFFFFFFF.toInt()) }
                    c === b -> { c.setBackgroundResource(R.drawable.bg_button_stop); c.setTextColor(0xFFFFFFFF.toInt()) }
                }
            }
            record(ok, label, answer)
            showFeedback(ok, label, answer)
            btnNext.isEnabled = true
        }
        choices.addView(b)
    }

    private fun onNext() {
        val item = items.getOrNull(index) ?: return
        if (!answered) {
            val given = builtParts.joinToString("")
            val target = when (item) {
                is Item.Order -> item.chunks.joinToString("")
                is Item.Kanji -> item.word.base.filter(StudyGen::isKanji)
                else -> { Toast.makeText(this, "Escolha uma alternativa", Toast.LENGTH_SHORT).show(); return }
            }
            answered = true
            stopAudio()
            val ok = given == target
            if (ok) correct++
            // Resposta dada fica no campo, colorida; a certa aparece no feedback
            val sb = SpannableStringBuilder(given.ifEmpty { "(vazio)" })
            sb.setSpan(ForegroundColorSpan(getColor(if (ok) R.color.ok else R.color.stop)), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            built.text = sb
            record(ok, given, if (item is Item.Kanji) item.word.base else target)
            showFeedback(ok, given, target)
            btnNext.text = "Próxima"
            progress.text = "${index + 1} de ${items.size} · $correct certas · ${item.kind}"
            return
        }
        next()
    }

    private fun record(ok: Boolean, given: String, answer: String) {
        val item = items[index]
        val promptText = when (item) {
            is Item.Choice -> item.prompt
            is Item.GrammarQ -> "Padrão da frase"
            is Item.GrammarCloze -> "Lacuna gramatical"
            is Item.Cloze -> "Palavra que completa a frase"
            is Item.Kanji -> "Montar 「${item.word.reading}」"
            is Item.Order -> "Ordenar a frase"
        }
        if (sessionId >= 0) Thread {
            VocabDb.get(this).addStudyItem(sessionId, VocabDb.StudyItem(item.kind, promptText, answer, given, ok, item.sentence))
        }.start()
    }

    private fun showFeedback(ok: Boolean, given: String, answer: String) {
        val item = items[index]
        val okColor = getColor(R.color.ok)
        val badColor = getColor(R.color.stop)
        val sb = SpannableStringBuilder()
        fun colored(text: String, color: Int) {
            val a = sb.length; sb.append(text)
            sb.setSpan(ForegroundColorSpan(color), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (ok) colored("✓ Certo!", okColor) else {
            colored("✗ Sua resposta: ${given.ifEmpty { "(vazio)" }}", badColor)
            sb.append("\n")
            colored("Certa: $answer", okColor)
        }
        sb.append("\n\n")
        when (item) {
            is Item.Order -> sb.append(item.sentence)
            is Item.Cloze -> sb.append(item.sentence).append("\n").append(item.word.base)
                .append(if (item.word.reading != item.word.base) " (${item.word.reading})" else "").append(" · ").append(item.word.pos)
            is Item.GrammarCloze -> sb.append(item.sentence).append("\n").append(item.hit.point.name).append(" · ")
                .append(item.hit.point.level).append("\n").append(item.hit.point.explanation)
            is Item.Kanji -> sb.append(item.word.base).append(" (").append(item.word.reading).append(") · ").append(item.word.pos)
                .append("\n").append(item.sentence)
            is Item.Choice -> sb.append(item.note).append("\n").append(item.sentence)
            is Item.GrammarQ -> sb.append(item.hit.point.name).append(" · ").append(item.hit.point.level)
                .append("\n").append(item.hit.point.explanation).append("\nEx.: ").append(item.hit.point.example)
        }
        Tr.cached(this, item.sentence)?.let { sb.append("\n").append(it) }
        feedback.text = sb
        feedback.visibility = View.VISIBLE
    }

    private fun summary() {
        choices.removeAllViews()
        sentenceView.visibility = View.GONE
        built.visibility = View.GONE
        btnUndo.visibility = View.GONE
        btnAudio.visibility = View.GONE
        feedback.visibility = View.GONE
        progress.text = ""
        prompt.textSize = 18f
        prompt.text = "Sessão concluída: $correct de ${items.size} certas.\nO resultado está no histórico desta gravação."
        btnNext.text = "Nova sessão"
        btnNext.isEnabled = true
        btnNext.setOnClickListener {
            prompt.textSize = 14f
            correct = 0; index = -1
            items = items.shuffled()
            sessionId = VocabDb.get(this).startSession(recording, focus.label, items.size)
            btnNext.setOnClickListener { onNext() }
            next()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------- áudio ----------

    private fun setupPlayer(uri: Uri) {
        val p = MediaPlayer()
        p.setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        )
        p.setOnPreparedListener { prepared = true; if (index >= 0 && index < items.size) btnAudio.visibility = View.VISIBLE }
        p.setOnErrorListener { _, _, _ -> true }
        try {
            p.setDataSource(this, uri)
            p.prepareAsync()
            player = p
        } catch (_: Exception) { p.release() }
    }

    private var stopAt = Runnable {}

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
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
