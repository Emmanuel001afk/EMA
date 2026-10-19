package com.ema.assistant

import kotlin.math.max
import kotlin.math.min

/**
 * Dynamic time warping, the comparison used by the personal wake-word check.
 *
 * Two recordings of "Hey EMA" never line up frame by frame: the speaker is
 * faster one time and slower the next. DTW finds the cheapest path through
 * the two frame matrices, which is how two utterances of the same words are
 * compared at all.
 *
 * The result is normalised by the path length, so a long recording is not
 * punished for being long.
 *
 * Pure arithmetic, unit tested with synthetic frames.
 */
object EmaDtw {

    /** How far the path may drift from the diagonal. */
    const val BAND = 10

    /** The distance between two utterances, or [Float.MAX_VALUE] when there is nothing to compare. */
    fun distance(a: List<FloatArray>, b: List<FloatArray>, band: Int = BAND): Float {
        if (a.isEmpty() || b.isEmpty()) return Float.MAX_VALUE
        val n = a.size
        val m = b.size
        val unreachable = Float.MAX_VALUE / 4
        var previous = FloatArray(m + 1) { unreachable }
        var current = FloatArray(m + 1) { unreachable }
        previous[0] = 0f
        for (i in 1..n) {
            current.fill(unreachable)
            val from = max(1, i - band)
            val to = min(m, i + band)
            for (j in from..to) {
                val cost = frameDistance(a[i - 1], b[j - 1])
                val best = minOf(previous[j], current[j - 1], previous[j - 1])
                current[j] = cost + best
            }
            val swap = previous
            previous = current
            current = swap
        }
        val total = previous[m]
        if (total >= unreachable) return Float.MAX_VALUE
        return total / (n + m).toFloat()
    }

    /** The Euclidean distance between two frames. */
    fun frameDistance(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return Float.MAX_VALUE
        var sum = 0.0
        for (i in a.indices) {
            val difference = a[i].toDouble() - b[i].toDouble()
            sum += difference * difference
        }
        return Math.sqrt(sum).toFloat()
    }
}
