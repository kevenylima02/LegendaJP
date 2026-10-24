package com.keveny.legendajp

import android.content.Context
import android.graphics.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Ordem dos traços dos kanji (dados do KanjiVG, CC BY-SA 3.0, assets/strokes.txt).
 * Cada linha: caractere TAB d1|d2|... (caminhos SVG no espaço 109×109, na ordem oficial).
 */
object Strokes {
    const val VIEWBOX = 109f
    private val cache = ConcurrentHashMap<Char, List<String>>()
    private val missing = ConcurrentHashMap.newKeySet<Char>()

    /** Caminhos dos traços do kanji, ou null se não houver dados. Lê o asset; chame fora da UI. */
    fun get(ctx: Context, c: Char): List<Path>? {
        load(ctx, setOf(c))
        return cache[c]?.map { parse(it) }
    }

    /** Carrega de uma vez os kanji pedidos (uma passada pelo arquivo). */
    fun load(ctx: Context, chars: Set<Char>) {
        val need = chars.filter { !cache.containsKey(it) && it !in missing }.toMutableSet()
        if (need.isEmpty()) return
        try {
            ctx.assets.open("strokes.txt").bufferedReader(Charsets.UTF_8).useLines { seq ->
                for (line in seq) {
                    if (need.isEmpty()) break
                    val c = line[0]
                    if (c in need) {
                        cache[c] = line.substring(2).split('|')
                        need.remove(c)
                    }
                }
            }
        } catch (_: Exception) {}
        missing.addAll(need)
    }

    private val token = Regex("[MmLlCcSsZzHhVv]|-?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?")

    /** Parser mínimo de caminho SVG (comandos usados pelo KanjiVG). */
    fun parse(d: String): Path {
        val p = Path()
        val t = token.findAll(d).map { it.value }.toList()
        var i = 0
        var cx = 0f; var cy = 0f; var sx = 0f; var sy = 0f
        var c2x = 0f; var c2y = 0f
        var cmd = ' '
        var last = ' '
        fun n(): Float = t[i++].toFloat()
        try {
            while (i < t.size) {
                val tk = t[i]
                if (tk[0].isLetter()) {
                    cmd = tk[0]; i++
                    if (cmd == 'Z' || cmd == 'z') { p.close(); cx = sx; cy = sy; last = cmd; continue }
                }
                when (cmd) {
                    'M' -> { cx = n(); cy = n(); p.moveTo(cx, cy); sx = cx; sy = cy; last = 'M'; cmd = 'L'; continue }
                    'm' -> { cx += n(); cy += n(); p.moveTo(cx, cy); sx = cx; sy = cy; last = 'm'; cmd = 'l'; continue }
                    'L' -> { cx = n(); cy = n(); p.lineTo(cx, cy) }
                    'l' -> { cx += n(); cy += n(); p.lineTo(cx, cy) }
                    'H' -> { cx = n(); p.lineTo(cx, cy) }
                    'h' -> { cx += n(); p.lineTo(cx, cy) }
                    'V' -> { cy = n(); p.lineTo(cx, cy) }
                    'v' -> { cy += n(); p.lineTo(cx, cy) }
                    'C' -> {
                        val x1 = n(); val y1 = n(); val x2 = n(); val y2 = n(); val x = n(); val y = n()
                        p.cubicTo(x1, y1, x2, y2, x, y); c2x = x2; c2y = y2; cx = x; cy = y
                    }
                    'c' -> {
                        val x1 = cx + n(); val y1 = cy + n(); val x2 = cx + n(); val y2 = cy + n(); val x = cx + n(); val y = cy + n()
                        p.cubicTo(x1, y1, x2, y2, x, y); c2x = x2; c2y = y2; cx = x; cy = y
                    }
                    'S', 's' -> {
                        val curve = last in "CcSs"
                        val x1 = if (curve) 2 * cx - c2x else cx
                        val y1 = if (curve) 2 * cy - c2y else cy
                        val rel = cmd == 's'
                        val x2 = (if (rel) cx else 0f) + n(); val y2 = (if (rel) cy else 0f) + n()
                        val x = (if (rel) cx else 0f) + n(); val y = (if (rel) cy else 0f) + n()
                        p.cubicTo(x1, y1, x2, y2, x, y); c2x = x2; c2y = y2; cx = x; cy = y
                    }
                    else -> i++
                }
                last = cmd
            }
        } catch (_: Exception) {}
        return p
    }
}
