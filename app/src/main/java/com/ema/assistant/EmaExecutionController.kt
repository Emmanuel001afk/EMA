package com.ema.assistant

import android.os.Handler
import android.os.Looper

/**
 * Lightweight lifecycle guard for multi-step assistant execution.
 *
 * It guarantees that each step reaches exactly one terminal callback. A late
 * callback from a timed-out/cancelled step is ignored, preventing a stale
 * operation from advancing a later step or leaving EMA stuck indefinitely.
 */
class EmaExecutionController(
    private val timeoutMs: Long = 25_000L
) {
    private val handler = Handler(Looper.getMainLooper())

    fun <T> runStep(
        action: (finish: (T) -> Unit) -> Unit,
        onTimeout: () -> T,
        done: (T) -> Unit
    ) {
        var finished = false
        val timeout = Runnable {
            if (finished) return@Runnable
            finished = true
            done(onTimeout())
        }

        handler.postDelayed(timeout, timeoutMs)

        try {
            action { result ->
                if (finished) return@action
                finished = true
                handler.removeCallbacks(timeout)
                done(result)
            }
        } catch (_: Throwable) {
            if (!finished) {
                finished = true
                handler.removeCallbacks(timeout)
                done(onTimeout())
            }
        }
    }

    fun shutdown() {
        handler.removeCallbacksAndMessages(null)
    }
}
