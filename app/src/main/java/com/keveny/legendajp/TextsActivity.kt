package com.keveny.legendajp

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/**
 * Textos para leitura: a lista e as formas de trazer um texto (colar, foto, galeria, arquivo ou
 * compartilhar de outro app para o Michi). Tocar abre o leitor.
 */
class TextsActivity : Activity() {

    private data class Row(val item: Recordings.Item, val sentences: Int, val preview: String, val title: String?)

    private lateinit var list: ListView
    private lateinit var empty: TextView
    private var rows: List<Row> = emptyList()
    private var photoUri: Uri? = null

    private val reqPhoto = 11
    private val reqGallery = 12
    private val reqFile = 13

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Textos"
        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((16 * d).toInt(), (4 * d).toInt(), (16 * d).toInt(), (16 * d).toInt()) }
        val actions = FlowLayout(this)
        fun action(label: String, onClick: () -> Unit) = actions.addView(Button(this).apply {
            text = label; isAllCaps = false; textSize = 14f
            setBackgroundResource(R.drawable.bg_button_outline); setTextColor(getColor(R.color.text))
            minHeight = 0; minimumHeight = 0; setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
            stateListAnimator = null
            setOnClickListener { onClick() }
        })
        action("Colar texto") { openEditor(text = "") }
        action("📷 Foto") { takePhoto() }
        action("Imagem da galeria") { startActivityForResult(Intent(MediaStore.ACTION_PICK_IMAGES), reqGallery) }
        action("Arquivo .txt") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/*"), reqFile)
        }
        root.addView(actions)
        root.addView(TextView(this).apply {
            text = "Também dá para selecionar um texto em outro app (navegador, notícias…) e usar Compartilhar → Michi."
            textSize = 12f; setTextColor(getColor(R.color.text_secondary)); setPadding(0, (6 * d).toInt(), 0, (8 * d).toInt())
        })
        empty = TextView(this).apply {
            text = "Nenhum texto ainda."; textSize = 15f; setTextColor(getColor(R.color.text_secondary)); setPadding(0, (24 * d).toInt(), 0, 0)
        }
        list = ListView(this).apply { divider = null }
        root.addView(empty)
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            startActivity(Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_NAME, rows[pos].item.name))
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val r = rows[pos]
            AlertDialog.Builder(this).setTitle(r.title ?: Recordings.prettyName(r.item.name))
                .setItems(arrayOf("Renomear", "Apagar")) { _, which ->
                    if (which == 0) Recordings.renameDialog(this, r.item.name) { load() }
                    else AlertDialog.Builder(this).setMessage("Apagar este texto?").setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton("Apagar") { _, _ ->
                            Recordings.delete(this, r.item.uri)
                            Recordings.find(this, Recordings.baseName(r.item.name) + ".wav")?.let { Recordings.delete(this, it.uri) }
                            load()
                        }.show()
                }.show()
            true
        }
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Texto compartilhado de outro app. */
    private fun handleShare(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val t = i.getStringExtra(Intent.EXTRA_TEXT)
            if (!t.isNullOrBlank()) openEditor(text = t, title = i.getStringExtra(Intent.EXTRA_SUBJECT))
            i.action = null
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        Thread {
            val items = Recordings.list(this, ".txt").filter { Texts.isText(it.name) }
            val r = items.map { item ->
                val paras = Texts.read(this, item.name)
                val all = paras.flatten()
                Row(item, all.size, all.take(2).joinToString("") { it.text }, Recordings.title(this, item.name))
            }
            runOnUiThread {
                rows = r
                adapter.notifyDataSetChanged()
                empty.visibility = if (r.isEmpty()) View.VISIBLE else View.GONE
            }
        }.start()
    }

    private fun openEditor(text: String? = null, image: Uri? = null, title: String? = null, deleteImage: Boolean = false) {
        startActivity(Intent(this, TextEditActivity::class.java).apply {
            putExtra(TextEditActivity.EXTRA_TEXT, text)
            putExtra(TextEditActivity.EXTRA_TITLE, title)
            if (image != null) putExtra(TextEditActivity.EXTRA_IMAGE, image.toString())
            putExtra(TextEditActivity.EXTRA_DELETE_IMAGE, deleteImage)
        })
    }

    /** Foto pela câmera do celular; a imagem fica temporária em Pictures/Michi e é apagada depois. */
    private fun takePhoto() {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "michi_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Michi")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) { Toast.makeText(this, "Não consegui preparar a foto", Toast.LENGTH_SHORT).show(); return }
        photoUri = uri
        try {
            startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri), reqPhoto)
        } catch (_: Exception) {
            contentResolver.delete(uri, null, null)
            Toast.makeText(this, "Nenhum app de câmera encontrado", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Activity simples sem AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            reqPhoto -> {
                val u = photoUri ?: return
                if (resultCode == RESULT_OK) openEditor(image = u, deleteImage = true)
                else try { contentResolver.delete(u, null, null) } catch (_: Exception) {}
            }
            reqGallery -> if (resultCode == RESULT_OK) data?.data?.let { openEditor(image = it) }
            reqFile -> if (resultCode == RESULT_OK) data?.data?.let { uri ->
                val t = try { contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } } catch (_: Exception) { null }
                if (t != null) openEditor(text = t)
            }
        }
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_recording, parent, false)
            val r = rows[position]
            v.findViewById<TextView>(R.id.title).text = r.title ?: Recordings.prettyName(r.item.name)
            v.findViewById<TextView>(R.id.subtitle).text = listOfNotNull(
                if (r.title != null) Recordings.prettyName(r.item.name) else null, "${r.sentences} frases"
            ).joinToString(" · ")
            v.findViewById<TextView>(R.id.preview).text = r.preview
            return v
        }
    }
}
