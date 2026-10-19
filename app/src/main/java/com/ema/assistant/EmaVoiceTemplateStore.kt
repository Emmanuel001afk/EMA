package com.ema.assistant

import android.content.Context
import java.io.File

/**
 * The owner's own voice templates, stored on the phone.
 *
 * The templates are small (a few kilobytes each) and are written as text, so
 * they can be read, copied and checked. Nothing leaves the device, and the
 * files live in the app's private storage.
 */
object EmaVoiceTemplateStore {

    private const val DIRECTORY = "ema_voice"
    private const val PREFS = "ema_voice"
    private const val KEY_THRESHOLD = "threshold"
    private const val KEY_OFFSET = "offset"
    private const val KEY_ENROLLED = "enrolled"

    private fun directory(context: Context): File =
        File(context.filesDir, DIRECTORY).apply { if (!exists()) mkdirs() }

    /** Every stored template, oldest first. */
    fun templates(context: Context): List<EmaVoiceVerifier.Template> {
        val files = directory(context).listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.name.endsWith(".txt") }
            .mapNotNull { file ->
                runCatching { EmaVoiceTemplateFormat.decode(file.readText()) }.getOrNull()
            }
            .sortedBy { it.addedAt }
    }

    /** True when the owner has enrolled a voice. */
    fun isEnrolled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENROLLED, false) &&
            templates(context).any { it.kind == EmaVoiceVerifier.Template.Kind.POSITIVE }

    /** Store a template. An older template with the same id is replaced. */
    fun add(context: Context, template: EmaVoiceVerifier.Template) {
        runCatching {
            val file = File(directory(context), safeName(template.id) + ".txt")
            file.writeText(EmaVoiceTemplateFormat.encode(template))
        }
        if (template.kind == EmaVoiceVerifier.Template.Kind.POSITIVE) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENROLLED, true).apply()
        }
    }

    /** Remove one template, for example the one that was replaced. */
    fun remove(context: Context, id: String) {
        runCatching {
            val file = File(directory(context), safeName(id) + ".txt")
            if (file.exists()) file.delete()
        }
    }

    /** The learned threshold, or the default when there is none. */
    fun threshold(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_THRESHOLD, EmaVoiceVerifier.DEFAULT_THRESHOLD)

    /** Keep the learned threshold. */
    fun setThreshold(context: Context, threshold: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_THRESHOLD, threshold).apply()
    }

    /** What the negative examples have added to the threshold. */
    fun offset(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY_OFFSET, 0f)

    /** Keep the learned part of the threshold. */
    fun setOffset(context: Context, offset: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_OFFSET, offset).apply()
    }

    /** The verifier with the stored templates and the learned threshold. */
    fun verifier(context: Context): EmaVoiceVerifier =
        EmaVoiceVerifier(offset(context), templates(context))

    /** Forget every template and the learned threshold. */
    fun clear(context: Context) {
        runCatching {
            directory(context).listFiles()?.forEach { it.delete() }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_THRESHOLD).remove(KEY_OFFSET).remove(KEY_ENROLLED).apply()
    }

    /** How the owner is told about the personal check. */
    fun statusText(context: Context): String {
        val templates = templates(context)
        if (!isEnrolled(context)) {
            return "The personal voice check is off. EMA uses the decoder alone, exactly as before."
        }
        val positives = templates.count { it.kind == EmaVoiceVerifier.Template.Kind.POSITIVE }
        val negatives = templates.count { it.kind == EmaVoiceVerifier.Template.Kind.NEGATIVE }
        val verifier = verifier(context)
        return "The personal voice check is on: $positives of your own \"Hey EMA\", $negatives other sounds. " +
            "Threshold ${String.format(java.util.Locale.US, "%.2f", verifier.threshold)}."
    }

    private fun safeName(id: String): String = id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(60)
}
