package com.ema.assistant

import android.content.Context

/**
 * The learned links, kept on the phone.
 *
 * One preferences file, plain text, nothing that leaves the device. The
 * "remember" shortcuts live elsewhere (EmaMemory), so "forget what you
 * learned" never touches them.
 */
object EmaLearningStore {

    private const val PREFS = "ema_learning_links"
    private const val KEY_LINKS = "links"

    /** The learned links and the usage preferences. */
    fun model(context: Context): EmaLearningModel {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LINKS, "").orEmpty()
        return if (text.isBlank()) EmaLearningModel() else EmaLearningModel.parse(text)
    }

    /** Keep the learned links. */
    fun save(context: Context, model: EmaLearningModel) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LINKS, model.serialize()).apply()
    }

    /** Forget every learned link. The "remember" shortcuts are not touched. */
    fun forgetLinks(context: Context) {
        val model = model(context)
        model.clear()
        save(context, model)
    }

    /** Forget the learned links and the usage preferences. */
    fun forgetAll(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_LINKS).apply()
    }
}
