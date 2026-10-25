package com.keveny.legendajp

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Bloco "✦ IA" no fim de uma tela: um botão por tarefa e, embaixo, as respostas no formato fixo.
 * Respostas já guardadas aparecem direto. "↻" pede uma resposta nova.
 */
class AiPanel(private val ctx: Context, private val box: LinearLayout, private val requests: List<Ai.Request>) {

    private val density = ctx.resources.displayMetrics.density
    private val results = HashMap<Ai.Task, TextView>()
    private val buttons = HashMap<Ai.Task, Button>()
    private lateinit var answers: LinearLayout
    @Volatile private var alive = true

    fun show() {
        box.removeAllViews()
        box.visibility = View.VISIBLE
        box.addView(TextView(ctx).apply {
            text = "✦ IA · ${Ai.modelLabel(ctx)}"
            textSize = 13f
            setTextColor(ctx.getColor(R.color.accent))
        })
        val row = FlowLayout(ctx)
        row.setPadding(0, dp(6), 0, 0)
        for (r in requests) {
            val b = Button(ctx).apply {
                text = r.task.label
                isAllCaps = false
                textSize = 14f
                setBackgroundResource(R.drawable.bg_button_outline)
                setTextColor(ctx.getColor(R.color.text))
                minHeight = 0; minimumHeight = 0
                setPadding(dp(14), dp(8), dp(14), dp(8))
                stateListAnimator = null
                setOnClickListener { run(r, fresh = false) }
            }
            buttons[r.task] = b
            row.addView(b)
        }
        box.addView(row)
        answers = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(answers)
        // Respostas já guardadas aparecem sem clicar
        Thread {
            val hits = requests.mapNotNull { r -> Ai.cached(ctx, r)?.let { r to it } }
            box.post { if (alive) for ((r, res) in hits) showResult(r, res) }
        }.start()
    }

    fun hide() {
        alive = false
        box.removeAllViews()
        box.visibility = View.GONE
    }

    private fun run(r: Ai.Request, fresh: Boolean) {
        val tv = resultView(r)
        tv.text = "✦ ${r.task.label}…"
        tv.setTextColor(ctx.getColor(R.color.text_secondary))
        buttons[r.task]?.isEnabled = false
        Ai.ask(ctx, r, fresh) { res -> if (alive) showResult(r, res) }
    }

    private fun resultView(r: Ai.Request): TextView = results.getOrPut(r.task) {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(14), dp(10), dp(14), dp(8))
        }
        val tv = TextView(ctx).apply {
            textSize = 15f
            setTextColor(ctx.getColor(R.color.text))
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextIsSelectable(true)
        }
        val again = Button(ctx).apply {
            text = "↻ Gerar de novo"
            isAllCaps = false
            textSize = 12f
            background = null
            setTextColor(ctx.getColor(R.color.text_secondary))
            minHeight = 0; minimumHeight = 0
            setPadding(0, dp(6), 0, 0)
            stateListAnimator = null
            setOnClickListener { run(r, fresh = true) }
        }
        card.addView(tv)
        card.addView(again)
        answers.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        tv
    }

    private fun showResult(r: Ai.Request, res: Ai.Result) {
        buttons[r.task]?.isEnabled = true
        val tv = resultView(r)
        val sec = ctx.getColor(R.color.text_secondary)
        val sb = SpannableStringBuilder()
        fun bold(s: String) { val a = sb.length; sb.append(s); sb.setSpan(StyleSpan(Typeface.BOLD), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        fun grey(s: String) { val a = sb.length; sb.append(s); sb.setSpan(ForegroundColorSpan(sec), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        grey("✦ ${r.task.label}" + (if (res.cached) " · salva" else "") + "\n")
        when {
            res.error != null -> { val a = sb.length; sb.append(res.error); sb.setSpan(ForegroundColorSpan(ctx.getColor(R.color.stop)), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            res.fields.isEmpty() -> sb.append(res.raw)
            else -> {
                if (r.task == Ai.Task.TRANSLATE) {
                    // A tradução antiga (ML Kit) fica junto, para comparar
                    Tr.cached(ctx, r.sentence)?.let { bold("Tradução (ML Kit): "); sb.append(it).append("\n") }
                }
                for ((i, f) in res.fields.withIndex()) {
                    if (i > 0) sb.append("\n")
                    if (f.first.isEmpty()) sb.append("   ").append(f.second) else { bold(f.first + ": "); sb.append(f.second) }
                }
            }
        }
        tv.setTextColor(ctx.getColor(R.color.text))
        tv.text = sb
    }

    private fun dp(v: Int) = (v * density).toInt()
}
