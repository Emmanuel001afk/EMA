package com.ema.assistant

import android.content.Context

/** App-private atomic persistence for owner-defined [EmaNamedRoutines.CustomRoutine]s. */
object EmaNamedRoutineStore {
    private const val PREFS = "ema_named_routines_store"
    private const val KEY = "routines_v1"

    fun load(context: Context): Map<String, EmaNamedRoutines.CustomRoutine> =
        EmaNamedRoutines.decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    fun save(context: Context, routines: Map<String, EmaNamedRoutines.CustomRoutine>): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, EmaNamedRoutines.encode(routines))
            .commit()

    fun clear(context: Context): Boolean = save(context, emptyMap())
}
