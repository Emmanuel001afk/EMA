package com.ema.assistant

import android.content.Context

/**
 * The last runs, kept on the phone.
 *
 * At most [EmaRunTrace.MAX] runs are stored, in one preferences file, as
 * plain text so the owner can copy them into a bug report without any
 * tooling. Nothing leaves the device.
 */
object EmaRunTraceStore {

    private const val PREFS = "ema_runs"
    private const val KEY_RUNS = "runs"
    private const val SEPARATOR = "\n<<<EMA-RUN>>>\n"

    /** Save a finished run, dropping the oldest when the bound is reached. */
    fun store(context: Context, trace: EmaRunTrace) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val runs = runs(context).toMutableList()
            runs += trace.format()
            while (runs.size > EmaRunTrace.MAX) runs.removeAt(0)
            prefs.edit().putString(KEY_RUNS, runs.joinToString(SEPARATOR)).apply()
        }
    }

    /** Every stored run, newest last. */
    fun runs(context: Context): List<String> = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RUNS, "")
            .orEmpty()
            .split(SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    /** The stored runs as one text block, ready for the clipboard. */
    fun asText(context: Context): String = runs(context).joinToString("\n\n")

    /** The most recent run, or null when there is none. */
    fun lastRun(context: Context): String? = runs(context).lastOrNull()

    /** The most recent run as a trace, or null when there is none. */
    fun lastTrace(context: Context): EmaRunTrace? = lastRun(context)?.let { EmaRunTrace.parse(it) }

    fun clear(context: Context) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_RUNS).apply()
        }
    }
}
