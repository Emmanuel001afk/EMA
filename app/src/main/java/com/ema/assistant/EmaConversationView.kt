package com.ema.assistant

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The conversation surface of the main screen: the user's requests, EMA's
 * replies, a live state indicator and a step-by-step progress card.
 *
 * Every state shown here comes from the router (via [EmaProgressListener]) or
 * from the final reply; nothing is animated optimistically. All methods must
 * be called on the main thread.
 */
class EmaConversationView(private val context: Context, private val onOptionChosen: (String) -> Unit) {

    enum class State(val label: String, val color: Int) {
        READY("Ready", Color.rgb(98, 84, 199)),
        LISTENING("Listening…", Color.rgb(14, 122, 254)),
        UNDERSTANDING("Understanding…", Color.rgb(98, 84, 199)),
        WORKING("Working", Color.rgb(98, 84, 199)),
        DONE("Done", Color.rgb(26, 127, 75)),
        UNVERIFIED("Done, not confirmed", Color.rgb(170, 110, 0)),
        NEEDS_ANSWER("Needs your answer", Color.rgb(170, 110, 0)),
        NEEDS_PERMISSION("Needs permission", Color.rgb(170, 110, 0)),
        FAILED("Couldn't finish", Color.rgb(186, 38, 38))
    }

    val root: LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val statePill = TextView(context)
    private val messages = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var progressCard: LinearLayout? = null
    private val stepRows = mutableListOf<TextView>()
    private var stepTexts: List<String> = emptyList()

    init {
        statePill.apply {
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(6), dp(12), dp(6))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val pillRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(8))
            addView(statePill)
        }
        root.addView(pillRow)
        root.addView(messages)
        setState(State.READY)
    }

    fun setState(state: State, detail: String? = null) {
        statePill.text = if (detail.isNullOrBlank()) state.label else "${state.label} · $detail"
        statePill.setTextColor(state.color)
        statePill.background = rounded(withAlpha(state.color, 28), dp(14))
        statePill.contentDescription = "EMA status: ${statePill.text}"
    }

    fun addUser(text: String) {
        progressCard = null
        stepRows.clear()
        messages.addView(bubble(text, user = true))
        trim()
    }

    fun addAssistant(text: String, status: EmaOutcomeStatus) {
        messages.addView(bubble(text, user = false, status = status))
        setState(stateFor(status))
        trim()
    }

    /** A plan was understood: show its steps, all pending. */
    fun showPlan(steps: List<String>) {
        if (steps.size < 2) { setState(State.WORKING); return }
        stepTexts = steps
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(Color.WHITE, dp(16)).apply { setStroke(dp(1), Color.rgb(226, 229, 240)) }
            contentDescription = "Plan with ${steps.size} steps"
        }
        card.addView(TextView(context).apply {
            text = "PLAN"
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(Color.rgb(110, 118, 140))
        })
        stepRows.clear()
        steps.forEachIndexed { i, s ->
            val row = TextView(context).apply {
                textSize = 14f
                setPadding(0, dp(4), 0, dp(4))
                setTextColor(Color.rgb(56, 68, 99))
            }
            stepRows += row
            renderStep(i, "○", s)
            card.addView(row)
        }
        messages.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(4), dp(32), dp(6))
        })
        progressCard = card
        setState(State.WORKING, "step 1 of ${steps.size}")
    }

    fun stepStarted(index: Int) {
        renderStep(index, "◐", stepTexts.getOrNull(index).orEmpty())
        if (stepTexts.size > 1) setState(State.WORKING, "step ${index + 1} of ${stepTexts.size}")
    }

    fun stepFinished(index: Int, outcome: EmaStepOutcome) {
        val mark = when (outcome.status) {
            EmaOutcomeStatus.SUCCESS -> "✓"
            EmaOutcomeStatus.UNVERIFIED -> "!"
            EmaOutcomeStatus.SKIPPED -> "–"
            EmaOutcomeStatus.NEEDS_INPUT, EmaOutcomeStatus.NEEDS_PERMISSION -> "?"
            else -> "✕"
        }
        renderStep(index, mark, stepTexts.getOrNull(index).orEmpty(), outcome.status)
    }

    /** EMA asked something; offer the options as one-tap answers. */
    fun showOptions(options: List<String>) {
        setState(State.NEEDS_ANSWER)
        if (options.isEmpty()) return
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        options.take(4).forEach { option ->
            row.addView(Button(context).apply {
                text = option
                isAllCaps = false
                textSize = 13f
                setOnClickListener { onOptionChosen(option) }
            })
        }
        messages.addView(HorizontalScrollView(context).apply { addView(row) })
    }

    private fun renderStep(index: Int, mark: String, text: String, status: EmaOutcomeStatus? = null) {
        val row = stepRows.getOrNull(index) ?: return
        row.text = "$mark  $text"
        row.setTextColor(when (status) {
            EmaOutcomeStatus.SUCCESS -> Color.rgb(26, 127, 75)
            EmaOutcomeStatus.UNVERIFIED, EmaOutcomeStatus.NEEDS_INPUT, EmaOutcomeStatus.NEEDS_PERMISSION -> Color.rgb(150, 95, 0)
            EmaOutcomeStatus.SKIPPED -> Color.rgb(140, 146, 160)
            null -> Color.rgb(56, 68, 99)
            else -> Color.rgb(186, 38, 38)
        })
        val spoken = when (mark) { "✓" -> "done"; "!" -> "done, not confirmed"; "–" -> "skipped"; "◐" -> "in progress"; "○" -> "pending"; "?" -> "waiting"; else -> "failed" }
        row.contentDescription = "Step ${index + 1}: $text, $spoken"
    }

    private fun bubble(text: String, user: Boolean, status: EmaOutcomeStatus? = null): View {
        val accent = Color.rgb(98, 84, 199)
        val tv = TextView(context).apply {
            this.text = text
            textSize = 16f
            setTextIsSelectable(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setTextColor(if (user) Color.WHITE else Color.rgb(33, 40, 62))
            val bg = when {
                user -> accent
                status == EmaOutcomeStatus.FAILED || status == EmaOutcomeStatus.TIMED_OUT -> Color.rgb(253, 238, 238)
                status == EmaOutcomeStatus.NEEDS_PERMISSION || status == EmaOutcomeStatus.UNVERIFIED -> Color.rgb(255, 246, 228)
                else -> Color.WHITE
            }
            background = rounded(bg, dp(18))
            contentDescription = (if (user) "You said: " else "EMA: ") + text
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = if (user) Gravity.END else Gravity.START
            setMargins(if (user) dp(48) else 0, dp(4), if (user) 0 else dp(48), dp(4))
        }
        tv.layoutParams = lp
        return tv
    }

    private fun trim() {
        while (messages.childCount > 40) messages.removeViewAt(0)
    }

    companion object {
        fun stateFor(status: EmaOutcomeStatus): State = when (status) {
            EmaOutcomeStatus.SUCCESS -> State.DONE
            EmaOutcomeStatus.UNVERIFIED -> State.UNVERIFIED
            EmaOutcomeStatus.NEEDS_INPUT -> State.NEEDS_ANSWER
            EmaOutcomeStatus.NEEDS_PERMISSION -> State.NEEDS_PERMISSION
            EmaOutcomeStatus.BLOCKED -> State.DONE
            else -> State.FAILED
        }
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = radius.toFloat() }
    private fun withAlpha(color: Int, alpha: Int) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
