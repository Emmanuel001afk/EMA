package com.ema.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs a scheduled command that arrives through a BroadcastReceiver.
 *
 * Three failure modes are handled explicitly, because all three were live:
 *
 * 1. **Broadcast ANR.** `goAsync()` only buys about ten seconds, but a command
 *    like "open Spotify and play Arcade" drives real UI automation for far
 *    longer. The broadcast is therefore always released inside a hard window,
 *    and the command keeps running on the application context afterwards. When
 *    EMA's accessibility service is bound — the normal case for UI automation —
 *    Android keeps the process alive, so nothing is cut short.
 * 2. **TextToSpeech that never initialises.** The command used to be executed
 *    only from inside the TTS init callback, so a device whose engine stalled
 *    produced silence and a leaked broadcast. A fallback starts the command
 *    anyway, without speech.
 * 3. **A router callback that never arrives.** The same deadline releases the
 *    broadcast regardless, and `finish()` is idempotent so it can be reached
 *    from any of these paths without double-finishing.
 */
object EmaReceiverRunner {

    /** Comfortably inside Android's ~10s background-broadcast limit. */
    private const val RELEASE_AFTER_MS = 8_000L

    /** How long to wait for a TTS engine before running the command silently. */
    private const val TTS_INIT_TIMEOUT_MS = 2_500L

    private val main = Handler(Looper.getMainLooper())

    fun run(context: Context, command: String, pending: BroadcastReceiver.PendingResult?) {
        if (command.isBlank()) {
            finish(pending, null)
            return
        }
        val app = context.applicationContext
        val finished = AtomicBoolean(false)
        val started = AtomicBoolean(false)
        var tts: TextToSpeech? = null

        fun release() {
            if (!finished.compareAndSet(false, true)) return
            main.removeCallbacksAndMessages(null)
            runCatching { tts?.shutdown() }
            runCatching { pending?.finish() }
                .onFailure { android.util.Log.e("EMA", "Broadcast finish failed", it) }
        }

        // Single absolute deadline, never rescheduled: the broadcast is released
        // on time even if every other path stalls.
        main.postDelayed({ release() }, RELEASE_AFTER_MS)

        fun start(provider: () -> TextToSpeech?) {
            // The fallback runnable checks `started` itself, so it does not need
            // cancelling here (and cancelling it would forward-reference a local).
            if (!started.compareAndSet(false, true)) return
            EmaCommandRouter(app, provider).execute(command) {
                release()
            }
        }

        val ttsFallback = Runnable {
            if (!started.get()) {
                android.util.Log.w("EMA", "TTS engine did not initialise; running scheduled command silently")
                start { tts }
            }
        }
        main.postDelayed(ttsFallback, TTS_INIT_TIMEOUT_MS)

        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                runCatching { tts?.let { EmaVoiceSetup.apply(app, it) } }
            } else {
                android.util.Log.w("EMA", "TTS init failed with status $status")
            }
            main.post { start { tts } }
        }
    }

    /** Release a broadcast that has nothing to do. */
    fun finish(pending: BroadcastReceiver.PendingResult?, tts: TextToSpeech?) {
        runCatching { tts?.shutdown() }
        runCatching { pending?.finish() }
    }
}
