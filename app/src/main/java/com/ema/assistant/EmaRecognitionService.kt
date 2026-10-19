package com.ema.assistant

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * EMA's own recognition service.
 *
 * Declaring one is mandatory for Android's digital-assistant registration:
 * `VoiceInteractionServiceInfo` treats a voice-interaction XML without a
 * recognition service as a parse error, so the assistant never appears in the
 * system list. It delegates to an explicit platform recogniser by default and
 * runs the local Vosk backend only when a request selects it; it never resolves
 * to itself recursively.
 */
class EmaRecognitionService : RecognitionService() {

    companion object {
        const val EXTRA_FORCE_OFFLINE = "com.ema.assistant.extra.FORCE_OFFLINE_RECOGNITION"
    }

    @Volatile private var delegate: SpeechRecognizer? = null
    @Volatile private var offline: EmaOfflineCommandRecognition? = null

    override fun onCreate() {
        super.onCreate()
        // Nothing eager: the recogniser is created only when a client listens.
    }

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        release()
        if (recognizerIntent.getBooleanExtra(EXTRA_FORCE_OFFLINE, false)) {
            val local = EmaOfflineCommandRecognition(this, listener)
            offline = local
            local.start()
            return
        }
        val recognizer = EmaSpeechRecognition.createRecognizer(this)
        if (recognizer == null) {
            listener.error(SpeechRecognizer.ERROR_CLIENT)
            return
        }
        delegate = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = listener.readyForSpeech(params)
            override fun onBeginningOfSpeech() = listener.beginningOfSpeech()
            override fun onRmsChanged(rmsdB: Float) = listener.rmsChanged(rmsdB)
            override fun onBufferReceived(buffer: ByteArray?) = listener.bufferReceived(buffer)
            override fun onEndOfSpeech() = listener.endOfSpeech()
            override fun onPartialResults(partialResults: Bundle?) = listener.partialResults(partialResults)

            // RecognitionService has no client-facing event channel in this API.
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onResults(results: Bundle?) {
                runCatching { listener.results(results) }
                release()
            }

            override fun onError(error: Int) {
                runCatching { listener.error(error) }
                release()
            }
        })
        runCatching { recognizer.startListening(recognizerIntent) }.onFailure {
            android.util.Log.e("EMA", "Recognition delegate could not start listening", it)
            runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
            release()
        }
    }

    override fun onStopListening(listener: Callback) {
        offline?.stopListening() ?: runCatching { delegate?.stopListening() }
    }

    override fun onCancel(listener: Callback) {
        offline?.cancel()
        runCatching { delegate?.cancel() }
        release()
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        offline?.cancel()
        offline = null
        runCatching { delegate?.destroy() }
        delegate = null
    }
}
