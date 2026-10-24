package com.keveny.legendajp

/** Estado compartilhado entre o serviço de captura e a tela (mesmo processo). */
object TranscriptStore {
    @Volatile var status: String = "Pronto. Toque em Verificar japonês primeiro."
    @Volatile var partial: String = ""
    @Volatile var level: Int = 0
    val lines: MutableList<String> = mutableListOf()   // só alterado na main thread

    @Volatile var listener: (() -> Unit)? = null

    fun notifyChanged() {
        listener?.invoke()
    }
}
