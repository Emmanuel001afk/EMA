package com.ema.assistant

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

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

    /**
     * Set once the owning command has reached its terminal result.
     *
     * Without this, a step that completes *after* the command timed out would
     * still be delivered, advancing a sequence the user was already told had
     * failed. `shutdown()` raises it; `reset()` clears it for the next command.
     */
    private val cancelled = AtomicBoolean(false)

    /** Begin a new command: later steps are allowed to report again. */
    fun reset() {
        cancelled.set(false)
    }

    fun isCancelled(): Boolean = cancelled.get()

    fun <T> runStep(
        action: (finish: (T) -> Unit) -> Unit,
        onTimeout: () -> T,
        done: (T) -> Unit
    ) {
        val finished = AtomicBoolean(false)
        val timeout = Runnable {
            if (cancelled.get()) return@Runnable
            if (!finished.compareAndSet(false, true)) return@Runnable
            runCatching { done(onTimeout()) }
                .onFailure { android.util.Log.e("EMA", "Execution timeout callback failed", it) }
        }

        handler.postDelayed(timeout, timeoutMs)

        try {
            action { result ->
                // A result arriving after cancellation belongs to an abandoned
                // command and must not advance anything.
                if (cancelled.get()) return@action
                if (!finished.compareAndSet(false, true)) return@action
                handler.removeCallbacks(timeout)
                runCatching { done(result) }
                    .onFailure { android.util.Log.e("EMA", "Execution result callback failed", it) }
            }
        } catch (t: Throwable) {
            if (finished.compareAndSet(false, true)) {
                handler.removeCallbacks(timeout)
                android.util.Log.e("EMA", "Execution step threw before completion", t)
                runCatching { done(onTimeout()) }
                    .onFailure { android.util.Log.e("EMA", "Execution fallback callback failed", it) }
            }
        }
    }

    fun shutdown() {
        cancelled.set(true)
        handler.removeCallbacksAndMessages(null)
    }
}
