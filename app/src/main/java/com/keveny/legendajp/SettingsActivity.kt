package com.keveny.legendajp

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.MenuItem
import android.widget.Button
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.Toast

/** Configurações: nível de estudo, legenda flutuante e áudio. */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Configurações"
        prefs = Prefs(this)

        // Níveis: vários marcados (prioridade); pelo menos um fica marcado
        val levelBoxes = mapOf(5 to R.id.n5, 4 to R.id.n4, 3 to R.id.n3, 2 to R.id.n2, 1 to R.id.n1)
            .mapValues { findViewById<android.widget.CheckBox>(it.value) }
        val current = prefs.levels
        for ((lvl, cb) in levelBoxes) {
            cb.isChecked = lvl in current
            cb.setOnCheckedChangeListener { box, on ->
                val set = prefs.levels.toMutableSet()
                if (on) set.add(lvl) else set.remove(lvl)
                if (set.isEmpty()) { box.isChecked = true; return@setOnCheckedChangeListener }
                prefs.levels = set
            }
        }
        val furi = findViewById<RadioGroup>(R.id.furiganaMode)
        furi.check(when (prefs.furiganaMode) { Furigana.ALL -> R.id.furiAll; Furigana.NONE -> R.id.furiNone; else -> R.id.furiUnknown })
        furi.setOnCheckedChangeListener { _, id ->
            prefs.furiganaMode = when (id) { R.id.furiAll -> Furigana.ALL; R.id.furiNone -> Furigana.NONE; else -> Furigana.UNKNOWN }
        }
        val ovf = findViewById<Switch>(R.id.swOverlayFurigana)
        ovf.isChecked = prefs.overlayFurigana
        ovf.setOnCheckedChangeListener { _, on -> prefs.overlayFurigana = on }
        val words = findViewById<RadioGroup>(R.id.studyWords)
        words.check(when (prefs.studyWords) { 5 -> R.id.w5; 15 -> R.id.w15; 20 -> R.id.w20; else -> R.id.w10 })
        words.setOnCheckedChangeListener { _, id ->
            prefs.studyWords = when (id) { R.id.w5 -> 5; R.id.w15 -> 15; R.id.w20 -> 20; else -> 10 }
        }
        findViewById<Button>(R.id.btnLevelInfo).setOnClickListener {
            startActivity(Intent(this, LevelActivity::class.java))
        }

        val overlay = findViewById<Switch>(R.id.swOverlay)
        overlay.isChecked = prefs.overlay && Overlay.canDraw(this)
        overlay.setOnCheckedChangeListener { _, on ->
            prefs.overlay = on
            prefs.overlayAsked = true
            if (on && !Overlay.canDraw(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                Toast.makeText(this, "Ative \"Exibir sobre outros apps\" para o Michi e volte", Toast.LENGTH_LONG).show()
            }
        }
        // Tipos de exercício com dificuldade
        val box = findViewById<android.widget.LinearLayout>(R.id.kindsBox)
        var lastFocus: StudyGen.Focus? = null
        for (k in StudyGen.allKinds) {
            if (k.focus != lastFocus) {
                lastFocus = k.focus
                box.addView(android.widget.TextView(this).apply {
                    text = k.focus.label
                    textSize = 13f
                    setTextColor(getColor(R.color.accent))
                    setPadding(0, dp(10), 0, dp(2))
                })
            }
            val cb = android.widget.CheckBox(this).apply {
                text = "${k.name}  ·  ${k.difficulty.label}"
                textSize = 15f
                setTextColor(getColor(R.color.text))
                isChecked = k.name !in prefs.disabledKinds
                setPadding(dp(4), dp(6), 0, dp(6))
                setOnCheckedChangeListener { _, on ->
                    val set = prefs.disabledKinds.toMutableSet()
                    if (on) set.remove(k.name) else set.add(k.name)
                    prefs.disabledKinds = set
                }
            }
            box.addView(cb)
            box.addView(android.widget.TextView(this).apply {
                text = k.description
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(dp(36), 0, 0, dp(4))
            })
        }

        val np = findViewById<RadioGroup>(R.id.newPerDay)
        np.check(when (prefs.newPerDay) { 5 -> R.id.np5; 20 -> R.id.np20; 30 -> R.id.np30; else -> R.id.np10 })
        np.setOnCheckedChangeListener { _, id ->
            prefs.newPerDay = when (id) { R.id.np5 -> 5; R.id.np20 -> 20; R.id.np30 -> 30; else -> 10 }
        }

        // IA
        val modes = findViewById<RadioGroup>(R.id.aiModes)
        modes.check(when (prefs.aiMode) { Ai.OFF -> R.id.aiOff; Ai.NANO -> R.id.aiNano; else -> R.id.aiCloud })
        modes.setOnCheckedChangeListener { _, id ->
            prefs.aiMode = when (id) { R.id.aiOff -> Ai.OFF; R.id.aiNano -> Ai.NANO; else -> Ai.CLOUD }
            findViewById<android.widget.TextView>(R.id.aiTestResult).text = ""
        }
        findViewById<android.widget.EditText>(R.id.geminiKey).setText(prefs.geminiKey)
        findViewById<android.widget.EditText>(R.id.geminiModel).setText(prefs.geminiModel)
        val tts = findViewById<RadioGroup>(R.id.ttsEngine)
        tts.check(if (prefs.ttsEngine == AudioGen.GEMINI) R.id.ttsGemini else R.id.ttsPhone)
        tts.setOnCheckedChangeListener { _, id -> prefs.ttsEngine = if (id == R.id.ttsGemini) AudioGen.GEMINI else AudioGen.PHONE }
        val engineBtn = findViewById<Button>(R.id.btnTtsEngine)
        fun engineLabel() { engineBtn.text = "Motor: " + prefs.ttsPackage.ifBlank { "padrão" }.substringAfterLast('.') }
        engineLabel()
        engineBtn.setOnClickListener { PhoneVoices.chooseEngine(this) { engineLabel() } }
        findViewById<Button>(R.id.btnTtsVoices).setOnClickListener { PhoneVoices.voicesDialog(this) }
        val prefer = findViewById<Switch>(R.id.swPreferGemini)
        prefer.isChecked = prefs.trPreferGemini
        prefer.setOnCheckedChangeListener { _, on -> prefs.trPreferGemini = on }
        val testBtn = findViewById<Button>(R.id.btnAiTest)
        testBtn.setOnClickListener {
            saveAiFields()
            val out = findViewById<android.widget.TextView>(R.id.aiTestResult)
            testBtn.isEnabled = false
            out.text = "Testando…"
            Ai.test(this, progress = { out.text = it }) { msg ->
                testBtn.isEnabled = true
                out.text = msg
            }
        }

        val wav = findViewById<Switch>(R.id.swWav)
        wav.isChecked = prefs.saveWav
        wav.setOnCheckedChangeListener { _, on -> prefs.saveWav = on }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun saveAiFields() {
        prefs.geminiKey = findViewById<android.widget.EditText>(R.id.geminiKey).text.toString()
        prefs.geminiModel = findViewById<android.widget.EditText>(R.id.geminiModel).text.toString().ifBlank { Ai.DEFAULT_MODEL }
    }

    override fun onPause() {
        super.onPause()
        saveAiFields()
    }

    override fun onResume() {
        super.onResume()
        findViewById<Switch>(R.id.swOverlay).isChecked = prefs.overlay && Overlay.canDraw(this)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
