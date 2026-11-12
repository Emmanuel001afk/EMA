package com.ema.assistant

import android.content.Context
import org.vosk.Model
import java.io.File

/**
 * The one copy of EMA's bundled English model in this process.
 *
 * The screen-off wake service and the local command recogniser both want the
 * same model. Loading it twice would roughly double the memory cost, which is
 * why the command recogniser used to refuse to start while the wake service
 * was running. Sharing one reference-counted instance lets both exist at the
 * same time - and the microphone is still only ever captured by one of them,
 * because capture is paused on handoff.
 *
 * Every [acquire] must be paired with exactly one [release] in a `finally`.
 */
internal object EmaLocalModel {

    private var model: Model? = null
    private var users = 0
    /** Cached dictionary; a listening session must not re-read a 3 MB file. */
    private var words: Set<String>? = null

    /** Load the model if nobody has it yet, and add one user. */
    @Synchronized
    fun acquire(context: Context): Model {
        model?.let { existing ->
            users++
            return existing
        }
        val directory = EmaWakeModel.prepare(context.applicationContext)
        val loaded = Model(directory.path)
        model = loaded
        users = 1
        return loaded
    }

    @Synchronized
    fun release() {
        if (users > 0) users--
        if (users == 0) {
            runCatching { model?.close() }
            model = null
            words = null
        }
    }

    /** True once the model is in memory, so no extra RAM needs to be reserved. */
    @Synchronized
    fun isLoaded(): Boolean = model != null

    /** How many components currently hold the model. */
    @Synchronized
    fun users(): Int = users

    /**
     * The words this model knows, read from the dictionary it ships with.
     *
     * The command grammar may only use known words. Returns null when the file
     * is missing or unreadable, and callers then decode free-form - a missing
     * word list must never stop EMA from listening.
     */
    @Synchronized
    fun vocabulary(context: Context): Set<String>? {
        words?.let { return it }
        val file = File(EmaWakeModel.directory(context.applicationContext), WORDS_FILE)
        if (!file.isFile) return null
        val read = runCatching { file.useLines { it.toHashSet() } }.getOrNull() ?: return null
        if (read.isEmpty()) return null
        words = read
        return read
    }

    private const val WORDS_FILE = "graph/words.txt"
}
