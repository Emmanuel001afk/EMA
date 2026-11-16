package com.ema.assistant

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.*
import org.vosk.Recognizer
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Local software keyword detector. FGS + partial wake lock support screen-off capture.
 * Not a DSP hotword engine; battery use and false triggers must be measured on devices.
 */
class EmaWakeWordService : Service() {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var recorder: AudioRecord? = null
    private val captureLock = ReentrantLock()
    private val captureReleased = captureLock.newCondition()
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val prefs by lazy { getSharedPreferences("ema", MODE_PRIVATE) }

    /** The last two seconds of sound, so the personal check has something to compare. */
    private val recent = ArrayDeque<ShortArray>()
    private var recentSamples = 0

    /** The sound of the wake that is waiting for a command to confirm it. */
    @Volatile private var pendingWake: ShortArray? = null

    companion object {
        private const val CHANNEL = "ema_wake_word"
        private const val ID = 701

        /** How much sound around the candidate is compared. */
        private const val WAKE_AUDIO_MILLIS = 900

        /** How much sound is kept for the comparison (two seconds). */
        private const val MAX_RECENT_SAMPLES = 32_000
        @Volatile private var active: EmaWakeWordService? = null
        fun isRunning() = active?.running == true
        fun isCapturing() = active?.recorder != null
        fun pauseForSpeech() { active?.pauseCapture() }
        fun resumeAfterSession() { active?.let { it.paused = false } }

        /**
         * Wait up to [timeoutMs] on a worker thread until wake-word capture has
         * fully released [AudioRecord], preventing mic contention during handoff.
         */
        fun awaitCaptureReleased(timeoutMs: Long = 650L): Boolean {
            val svc = active ?: return true
            if (svc.recorder == null) return true
            val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(0L)
            svc.captureLock.withLock {
                while (svc.recorder != null) {
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    if (remaining <= 0L) break
                    runCatching { svc.captureReleased.await(remaining.coerceAtMost(80L), TimeUnit.MILLISECONDS) }
                }
            }
            return svc.recorder == null
        }

        /**
         * Tell the wake word that a command really ran after the wake.
         *
         * A wake that was followed by a real command is a positive example of
         * the owner's own voice, so it is learned. A wake that was followed by
         * nothing is not.
         */
        fun confirmLastWake(commandRan: Boolean) {
            active?.confirmWake(commandRan)
        }

        /**
         * The owner says the wake word was not them ("not you").
         *
         * The sound of that wake becomes a negative example, so the same sound
         * is less likely to pass the personal check next time.
         */
        fun markNotYou() {
            active?.markNotYou()
        }
        fun startFromVisibleActivity(context: Context) {
            try {
                val intent = Intent(context, EmaWakeWordService::class.java)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            } catch (e: Exception) {
                context.getSharedPreferences("ema", MODE_PRIVATE).edit()
                    .putString("wake_status", "Android blocked listening. Open EMA and tap Resume. ${e.javaClass.simpleName}").apply()
            }
        }
        fun postResumeNotice(context: Context, message: String) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Hey EMA listening", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            runCatching { manager.notify(ID, Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_ema_logo).setContentTitle("Hey EMA is stopped")
                .setContentText(message).setContentIntent(open).setAutoCancel(true).build()) }
        }
    }

    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        active = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Hey EMA listening", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            prefs.edit().putBoolean("wake_enabled", false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) { paused = false; return START_NOT_STICKY }
        try {
            startForeground(ID, notification("Starting local detector…"))
            check(prefs.getBoolean("wake_enabled", false)) { "Wake word is switched off" }
            check(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "Microphone permission required" }
            running = true
            worker = Thread({ detect() }, "ema-local-wake").apply { start() }
        } catch (e: Exception) {
            update("Stopped: ${e.message}")
            stopSelf()
        }
        // Do not silently restart a microphone FGS from the background. Modern
        // Android requires a user-visible start; force-stop is never circumvented.
        return START_NOT_STICKY
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, EmaWakeActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, EmaWakeWordService::class.java).setAction("STOP"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_ema_logo)
            .setContentTitle("Hey EMA • local microphone").setContentText(text)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Stop listening", stop).build()).build()
    }

    private fun update(text: String) {
        prefs.edit().putString("wake_status", text).apply()
        if (running) getSystemService(NotificationManager::class.java).notify(ID, notification(text))
    }

    private fun pauseCapture() {
        paused = true
        runCatching { recorder?.stop() }
    }

    private fun detect() {
        try {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
            // Share the process-wide EmaOfflineSpeechModel lease so offline command
            // recognition runs instantaneously with zero duplicate RAM while Hey EMA is on.
            EmaOfflineSpeechModel.acquire(
                context = this,
                onPreparing = { update("Preparing built-in Hey EMA engine • no download needed") }
            ).use { lease ->
                if (!running) return
                val model = lease.model
                while (running) {
                    if (paused || EmaVoiceInteractionSession.isActiveVisible()) {
                        update("Paused • microphone handed to EMA. Close the assistant to resume.")
                        while (running && (paused || EmaVoiceInteractionSession.isActiveVisible())) Thread.sleep(150)
                        continue
                    }
                    Recognizer(model, 16000f, EmaWakeDetector.grammarJson()).use { decoder ->
                        val size = maxOf(4096, AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
                        val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size * 2)
                        captureLock.withLock { recorder = audio }
                        try {
                            check(audio.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
                            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EMA:local-wake").apply { acquire(60 * 60 * 1000L) }
                            audio.startRecording()
                            update("Listening locally for Hey EMA • screen-off enabled")
                            // Microphone capture remains active for screen-off wake;
                            // only bounded, voice-like windows reach Vosk.
                            val gate = EmaWakeAudioGate()
                            // With an enrolled voice the decoder may be lenient:
                            // one partial is enough to make a candidate, and the
                            // personal check throws the false ones away. Without
                            // an enrolled voice the old rule is kept.
                            val detector = EmaWakeDetector(lenient = runCatching {
                                EmaVoiceTemplateStore.isEnrolled(this@EmaWakeWordService)
                            }.getOrDefault(false))
                            val buffer = ByteArray(1600) // 50 ms, PCM16 mono 16 kHz
                            var hpPrevIn = 0f
                            var hpPrevOut = 0f
                            var renewal = SystemClock.elapsedRealtime()
                            capture@ while (running && !paused && !EmaVoiceInteractionSession.isActiveVisible()) {
                                val count = audio.read(buffer, 0, buffer.size)
                                if (count <= 0) {
                                    if (paused || !running) break
                                    error("Microphone capture stopped ($count)")
                                }
                                val even = count - count % 2
                                // Remove DC offset and sub-75 Hz handling rumble before gating.
                                var b = 0
                                while (b + 1 < even) {
                                    val x = ((buffer[b].toInt() and 0xff) or (buffer[b + 1].toInt() shl 8)).toShort().toFloat()
                                    val y = 0.971f * (hpPrevOut + x - hpPrevIn)
                                    hpPrevIn = x
                                    hpPrevOut = y
                                    val s = y.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                                    buffer[b] = (s and 0xff).toByte()
                                    buffer[b + 1] = ((s ushr 8) and 0xff).toByte()
                                    b += 2
                                }
                                val window = gate.accept(buffer, even)
                                for (frame in window.audio) {
                                    rememberAudio(frame)
                                    val final = decoder.acceptWaveForm(frame, frame.size)
                                    val words = if (final) {
                                        EmaVoskJsonParser.parseResult(decoder.result).firstOrNull()?.text.orEmpty()
                                    } else {
                                        EmaVoskJsonParser.parsePartial(decoder.partialResult).text
                                    }
                                    // Token search, not string equality: "[unk] hey emma" counts.
                                    val heard = if (final) detector.onFinal(words) else detector.onPartial(words)
                                    if (heard) {
                                        // Stage two: is that really the owner's
                                        // own "Hey EMA"? Without a profile there
                                        // is nothing to compare with, and the
                                        // wake word behaves exactly as before.
                                        if (!personalCheckAccepts()) {
                                            detector.reset()
                                            continue
                                        }
                                        paused = true
                                        // Release AudioRecord before activating the session so EMA's
                                        // live command recognizer acquires the microphone with zero contention.
                                        runCatching { audio.stop() }
                                        runCatching { audio.release() }
                                        captureLock.withLock {
                                            recorder = null
                                            captureReleased.signalAll()
                                        }
                                        main.post { activate() }
                                        break@capture
                                    }
                                }
                                if (window.resetDecoderAfter) { decoder.reset(); detector.reset() }
                                if (SystemClock.elapsedRealtime() - renewal > 30 * 60 * 1000L) {
                                    wakeLock?.let { if (it.isHeld) it.release(); it.acquire(60 * 60 * 1000L) }
                                    renewal = SystemClock.elapsedRealtime()
                                }
                            }
                        } finally {
                            runCatching { audio.stop() }
                            runCatching { audio.release() }
                            captureLock.withLock {
                                recorder = null
                                captureReleased.signalAll()
                            }
                            wakeLock?.let { if (it.isHeld) it.release() }
                            wakeLock = null
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            // Includes unsupported native ABI/model loading failures; don't leave
            // a foreground notification claiming an unavailable detector runs.
            if (running) {
                prefs.edit().putString("wake_status", "Stopped: ${e.message}. Open EMA to resume.").apply()
                main.post { stopSelf() }
            }
        }
    }

    /**
     * The personal wake-word check.
     *
     * The decoder only says *what* was heard, so a television saying "Hey EMA"
     * is the same words as the owner saying it. When the owner has enrolled
     * their voice, the sound is compared with their own recorded "Hey EMA" and
     * a candidate that does not fit is thrown away - and remembered as a
     * negative example, so the same sound is less likely to pass next time.
     *
     * Returns true when the wake may go on.
     */
    private fun personalCheckAccepts(): Boolean {
        val tail = recentTail(WAKE_AUDIO_MILLIS)
        if (tail.isEmpty()) return true
        val features = EmaVoiceFeatures.mfcc(tail)
        if (features.isEmpty()) return true
        val verifier = runCatching { EmaVoiceTemplateStore.verifier(this) }.getOrNull() ?: return true
        if (!verifier.hasProfile()) return true
        val verdict = verifier.verdict(features)
        if (!verdict.accepted) {
            android.util.Log.i("EMA", "Wake candidate rejected: ${verdict.reason} (${verdict.distance})")
            learn(features, EmaVoiceVerifier.Template.Kind.NEGATIVE)
            return false
        }
        pendingWake = tail
        return true
    }

    /**
     * Learn one sample of the owner's voice.
     *
     * The list is bounded and the sample that fits the others least well is
     * replaced when it is full. A negative example also raises the threshold.
     */
    private fun learn(features: List<FloatArray>, kind: EmaVoiceVerifier.Template.Kind) {
        runCatching {
            val verifier = EmaVoiceTemplateStore.verifier(this)
            val template = EmaVoiceVerifier.Template(
                id = "${kind.name.lowercase()}-${System.currentTimeMillis()}",
                kind = kind,
                label = kind.name.lowercase(),
                frames = EmaVoiceFeatures.resample(features),
                addedAt = System.currentTimeMillis()
            )
            val dropped = verifier.add(template)
            EmaVoiceTemplateStore.add(this, template)
            if (dropped != null) EmaVoiceTemplateStore.remove(this, dropped.id)
            if (kind == EmaVoiceVerifier.Template.Kind.NEGATIVE) {
                verifier.noteNegative()
                EmaVoiceTemplateStore.setOffset(this, verifier.offset)
            }
        }.onFailure { android.util.Log.w("EMA", "Could not learn the wake sample", it) }
    }

    /** The owner says the wake word was not them: learn it as a negative. */
    private fun markNotYou() {
        val pending = pendingWake ?: return
        pendingWake = null
        val features = EmaVoiceFeatures.mfcc(pending)
        if (features.isEmpty()) return
        learn(features, EmaVoiceVerifier.Template.Kind.NEGATIVE)
    }

    /** A wake that was followed by a real command is the owner's own voice. */
    private fun confirmWake(commandRan: Boolean) {
        val pending = pendingWake ?: return
        pendingWake = null
        if (!commandRan) return
        val features = EmaVoiceFeatures.mfcc(pending)
        if (features.isEmpty()) return
        learn(features, EmaVoiceVerifier.Template.Kind.POSITIVE)
    }

    /** Keep the last two seconds of sound. */
    private fun rememberAudio(frame: ByteArray) {
        val samples = ShortArray(frame.size / 2)
        for (i in samples.indices) {
            samples[i] = ((frame[i * 2].toInt() and 255) or (frame[i * 2 + 1].toInt() shl 8)).toShort()
        }
        recent.addLast(samples)
        recentSamples += samples.size
        while (recentSamples > MAX_RECENT_SAMPLES && recent.isNotEmpty()) {
            recentSamples -= recent.removeFirst().size
        }
    }

    /** The last [millis] milliseconds of sound. */
    private fun recentTail(millis: Int): ShortArray {
        val wanted = (16000 * millis / 1000).coerceAtMost(recentSamples)
        if (wanted <= 0) return ShortArray(0)
        val out = ShortArray(wanted)
        var written = wanted
        for (i in recent.indices.reversed()) {
            if (written <= 0) break
            val frame = recent[recent.size - 1 - i]
            val take = minOf(written, frame.size)
            System.arraycopy(frame, frame.size - take, out, written - take, take)
            written -= take
        }
        return out
    }

    private fun activate() {
        if (!running) return
        // Release capture before Android SpeechRecognizer takes the microphone.
        if (recorder != null) { main.postDelayed({ activate() }, 100); return }
        update("Hey EMA detected • opening assistant, or tap here to continue")
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (EmaVoiceInteractionService.showFromWake()) {
            main.postDelayed({
                if (running && !EmaVoiceInteractionSession.isActiveVisible()) {
                    update("Activation blocked by Android • tap notification to open EMA")
                    paused = false
                }
            }, 5000)
            return
        }
        // Notification PendingIntent is the allowed fallback when background UI
        // launch or a secure keyguard prevents direct assistant activation.
        update("Hey EMA detected • tap to wake EMA${if (keyguard.isKeyguardLocked) " and unlock securely" else ""}")
        main.postDelayed({ if (running && !EmaVoiceInteractionSession.isActiveVisible()) paused = false }, 30_000)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        EmaOfflineSpeechModel.onTrimMemory(level)
    }

    override fun onDestroy() {
        running = false
        pauseCapture()
        worker?.interrupt()
        main.removeCallbacksAndMessages(null)
        if (active === this) active = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
