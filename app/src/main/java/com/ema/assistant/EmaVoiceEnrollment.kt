package com.ema.assistant

/**
 * The enrollment of the owner's own "Hey EMA".
 *
 * The owner says "Hey EMA" a number of times and EMA keeps one template made
 * of the average of those recordings. The state machine is pure: it counts,
 * it holds the samples and it produces the template. The recording itself is
 * the phone's job.
 */
class EmaVoiceEnrollment(val required: Int = REQUIRED) {

    private val samples = mutableListOf<List<FloatArray>>()

    /** How many samples have been taken. */
    fun count(): Int = samples.size

    /** True when enough samples have been taken. */
    fun isComplete(): Boolean = samples.size >= required

    /** The samples taken so far. */
    fun samples(): List<List<FloatArray>> = samples.toList()

    /**
     * Add one recording.
     *
     * Returns true when the enrollment is complete. A recording with no sound
     * is not a sample and does not count.
     */
    fun add(features: List<FloatArray>): Boolean {
        if (features.isEmpty()) return isComplete()
        samples += EmaVoiceFeatures.resample(features)
        return isComplete()
    }

    /**
     * The template made of the average of the samples, or null when there is
     * nothing to average yet.
     */
    fun template(label: String = "enrolled", id: String = "enrolled", at: Long = 0L): EmaVoiceVerifier.Template? {
        if (samples.isEmpty()) return null
        return EmaVoiceVerifier.Template(
            id = id,
            kind = EmaVoiceVerifier.Template.Kind.POSITIVE,
            label = label,
            frames = EmaVoiceFeatures.average(samples),
            addedAt = at
        )
    }

    /** What to tell the owner while the enrollment runs. */
    fun progressText(): String {
        val left = (required - samples.size).coerceAtLeast(0)
        return if (isComplete()) {
            "That is all of them. I am learning your voice now."
        } else {
            "Say \"Hey EMA\" again, ${samples.size} of $required done."
        }
    }

    /** Start again. */
    fun reset() {
        samples.clear()
    }

    companion object {
        /** How many times the owner says "Hey EMA" during the enrollment. */
        const val REQUIRED = 8
    }
}
