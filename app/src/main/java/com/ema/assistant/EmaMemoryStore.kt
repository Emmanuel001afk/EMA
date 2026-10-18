package com.ema.assistant

import android.content.Context

/** Persists [EmaMemory] in app-private storage. Nothing leaves the phone. */
object EmaMemoryStore {
    private const val PREFS = "ema_memory"
    private const val KEY = "memory_v1"

    @Volatile private var cache: EmaMemory? = null

    fun load(context: Context): EmaMemory {
        cache?.let { return it }
        val loaded = EmaMemory.decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))
        cache = loaded
        return loaded
    }

    fun save(context: Context, memory: EmaMemory) {
        cache = memory
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, EmaMemory.encode(memory)).apply()
    }

    fun clear(context: Context) = save(context, EmaMemory())
}
