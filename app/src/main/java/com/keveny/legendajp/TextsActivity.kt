package com.keveny.legendajp

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/**
 * Textos para leitura: a lista (em pastas próprias, separadas das de Gravações) e as formas de trazer um texto
 * (colar, foto, galeria, arquivo .txt ou PDF, ou compartilhar de outro app para o Michi). Tocar abre o leitor;
 * toque longo: mover para pasta, selecionar vários, renomear, apagar.
 */
class TextsActivity : Activity() {

    private data class Row(val item: Recordings.Item, val sentences: Int, val preview: String, val title: String?) {
        val base get() = Recordings.baseName(item.name)
    }

    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var folders: LinearLayout
    private lateinit var selectBar: LinearLayout
    private lateinit var moveBtn: Button
    private var allRows: List<Row> = emptyList()
    private var rows: List<Row> = emptyList()
    private var folderMap: Map<String, Long> = emptyMap()
    private var folder = Folders.ALL
    private var selecting = false
    private val selected = HashSet<String>()
    private var photoUri: Uri? = null

    private val reqPhoto = 11
    private val reqGallery = 12
    private val reqFile = 13
    private val reqManga = 14

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Textos"
        folder = Prefs(this).folderTexts
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), 0) }
        val actions = FlowLayout(this)
        fun action(label: String, onClick: () -> Unit) = actions.addView(Button(this).apply {
            text = label; isAllCaps = false; textSize = 14f
            setBackgroundResource(R.drawable.bg_button_outline); setTextColor(getColor(R.color.text))
            minHeight = 0; minimumHeight = 0; setPadding(dp(14), dp(8), dp(14), dp(8))
            stateListAnimator = null
            setOnClickListener { onClick() }
        })
        action("Colar texto") { openEditor(text = "") }
        action("📷 Foto") { takePhoto() }
        action("Imagem da galeria") { startActivityForResult(Intent(MediaStore.ACTION_PICK_IMAGES), reqGallery) }
        action("Arquivo .txt / PDF") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "text/*", "application/pdf")), reqFile)
        }
        action("📖 Mangá (PDF)") { mangaLibrary() }
        root.addView(actions)
        root.addView(TextView(this).apply {
            text = "Também dá para selecionar um texto em outro app (navegador, notícias…) e usar Compartilhar → Michi."
            textSize = 12f; setTextColor(getColor(R.color.text_secondary)); setPadding(0, dp(6), 0, dp(8))
        })
        folders = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(folders); setPadding(0, 0, 0, dp(8)) })
        empty = TextView(this).apply {
            text = "Nenhum texto ainda."; textSize = 15f; setTextColor(getColor(R.color.text_secondary)); setPadding(0, dp(24), 0, 0)
        }
        list = ListView(this).apply {
            divider = null; dividerHeight = dp(10)
            clipToPadding = false; setPadding(0, dp(2), 0, dp(16))
        }
        root.addView(empty)
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        moveBtn = Button(this).apply { isAllCaps = false }
        val cancelBtn = Button(this).apply { text = "Cancelar"; isAllCaps = false; setOnClickListener { stopSelecting() } }
        selectBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(8)); visibility = View.GONE
            setBackgroundColor(getColor(R.color.card))
            addView(moveBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
            addView(cancelBtn)
        }
        root.addView(selectBar)
        setContentView(root)
        moveBtn.setOnClickListener {
            if (selected.isEmpty()) return@setOnClickListener
            Folders.moveDialog(this, Folders.TEXT, selected.toList()) { stopSelecting(); load() }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            val r = rows[pos]
            if (selecting) { toggle(r); return@setOnItemClickListener }
            startActivity(Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_NAME, r.item.name))
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val r = rows[pos]
            if (selecting) { toggle(r); return@setOnItemLongClickListener true }
            AlertDialog.Builder(this).setTitle(r.title ?: Recordings.prettyName(r.item.name))
                .setItems(arrayOf("Mover para pasta…", "Selecionar vários", "Renomear", "Apagar")) { _, which ->
                    when (which) {
                        0 -> Folders.moveDialog(this, Folders.TEXT, listOf(r.base)) { load() }
                        1 -> { startSelecting(); toggle(r) }
                        2 -> Recordings.renameDialog(this, r.item.name) { load() }
                        else -> AlertDialog.Builder(this).setMessage("Apagar este texto?").setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton("Apagar") { _, _ ->
                                Recordings.delete(this, r.item.uri)
                                Recordings.find(this, Recordings.baseName(r.item.name) + ".wav")?.let { Recordings.delete(this, it.uri) }
                                load()
                            }.show()
                    }
                }.show()
            true
        }
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onBackPressed() {
        if (selecting) stopSelecting() else super.onBackPressed()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(0, 1, 0, "Estudar a pasta inteira")
        menu.add(0, 2, 0, "Selecionar vários")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            1 -> if (folder > 0) startActivity(Folders.studyIntent(this, folder))
                 else Toast.makeText(this, "Escolha uma pasta sua em cima (não Tudo / Sem pasta)", Toast.LENGTH_LONG).show()
            2 -> startSelecting()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
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

    // ---------- pastas e seleção ----------

    private fun bindFolders() {
        Folders.bind(this, folders, Folders.TEXT, folder,
            counts = { id -> allRows.count { Folders.matches(id, it.base, folderMap) } },
            onSelect = { id -> folder = id; Prefs(this).folderTexts = id; applyFolder() },
            onChanged = { load() })
    }

    private fun applyFolder() {
        rows = allRows.filter { Folders.matches(folder, it.base, folderMap) }
        adapter.notifyDataSetChanged()
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        empty.text = if (allRows.isEmpty()) "Nenhum texto ainda." else "Nenhum texto nesta pasta.\nToque longo num texto para mover."
        bindFolders()
    }

    private fun startSelecting() {
        selecting = true
        selected.clear()
        selectBar.visibility = View.VISIBLE
        updateSelectBar()
    }

    private fun stopSelecting() {
        selecting = false
        selected.clear()
        selectBar.visibility = View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun toggle(r: Row) {
        if (!selected.remove(r.base)) selected.add(r.base)
        updateSelectBar()
        adapter.notifyDataSetChanged()
    }

    private fun updateSelectBar() {
        moveBtn.text = if (selected.isEmpty()) "Toque nos itens para marcar" else "Mover ${selected.size} para pasta…"
    }

    private fun load() {
        Thread {
            val items = Recordings.list(this, ".txt").filter { Texts.isText(it.name) }
            val r = items.map { item ->
                val paras = Texts.read(this, item.name)
                val all = paras.flatten()
                Row(item, all.size, all.take(2).joinToString("") { it.text }, Recordings.title(this, item.name))
            }
            val fm = Folders.map(this)
            runOnUiThread {
                allRows = r
                folderMap = fm
                applyFolder()
            }
        }.start()
    }

    private fun openEditor(text: String? = null, image: Uri? = null, title: String? = null, deleteImage: Boolean = false) {
        startActivity(Intent(this, TextEditActivity::class.java).apply {
            putExtra(TextEditActivity.EXTRA_TEXT, text)
            putExtra(TextEditActivity.EXTRA_TITLE, title)
            if (image != null) putExtra(TextEditActivity.EXTRA_IMAGE, image.toString())
            putExtra(TextEditActivity.EXTRA_DELETE_IMAGE, deleteImage)
            // Um texto criado dentro de uma pasta já entra nela
            if (folder > 0) putExtra(TextEditActivity.EXTRA_FOLDER, folder)
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

    /** Mangás já abertos (continua na página onde parou) e "abrir outro PDF". Toque longo tira da lista. */
    private fun mangaLibrary() {
        val list = MangaStore.all(this)
        val names = list.map { "${it.title}  ·  pág. ${it.page + 1}" + (if (it.pages > 0) "/${it.pages}" else "") } + "＋ Abrir um PDF de mangá…"
        val dlg = AlertDialog.Builder(this).setTitle("📖 Mangá")
            .setItems(names.toTypedArray()) { _, i ->
                if (i < list.size) startActivity(Intent(this, MangaActivity::class.java).putExtra(MangaActivity.EXTRA_URI, list[i].uri))
                else startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf"), reqManga)
            }
            .setNegativeButton("Fechar", null).create()
        dlg.setOnShowListener {
            dlg.listView?.setOnItemLongClickListener { _, _, pos, _ ->
                if (pos >= list.size) return@setOnItemLongClickListener false
                AlertDialog.Builder(this).setMessage("Tirar \"${list[pos].title}\" da lista? O PDF e o texto dos balões continuam onde estão.")
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton("Tirar") { _, _ -> MangaStore.remove(this, list[pos].uri); dlg.dismiss(); mangaLibrary() }.show()
                true
            }
        }
        dlg.show()
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
            reqManga -> if (resultCode == RESULT_OK) data?.data?.let { uri ->
                try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                val e = MangaStore.get(this, uri.toString()) ?: MangaStore.Entry(uri.toString(), MangaStore.displayName(this, uri), 0, 0, true, null)
                MangaStore.put(this, e)
                startActivity(Intent(this, MangaActivity::class.java).putExtra(MangaActivity.EXTRA_URI, uri.toString()))
            }
            reqFile -> if (resultCode == RESULT_OK) data?.data?.let { uri ->
                val isPdf = contentResolver.getType(uri) == "application/pdf" || uri.toString().lowercase().endsWith(".pdf")
                if (isPdf) {
                    startActivity(Intent(this, TextEditActivity::class.java).apply {
                        putExtra(TextEditActivity.EXTRA_PDF, uri.toString())
                        if (folder > 0) putExtra(TextEditActivity.EXTRA_FOLDER, folder)
                    })
                } else {
                    val t = try { contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } } catch (_: Exception) { null }
                    if (t != null) openEditor(text = t)
                }
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
            val on = selecting && r.base in selected
            v.findViewById<TextView>(R.id.title).text = (if (on) "☑ " else if (selecting) "☐ " else "") + (r.title ?: Recordings.prettyName(r.item.name))
            val parts = mutableListOf<String>()
            if (r.title != null) parts.add(Recordings.prettyName(r.item.name))
            parts.add("${r.sentences} frases")
            if (folder == Folders.ALL) folderMap[r.base]?.let { id -> Folders.name(this@TextsActivity, id)?.let { parts.add("📁 $it") } }
            v.findViewById<TextView>(R.id.subtitle).text = parts.joinToString(" · ")
            v.findViewById<TextView>(R.id.preview).text = r.preview
            v.alpha = if (selecting && !on) 0.6f else 1f
            return v
        }
    }
}
