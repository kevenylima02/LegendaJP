package com.keveny.legendajp

import android.content.res.ColorStateList
import android.graphics.Color
import android.widget.ProgressBar

/** Barrinha de domínio de uma palavra: 0 a 1000 pontos, vermelho (0) → azul (1000). */
object Score {
    const val MAX = 1000
    const val GAIN = 10   // por acerto: 100 acertos para dominar
    const val LOSS = 5    // por erro

    fun percent(score: Int) = (score.coerceIn(0, MAX) * 100) / MAX

    fun color(score: Int): Int {
        val t = score.coerceIn(0, MAX) / MAX.toFloat()
        val r = (229 + (61 - 229) * t).toInt()
        val g = (57 + (90 - 57) * t).toInt()
        val b = (53 + (254 - 53) * t).toInt()
        return Color.rgb(r, g, b)
    }

    fun bind(bar: ProgressBar, score: Int) {
        bar.max = MAX
        bar.progress = score.coerceIn(0, MAX)
        bar.progressTintList = ColorStateList.valueOf(color(score))
    }
}
