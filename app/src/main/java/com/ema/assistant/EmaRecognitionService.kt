package com.ema.assistant

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class EmaRecognitionService : RecognitionService() {
    private var recognizer: SpeechRecognizer? = null
    private var activeCallback: Callback? = null

    override fun onStartListening(intent: Intent?, callback: Callback?) {
        activeCallback = callback ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            callback.error(SpeechRecognizer.ERROR_SERVICE_NOT_AVAILABLE)
            cleanup()
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { activeCallback?.readyForSpeech(params) }
            override fun onBeginningOfSpeech() { activeCallback?.beginningOfSpeech() }
            override fun onRmsChanged(rmsdB: Float) { activeCallback?.rmsChanged(rmsdB) }
            override fun onBufferReceived(buffer: ByteArray?) { activeCallback?.bufferReceived(buffer) }
            override fun onEndOfSpeech() { activeCallback?.endOfSpeech() }
            override fun onError(error: Int) { activeCallback?.error(error); cleanup() }
            override fun onResults(results: Bundle?) { activeCallback?.results(results ?: Bundle()); cleanup() }
            override fun onPartialResults(partialResults: Bundle?) { activeCallback?.partialResults(partialResults ?: Bundle()) }
            override fun onEvent(eventType: Int, params: Bundle?) { activeCallback?.event(eventType, params ?: Bundle()) }
        })
        recognizer?.startListening((intent ?: Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        })
    }

    override fun onStopListening(callback: Callback?) { recognizer?.stopListening() }
    override fun onCancel(callback: Callback?) { cleanup() }
    override fun onDestroy() { cleanup(); super.onDestroy() }

    private fun cleanup() {
        recognizer?.destroy()
        recognizer = null
        activeCallback = null
    }
}