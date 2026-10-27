package com.keveny.legendajp

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Leitor de textos (estilo Kindle): parágrafos com as palavras tocáveis (cartão da palavra, salvar,
 * Já conheço), cores de conhecidas/em estudo, furigana por cima dos kanji (liga/desliga), tamanho da
 * letra e traduções. Toque longo numa frase: tradução (ML Kit ⇄ Gemini), gramática e áudio.
 */
class ReaderActivity : Activity() {

    companion object {
        const val EXTRA_NAME = "name"
    }

    private lateinit var name: String
    private lateinit var base: String
    private lateinit var content: LinearLayout
    private var paragraphs: List<List<Texts.Sentence>> = emptyList()
    private var hasAudio = false
    private var player: MediaPlayer? = null
    private val ui = Handler(Looper.getMainLooper())
    private var stopAt = Runnable {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        name = intent.getStringExtra(EXTRA_NAME) ?: run { finish(); return }
        base = Recordings.baseName(name)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(40)) }
        setContentView(ScrollView(this).apply { addView(content) })
        load()
    }

    private fun load() {
        Thread {
            val p = Texts.read(this, name)
            val audio = Recordings.find(this, "$base.wav") != null
            val t = Recordings.display(this, name)
            val level = try { TextLevel.analyze(p.flatten().map { it.text }) } catch (_: Exception) { null }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                title = t
                actionBar?.subtitle = if (t != Recordings.prettyName(name)) "Criado em ${Recordings.prettyName(name)}" else null
                paragraphs = p
                hasAudio = audio
                render(level)
                invalidateOptionsMenu()
            }
        }.start()
    }

    private var lastLevel: TextLevel.Result? = null

    /** Desenha tudo de novo (furigana, tamanho, cores e traduções). */
    private fun render(level: TextLevel.Result? = lastLevel) {
        lastLevel = level
        content.removeAllViews()
        val prefs = Prefs(this)
        val size = prefs.readerSize.toFloat()
        val furigana = prefs.furigana && Furigana.enabled(this)
        if (level != null && level.tokens > 0) {
            fun pct(n: Int) = (n * 100 + level.tokens / 2) / level.tokens
            content.addView(TextView(this).apply {
                text = "Para você: ${level.label} · nível estimado N${level.estLevel} · conheço ${pct(level.known)}% · estudando ${pct(level.studying)}%"
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, 0, 0, dp(12))
            })
        }
        if (paragraphs.isEmpty()) {
            content.addView(TextView(this).apply { text = "Texto vazio."; textSize = 16f; setTextColor(getColor(R.color.text)) })
            return
        }
        val saved = getColor(R.color.saved_word)
        val known = getColor(R.color.known_word)
        for (para in paragraphs) {
            val sb = SpannableStringBuilder()
            val ranges = mutableListOf<Pair<IntRange, Texts.Sentence>>()
            for (s in para) {
                val off = sb.length
                sb.append(Jp.decorate(s.text, VocabDb.studyKeys, saved, VocabDb.knownKeys, known) { w ->
                    WordCard.show(this, w, s.text, base, maxOf(0, s.sec),
                        onPlay = if (hasAudio && s.sec >= 0) ({ playSentence(s) }) else null,
                        onChanged = { render() })
                })
                if (furigana) Furigana.decorate(this, sb, off, s.text)
                ranges.add(off until sb.length to s)
            }
            val tv = TextView(this).apply {
                text = sb
                setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
                setTextColor(getColor(R.color.text))
                // O furigana ocupa o espaço entre as linhas (e o topo do parágrafo)
                val extra = if (furigana) size * 0.62f else size * 0.3f
                setLineSpacing(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, extra, resources.displayMetrics), 1f)
                setPadding(0, if (furigana) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size * 0.55f, resources.displayMetrics).toInt() else 0, 0, dp(10))
                movementMethod = LinkMovementMethod.getInstance()
                highlightColor = Color.TRANSPARENT
            }
            // Toque longo: a frase sob o dedo
            var lastX = 0f
            var lastY = 0f
            tv.setOnTouchListener { _, ev -> if (ev.action == MotionEvent.ACTION_DOWN) { lastX = ev.x; lastY = ev.y }; false }
            tv.setOnLongClickListener {
                val o = tv.getOffsetForPosition(lastX, lastY)
                val s = ranges.firstOrNull { o in it.first }?.second ?: ranges.lastOrNull()?.second
                if (s != null) {
                    Jp.suppressTapUntil = android.os.SystemClock.uptimeMillis() + 1000
                    showSentence(s)
                }
                true
            }
            content.addView(tv)
            if (prefs.readerTranslations) content.addView(paragraphTranslation(para))
        }
    }

    /** Tradução do parágrafo (Gemini quando houver, senão ML Kit; pede o que falta). */
    private fun paragraphTranslation(para: List<Texts.Sentence>): TextView {
        val tv = TextView(this).apply {
            textSize = 14f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, 0, 0, dp(18))
        }
        fun fill() {
            val parts = para.map { s -> Tr.aiCached(this, s.text) ?: Tr.cached(this, s.text) }
            tv.text = parts.joinToString(" ") { it ?: "…" }
        }
        fill()
        for (s in para) if (Tr.aiCached(this, s.text) == null && Tr.cached(this, s.text) == null) {
            Tr.translate(this, s.text) { if (!isFinishing) fill() }
        }
        return tv
    }

    /** Frase: texto, tradução (tocar alterna), padrões de gramática e áudio, se houver. */
    private fun showSentence(s: Texts.Sentence) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(16), dp(22), dp(6)) }
        box.addView(TextView(this).apply {
            textSize = 20f; setTextColor(getColor(R.color.text)); setTextIsSelectable(true)
            Furigana.set(this@ReaderActivity, this, Jp.highlight(s.text, VocabDb.studyKeys, getColor(R.color.saved_word), VocabDb.knownKeys, getColor(R.color.known_word)), s.text)
        })
        val tr = TextView(this).apply { textSize = 15f; setPadding(0, dp(8), 0, dp(8)) }
        TrSwitch.bind(this, tr, s.text)
        box.addView(tr)
        val hits = try { Grammar.find(s.text) } catch (_: Exception) { emptyList() }
        if (hits.isNotEmpty()) {
            val sb = SpannableStringBuilder()
            for ((i, h) in hits.withIndex()) {
                if (i > 0) sb.append("\n\n")
                val a = sb.length
                sb.append(h.point.name).append("  ").append(h.point.level)
                sb.setSpan(StyleSpan(Typeface.BOLD), a, sb.length, 0)
                sb.append("\n").append(h.point.explanation)
            }
            box.addView(TextView(this).apply { text = sb; textSize = 14f; setTextColor(getColor(R.color.text)) })
        }
        val b = AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(box) }).setNegativeButton("Fechar", null)
        if (hasAudio && s.sec >= 0) b.setPositiveButton("▶ Ouvir", null)
        val dlg = b.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { playSentence(s) }
    }

    // ---------- áudio (quando o texto ganhou áudio) ----------

    private fun playSentence(s: Texts.Sentence) {
        val all = paragraphs.flatten()
        val next = all.getOrNull(all.indexOf(s) + 1)?.sec ?: (s.sec + 8)
        val p = player ?: run {
            val uri = Recordings.find(this, "$base.wav")?.uri ?: return
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(this@ReaderActivity, uri)
                prepare()
            }.also { player = it }
        }
        try {
            val start = maxOf(0, s.sec * 1000 - 300)
            p.seekTo(start)
            p.start()
            ui.removeCallbacks(stopAt)
            stopAt = Runnable { try { if (p.isPlaying) p.pause() } catch (_: IllegalStateException) {} }
            ui.postDelayed(stopAt, (maxOf(next, s.sec + 1) * 1000L - 200 - start))
        } catch (_: Exception) {}
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(stopAt)
        try { if (player?.isPlaying == true) player?.pause() } catch (_: IllegalStateException) {}
    }

    override fun onDestroy() {
        player?.release(); player = null
        super.onDestroy()
    }

    // ---------- menu ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val prefs = Prefs(this)
        menu.add(0, 1, 0, "Estudar").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, 2, 0, if (!Furigana.enabled(this)) "Furigana (desligado em Configurações)" else if (prefs.furigana) "Esconder furigana" else "Mostrar furigana")
        menu.add(0, 3, 0, "Letra maior")
        menu.add(0, 4, 0, "Letra menor")
        menu.add(0, 5, 0, if (prefs.readerTranslations) "Esconder traduções" else "Mostrar traduções")
        menu.add(0, 6, 0, "✦ Traduzir texto com IA")
        menu.add(0, 7, 0, if (hasAudio) "Criar áudio de novo" else "Criar áudio do texto")
        if (hasAudio) menu.add(0, 8, 0, "Ouvir com legenda")
        menu.add(0, 9, 0, "Renomear")
        menu.add(0, 10, 0, "Apagar")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val prefs = Prefs(this)
        when (item.itemId) {
            android.R.id.home -> finish()
            1 -> Recordings.find(this, "$base.txt")?.let {
                startActivity(Intent(this, StudyMenuActivity::class.java).putExtra(StudyActivity.EXTRA_URI, it.uri).putExtra(StudyActivity.EXTRA_NAME, it.name))
            }
            2 -> if (Furigana.enabled(this)) { prefs.furigana = !prefs.furigana; render(); invalidateOptionsMenu() }
                 else startActivity(Intent(this, SettingsActivity::class.java))
            3 -> { prefs.readerSize = (prefs.readerSize + 2).coerceAtMost(34); render() }
            4 -> { prefs.readerSize = (prefs.readerSize - 2).coerceAtLeast(14); render() }
            5 -> { prefs.readerTranslations = !prefs.readerTranslations; render(); invalidateOptionsMenu() }
            6 -> translateAll()
            7 -> createAudio()
            8 -> Recordings.find(this, "$base.txt")?.let {
                startActivity(Intent(this, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_URI, it.uri).putExtra(ViewerActivity.EXTRA_NAME, it.name))
            }
            9 -> Recordings.renameDialog(this, name) { load() }
            10 -> AlertDialog.Builder(this).setMessage("Apagar este texto?").setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Apagar") { _, _ ->
                    Recordings.find(this, "$base.txt")?.let { Recordings.delete(this, it.uri) }
                    Recordings.find(this, "$base.wav")?.let { Recordings.delete(this, it.uri) }
                    finish()
                }.show()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun progressDialog(title: String): Pair<AlertDialog, TextView> {
        val label = TextView(this).apply { text = "Preparando…"; textSize = 14f; setTextColor(getColor(R.color.text)); setPadding(dp(24), dp(16), dp(24), dp(8)) }
        return AlertDialog.Builder(this).setTitle(title).setView(label).setCancelable(false)
            .setNegativeButton("Parar") { _, _ -> Ai.stopRequested = true; AudioGen.stopRequested = true }.show() to label
    }

    private fun translateAll() {
        if (!Ai.enabled(this)) { Toast.makeText(this, "Ative a IA em Configurações", Toast.LENGTH_LONG).show(); return }
        val (dlg, label) = progressDialog("✦ Traduzindo o texto")
        Ai.translateRecording(this, base, onlyMissing = true,
            progress = { d, t -> label.text = "$d de $t frases" },
            done = { n, err ->
                if (dlg.isShowing) dlg.dismiss()
                Toast.makeText(this, err?.let { "Parou: $it ($n traduzidas)" } ?: "$n frases traduzidas", Toast.LENGTH_LONG).show()
                Prefs(this).readerTranslations = true
                render(); invalidateOptionsMenu()
            })
    }

    private fun createAudio() {
        val (dlg, label) = progressDialog("Criando o áudio")
        AudioGen.generate(this, "$base.txt", Prefs(this).ttsEngine, false,
            progress = { msg, d, t -> label.text = "$msg\n$d de $t frases" },
            done = { err, ok, total ->
                if (dlg.isShowing) dlg.dismiss()
                if (err == null) { Toast.makeText(this, "Áudio criado", Toast.LENGTH_LONG).show(); player?.release(); player = null; load(); return@generate }
                val b = AlertDialog.Builder(this).setTitle("O áudio não terminou").setMessage(err).setNegativeButton("Fechar", null)
                if (total > 0 && ok < total) b.setPositiveButton("Completar com a voz do celular") { _, _ ->
                    AudioGen.generate(this, "$base.txt", AudioGen.PHONE, true, { _, _, _ -> }, { e, _, _ ->
                        Toast.makeText(this, e ?: "Áudio criado", Toast.LENGTH_LONG).show(); if (e == null) load()
                    })
                }
                b.show()
            })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
