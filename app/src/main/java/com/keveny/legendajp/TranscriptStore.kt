package com.keveny.legendajp

/** Estado compartilhado entre o serviço de captura e as telas (mesmo processo). */
object TranscriptStore {
    enum class State { IDLE, RUNNING, STOPPING }

    @Volatile var state: State = State.IDLE
    @Volatile var mode: Int = CaptureService.MODE_CAPTURE
    @Volatile var status: String = ""
    @Volatile var detail: String = ""
    @Volatile var partial: String = ""
    @Volatile var level: Int = 0
    @Volatile var startedAt: Long = 0L
    /** Linhas já formatadas ("[mm:ss] texto"); só alteradas na main thread. */
    val lines: MutableList<String> = mutableListOf()

    @Volatile var listener: (() -> Unit)? = null
    @Volatile var overlayListener: (() -> Unit)? = null

    fun notifyChanged() {
        listener?.invoke()
        overlayListener?.invoke()
    }

    fun reset(mode: Int) {
        this.mode = mode
        state = State.RUNNING
        status = ""
        detail = ""
        partial = ""
        level = 0
        startedAt = System.currentTimeMillis()
        lines.clear()
    }
}
