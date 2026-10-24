package com.keveny.legendajp

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
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
        title = Recordings.prettyName(name)

        listView = findViewById(R.id.lines)
        playerBar = findViewById(R.id.player)
        btnPlay = findViewById(R.id.btnPlay)
        seek = findViewById(R.id.seek)
        time = findViewById(R.id.time)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, pos, _ -> playFrom(lines[pos].sec) }

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
            runOnUiThread {
                fullText = text
                lines = parsed
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

    // ---------- Menu ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_viewer, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            R.id.action_share -> {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, fullText)
                startActivity(Intent.createChooser(send, getString(R.string.share)))
            }
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
            v.findViewById<TextView>(R.id.stamp).text = if (l.sec >= 0) CaptureService.formatTime(l.sec) else ""
            v.findViewById<TextView>(R.id.text).text = l.text
            v.setBackgroundColor(if (position == currentIndex) getColor(R.color.accent_soft) else 0)
            return v
        }
    }
}
