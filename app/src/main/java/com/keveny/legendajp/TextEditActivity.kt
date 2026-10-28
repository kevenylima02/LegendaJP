package com.keveny.legendajp

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions

/**
 * Conferir o texto antes de salvar: colado, compartilhado, de arquivo ou lido de uma foto. Na foto,
 * o ML Kit lê no celular; "✦ Ler com IA" manda a imagem ao Gemini (melhor para texto vertical e mangá).
 */
class TextEditActivity : Activity() {

    companion object {
        const val EXTRA_TEXT = "text"
        const val EXTRA_TITLE = "title"
        const val EXTRA_IMAGE = "image"
        const val EXTRA_DELETE_IMAGE = "deleteImage"
        /** Pasta (Folders) em que o texto novo entra, quando criado de dentro de uma pasta. */
        const val EXTRA_FOLDER = "folder"
    }

    private lateinit var titleIn: EditText
    private lateinit var body: EditText
    private lateinit var status: TextView
    private var image: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Novo texto"
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        image = intent.getStringExtra(EXTRA_IMAGE)?.let { Uri.parse(it) }
        titleIn = EditText(this).apply { hint = "Título (opcional)"; setSingleLine(); textSize = 16f; setText(intent.getStringExtra(EXTRA_TITLE) ?: "") }
        body = EditText(this).apply {
            hint = "Cole ou escreva o texto em japonês. Linha em branco separa parágrafos. Markdown leve: # título, | tabela |, - item."
            gravity = Gravity.TOP; textSize = 17f
            setText(intent.getStringExtra(EXTRA_TEXT) ?: "")
        }
        status = TextView(this).apply { textSize = 12f; setTextColor(getColor(R.color.text_secondary)) }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (image != null) {
            buttons.addView(Button(this).apply {
                text = "✦ Ler com IA"; isAllCaps = false
                setBackgroundResource(R.drawable.bg_button_outline); setTextColor(getColor(R.color.text)); stateListAnimator = null
                setOnClickListener { readWithAi() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        }
        buttons.addView(Button(this).apply {
            text = "Salvar e abrir"; isAllCaps = false
            setBackgroundResource(R.drawable.bg_button_primary); setTextColor(0xFFFFFFFF.toInt()); stateListAnimator = null
            setOnClickListener { save() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
            addView(titleIn)
            addView(status)
            addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(buttons)
        }
        setContentView(root)
        image?.let { readWithMlKit(it) }
    }

    private fun readWithMlKit(uri: Uri) {
        status.text = "Lendo a imagem no celular…"
        val img = try { InputImage.fromFilePath(this, uri) } catch (e: Exception) { status.text = "Não consegui abrir a imagem: ${e.message}"; return }
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()).process(img)
            .addOnSuccessListener { t ->
                val text = t.textBlocks.joinToString("\n\n") { b -> b.lines.joinToString("\n") { it.text } }
                body.setText(text)
                status.text = if (text.isBlank()) "Não achei texto. Tente ✦ Ler com IA." else
                    "Lido no celular (ML Kit). Confira; se saiu ruim (texto vertical, mangá), toque ✦ Ler com IA."
            }
            .addOnFailureListener { e -> status.text = "Falha ao ler: ${e.message}. Tente ✦ Ler com IA." }
    }

    private fun readWithAi() {
        val uri = image ?: return
        if (!Ai.enabled(this) || Ai.mode(this) != Ai.CLOUD) { Toast.makeText(this, "Precisa do Gemini na nuvem (Configurações → IA)", Toast.LENGTH_LONG).show(); return }
        status.text = "✦ Lendo a imagem com ${Ai.modelLabel(this)}…"
        Ai.readImage(this, uri) { text, err ->
            if (text != null) { body.setText(text); status.text = "Lido pelo Gemini com o layout da página (títulos, tabelas e listas em Markdown). Confira antes de salvar." }
            else status.text = "Não consegui: $err"
        }
    }

    private fun save() {
        val paras = Texts.toLines(body.text.toString())
        if (paras.none { !it.startsWith("#") }) { Toast.makeText(this, "O texto está vazio", Toast.LENGTH_SHORT).show(); return }
        val t = titleIn.text.toString().trim().ifEmpty { null }
        Thread {
            val name = try { Texts.save(this, t, paras) } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Não consegui salvar: ${e.message}", Toast.LENGTH_LONG).show() }; null
            }
            val folder = intent.getLongExtra(EXTRA_FOLDER, Folders.NONE)
            if (name != null && folder > 0) Folders.move(this, listOf(Recordings.baseName(name)), folder)
            runOnUiThread {
                if (name == null) return@runOnUiThread
                startActivity(Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_NAME, name))
                finish()
            }
        }.start()
    }

    override fun onDestroy() {
        // A foto tirada só para ler o texto não fica na galeria
        if (intent.getBooleanExtra(EXTRA_DELETE_IMAGE, false)) image?.let { try { contentResolver.delete(it, null, null) } catch (_: Exception) {} }
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
