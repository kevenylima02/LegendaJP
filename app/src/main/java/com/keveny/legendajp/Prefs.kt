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

    /**
     * Níveis JLPT marcados (5 = N5 … 1 = N1). É prioridade, não filtro: palavras e exercícios desses
     * níveis vêm primeiro. Sem marcação salva, vale "de N5 até o nível antigo" (configuração anterior).
     */
    var levels: Set<Int>
        get() = sp.getStringSet("levels", null)?.mapNotNull { it.toIntOrNull() }?.toSet()?.takeIf { it.isNotEmpty() }
            ?: (sp.getInt("level", 5)..5).toSet()
        set(v) = sp.edit().putStringSet("levels", v.map { it.toString() }.toSet()).apply()

    /** O nível mais difícil marcado (para a IA e a tela de níveis). */
    val level: Int get() = levels.minOrNull() ?: 5

    /** Ordem da escolha de palavras: "aleatoria", "frequentes" ou "audio" (no vocabulário salvo: mais recentes). */
    var pickOrder: String
        get() = sp.getString("pickOrder", "aleatoria") ?: "aleatoria"
        set(v) = sp.edit().putString("pickOrder", v).apply()

    /** Quantas palavras escolher antes de estudar (tela Escolher palavras). */
    var studyWords: Int
        get() = sp.getInt("studyWords", 10)
        set(v) = sp.edit().putInt("studyWords", v).apply()

    /** Revisão espaçada: cartões novos por dia. */
    var newPerDay: Int
        get() = sp.getInt("newPerDay", 10)
        set(v) = sp.edit().putInt("newPerDay", v).apply()

    /** Revisão: "misto", "exercicios" ou "flash". */
    var reviewMode: String
        get() = sp.getString("reviewMode", "misto") ?: "misto"
        set(v) = sp.edit().putString("reviewMode", v).apply()

    /** Tipos de exercício desligados (nomes dos tipos). */
    var disabledKinds: Set<String>
        get() = sp.getStringSet("disabledKinds", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("disabledKinds", v).apply()

    /** IA: Ai.OFF, Ai.NANO (no aparelho) ou Ai.CLOUD (Gemini na nuvem). */
    var aiMode: Int
        get() = sp.getInt("aiMode", Ai.CLOUD)
        set(v) = sp.edit().putInt("aiMode", v).apply()

    /** Chave do Google AI Studio; vazia = chave de teste embutida. */
    var geminiKey: String
        get() = sp.getString("geminiKey", "") ?: ""
        set(v) = sp.edit().putString("geminiKey", v.trim()).apply()

    /** Tradução mostrada primeiro: a do Gemini quando houver (true) ou a do ML Kit. */
    var trPreferGemini: Boolean
        get() = sp.getBoolean("trPreferGemini", true)
        set(v) = sp.edit().putBoolean("trPreferGemini", v).apply()

    /** Gerar áudio de texto importado: AudioGen.PHONE (TTS do celular) ou AudioGen.GEMINI. */
    var ttsEngine: String
        get() = sp.getString("ttsEngine", AudioGen.PHONE) ?: AudioGen.PHONE
        set(v) = sp.edit().putString("ttsEngine", v).apply()

    var geminiModel: String
        get() = sp.getString("geminiModel", Ai.DEFAULT_MODEL) ?: Ai.DEFAULT_MODEL
        set(v) = sp.edit().putString("geminiModel", v.trim()).apply()

    var language: String
        get() = sp.getString("language", "ja-JP") ?: "ja-JP"
        set(v) = sp.edit().putString("language", v).apply()
}
