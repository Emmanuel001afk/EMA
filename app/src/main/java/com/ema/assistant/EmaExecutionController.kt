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
     * Generation identity of the owning command.
     *
     * Without this, a step that completes *after* the command timed out would
     * still be delivered, advancing a sequence the user was already told had
     * failed. Both shutdown and reset invalidate old tokens; reset cannot revive them.
     */
    private val epoch = EmaExecutionEpoch().apply { begin() }

    /** Begin a new command: later steps are allowed to report again. */
    fun reset() {
        epoch.begin()
        handler.removeCallbacksAndMessages(null)
    }

    fun isCancelled(): Boolean = !epoch.isActive()

    fun <T> runStep(
        action: (finish: (T) -> Unit) -> Unit,
        onTimeout: () -> T,
        done: (T) -> Unit
    ) {
        val token = epoch.token()
        if (!epoch.isCurrent(token)) return
        val finished = AtomicBoolean(false)
        val timeout = Runnable {
            if (!epoch.isCurrent(token)) return@Runnable
            if (!finished.compareAndSet(false, true)) return@Runnable
            runCatching { done(onTimeout()) }
                .onFailure { android.util.Log.e("EMA", "Execution timeout callback failed", it) }
        }

        handler.postDelayed(timeout, timeoutMs)

        try {
            action { result ->
                // A result arriving after cancellation belongs to an abandoned
                // command and must not advance anything.
                if (!epoch.isCurrent(token)) return@action
                if (!finished.compareAndSet(false, true)) return@action
                handler.removeCallbacks(timeout)
                runCatching { done(result) }
                    .onFailure { android.util.Log.e("EMA", "Execution result callback failed", it) }
            }
        } catch (t: Throwable) {
            if (epoch.isCurrent(token) && finished.compareAndSet(false, true)) {
                handler.removeCallbacks(timeout)
                android.util.Log.e("EMA", "Execution step threw before completion", t)
                runCatching { done(onTimeout()) }
                    .onFailure { android.util.Log.e("EMA", "Execution fallback callback failed", it) }
            }
        }
    }

    fun shutdown() {
        epoch.invalidate()
        handler.removeCallbacksAndMessages(null)
    }
}
