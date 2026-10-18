package com.ema.assistant

import android.content.Context

/**
 * Pure persistence core for [EmaMemory].
 *
 * Kept free of Android types so "memory survives process death" is a real unit
 * test: create a persistence over a variable, save, then create a *new*
 * persistence over the same variable (as a new process would) and load.
 */
class EmaMemoryPersistence(
    private val readRaw: () -> String?,
    private val writeRaw: (String) -> Boolean
) {
    fun load(): EmaMemory = EmaMemory.decode(readRaw())

    /** Synchronous write: returns only after the storage reports success. */
    fun save(memory: EmaMemory): Boolean = writeRaw(EmaMemory.encode(memory))

    fun clear(): Boolean = save(EmaMemory())
}

/** Persists [EmaMemory] in app-private storage. Nothing leaves the phone. */
object EmaMemoryStore {
    private const val PREFS = "ema_memory"
    private const val KEY = "memory_v2"

    /** Pre-schema-key payload, read once and migrated on the next save. */
    private const val LEGACY_KEY = "memory_v1"

    @Volatile private var cache: EmaMemory? = null

    fun load(context: Context): EmaMemory {
        cache?.let { return it }
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = preferences.getString(KEY, null) ?: preferences.getString(LEGACY_KEY, null)
        val loaded = EmaMemory.decode(stored)
        cache = loaded
        return loaded
    }

    /**
     * Write memory synchronously with [android.content.SharedPreferences.Editor.commit].
     *
     * The previous version used apply(), which returns before the disk write.
     * A process killed immediately afterwards could lose a shortcut the user was
     * told had been saved. commit() is bounded and this is a tiny payload.
     */
    fun save(context: Context, memory: EmaMemory): Boolean {
        cache = memory
        return runCatching {
            val ok = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, EmaMemory.encode(memory))
                .commit()
            if (ok) {
                // The v1 key is migrated; never keep a stale copy that a later
                // legacy read could resurrect.
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(LEGACY_KEY).commit()
            }
            ok
        }.getOrDefault(false)
    }

    fun clear(context: Context): Boolean = save(context, EmaMemory())

    /** Test/diagnostic hook: clear the in-process cache so the next load re-reads storage. */
    fun resetCache() {
        cache = null
    }
}
