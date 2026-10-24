package com.keveny.legendajp

import android.content.Context

/** Preferências simples do app. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("legendajp", Context.MODE_PRIVATE)

    var source: Int
        get() = sp.getInt("source", CaptureService.MODE_CAPTURE)
        set(v) = sp.edit().putInt("source", v).apply()

    /** Salvar o .wav junto do .txt (necessário para ouvir as frases no visualizador). */
    var saveWav: Boolean
        get() = sp.getBoolean("saveWav", true)
        set(v) = sp.edit().putBoolean("saveWav", v).apply()

    var language: String
        get() = sp.getString("language", "ja-JP") ?: "ja-JP"
        set(v) = sp.edit().putString("language", v).apply()
}
