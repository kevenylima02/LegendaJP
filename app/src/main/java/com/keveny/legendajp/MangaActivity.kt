package com.keveny.legendajp

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.InputType
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject

/** Mangás abertos (PDF), com a página onde você parou e o texto onde vão os balões lidos. */
object MangaStore {
    class Entry(val uri: String, var title: String, var page: Int, var pages: Int, var rtl: Boolean, var base: String?)

    private fun sp(ctx: Context) = ctx.getSharedPreferences("manga", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Entry> {
        val arr = try { JSONArray(sp(ctx).getString("list", "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Entry(o.optString("uri"), o.optString("title"), o.optInt("page"), o.optInt("pages"), o.optBoolean("rtl", true),
                o.optString("base").ifBlank { null })
        }
    }

    private fun write(ctx: Context, list: List<Entry>) {
        val arr = JSONArray()
        for (e in list) arr.put(JSONObject().put("uri", e.uri).put("title", e.title).put("page", e.page).put("pages", e.pages)
            .put("rtl", e.rtl).put("base", e.base ?: ""))
        sp(ctx).edit().putString("list", arr.toString()).apply()
    }

    fun get(ctx: Context, uri: String): Entry? = all(ctx).firstOrNull { it.uri == uri }

    /** Guarda (e põe no topo da lista). */
    fun put(ctx: Context, e: Entry) = write(ctx, listOf(e) + all(ctx).filter { it.uri != e.uri })

    fun remove(ctx: Context, uri: String) = write(ctx, all(ctx).filter { it.uri != uri })

    fun displayName(ctx: Context, uri: Uri): String = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) { null }?.removeSuffix(".pdf")?.removeSuffix(".PDF") ?: "Mangá"
}

/**
 * Leitor de mangá em PDF: uma página por vez (PdfRenderer), deslizar ou tocar nas bordas para
 * trocar (sentido japonês por padrão), pinça e toque duplo para ampliar.
 *
 * Barra: ✋ navegar · ✨ varinha (toque num balão: OCR do celular na página e, se não achar ou você
 * pedir, ✦ Gemini só no recorte em volta do toque) · ✦ página inteira (todos os balões, numerados
 * na imagem). Cada balão lido abre um quadro com o texto (palavras tocáveis para salvar), tradução,
 * gramática e ✦ Palavras (IA); e vai para o texto "Mangá · título", então Estudar funciona nele.
 */
class MangaActivity : Activity() {

    companion object {
        const val EXTRA_URI = "uri"
        private const val HAND = "hand"
        private const val WAND = "wand"
        private const val OCR_TIMEOUT_MS = 8000L
    }

    private lateinit var entry: MangaStore.Entry
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var page = 0
    private var bitmap: Bitmap? = null
    private var ocrPage = -1
    private var ocrText: Text? = null
    private var bubbles: List<MangaAi.Bubble> = emptyList()
    private var tool = WAND
    private lateinit var view: MangaPageView
    private lateinit var toolbar: LinearLayout
    private lateinit var counter: TextView
    private val toolButtons = HashMap<String, TextView>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        val u = intent.getStringExtra(EXTRA_URI) ?: run { finish(); return }
        entry = MangaStore.get(this, u) ?: MangaStore.Entry(u, MangaStore.displayName(this, Uri.parse(u)), 0, 0, true, null)
        title = entry.title
        tool = getSharedPreferences("manga", MODE_PRIVATE).getString("tool", WAND) ?: WAND

        val root = FrameLayout(this)
        view = MangaPageView(this).apply { background0 = getColor(R.color.background) }
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        buildToolbar(root)
        setContentView(root)

        view.onSwipe = { dir -> if ((dir > 0) == entry.rtl) go(page + 1) else go(page - 1) }
        view.onTap = { bx, by, sx, sy -> onTap(bx, by, sx, sy) }

        try {
            pfd = contentResolver.openFileDescriptor(Uri.parse(u), "r")
            renderer = PdfRenderer(pfd!!)
        } catch (e: Exception) {
            Toast.makeText(this, "Não consegui abrir o PDF: ${e.message}", Toast.LENGTH_LONG).show()
            MangaStore.remove(this, u); finish(); return
        }
        entry.pages = renderer!!.pageCount
        MangaStore.put(this, entry)
        show(entry.page.coerceIn(0, maxOf(0, entry.pages - 1)))
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.execute { try { renderer?.close() } catch (_: Exception) {}; try { pfd?.close() } catch (_: Exception) {} }
        worker.shutdown()
    }

    // ---------- páginas ----------

    private fun go(p: Int) {
        if (p < 0) { Toast.makeText(this, "Primeira página", Toast.LENGTH_SHORT).show(); return }
        if (p >= entry.pages) { Toast.makeText(this, "Fim do mangá", Toast.LENGTH_SHORT).show(); return }
        show(p)
    }

    private fun pageKey(p: Int = page) = "${entry.uri}|$p"

    private fun show(p: Int) {
        page = p
        entry.page = p
        MangaStore.put(this, entry)
        counter.text = "${p + 1}/${entry.pages}"
        bubbles = emptyList(); view.markers = emptyList(); view.tapMark = null
        val w = minOf(resources.displayMetrics.widthPixels * 2, 1800)
        worker.execute {
            val r = renderer ?: return@execute
            val bmp = try {
                r.openPage(p).use { pg ->
                    val h = (pg.height.toLong() * w / maxOf(1, pg.width)).toInt().coerceIn(1, 4000)
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        pg.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            } catch (e: Exception) { null }
            val cached = MangaAi.cachedPage(this, pageKey(p))
            runOnUiThread {
                if (isFinishing || page != p) { bmp?.recycle(); return@runOnUiThread }
                val old = bitmap
                bitmap = bmp
                view.bitmap = bmp
                if (old != null && old !== bmp) old.recycle()
                ocrPage = -1; ocrText = null
                if (bmp == null) Toast.makeText(this, "Não consegui desenhar a página ${p + 1}", Toast.LENGTH_SHORT).show()
                if (cached != null) setBubbles(cached)
            }
        }
    }

    private fun setBubbles(list: List<MangaAi.Bubble>) {
        bubbles = list
        val b = bitmap ?: return
        view.markers = list.mapIndexedNotNull { i, x ->
            if (x.x < 0 || x.y < 0) null else MangaPageView.Marker(x.x * b.width, x.y * b.height, "${i + 1}")
        }
    }

    // ---------- toque ----------

    private fun onTap(bx: Float, by: Float, sx: Float, sy: Float) {
        val b = bitmap ?: return
        // Balão numerado pelo ✦: abre direto
        val near = bubbles.withIndex().filter { it.value.x >= 0 }
            .minByOrNull { view.screenDistance(it.value.x * b.width, it.value.y * b.height, sx, sy) }
        if (near != null && view.screenDistance(near.value.x * b.width, near.value.y * b.height, sx, sy) < dp(26)) {
            showBubble(near.value.text, near.value.translation, "✦ balão ${near.index + 1} da página")
            return
        }
        if (tool == HAND) {
            val third = view.width / 4f
            when {
                sx < third -> if (entry.rtl) go(page + 1) else go(page - 1)
                sx > view.width - third -> if (entry.rtl) go(page - 1) else go(page + 1)
            }
            return
        }
        if (bx < 0 || by < 0 || bx > b.width || by > b.height) return
        view.tapMark = bx to by
        extractAt(bx, by)
    }

    /** Varinha: OCR do celular na página (uma vez por página); sem bloco no ponto → ✦ Gemini no recorte. */
    private fun extractAt(bx: Float, by: Float) {
        val b = bitmap ?: return
        val done = ocrText
        if (ocrPage == page && done != null) { pickBlock(done, bx, by); return }
        val p = page
        val dlg = progress("Lendo a página no celular…")
        var finished = false
        val timeout = Runnable {
            if (finished) return@Runnable
            finished = true; dlg.dismiss()
            gemini(bx, by, "o leitor do celular não respondeu")
        }
        ui.postDelayed(timeout, OCR_TIMEOUT_MS)
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()).process(InputImage.fromBitmap(b, 0))
            .addOnSuccessListener { t ->
                if (finished) return@addOnSuccessListener
                finished = true; ui.removeCallbacks(timeout); dlg.dismiss()
                if (page != p) return@addOnSuccessListener
                ocrPage = p; ocrText = t
                pickBlock(t, bx, by)
            }
            .addOnFailureListener { e ->
                if (finished) return@addOnFailureListener
                finished = true; ui.removeCallbacks(timeout); dlg.dismiss()
                gemini(bx, by, "o leitor do celular falhou (${e.message})")
            }
    }

    private fun pickBlock(t: Text, bx: Float, by: Float) {
        val b = bitmap ?: return
        val pad = (b.width * 0.015f).toInt()
        val inside = t.textBlocks.filter { blk ->
            val r = blk.boundingBox ?: return@filter false
            Rect(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad).contains(bx.toInt(), by.toInt())
        }
        val blk = inside.minByOrNull { (it.boundingBox!!.width() * it.boundingBox!!.height()) }
            ?: t.textBlocks.filter { it.boundingBox != null }.minByOrNull { kotlin.math.hypot(it.boundingBox!!.exactCenterX() - bx, it.boundingBox!!.exactCenterY() - by) }
                ?.takeIf { kotlin.math.hypot(it.boundingBox!!.exactCenterX() - bx, it.boundingBox!!.exactCenterY() - by) < b.width * 0.08f }
        val text = blk?.lines?.joinToString("") { it.text }?.replace(" ", "")?.trim().orEmpty()
        if (text.isEmpty() || !Jp.isLexical(text)) { gemini(bx, by, "o celular não achou texto aí"); return }
        showBubble(text, null, "lido no celular", retry = { gemini(bx, by, null) })
    }

    /** ✦ Gemini só no recorte em volta do toque (com o ponto marcado em vermelho). */
    private fun gemini(bx: Float, by: Float, why: String?) {
        val b = bitmap ?: return
        if (!Ai.enabled(this)) {
            Toast.makeText(this, (why?.let { "$it. " } ?: "") + "Ative a IA em Configurações para ler com o Gemini.", Toast.LENGTH_LONG).show(); return
        }
        val side = (b.width * 0.5f).toInt().coerceAtMost(minOf(b.width, b.height))
        val left = (bx - side / 2f).toInt().coerceIn(0, b.width - side)
        val top = (by - side / 2f).toInt().coerceIn(0, b.height - side)
        val crop = Bitmap.createBitmap(b, left, top, side, side).copy(Bitmap.Config.ARGB_8888, true)
        Canvas(crop).drawCircle(bx - left, by - top, side * 0.06f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = side * 0.012f; color = Color.RED })
        val grid = 20
        val key = "${pageKey()}|${(bx / b.width * grid).toInt()}|${(by / b.height * grid).toInt()}"
        val dlg = progress("✦ Lendo o balão com ${Ai.modelLabel(this)}…")
        MangaAi.bubble(this, crop, key) { res, err ->
            crop.recycle()
            if (dlg.isShowing) dlg.dismiss()
            if (isFinishing) return@bubble
            if (res == null) { Toast.makeText(this, "Não consegui ler: ${err ?: "sem texto"}", Toast.LENGTH_LONG).show(); return@bubble }
            showBubble(res.text, res.translation, "✦ lido pelo Gemini")
        }
    }

    /** ✦ Página inteira: todos os balões, numerados na imagem; lista para tocar. */
    private fun readPage() {
        if (bubbles.isNotEmpty()) { listBubbles(); return }
        val b = bitmap ?: return
        if (!Ai.enabled(this)) { Toast.makeText(this, "Ative a IA em Configurações", Toast.LENGTH_LONG).show(); return }
        val p = page
        val dlg = progress("✦ Lendo a página ${p + 1} com ${Ai.modelLabel(this)}…")
        MangaAi.page(this, b, pageKey(p)) { list, err ->
            if (dlg.isShowing) dlg.dismiss()
            if (isFinishing || page != p) return@page
            if (list.isNullOrEmpty()) { Toast.makeText(this, "Não consegui: ${err ?: "sem balões nesta página"}", Toast.LENGTH_LONG).show(); return@page }
            setBubbles(list)
            listBubbles()
        }
    }

    private fun listBubbles() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        var dlg: AlertDialog? = null
        for ((i, bb) in bubbles.withIndex()) {
            box.addView(TextView(this).apply {
                text = "${i + 1}. ${bb.text}\n${bb.translation}"
                textSize = 16f; setTextColor(getColor(R.color.text)); setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { dlg?.dismiss(); showBubble(bb.text, bb.translation, "✦ balão ${i + 1} da página") }
            })
        }
        box.addView(TextView(this).apply {
            text = "Os números também aparecem na página: toque num deles para abrir o balão."
            textSize = 12f; setTextColor(getColor(R.color.text_secondary)); setPadding(0, dp(8), 0, 0)
        })
        dlg = AlertDialog.Builder(this).setTitle("✦ Página ${page + 1} · ${bubbles.size} balões")
            .setView(ScrollView(this).apply { addView(box) }).setPositiveButton("Fechar", null).show()
    }

    // ---------- quadro do balão ----------

    /**
     * Texto do balão com as palavras tocáveis (salvar, conheço), tradução, gramática e ✦ Palavras (IA).
     * O balão entra no texto do mangá, para as palavras salvas terem frase e para estudar depois.
     */
    private fun showBubble(text: String, translation: String?, source: String, retry: (() -> Unit)? = null) {
        val base = appendToText(text)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(14), dp(22), dp(6)) }
        box.addView(TextView(this).apply {
            this.text = "$source · página ${page + 1} · toque numa palavra"
            textSize = 12f; setTextColor(getColor(R.color.text_secondary))
        })
        var dlg: AlertDialog? = null
        val tv = TextView(this).apply {
            textSize = 22f; setTextColor(getColor(R.color.text)); setPadding(0, dp(8), 0, dp(6))
            movementMethod = LinkMovementMethod.getInstance(); highlightColor = Color.TRANSPARENT
        }
        fun paint() {
            Furigana.set(this, tv, Jp.decorate(text, VocabDb.studyKeys, getColor(R.color.saved_word), VocabDb.knownKeys, getColor(R.color.known_word), all = true) { w ->
                WordCard.show(this, w, text, base ?: "", 0, onChanged = { paint() })
            }, text)
        }
        paint()
        box.addView(tv)
        val tr = TextView(this).apply { textSize = 15f; setTextColor(getColor(R.color.text)) }
        if (!translation.isNullOrBlank()) tr.text = "✦ $translation"
        else { tr.text = "ML Kit: …"; Tr.translate(this, text) { t -> if (!isFinishing) tr.text = "ML Kit: " + (t ?: "(sem tradução)") } }
        box.addView(tr)
        val hits = try { Grammar.find(text) } catch (_: Exception) { emptyList() }
        if (hits.isNotEmpty()) box.addView(TextView(this).apply {
            this.text = hits.joinToString("\n") { "${it.point.name} ${it.point.level}: ${it.point.explanation}" }
            textSize = 13f; setTextColor(getColor(R.color.text)); setPadding(0, dp(10), 0, 0)
        })
        val actions = FlowLayout(this).apply { setPadding(0, dp(10), 0, 0) }
        fun action(label: String, onClick: () -> Unit) = actions.addView(Button(this).apply {
            this.text = label; isAllCaps = false; textSize = 13f
            setBackgroundResource(R.drawable.bg_button_outline); setTextColor(getColor(R.color.text))
            minHeight = 0; minimumHeight = 0; setPadding(dp(12), dp(6), dp(12), dp(6)); stateListAnimator = null
            setOnClickListener { onClick() }
        })
        if (Ai.enabled(this)) action("✦ Palavras (IA)") { AiWords.show(this, text, text, base ?: "", 0) { paint() } }
        if (retry != null && Ai.enabled(this)) action("✦ Ler com IA") { dlg?.dismiss(); retry() }
        action("✏ Corrigir") {
            val input = EditText(this).apply { setText(text); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
            val wrap = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
            AlertDialog.Builder(this).setTitle("Corrigir o texto do balão").setView(wrap)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("OK") { _, _ ->
                    val t = input.text.toString().replace("\n", "").trim()
                    if (t.isNotEmpty() && t != text) { dlg?.dismiss(); showBubble(t, null, "corrigido por você") }
                }.show()
        }
        box.addView(actions)
        dlg = AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Fechar", null).setOnDismissListener { view.tapMark = null }.show()
    }

    /**
     * Junta o balão ao texto "Mangá · título" (cria na primeira vez), com um título por página.
     * Devolve o nome base do texto (para salvar palavras com a frase de origem).
     */
    private fun appendToText(sentence: String): String? {
        val s = sentence.trim()
        if (s.isEmpty()) return entry.base
        val heading = "#h2 Página ${page + 1}"
        return try {
            val existing = entry.base?.let { Recordings.find(this, "$it.txt") }
            if (existing == null) {
                val name = Texts.save(this, "Mangá · ${entry.title}", listOf(heading, s))
                entry.base = Recordings.baseName(name)
                MangaStore.put(this, entry)
            } else {
                val body = Recordings.readText(this, existing.uri)
                val lines = body.lines().map { it.trim() }
                if (s !in lines) {
                    val lastHeading = lines.lastOrNull { it.startsWith("#h2 ") }
                    val add = (if (lastHeading != heading) "$heading\n" else "") + s + "\n"
                    val out = (if (body.endsWith("\n") || body.isEmpty()) body else body + "\n") + add
                    contentResolver.openOutputStream(existing.uri, "wt")?.use { it.write(out.toByteArray(Charsets.UTF_8)) }
                }
            }
            entry.base
        } catch (_: Exception) { entry.base }
    }

    // ---------- barra, menu ----------

    private fun buildToolbar(root: FrameLayout) {
        toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4)); elevation = dp(6).toFloat(); isClickable = true
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(getColor(R.color.card)); setStroke(dp(1), getColor(R.color.outline)) }
        }
        fun button(id: String, icon: String, hint: String, onClick: () -> Unit) {
            val b = TextView(this).apply {
                text = icon; textSize = 19f; gravity = Gravity.CENTER; setTextColor(getColor(R.color.text))
                setPadding(dp(10), dp(6), dp(10), dp(6)); contentDescription = hint
                setOnClickListener { onClick() }
                setOnLongClickListener { Toast.makeText(this@MangaActivity, hint, Toast.LENGTH_SHORT).show(); true }
            }
            toolButtons[id] = b
            toolbar.addView(b)
        }
        button(HAND, "✋", "Mão: deslizar ou tocar nas bordas para virar a página") { setTool(HAND) }
        button(WAND, "✨", "Varinha: toque num balão para ler") { setTool(WAND) }
        button("page", "✦", "Ler a página inteira com IA") { readPage() }
        toolbar.addView(View(this).apply { setBackgroundColor(getColor(R.color.outline)) }, LinearLayout.LayoutParams(dp(1), dp(22)).apply { marginStart = dp(4); marginEnd = dp(4) })
        button("prev", "‹", "Página anterior (no sentido de leitura)") { go(page - 1) }
        counter = TextView(this).apply {
            textSize = 13f; setTextColor(getColor(R.color.text)); setPadding(dp(6), 0, dp(6), 0)
            setOnClickListener { askPage() }
        }
        toolbar.addView(counter)
        button("next", "›", "Próxima página") { go(page + 1) }
        root.addView(toolbar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; bottomMargin = dp(14)
        })
        styleToolbar()
    }

    private fun setTool(t: String) {
        tool = t
        getSharedPreferences("manga", MODE_PRIVATE).edit().putString("tool", t).apply()
        styleToolbar()
        Toast.makeText(this, if (t == HAND) "Mão: deslize ou toque nas bordas para virar" else "Varinha: toque num balão", Toast.LENGTH_SHORT).show()
    }

    private fun styleToolbar() {
        for ((id, b) in toolButtons) {
            b.background = if (id == tool) GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(getColor(R.color.accent_soft)) } else null
        }
    }

    private fun askPage() {
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "1 a ${entry.pages}" }
        val wrap = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        AlertDialog.Builder(this).setTitle("Ir para a página").setView(wrap)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Ir") { _, _ -> input.text.toString().toIntOrNull()?.let { go(it - 1) } }.show()
    }

    private fun progress(msg: String): AlertDialog {
        val label = TextView(this).apply { text = msg; textSize = 14f; setTextColor(getColor(R.color.text)); setPadding(dp(24), dp(20), dp(24), dp(12)) }
        return AlertDialog.Builder(this).setView(label).setCancelable(true).show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Ir para a página…")
        menu.add(0, 2, 1, "")
        menu.add(0, 3, 2, "Abrir o texto dos balões")
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(2)?.title = if (entry.rtl) "Sentido: direita → esquerda (japonês)" else "Sentido: esquerda → direita"
        menu.findItem(3)?.isVisible = entry.base != null
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            1 -> askPage()
            2 -> {
                entry.rtl = !entry.rtl; MangaStore.put(this, entry); invalidateOptionsMenu()
                Toast.makeText(this, if (entry.rtl) "Deslize para a direita para avançar (mangá japonês)" else "Deslize para a esquerda para avançar", Toast.LENGTH_LONG).show()
            }
            3 -> entry.base?.let { startActivity(Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_NAME, "$it.txt")) }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }
}
