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
 * system list. The service delegates to an explicit external recogniser (or the
 * on-device one) — never to the default recogniser, which could resolve back to
 * this service and recurse.
 */
class EmaRecognitionService : RecognitionService() {

    @Volatile private var delegate: SpeechRecognizer? = null

    override fun onCreate() {
        super.onCreate()
        // Nothing eager: the recogniser is created only when a client listens.
    }

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        release()
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
        runCatching { delegate?.stopListening() }
    }

    override fun onCancel(listener: Callback) {
        runCatching { delegate?.cancel() }
        release()
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        runCatching { delegate?.destroy() }
        delegate = null
    }
}
