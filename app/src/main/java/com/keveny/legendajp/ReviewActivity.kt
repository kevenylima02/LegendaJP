package com.keveny.legendajp

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioGroup
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Revisão espaçada: o que vence hoje, sequência de dias, cartões por tipo e estado, previsão da
 * semana e a lista de cartões. "Começar revisão" abre a sessão (exercícios, flashcards ou misto).
 */
class ReviewActivity : Activity() {

    private lateinit var list: ListView
    private lateinit var header: LinearLayout
    private var cards: List<Srs.Card> = emptyList()
    private val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_review)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Revisão"
        list = findViewById(R.id.list)
        header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(8)) }
        list.addHeaderView(header, null, false)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> cards.getOrNull(pos - list.headerViewsCount)?.let { details(it) } }

        val prefs = Prefs(this)
        val modes = findViewById<RadioGroup>(R.id.modes)
        modes.check(when (prefs.reviewMode) { "exercicios" -> R.id.modeEx; "flash" -> R.id.modeFlash; else -> R.id.modeMix })
        modes.setOnCheckedChangeListener { _, id ->
            prefs.reviewMode = when (id) { R.id.modeEx -> "exercicios"; R.id.modeFlash -> "flash"; else -> "misto" }
        }
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            startActivity(
                Intent(this, StudyActivity::class.java)
                    .putExtra(StudyActivity.EXTRA_SRS, true)
                    .putExtra(StudyActivity.EXTRA_SRS_MODE, prefs.reviewMode)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        Thread {
            Srs.sync(this)
            val today = Srs.today(this)
            val all = Srs.all(this).filter(Srs::active)
            val forecast = Srs.forecast(this)
            val streak = Srs.streak(this)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                cards = all.sortedWith(compareBy<Srs.Card>({ if (it.state == Srs.NEW) 1 else 0 }, { it.due }))
                buildHeader(today, all, forecast, streak)
                adapter.notifyDataSetChanged()
                val n = today.due.size + today.fresh.size
                val btn = findViewById<Button>(R.id.btnStart)
                btn.isEnabled = n > 0
                btn.text = if (n > 0) "Começar revisão · $n (≈ ${maxOf(1, (n * 25 + 59) / 60)} min)" else "Nada para revisar agora"
            }
        }.start()
    }

    private fun buildHeader(today: Srs.Today, all: List<Srs.Card>, forecast: IntArray, streak: Int) {
        header.removeAllViews()
        val sec = getColor(R.color.text_secondary)

        // Hoje
        val sb = SpannableStringBuilder()
        val a = sb.length
        sb.append("Hoje: ${today.due.size} para revisar · ${today.fresh.size} novos")
        sb.setSpan(StyleSpan(Typeface.BOLD), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(RelativeSizeSpan(1.2f), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("\n")
        val b = sb.length
        sb.append(if (streak > 0) "Sequência: $streak dia" + (if (streak > 1) "s" else "") else "Sem sequência ainda")
        sb.append(" · ${Prefs(this).newPerDay} novos por dia (Configurações)")
        sb.setSpan(ForegroundColorSpan(sec), b, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        header.addView(card(TextView(this).apply { text = sb; textSize = 15f; setTextColor(getColor(R.color.text)) }))

        // Por tipo e estado
        val buckets = listOf("novos", "aprendendo", "revisando", "dominados")
        val table = SpannableStringBuilder()
        val t0 = table.length
        table.append("Cartões")
        table.setSpan(StyleSpan(Typeface.BOLD), t0, table.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        for (kind in listOf(Srs.MEANING, Srs.KANJI, Srs.GRAMMAR)) {
            val mine = all.filter { it.kind == kind }
            val counts = buckets.map { bk -> mine.count { Srs.bucket(it) == bk } }
            table.append("\n").append(when (kind) { Srs.MEANING -> "Palavras"; Srs.KANJI -> "Kanji"; else -> "Gramática" })
            table.append(" · ${mine.size}\n")
            val c0 = table.length
            table.append(buckets.zip(counts).joinToString("  ·  ") { (bk, n) -> "$n $bk" })
            table.setSpan(ForegroundColorSpan(sec), c0, table.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        header.addView(card(TextView(this).apply { text = table; textSize = 14f; setTextColor(getColor(R.color.text)); setLineSpacing(dp(2).toFloat(), 1f) }))

        // Previsão da semana
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(this).apply { text = "Próximos 7 dias"; textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(getColor(R.color.text)) })
        val max = maxOf(1, forecast.maxOrNull() ?: 1)
        val names = listOf("hoje", "amanhã") + (2 until forecast.size).map { d ->
            SimpleDateFormat("EEE", Locale("pt", "BR")).format(Date(Srs.startOfDay() + d * 86_400_000L))
        }
        for ((i, n) in forecast.withIndex()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(3), 0, dp(3)) }
            row.addView(TextView(this).apply { text = names[i]; textSize = 12f; setTextColor(sec); width = dp(64) })
            val barArea = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            barArea.addView(View(this).apply { setBackgroundColor(getColor(R.color.accent)) },
                LinearLayout.LayoutParams(0, dp(10), n.toFloat().coerceAtLeast(0.02f)))
            barArea.addView(View(this), LinearLayout.LayoutParams(0, dp(10), (max - n).toFloat().coerceAtLeast(0.02f)))
            row.addView(barArea, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply { text = "$n"; textSize = 12f; setTextColor(getColor(R.color.text)); gravity = Gravity.END; width = dp(36) })
            box.addView(row)
        }
        header.addView(card(box))

        header.addView(TextView(this).apply {
            text = if (all.isEmpty()) "Ainda não há cartões. Salve palavras (ou escolha \"Estudar\" antes dos exercícios) e faça exercícios de gramática: eles entram aqui sozinhos."
            else "Cartões (toque para ver o histórico)"
            textSize = 12f
            setTextColor(sec)
            setPadding(0, dp(12), 0, dp(4))
        })
    }

    private fun card(v: View): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(v)
        }
        return LinearLayout(this).apply { setPadding(0, dp(4), 0, dp(4)); addView(box, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }
    }

    private fun nextText(c: Srs.Card): String {
        if (c.state == Srs.NEW) return "novo"
        val now = System.currentTimeMillis()
        val r = Srs.retrievability(c, now)
        val next = if (c.due <= now) "vence agora" else "próxima em ${Srs.human(c.due - now)}"
        return next + (if (r != null) " · lembra ${(r * 100).toInt()}%" else "")
    }

    private fun details(c: Srs.Card) {
        Thread {
            val log = Srs.log(this, c.id)
            runOnUiThread {
                val sb = StringBuilder()
                sb.append("Tipo: ${Srs.kindLabel(c.kind)} · ${Srs.bucket(c)}\n")
                sb.append(nextText(c)).append("\n")
                if (c.reps > 0) sb.append("Revisões: ${c.reps} · erros: ${c.lapses} · estabilidade: ${"%.1f".format(c.stability)} d\n")
                if (!c.sentence.isNullOrEmpty()) sb.append("\nFrase: ${c.sentence}\n")
                if (log.isNotEmpty()) {
                    sb.append("\nHistórico\n")
                    for ((ts, g) in log) sb.append("${fmt.format(Date(ts))} · ${Srs.gradeLabel(g)}\n")
                }
                AlertDialog.Builder(this).setTitle(c.label).setMessage(sb.toString().trim())
                    .setPositiveButton("Fechar", null).show()
            }
        }.start()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = cards.size
        override fun getItem(position: Int) = cards[position]
        override fun getItemId(position: Int) = cards[position].id
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_vocab, parent, false)
            val c = cards[position]
            v.findViewById<TextView>(R.id.base).text = c.label
            v.findViewById<TextView>(R.id.reading).text = Srs.kindLabel(c.kind)
            v.findViewById<TextView>(R.id.meta).text = nextText(c)
            v.findViewById<TextView>(R.id.count).text = Srs.bucket(c)
            v.findViewById<View>(R.id.bars).visibility = View.GONE
            return v
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
