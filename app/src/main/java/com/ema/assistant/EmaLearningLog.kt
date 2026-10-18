package com.ema.assistant

import android.content.Context

/**
 * Development-time improvement loop, on the user's terms.
 *
 * EMA does not retrain itself on the phone. When the user opts in, requests
 * that no native layer understood are kept locally (at most [MAX] entries, no
 * results, no timestamps beyond order) so the user can copy them into a bug
 * report. A developer then adds each one to the understanding corpus
 * (app/src/test/resources/ema_understanding_corpus.tsv) with the expected
 * frame, fixes the logic, and the test suite guards it from then on.
 * Off by default; clearing is one tap in Settings.
 */
object EmaLearningLog {
    private const val PREFS = "ema_learning"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ITEMS = "not_understood"
    const val MAX = 30

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) clear(context)
    }

    fun recordNotUnderstood(context: Context, request: String) {
        if (!isEnabled(context)) return
        val text = request.trim().replace(Regex("\\s+"), " ").take(300)
        if (text.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val items = entries(context).filter { it != text } + text
        prefs.edit().putString(KEY_ITEMS, items.takeLast(MAX).joinToString("\n")).apply()
    }

    fun entries(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ITEMS, "").orEmpty()
            .split('\n').filter { it.isNotBlank() }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_ITEMS).apply()
    }
}
