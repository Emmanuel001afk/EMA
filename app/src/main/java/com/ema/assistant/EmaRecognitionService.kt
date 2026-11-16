package com.ema.assistant

import android.annotation.TargetApi
import android.content.AttributionSource
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * EMA's system-registered Android [RecognitionService].
 *
 * Serves two roles:
 *  1. Digital-assistant registration (`VoiceInteractionServiceInfo` requires a
 *     real recognition service declared by the assistant package).
 *  2. Full offline speech-to-text service powered by [EmaOfflineSpeechEngine]:
 *     runs EMA's built-in offline engine whenever offline recognition is
 *     configured, requested (`EXTRA_PREFER_OFFLINE` / [EXTRA_FORCE_OFFLINE]),
 *     fed an audio pipe (`EXTRA_AUDIO_SOURCE`), invoked while the phone is
 *     offline, or when no external recognizer is installed. When delegating to
 *     an explicit non-EMA platform recognizer (e.g. `SYSTEM_FIRST` online or a
 *     non-English locale), it never resolves recursively to EMA and falls back
 *     automatically to [EmaOfflineSpeechEngine] if the delegate fails on a
 *     supported language.
 */
class EmaRecognitionService : RecognitionService() {

    companion object {
        const val EXTRA_FORCE_OFFLINE = "com.ema.assistant.extra.FORCE_OFFLINE_RECOGNITION"
        const val EXTRA_FORCE_SYSTEM = "com.ema.assistant.extra.FORCE_SYSTEM_RECOGNITION"
    }

    @Volatile private var delegate: SpeechRecognizer? = null
    @Volatile private var offlineEngine: EmaOfflineSpeechEngine? = null
    @Volatile private var pausedWakeForExternalClient = false

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        release(resumeWake = false)

        val config = EmaOfflineSpeechEngine.Config.fromIntent(recognizerIntent)
        val forceOffline = recognizerIntent.getBooleanExtra(EXTRA_FORCE_OFFLINE, false)
        val forceSystem = recognizerIntent.getBooleanExtra(EXTRA_FORCE_SYSTEM, false) && !forceOffline
        val preferOffline = recognizerIntent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        val hasAudioStream = config.audioSource != null

        val offlineAvailability = EmaOfflineSpeechModel.checkAvailability(
            context = this,
            languageTag = config.languageTag,
            ignoreLanguage = forceOffline
        )
        val mode = EmaSpeechRecognition.speechMode(this)
        val online = EmaSpeechRecognition.isNetworkAvailable(this)

        val shouldRunLocalFirst = !forceSystem && offlineAvailability.available && (
            forceOffline ||
                preferOffline ||
                hasAudioStream ||
                mode.prefersEmaOffline ||
                !online
            )

        if (shouldRunLocalFirst) {
            startLocalEngine(config.copy(ignoreLanguageCheck = forceOffline), listener)
            return
        }

        // Strict offline-only mode never touches an external speech service.
        if (mode == EmaSpeechMode.EMA_OFFLINE_ONLY && !forceSystem) {
            runCatching { config.audioSource?.close() }
            val err = if (!EmaSpeechMode.supportsOfflineLanguage(config.languageTag)) {
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
            } else {
                SpeechRecognizer.ERROR_CLIENT
            }
            runCatching { listener.error(err) }
            return
        }

        // Audio-stream intents cannot be forwarded safely to arbitrary delegates if local declined.
        if (hasAudioStream) {
            runCatching { config.audioSource?.close() }
            val err = if (!EmaSpeechMode.supportsOfflineLanguage(config.languageTag)) {
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
            } else {
                SpeechRecognizer.ERROR_CLIENT
            }
            runCatching { listener.error(err) }
            return
        }

        val platform = EmaSpeechRecognition.createRecognizer(this)
        if (platform == null) {
            // No external recognizer exists on this phone: use EMA's built-in engine if viable.
            if (offlineAvailability.available) {
                startLocalEngine(config, listener)
            } else {
                val err = if (!EmaSpeechMode.supportsOfflineLanguage(config.languageTag)) {
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                } else {
                    SpeechRecognizer.ERROR_CLIENT
                }
                runCatching { listener.error(err) }
            }
            return
        }

        delegate = platform
        platform.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                runCatching { listener.readyForSpeech(params ?: Bundle()) }
            }

            override fun onBeginningOfSpeech() {
                runCatching { listener.beginningOfSpeech() }
            }

            override fun onRmsChanged(rmsdB: Float) {
                runCatching { listener.rmsChanged(rmsdB) }
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                if (buffer != null) runCatching { listener.bufferReceived(buffer) }
            }

            override fun onEndOfSpeech() {
                runCatching { listener.endOfSpeech() }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                runCatching { listener.partialResults(partialResults ?: Bundle()) }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onResults(results: Bundle?) {
                runCatching { listener.results(results ?: Bundle()) }
                release(resumeWake = true)
            }

            override fun onError(error: Int) {
                // Seamlessly recover with EMA's built-in offline engine when the platform service fails.
                val canRecoverLocally = !forceSystem &&
                    EmaRecognizerFallback.shouldFallback(error) &&
                    EmaOfflineSpeechModel.checkAvailability(this@EmaRecognitionService, config.languageTag).available
                if (canRecoverLocally) {
                    android.util.Log.w("EMA", "Platform recognizer failed ($error); switching service client to EMA offline STT")
                    runCatching { delegate?.destroy() }
                    delegate = null
                    startLocalEngine(config, listener)
                    return
                }
                runCatching { listener.error(error) }
                release(resumeWake = true)
            }
        })

        runCatching { platform.startListening(recognizerIntent) }.onFailure { err ->
            android.util.Log.e("EMA", "Recognition delegate failed to start", err)
            runCatching { delegate?.destroy() }
            delegate = null
            if (!forceSystem && offlineAvailability.available) {
                startLocalEngine(config, listener)
            } else {
                runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
                release(resumeWake = true)
            }
        }
    }

    private fun startLocalEngine(config: EmaOfflineSpeechEngine.Config, listener: Callback) {
        if (config.audioSource == null && EmaWakeWordService.isCapturing()) {
            pausedWakeForExternalClient = true
            EmaWakeWordService.pauseForSpeech()
        }
        val engine = EmaOfflineSpeechEngine(this, object : EmaOfflineSpeechEngine.Listener {
            override fun onReadyForSpeech(params: Bundle) {
                runCatching { listener.readyForSpeech(params) }
            }

            override fun onBeginningOfSpeech() {
                runCatching { listener.beginningOfSpeech() }
            }

            override fun onRmsChanged(rmsdB: Float) {
                runCatching { listener.rmsChanged(rmsdB) }
            }

            override fun onBufferReceived(buffer: ByteArray) {
                runCatching { listener.bufferReceived(buffer) }
            }

            override fun onPartialResults(text: String, rawText: String, confidence: Float) {
                runCatching { listener.partialResults(EmaSpeechResult.partialBundle(text, confidence)) }
            }

            override fun onEndOfSpeech() {
                runCatching { listener.endOfSpeech() }
            }

            override fun onResults(result: EmaSpeechResult) {
                runCatching { listener.results(result.toRecognitionBundle()) }
                offlineEngine = null
                resumeWakeIfOwned()
            }

            override fun onError(errorCode: Int, message: String) {
                runCatching { listener.error(errorCode) }
                offlineEngine = null
                resumeWakeIfOwned()
            }
        })
        offlineEngine = engine
        engine.start(config)
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCheckRecognitionSupport(recognizerIntent: Intent, supportCallback: SupportCallback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        runCatching {
            val locales = EmaSpeechMode.SUPPORTED_OFFLINE_LOCALES
            // The model ships inside the APK, so English is always supported on-device
            // with zero network downloads required.
            val support = RecognitionSupport.Builder()
                .setInstalledOnDeviceLanguages(locales)
                .setSupportedOnDeviceLanguages(locales)
                .setPendingOnDeviceLanguages(emptyList())
                .setOnlineLanguages(emptyList())
                .build()
            supportCallback.onSupportResult(support)
        }.onFailure {
            runCatching { supportCallback.onError(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT) }
        }
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    override fun onTriggerModelDownload(recognizerIntent: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val lang = EmaOfflineSpeechEngine.Config.readLanguageTag(recognizerIntent.extras)
        if (EmaSpeechMode.supportsOfflineLanguage(lang)) {
            // No internet call: unpacks and warms the bundled APK model locally.
            EmaOfflineSpeechModel.warmUpAsync(this)
        }
    }

    @TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onTriggerModelDownload(
        recognizerIntent: Intent,
        attributionSource: AttributionSource,
        listener: ModelDownloadListener
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val lang = EmaOfflineSpeechEngine.Config.readLanguageTag(recognizerIntent.extras)
        if (!EmaSpeechMode.supportsOfflineLanguage(lang)) {
            runCatching { listener.onError(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) }
            return
        }
        if (EmaOfflineSpeechModel.isLoaded() || EmaWakeModel.ready(this)) {
            EmaOfflineSpeechModel.warmUpAsync(this)
            runCatching { listener.onSuccess() }
            return
        }
        runCatching { listener.onProgress(25) }
        EmaOfflineSpeechModel.warmUpAsync(this) { ok ->
            runCatching {
                if (ok || EmaWakeModel.ready(this)) listener.onSuccess()
                else listener.onError(SpeechRecognizer.ERROR_CLIENT)
            }
        }
    }

    override fun onStopListening(listener: Callback) {
        val local = offlineEngine
        if (local != null) {
            local.stopListening()
        } else {
            runCatching { delegate?.stopListening() }
        }
    }

    override fun onCancel(listener: Callback) {
        release(resumeWake = true)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        EmaOfflineSpeechModel.onTrimMemory(level)
    }

    override fun onDestroy() {
        release(resumeWake = true)
        super.onDestroy()
    }

    private fun release(resumeWake: Boolean) {
        offlineEngine?.cancel()
        offlineEngine = null
        runCatching { delegate?.cancel() }
        runCatching { delegate?.destroy() }
        delegate = null
        if (resumeWake) resumeWakeIfOwned()
    }

    private fun resumeWakeIfOwned() {
        if (pausedWakeForExternalClient) {
            pausedWakeForExternalClient = false
            if (!EmaVoiceInteractionSession.isActiveVisible()) {
                EmaWakeWordService.resumeAfterSession()
            }
        }
    }
}
