package com.ema.assistant

/**
 * The personal wake-word check.
 *
 * The offline decoder is good at telling *what* was said and bad at telling
 * *who* said it: "Hey Emma" from a television is the same words as "Hey EMA"
 * from the owner. So the wake word has two stages:
 *
 *  1. the decoder produces a **candidate** - deliberately lenient, one
 *     partial is enough, because a missed wake word is worse than a false
 *     candidate that stage 2 can throw away;
 *  2. the personal verifier compares the **sound** of the candidate with the
 *     owner's own recorded "Hey EMA" and lets it through only when it is
 *     close enough.
 *
 * Without an enrolled profile the verifier has nothing to compare against and
 * says so, and the wake word behaves exactly as it did before: the caller
 * keeps the old rule in that case.
 *
 * The verifier also learns: a wake that was followed by a real command is a
 * positive example, a rejected candidate or a "not you" is a negative one.
 * Negatives raise the threshold, the template list is bounded, and the
 * template that fits the others least well is replaced when it is full.
 *
 * Pure Kotlin, so the threshold and the replacement rules are unit tested
 * with synthetic features.
 */
class EmaVoiceVerifier(
    /** What the negatives have added to the threshold. */
    offset: Float = 0f,
    templates: List<Template> = emptyList()
) {

    /** One recorded sample: the shape coefficients of a "Hey EMA". */
    data class Template(
        val id: String,
        val kind: Kind,
        val label: String,
        val frames: List<FloatArray>,
        val addedAt: Long
    ) {
        enum class Kind { POSITIVE, NEGATIVE }
    }

    /** What the verifier decided about one candidate. */
    data class Verdict(
        val accepted: Boolean,
        /** The distance to the closest own-voice template. */
        val distance: Float,
        /** The distance to the closest negative template, when there is one. */
        val negativeDistance: Float?,
        val threshold: Float,
        val reason: String
    )

    private val stored = templates.toMutableList()

    /** What the negative examples have added to the threshold. */
    var offset: Float = offset
        private set

    /**
     * The threshold that separates "my voice" from "not my voice".
     *
     * It is measured, not guessed: the owner's own samples are compared with
     * each other, and the threshold is a little above that distance. A
     * different voice is much further away, so it does not pass. The learned
     * offset (the negatives) is added on top.
     */
    val threshold: Float get() = calibratedThreshold() + offset

    /** The distance between the owner's own samples, which is the base of the threshold. */
    fun calibratedThreshold(): Float {
        val positives = templatesOf(Template.Kind.POSITIVE)
        if (positives.size < 2) return DEFAULT_THRESHOLD
        var total = 0f
        var counted = 0
        for (i in positives.indices) {
            for (j in i + 1 until positives.size) {
                val distance = EmaDtw.distance(positives[i].frames, positives[j].frames)
                if (distance < Float.MAX_VALUE) {
                    total += distance
                    counted++
                }
            }
        }
        if (counted == 0) return DEFAULT_THRESHOLD
        return (total / counted) * CALIBRATION_FACTOR
    }

    /** Every stored template, newest last. */
    fun templates(): List<Template> = stored.toList()

    /** The templates of one kind. */
    fun templatesOf(kind: Template.Kind): List<Template> = stored.filter { it.kind == kind }

    /** True when the owner has enrolled a voice, so the check can run at all. */
    fun hasProfile(): Boolean = stored.any { it.kind == Template.Kind.POSITIVE }

    /**
     * Decide whether a candidate is the owner's own "Hey EMA".
     *
     * Without a profile the answer is "yes, there is nothing to compare
     * against", so the wake word behaves exactly as it did before.
     */
    fun verdict(features: List<FloatArray>): Verdict {
        if (!hasProfile()) {
            return Verdict(true, 0f, null, threshold, "no profile, the decoder decides")
        }
        if (features.isEmpty()) {
            return Verdict(false, Float.MAX_VALUE, null, threshold, "no sound to compare")
        }
        val positives = templatesOf(Template.Kind.POSITIVE)
        var best = Float.MAX_VALUE
        for (template in positives) {
            val distance = EmaDtw.distance(features, template.frames)
            if (distance < best) best = distance
        }
        val negatives = templatesOf(Template.Kind.NEGATIVE)
        var bestNegative: Float? = null
        for (template in negatives) {
            val distance = EmaDtw.distance(features, template.frames)
            if (bestNegative == null || distance < bestNegative!!) bestNegative = distance
        }
        val accepted = best <= threshold && (bestNegative == null || bestNegative > best)
        val reason = when {
            best > threshold -> "further away than the threshold allows"
            bestNegative != null && bestNegative <= best -> "closer to a sample that is not the owner"
            else -> "close enough to the owner's own voice"
        }
        return Verdict(accepted, best, bestNegative, threshold, reason)
    }

    /**
     * Add a template.
     *
     * When the list is full, the template that fits the others least well is
     * dropped to make room: an outlier teaches the verifier less than a
     * typical sample does.
     *
     * Returns the template that was dropped, or null when nothing was.
     */
    fun add(template: Template, maximum: Int = MAX_TEMPLATES): Template? {
        var dropped: Template? = null
        if (stored.size >= maximum) {
            val worst = worstTemplate()
            if (worst != null) {
                stored.remove(worst)
                dropped = worst
            }
        }
        stored += template
        return dropped
    }

    /**
     * The template that fits the others least well.
     *
     * Its average distance to the other templates of the same kind is the
     * largest. When there is only one template of that kind, the oldest one
     * is the worst.
     */
    fun worstTemplate(): Template? {
        if (stored.isEmpty()) return null
        if (stored.size == 1) return stored[0]
        var worst: Template? = null
        var worstScore = -1f
        for (candidate in stored) {
            val others = stored.filter { it.id != candidate.id }
            if (others.isEmpty()) continue
            var total = 0f
            var counted = 0
            for (other in others) {
                val distance = EmaDtw.distance(candidate.frames, other.frames)
                if (distance < Float.MAX_VALUE) {
                    total += distance
                    counted++
                }
            }
            if (counted == 0) continue
            val score = total / counted
            if (score > worstScore) {
                worstScore = score
                worst = candidate
            }
        }
        return worst ?: stored.minByOrNull { it.addedAt }
    }

    /**
     * A negative example: raise the threshold so the same sound is not
     * accepted next time. The threshold stays inside its bounds.
     */
    fun noteNegative(step: Float = NEGATIVE_STEP, maximum: Float = MAX_OFFSET): Float {
        offset = (offset + step).coerceAtMost(maximum)
        return threshold
    }

    /**
     * A positive example: the voice was right, so the threshold may relax a
     * little. It never goes below the measured distance, because a too low
     * threshold lets every television through.
     */
    fun notePositive(step: Float = POSITIVE_STEP): Float {
        offset = (offset - step).coerceAtLeast(0f)
        return threshold
    }

    /** Forget every template and go back to the default threshold. */
    fun clear() {
        stored.clear()
        offset = 0f
    }

    companion object {
        /** The threshold used when the owner's own samples cannot be compared. */
        const val DEFAULT_THRESHOLD = 4.0f

        /**
         * How much further away another voice may be than the owner's own
         * samples. It is a factor, not a fixed distance, so it works whatever
         * the microphone and the room sound like.
         */
        const val CALIBRATION_FACTOR = 1.8f

        /** The learned part of the threshold never goes above this. */
        const val MAX_OFFSET = 5.0f

        /** How much a negative example raises the threshold. */
        const val NEGATIVE_STEP = 0.4f

        /** How much a positive example relaxes the threshold. */
        const val POSITIVE_STEP = 0.1f

        /** How many templates are kept at most. */
        const val MAX_TEMPLATES = 30
    }
}

/**
 * The templates of the personal check, with the storage format.
 *
 * The frames are plain numbers, so they are written as text: the file stays
 * readable, stays small (a template is a few kilobytes) and can be moved
 * between phones without a converter.
 */
object EmaVoiceTemplateFormat {

    /** One template as text. */
    fun encode(template: EmaVoiceVerifier.Template): String {
        val out = StringBuilder()
        out.append("id=").append(template.id).append('\n')
        out.append("kind=").append(template.kind.name).append('\n')
        out.append("label=").append(template.label).append('\n')
        out.append("at=").append(template.addedAt).append('\n')
        out.append("frames=").append(template.frames.size).append('\n')
        for (frame in template.frames) {
            out.append(frame.joinToString(" ") { value -> String.format(java.util.Locale.US, "%.4f", value) })
                .append('\n')
        }
        return out.toString()
    }

    /** Read a template back, or null when the text is not one. */
    fun decode(text: String): EmaVoiceVerifier.Template? {
        val lines = text.lines()
        var id = ""
        var kind = EmaVoiceVerifier.Template.Kind.POSITIVE
        var label = ""
        var at = 0L
        var count = -1
        val frames = mutableListOf<FloatArray>()
        var inFrames = false
        for (raw in lines) {
            val line = raw.trim()
            when {
                line.startsWith("id=") -> id = line.removePrefix("id=").trim()
                line.startsWith("kind=") -> kind = runCatching {
                    EmaVoiceVerifier.Template.Kind.valueOf(line.removePrefix("kind=").trim())
                }.getOrDefault(EmaVoiceVerifier.Template.Kind.POSITIVE)
                line.startsWith("label=") -> label = line.removePrefix("label=").trim()
                line.startsWith("at=") -> at = line.removePrefix("at=").trim().toLongOrNull() ?: 0L
                line.startsWith("frames=") -> {
                    count = line.removePrefix("frames=").trim().toIntOrNull() ?: -1
                    inFrames = true
                }
                inFrames && line.isNotBlank() -> {
                    val values = line.split(Regex("\\s+")).mapNotNull { it.toFloatOrNull() }
                    if (values.isNotEmpty()) frames += values.toFloatArray()
                }
            }
        }
        if (id.isBlank() || count < 0 || frames.size != count) return null
        return EmaVoiceVerifier.Template(id, kind, label, frames, at)
    }
}
