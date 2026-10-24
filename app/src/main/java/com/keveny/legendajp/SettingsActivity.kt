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

        val levels = findViewById<RadioGroup>(R.id.levels)
        levels.check(
            when (prefs.level) { 1 -> R.id.n1; 2 -> R.id.n2; 3 -> R.id.n3; 4 -> R.id.n4; else -> R.id.n5 }
        )
        levels.setOnCheckedChangeListener { _, id ->
            prefs.level = when (id) { R.id.n1 -> 1; R.id.n2 -> 2; R.id.n3 -> 3; R.id.n4 -> 4; else -> 5 }
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

        val wav = findViewById<Switch>(R.id.swWav)
        wav.isChecked = prefs.saveWav
        wav.setOnCheckedChangeListener { _, on -> prefs.saveWav = on }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        findViewById<Switch>(R.id.swOverlay).isChecked = prefs.overlay && Overlay.canDraw(this)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
