package com.keveny.legendajp

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.text.method.LinkMovementMethod
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/** Mostra uma transcrição; se houver .wav, toca o áudio e pula para a frase tocada. */
class ViewerActivity : Activity() {

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_NAME = "name"
        const val EXTRA_SEEK = "seek"
    }

    private data class Line(val sec: Int, val text: String)

    private lateinit var txtUri: Uri
    private lateinit var name: String
    private var wavUri: Uri? = null
    private var lines: List<Line> = emptyList()
    private var fullText = ""

    private lateinit var listView: ListView
    private lateinit var playerBar: View
    private lateinit var btnPlay: Button
    private lateinit var seek: SeekBar
    private lateinit var time: TextView

    private var player: MediaPlayer? = null
    private var prepared = false
    private var pendingSeek = -1
    private var currentIndex = -1
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (prepared) {
                try {
                    val pos = p.currentPosition
                    seek.progress = pos
                    time.text = CaptureService.formatTime(pos / 1000)
                    highlight(pos / 1000)
                    btnPlay.text = if (p.isPlaying) "❚❚" else "▶"
                } catch (_: IllegalStateException) {
                    return
                }
            }
            ui.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        txtUri = intent.getParcelableExtra(EXTRA_URI, Uri::class.java) ?: run { finish(); return }
        name = intent.getStringExtra(EXTRA_NAME) ?: ""
        pendingSeek = intent.getIntExtra(EXTRA_SEEK, -1)
        title = Recordings.prettyName(name)

        listView = findViewById(R.id.lines)
        playerBar = findViewById(R.id.player)
        btnPlay = findViewById(R.id.btnPlay)
        seek = findViewById(R.id.seek)
        time = findViewById(R.id.time)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, pos, _ -> playFrom(lines[pos].sec) }
        listView.setOnItemLongClickListener { _, _, pos, _ -> showGrammar(lines[pos].text); true }

        btnPlay.setOnClickListener {
            val p = player ?: return@setOnClickListener
            if (!prepared) return@setOnClickListener
            try { if (p.isPlaying) p.pause() else p.start() } catch (_: IllegalStateException) {}
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) time.text = CaptureService.formatTime(progress / 1000)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                if (prepared) try { player?.seekTo(sb.progress) } catch (_: IllegalStateException) {}
            }
        })

        load()
    }

    private fun load() {
        Thread {
            val text = try { Recordings.readText(this, txtUri) } catch (_: Exception) { "" }
            val parsed = text.lines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { val (s, t) = Recordings.parseLine(it); Line(s, t) }
            val wav = Recordings.find(this, Recordings.baseName(name) + ".wav")?.uri
            val db = VocabDb.get(this)
            val tr = parsed.map { it.text }.filter { db.aiTranslation(it) != null || db.translation(it) != null }.toHashSet()
            runOnUiThread {
                fullText = text
                lines = parsed
                translated = tr
                adapter.notifyDataSetChanged()
                wavUri = wav
                if (wav != null) setupPlayer(wav)
                else findViewById<TextView>(R.id.hint).text = "Sem áudio salvo para esta transcrição."
            }
        }.start()
    }

    private fun setupPlayer(uri: Uri) {
        val p = MediaPlayer()
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        p.setOnPreparedListener {
            prepared = true
            seek.max = p.duration
            playerBar.visibility = View.VISIBLE
            ui.post(tick)
            if (pendingSeek >= 0) { playFrom(pendingSeek); pendingSeek = -1 }
        }
        p.setOnCompletionListener { btnPlay.text = "▶" }
        p.setOnErrorListener { _, what, extra ->
            // -38 = chamada em estado inválido (ex.: activity recriada); não é erro do arquivo
            if (!prepared && what != -38) {
                Toast.makeText(this, "Não consegui tocar o áudio ($what/$extra)", Toast.LENGTH_SHORT).show()
            }
            true
        }
        try {
            p.setDataSource(this, uri)
            p.prepareAsync()
            player = p
        } catch (e: Exception) {
            Toast.makeText(this, "Não consegui abrir o áudio: ${e.message}", Toast.LENGTH_SHORT).show()
            p.release()
        }
    }

    private fun playFrom(sec: Int) {
        val p = player
        if (p == null || !prepared) {
            if (wavUri == null) Toast.makeText(this, "Esta transcrição não tem áudio salvo", Toast.LENGTH_SHORT).show()
            return
        }
        if (sec < 0) return
        try {
            p.seekTo(maxOf(0, sec * 1000 - 500))
            p.start()
        } catch (_: IllegalStateException) {}
    }

    private fun highlight(sec: Int) {
        var idx = -1
        for (i in lines.indices) if (lines[i].sec in 0..sec) idx = i else if (lines[i].sec > sec) break
        if (idx != currentIndex) {
            currentIndex = idx
            adapter.notifyDataSetChanged()
            if (idx >= 0 && (idx < listView.firstVisiblePosition || idx > listView.lastVisiblePosition)) {
                listView.smoothScrollToPosition(idx)
            }
        }
    }

    override fun onDestroy() {
        ui.removeCallbacks(tick)
        player?.release()
        player = null
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        if (prepared) try { player?.pause() } catch (_: IllegalStateException) {}
    }

    /** Pontos gramaticais da frase (toque longo). */
    private fun showGrammar(sentence: String) {
        val hits = try { Grammar.find(sentence) } catch (_: Exception) { emptyList() }
        val sb = android.text.SpannableStringBuilder()
        if (hits.isEmpty()) {
            sb.append("Nenhum padrão do catálogo foi encontrado nesta frase.")
        } else {
            for ((i, h) in hits.withIndex()) {
                if (i > 0) sb.append("\n\n")
                val start = sb.length
                sb.append(h.point.name).append("  ").append(h.point.level)
                sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, sb.length, 0)
                sb.append("\n").append(h.point.explanation)
                sb.append("\nEx.: ").append(h.point.example)
            }
        }
        val tv = TextView(this).apply {
            text = sb
            textSize = 15f
            setLineSpacing(0f, 1.15f)
            setTextColor(getColor(R.color.text))
            setPadding(dp(24), dp(16), dp(24), dp(8))
            setTextIsSelectable(true)
        }
        val head = TextView(this).apply {
            text = Jp.highlight(sentence, VocabDb.studyKeys, getColor(R.color.saved_word))
            textSize = 20f
            setTextColor(getColor(R.color.text))
            setPadding(dp(24), dp(20), dp(24), dp(4))
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(head); addView(tv)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(box) }
        AlertDialog.Builder(this).setTitle("Gramática da frase").setView(scroll)
            .setPositiveButton("Fechar", null).show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Frases que já têm alguma tradução (aparecem embaixo da frase). */
    private var translated: Set<String> = emptySet()

    /** ✦ Traduz a gravação inteira com o Gemini, em partes, com progresso. Nada do ML Kit é apagado. */
    private fun translateAll() {
        if (!Ai.enabled(this)) {
            Toast.makeText(this, "Ative a IA em Configurações → Inteligência artificial", Toast.LENGTH_LONG).show()
            return
        }
        val db = VocabDb.get(this)
        val texts = lines.map { it.text }.distinct()
        val have = texts.count { db.aiTranslation(it) != null }
        val missing = texts.size - have
        val b = AlertDialog.Builder(this).setTitle("✦ Traduzir com ${Ai.modelLabel(this)}")
            .setMessage(
                "${texts.size} frases · $have já traduzidas pelo Gemini.\n\n" +
                    "A conversa inteira vai como contexto. As traduções do ML Kit continuam salvas: " +
                    "toque numa tradução para alternar entre as duas."
            )
            .setNegativeButton(android.R.string.cancel, null)
        if (missing > 0) b.setPositiveButton("Traduzir $missing") { _, _ -> runTranslate(onlyMissing = true) }
        if (have > 0) b.setNeutralButton("Refazer todas") { _, _ -> runTranslate(onlyMissing = false) }
        b.show()
    }

    private fun runTranslate(onlyMissing: Boolean) {
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        val label = TextView(this).apply { text = "Preparando…"; setTextColor(getColor(R.color.text)); textSize = 14f }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
            addView(label); addView(bar)
        }
        val dlg = AlertDialog.Builder(this).setTitle("✦ Traduzindo a gravação").setView(box).setCancelable(false)
            .setNegativeButton("Parar") { _, _ -> Ai.stopRequested = true }
            .show()
        Ai.translateRecording(this, Recordings.baseName(name), onlyMissing,
            progress = { done, total ->
                bar.isIndeterminate = false
                bar.max = maxOf(1, total)
                bar.progress = done
                label.text = "$done de $total frases"
            },
            done = { n, err ->
                if (dlg.isShowing) dlg.dismiss()
                Toast.makeText(this, if (err != null) "Parou: $err ($n traduzidas)" else "$n frases traduzidas pelo Gemini", Toast.LENGTH_LONG).show()
                refreshTranslations()
            })
    }

    private fun refreshTranslations() {
        Thread {
            val db = VocabDb.get(this)
            val tr = lines.map { it.text }.filter { db.aiTranslation(it) != null || db.translation(it) != null }.toHashSet()
            runOnUiThread { translated = tr; adapter.notifyDataSetChanged() }
        }.start()
    }

    // ---------- Menu ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_viewer, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            R.id.action_study -> {
                startActivity(
                    Intent(this, StudyMenuActivity::class.java)
                        .putExtra(StudyActivity.EXTRA_URI, txtUri)
                        .putExtra(StudyActivity.EXTRA_NAME, name)
                )
            }
            R.id.action_share -> {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, fullText)
                startActivity(Intent.createChooser(send, getString(R.string.share)))
            }
            R.id.action_translate_ai -> translateAll()
            R.id.action_copy -> {
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("LegendaJP", fullText))
                Toast.makeText(this, "Texto copiado", Toast.LENGTH_SHORT).show()
            }
            R.id.action_delete -> {
                AlertDialog.Builder(this)
                    .setMessage(R.string.delete_confirm)
                    .setPositiveButton(R.string.menu_delete) { _, _ ->
                        player?.release(); player = null
                        Recordings.delete(this, txtUri)
                        wavUri?.let { Recordings.delete(this, it) }
                        finish()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = lines.size
        override fun getItem(position: Int) = lines[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_line, parent, false)
            val l = lines[position]
            val stamp = v.findViewById<TextView>(R.id.stamp)
            stamp.text = if (l.sec >= 0) CaptureService.formatTime(l.sec) else ""
            stamp.setOnClickListener { playFrom(l.sec) }
            val tv = v.findViewById<TextView>(R.id.text)
            tv.movementMethod = LinkMovementMethod.getInstance()
            tv.highlightColor = Color.TRANSPARENT
            tv.text = Jp.decorate(l.text, VocabDb.studyKeys, getColor(R.color.saved_word)) { w ->
                WordCard.show(
                    this@ViewerActivity, w, l.text, Recordings.baseName(name), maxOf(0, l.sec),
                    onPlay = if (wavUri != null) ({ playFrom(l.sec) }) else null,
                    onChanged = { notifyDataSetChanged() }
                )
            }
            // Toque longo (no texto ou no tempo) mostra a gramática da frase
            val longPress = View.OnLongClickListener {
                Jp.suppressTapUntil = android.os.SystemClock.uptimeMillis() + 1000
                showGrammar(l.text); true
            }
            tv.setOnLongClickListener(longPress)
            stamp.setOnLongClickListener(longPress)
            // Toque fora de uma palavra toca o trecho
            tv.setOnClickListener {
                if (android.os.SystemClock.uptimeMillis() - Jp.lastWordTap > 400) playFrom(l.sec)
            }
            // Tradução embaixo da frase, quando já existe (tocar alterna ML Kit ⇄ Gemini)
            val trv = v.findViewById<TextView>(R.id.tr)
            if (l.text in translated) {
                trv.visibility = View.VISIBLE
                TrSwitch.bind(this@ViewerActivity, trv, l.text)
            } else {
                trv.visibility = View.GONE
                trv.setOnClickListener(null)
            }
            v.setBackgroundColor(if (position == currentIndex) getColor(R.color.accent_soft) else 0)
            return v
        }
    }
}
