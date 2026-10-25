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

    /** Mostrar a legenda flutuante sobre outros apps durante a captura. */
    var overlay: Boolean
        get() = sp.getBoolean("overlay", true)
        set(v) = sp.edit().putBoolean("overlay", v).apply()

    /** Já perguntamos sobre a permissão de sobreposição? */
    var overlayAsked: Boolean
        get() = sp.getBoolean("overlayAsked", false)
        set(v) = sp.edit().putBoolean("overlayAsked", v).apply()

    var overlayY: Int
        get() = sp.getInt("overlayY", -1)
        set(v) = sp.edit().putInt("overlayY", v).apply()

    var overlayX: Int
        get() = sp.getInt("overlayX", Int.MIN_VALUE)
        set(v) = sp.edit().putInt("overlayX", v).apply()

    /** 0 = solta, 1 = grudada à esquerda, 2 = grudada à direita. */
    var overlayDock: Int
        get() = sp.getInt("overlayDock", 0)
        set(v) = sp.edit().putInt("overlayDock", v).apply()

    var overlayExpanded: Boolean
        get() = sp.getBoolean("overlayExpanded", false)
        set(v) = sp.edit().putBoolean("overlayExpanded", v).apply()

    /** Nível JLPT que o usuário está estudando: 5 (N5) … 1 (N1). */
    var level: Int
        get() = sp.getInt("level", 5)
        set(v) = sp.edit().putInt("level", v).apply()

    /** Quantas palavras escolher antes de estudar (tela Escolher palavras). */
    var studyWords: Int
        get() = sp.getInt("studyWords", 10)
        set(v) = sp.edit().putInt("studyWords", v).apply()

    /** Tipos de exercício desligados (nomes dos tipos). */
    var disabledKinds: Set<String>
        get() = sp.getStringSet("disabledKinds", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("disabledKinds", v).apply()

    var language: String
        get() = sp.getString("language", "ja-JP") ?: "ja-JP"
        set(v) = sp.edit().putString("language", v).apply()
}
