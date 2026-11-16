package com.ema.assistant

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import org.vosk.Model
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide, reference-counted manager for EMA's built-in offline English
 * speech model ([EmaWakeModel]).
 *
 * Why this exists:
 *  1. Zero-latency start: keeping the loaded model warm between turns avoids a
 *     multi-second native FST load every time the owner taps Speak.
 *  2. Zero duplicate memory with "Hey EMA": [EmaWakeWordService] and
 *     [EmaOfflineSpeechEngine] lease the exact same immutable native [Model]
 *     instance, so offline command recognition works while screen-off wake is
 *     enabled without loading a second copy into RAM.
 *  3. Good memory citizenship: when no recognizer holds a lease, the model is
 *     unloaded automatically after [IDLE_KEEP_WARM_MS] or immediately on
 *     Android low-memory callbacks.
 *  4. Privacy-safe local telemetry: tracks load latency and turn count in
 *     volatile memory (never spoken words) for honest diagnostics.
 */
object EmaOfflineSpeechModel {

    enum class State {
        /** Bundled in APK assets; not yet unpacked into private storage. */
        BUNDLED,

        /** Unpacking and verifying the bundled archive on first use. */
        PREPARING,

        /** Unpacked and verified on disk; unloaded from RAM while idle. */
        INSTALLED_IDLE,

        /** Loading acoustic model and decoding graph into memory. */
        LOADING,

        /** Warm in memory and ready for immediate zero-delay recognition. */
        READY,

        /** Last load or verification attempt failed (reason in [lastFailure]). */
        FAILED
    }

    data class Availability(
        val available: Boolean,
        val reason: String,
        val unpackedOnDisk: Boolean,
        val warmInMemory: Boolean
    )

    data class Telemetry(
        val completedTurns: Int,
        val lastLoadMs: Long,
        val avgFinalizeMs: Long,
        val lastInputRoute: String
    )

    class Lease internal constructor(
        val model: Model,
        val directory: File,
        private val onRelease: () -> Unit
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                onRelease()
            }
        }
    }

    private const val IDLE_KEEP_WARM_MS = 90_000L
    private const val MIN_WARM_MEMORY_BYTES = 32L * 1024L * 1024L
    private const val MIN_COLD_MEMORY_BYTES = 96L * 1024L * 1024L
    private const val MIN_LOW_RAM_COLD_MEMORY_BYTES = 128L * 1024L * 1024L

    private val stateLock = Any()
    private val loadLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val warming = AtomicBoolean(false)
    private val warmupCallbacks = ArrayList<(Boolean) -> Unit>()

    private var cachedModel: Model? = null
    private var cachedDirectory: File? = null
    private var activeLeases = 0
    private var idleGeneration = 0L
    private var state: State = State.BUNDLED
    private var lastError: String? = null

    // Volatile privacy-safe session performance stats (no speech text ever stored).
    private var completedTurns = 0
    private var lastLoadMs = 0L
    private var emaFinalizeMs = 0.0
    private var lastInputRoute = ""

    /** True while the native acoustic model is resident in memory. */
    fun isLoaded(): Boolean = synchronized(stateLock) { cachedModel != null }

    /** Last error summary, or null when healthy. */
    fun lastFailure(): String? = synchronized(stateLock) { lastError }

    /** Privacy-safe performance snapshot for settings and diagnostics. */
    fun telemetry(): Telemetry = synchronized(stateLock) {
        Telemetry(
            completedTurns = completedTurns,
            lastLoadMs = lastLoadMs,
            avgFinalizeMs = emaFinalizeMs.toLong(),
            lastInputRoute = lastInputRoute
        )
    }

    internal fun recordTurnTelemetry(finalizeMs: Long, inputRoute: String) {
        synchronized(stateLock) {
            completedTurns++
            val clamped = finalizeMs.coerceAtLeast(1L).toDouble()
            emaFinalizeMs = if (emaFinalizeMs <= 0.0) clamped else 0.7 * emaFinalizeMs + 0.3 * clamped
            if (inputRoute.isNotBlank()) lastInputRoute = inputRoute
        }
    }

    /** Non-blocking snapshot of the engine state for Settings and diagnostics. */
    fun currentState(context: Context): State {
        val snap = synchronized(stateLock) {
            if (cachedModel != null) return State.READY
            if (state == State.PREPARING || state == State.LOADING || state == State.FAILED) return state
            null
        }
        if (snap != null) return snap
        return if (runCatching { EmaWakeModel.ready(context.applicationContext) }.getOrDefault(false)) {
            State.INSTALLED_IDLE
        } else {
            State.BUNDLED
        }
    }

    /** Plain, honest one-line status for Settings and voice status queries. */
    fun statusSummary(context: Context): String {
        val stats = telemetry()
        return when (currentState(context)) {
            State.READY -> {
                val routeNote = if (stats.lastInputRoute.isNotBlank() && stats.lastInputRoute != "built-in mic") {
                    " · ${stats.lastInputRoute}"
                } else {
                    ""
                }
                "Offline engine ready (warm in memory$routeNote · works with zero internet)."
            }
            State.INSTALLED_IDLE ->
                "Offline engine installed on this phone (loads on speak · no internet needed)."
            State.BUNDLED ->
                "Offline engine built into EMA (prepares automatically on first use · no download)."
            State.PREPARING ->
                "Preparing built-in offline speech engine (first-time setup, stays on phone)…"
            State.LOADING ->
                "Loading built-in offline speech engine…"
            State.FAILED -> {
                val detail = lastFailure()?.let { ": $it" }.orEmpty()
                "Offline speech engine paused$detail. Tap Prepare or Speak to retry."
            }
        }
    }

    /**
     * Check whether this phone can run EMA's built-in offline speech engine for
     * [languageTag] right now without risking an out-of-memory kill.
     */
    fun checkAvailability(
        context: Context,
        languageTag: String? = null,
        ignoreLanguage: Boolean = false
    ): Availability {
        val app = context.applicationContext
        val warm = isLoaded()
        val unpacked = warm || runCatching { EmaWakeModel.ready(app) }.getOrDefault(false)

        if (!ignoreLanguage && !EmaSpeechMode.supportsOfflineLanguage(languageTag)) {
            return Availability(
                available = false,
                reason = "The built-in offline model supports English; requested language is ${languageTag ?: "non-English"}.",
                unpackedOnDisk = unpacked,
                warmInMemory = warm
            )
        }

        val am = runCatching { app.getSystemService(ActivityManager::class.java) }.getOrNull()
        if (am != null) {
            val info = ActivityManager.MemoryInfo()
            runCatching { am.getMemoryInfo(info) }
            val requiredBytes = when {
                warm -> MIN_WARM_MEMORY_BYTES
                am.isLowRamDevice -> MIN_LOW_RAM_COLD_MEMORY_BYTES
                else -> MIN_COLD_MEMORY_BYTES
            }
            if ((info.lowMemory && !warm) || (info.availMem in 1 until requiredBytes)) {
                return Availability(
                    available = false,
                    reason = "Available device memory is too low to open the offline acoustic model safely.",
                    unpackedOnDisk = unpacked,
                    warmInMemory = warm
                )
            }
        }

        return Availability(
            available = true,
            reason = if (warm) "Ready in memory" else if (unpacked) "Installed on disk" else "Bundled in APK",
            unpackedOnDisk = unpacked,
            warmInMemory = warm
        )
    }

    /**
     * Acquire a counted [Lease] on the shared offline [Model].
     *
     * Worker-thread-only. Fast path returns immediately without disk I/O when
     * the model is already warm. Caller MUST close the returned [Lease] in a
     * `use {}` or `finally` block after closing any `Recognizer` built from it.
     */
    fun acquire(context: Context, onPreparing: (() -> Unit)? = null): Lease {
        tryFastLease()?.let { return it }

        val app = context.applicationContext
        synchronized(loadLock) {
            tryFastLease()?.let { return it }
            val loadStarted = SystemClock.elapsedRealtime()
            try {
                val alreadyReady = EmaWakeModel.ready(app)
                if (!alreadyReady) {
                    synchronized(stateLock) {
                        state = State.PREPARING
                        lastError = null
                    }
                    runCatching { onPreparing?.invoke() }
                }
                val directory = EmaWakeModel.prepare(app)
                synchronized(stateLock) {
                    state = State.LOADING
                    lastError = null
                }
                if (alreadyReady) {
                    runCatching { onPreparing?.invoke() }
                }
                val loaded = Model(directory.path)
                val elapsed = (SystemClock.elapsedRealtime() - loadStarted).coerceAtLeast(1L)
                synchronized(stateLock) {
                    cachedModel = loaded
                    cachedDirectory = directory
                    activeLeases = 1
                    idleGeneration++
                    state = State.READY
                    lastError = null
                    lastLoadMs = elapsed
                    return Lease(loaded, directory, ::releaseOne)
                }
            } catch (error: Throwable) {
                synchronized(stateLock) {
                    state = State.FAILED
                    lastError = error.message?.take(80) ?: error.javaClass.simpleName
                }
                throw error
            }
        }
    }

    /**
     * Unpack (if first run) and warm-load the model in the background so the
     * first spoken command starts with no preparation delay.
     */
    fun warmUpAsync(context: Context, onFinished: ((Boolean) -> Unit)? = null) {
        if (isLoaded()) {
            onFinished?.let { cb -> mainHandler.post { cb(true) } }
            return
        }
        val app = context.applicationContext
        val availability = checkAvailability(app, ignoreLanguage = true)
        if (!availability.available) {
            onFinished?.let { cb -> mainHandler.post { cb(false) } }
            return
        }
        val pendingCallbacks = synchronized(stateLock) {
            if (cachedModel != null) {
                onFinished?.let { cb -> mainHandler.post { runCatching { cb(true) } } }
                return
            }
            if (onFinished != null) warmupCallbacks += onFinished
            if (!warming.compareAndSet(false, true)) return
            true
        }
        if (!pendingCallbacks) return
        Thread({
            var ok = false
            try {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                acquire(app).use { ok = true }
            } catch (error: Throwable) {
                android.util.Log.w("EMA", "Background offline speech warm-up deferred: ${error.javaClass.simpleName}")
            } finally {
                val listeners = synchronized(stateLock) {
                    warming.set(false)
                    warmupCallbacks.toList().also { warmupCallbacks.clear() }
                }
                if (listeners.isNotEmpty()) {
                    val result = ok || isLoaded()
                    mainHandler.post { listeners.forEach { cb -> runCatching { cb(result) } } }
                }
            }
        }, "ema-offline-stt-warmup").apply {
            isDaemon = true
            start()
        }
    }

    /** Release an unleased cached model immediately when Android asks for RAM. */
    fun onTrimMemory(level: Int) {
        val shouldTrim = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        if (!shouldTrim) return
        evictIfIdle(expectedGeneration = null)
    }

    private fun tryFastLease(): Lease? = synchronized(stateLock) {
        val existing = cachedModel ?: return null
        val dir = cachedDirectory ?: return null
        activeLeases++
        idleGeneration++
        state = State.READY
        Lease(existing, dir, ::releaseOne)
    }

    private fun releaseOne() {
        val scheduleGen: Long = synchronized(stateLock) {
            activeLeases = (activeLeases - 1).coerceAtLeast(0)
            if (activeLeases == 0 && cachedModel != null) {
                ++idleGeneration
            } else {
                -1L
            }
        }
        if (scheduleGen > 0L) {
            mainHandler.postDelayed({ evictIfIdle(scheduleGen) }, IDLE_KEEP_WARM_MS)
        }
    }

    private fun evictIfIdle(expectedGeneration: Long?) {
        val toClose: Model = synchronized(stateLock) {
            if (activeLeases > 0) return
            if (expectedGeneration != null && idleGeneration != expectedGeneration) return
            val model = cachedModel ?: return
            cachedModel = null
            state = State.INSTALLED_IDLE
            model
        }
        Thread({
            runCatching { toClose.close() }
        }, "ema-offline-stt-release").apply {
            isDaemon = true
            start()
        }
    }
}
