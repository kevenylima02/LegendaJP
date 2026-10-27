package com.keveny.legendajp

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView

/** Lista as transcrições salvas em Download/LegendaJP. */
class RecordingsActivity : Activity() {

    private data class Row(val item: Recordings.Item, val lines: Int, val preview: String, val hasWav: Boolean, val title: String?)

    private lateinit var list: ListView
    private lateinit var empty: TextView
    private var rows: List<Row> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recordings)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.recordings)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            val r = rows[pos]
            startActivity(
                Intent(this, ViewerActivity::class.java)
                    .putExtra(ViewerActivity.EXTRA_URI, r.item.uri)
                    .putExtra(ViewerActivity.EXTRA_NAME, r.item.name)
            )
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val row = rows[pos]
            AlertDialog.Builder(this).setTitle(row.title ?: Recordings.prettyName(row.item.name))
                .setItems(arrayOf("Renomear", "Apagar")) { _, which ->
                    if (which == 0) Recordings.renameDialog(this, row.item.name) { load() } else confirmDelete(row)
                }
                .show()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(0, 1, 0, "Importar texto")
        menu.add(0, 2, 0, "Formato do texto (modelo)")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            1 -> importDialog()
            2 -> showTemplate()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ---------- importar texto ----------

    private var importInput: android.widget.EditText? = null
    private val pickFile = 7

    private fun importDialog() {
        val d = resources.displayMetrics.density
        val input = android.widget.EditText(this).apply {
            hint = "Cole aqui o texto (formato Michi, legenda .srt ou japonês simples)"
            minLines = 6
            gravity = android.view.Gravity.TOP
            textSize = 15f
        }
        importInput = input
        val pick = android.widget.Button(this).apply {
            text = "Escolher arquivo (.txt / .srt)"
            isAllCaps = false
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "application/x-subrip", "application/octet-stream")), pickFile)
            }
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
            addView(pick); addView(input)
        }
        AlertDialog.Builder(this).setTitle("Importar texto").setView(android.widget.ScrollView(this).apply { addView(box) })
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton("Modelo") { _, _ -> showTemplate() }
            .setPositiveButton("Importar") { _, _ -> doImport(input.text.toString()) }
            .show()
    }

    @Deprecated("Activity simples sem AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickFile || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val text = try { contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } } catch (_: Exception) { null }
        if (text != null) importInput?.setText(text)
    }

    private fun doImport(text: String) {
        val parsed = try { ImportText.parse(text) } catch (_: Exception) { null }
        if (parsed == null || parsed.lines.isEmpty()) {
            android.widget.Toast.makeText(this, "Não achei frases no texto", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            val res = try { ImportText.save(this, parsed) } catch (e: Exception) { null.also { _ -> runOnUiThread {
                android.widget.Toast.makeText(this, "Não consegui importar: ${e.message}", android.widget.Toast.LENGTH_LONG).show() } } }
            val item = res?.let { Recordings.find(this, it) }
            runOnUiThread {
                if (item == null) return@runOnUiThread
                load()
                val sp = parsed.speakers.size
                AlertDialog.Builder(this).setTitle("Importado")
                    .setMessage("${parsed.lines.size} frases" + (if (sp > 1) ", $sp locutores" else "") +
                        (if (parsed.lines.any { it.pt != null }) ", com tradução" else "") + ".\n\nGerar o áudio agora?")
                    .setNegativeButton("Depois") { _, _ -> openViewer(item, false) }
                    .setPositiveButton("Gerar áudio") { _, _ -> openViewer(item, true) }
                    .show()
            }
        }.start()
    }

    private fun openViewer(item: Recordings.Item, gen: Boolean) {
        startActivity(
            Intent(this, ViewerActivity::class.java)
                .putExtra(ViewerActivity.EXTRA_URI, item.uri)
                .putExtra(ViewerActivity.EXTRA_NAME, item.name)
                .putExtra(ViewerActivity.EXTRA_GEN_AUDIO, gen)
        )
    }

    private fun showTemplate() {
        val tv = android.widget.TextView(this).apply {
            text = ImportText.PROMPT
            textSize = 13f
            setTextIsSelectable(true)
            setTextColor(getColor(R.color.text))
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        AlertDialog.Builder(this).setTitle("Formato do texto").setView(android.widget.ScrollView(this).apply { addView(tv) })
            .setNegativeButton("Fechar", null)
            .setPositiveButton("Copiar") { _, _ ->
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Formato Michi", ImportText.PROMPT))
                android.widget.Toast.makeText(this, "Instruções copiadas: cole na IA junto com o seu texto", android.widget.Toast.LENGTH_LONG).show()
            }.show()
    }

    private fun load() {
        Thread {
            val wavs = Recordings.list(this, ".wav").map { Recordings.baseName(it.name) }.toSet()
            val result = Recordings.list(this, ".txt").filter { !Texts.isText(it.name) }.map { item ->
                val text = try { Recordings.readText(this, item.uri) } catch (_: Exception) { "" }
                val lines = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }
                val preview = lines.take(2).joinToString(" ") { Recordings.parseLine(it).second }
                Row(item, lines.size, preview, Recordings.baseName(item.name) in wavs, Recordings.title(this, item.name))
            }
            runOnUiThread {
                rows = result
                adapter.notifyDataSetChanged()
                empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            }
        }.start()
    }

    private fun confirmDelete(row: Row) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_confirm) + "\n" + (row.title?.let { "$it · " } ?: "") + Recordings.prettyName(row.item.name))
            .setPositiveButton(R.string.menu_delete) { _, _ ->
                Recordings.delete(this, row.item.uri)
                Recordings.find(this, Recordings.baseName(row.item.name) + ".wav")?.let { Recordings.delete(this, it.uri) }
                load()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_recording, parent, false)
            val r = rows[position]
            v.findViewById<TextView>(R.id.title).text = r.title ?: Recordings.prettyName(r.item.name)
            val parts = mutableListOf("${r.lines} frases", Recordings.prettySize(r.item.size))
            if (r.title != null) parts.add(0, Recordings.prettyName(r.item.name))
            if (r.hasWav) parts.add("com áudio")
            v.findViewById<TextView>(R.id.subtitle).text = parts.joinToString(" · ")
            v.findViewById<TextView>(R.id.preview).text = r.preview
            return v
        }
    }
}
