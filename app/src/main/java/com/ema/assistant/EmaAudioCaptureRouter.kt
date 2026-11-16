package com.ema.assistant

import android.annotation.TargetApi
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages microphone opening, exclusive transient audio focus, peripheral
 * routing (Bluetooth BLE/SCO headset, USB headset/mic, wired headset, or
 * built-in mic) and hardware audio pre-processing (NS / AEC / AGC) for a
 * single live offline speech recognition turn.
 *
 * Closing [OpenedCapture] deterministically releases hardware audio effects,
 * stops and frees [AudioRecord], clears any communication-device override and
 * abandons audio focus so background media playback resumes immediately.
 */
internal object EmaAudioCaptureRouter {

    class OpenedCapture(
        val recorder: AudioRecord,
        val sampleRate: Int,
        val hardwareNsActive: Boolean,
        val hardwareAgcActive: Boolean,
        val inputRoute: String,
        private val onClose: () -> Unit
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                onClose()
            }
        }
    }

    /**
     * Acquire transient assistant audio focus, select the cleanest available
     * voice input peripheral, initialize [AudioRecord] with resilient fallback
     * across audio sources and sample rates, attach hardware NS/AEC and start
     * recording. Returns null if the microphone cannot be opened or started.
     */
    fun openAndStart(context: Context, requestFocus: Boolean = true): OpenedCapture? {
        val app = context.applicationContext
        val am = runCatching { app.getSystemService(AudioManager::class.java) }.getOrNull()

        // 1. Pause/duck playing media & notification chimes during active listening.
        val focusRequest: AudioFocusRequest? = if (requestFocus && am != null) {
            acquireSpeechAudioFocus(am)
        } else {
            null
        }

        // 2. Route connected Bluetooth BLE / HFP headset on Android 12+ if present.
        var commDeviceSet = false
        if (am != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            commDeviceSet = selectBluetoothCommDeviceApi31(am)
        }

        // 3. Pick preferred close-talk input peripheral (BLE -> USB -> Wired -> BT SCO -> Built-in).
        val preferredInput = am?.let(::choosePreferredInputDevice)
        val routeLabel = describeInputDevice(preferredInput)

        // 4. Open AudioRecord across ordered OEM-safe source + sample-rate fallbacks.
        val opened = openBestAudioRecord(preferredInput)
        if (opened == null) {
            cleanupRouting(am, focusRequest, commDeviceSet)
            return null
        }

        val (audio, rate) = opened
        val effects = attachEffects(audio.audioSessionId)

        val started = runCatching {
            audio.startRecording()
            audio.recordingState == AudioRecord.RECORDSTATE_RECORDING
        }.getOrDefault(false)

        if (!started) {
            releaseEffects(effects)
            runCatching { audio.stop() }
            runCatching { audio.release() }
            cleanupRouting(am, focusRequest, commDeviceSet)
            return null
        }

        return OpenedCapture(
            recorder = audio,
            sampleRate = rate,
            hardwareNsActive = effects.nsActive,
            hardwareAgcActive = effects.agcActive,
            inputRoute = routeLabel,
            onClose = {
                releaseEffects(effects)
                runCatching { audio.stop() }
                runCatching { audio.release() }
                cleanupRouting(am, focusRequest, commDeviceSet)
            }
        )
    }

    private fun acquireSpeechAudioFocus(am: AudioManager): AudioFocusRequest? = runCatching {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attrs)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { /* short single-turn capture */ }
            .build()
        val granted = am.requestAudioFocus(req)
        if (granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) req else null
    }.getOrNull()

    @TargetApi(Build.VERSION_CODES.S)
    private fun selectBluetoothCommDeviceApi31(am: AudioManager): Boolean = runCatching {
        val available = am.availableCommunicationDevices
        val bt = available.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
            ?: available.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ?: return@runCatching false
        am.setCommunicationDevice(bt)
    }.getOrDefault(false)

    private fun choosePreferredInputDevice(am: AudioManager): AudioDeviceInfo? = runCatching {
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.isSource }
        if (inputs.isEmpty()) return@runCatching null
        val priority = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
            add(AudioDeviceInfo.TYPE_USB_HEADSET)
            add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
            add(AudioDeviceInfo.TYPE_USB_DEVICE)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        }
        for (wantedType in priority) {
            inputs.firstOrNull { it.type == wantedType }?.let { return@runCatching it }
        }
        null // Default built-in voice-recognition mic selected by audio policy.
    }.getOrNull()

    private fun describeInputDevice(device: AudioDeviceInfo?): String {
        val type = device?.type ?: return "built-in mic"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
            return "BLE headset"
        }
        return when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset"
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB microphone"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
            else -> "built-in mic"
        }
    }

    private fun openBestAudioRecord(preferredInput: AudioDeviceInfo?): Pair<AudioRecord, Int>? {
        val candidates = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION to 16_000,
            MediaRecorder.AudioSource.MIC to 16_000,
            MediaRecorder.AudioSource.UNPROCESSED to 16_000,
            MediaRecorder.AudioSource.VOICE_RECOGNITION to 48_000,
            MediaRecorder.AudioSource.VOICE_RECOGNITION to 44_100,
            MediaRecorder.AudioSource.MIC to 48_000,
            MediaRecorder.AudioSource.MIC to 44_100
        )
        for ((source, rate) in candidates) {
            val minBuf = runCatching {
                AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            }.getOrDefault(-1)
            if (minBuf <= 0) continue
            // ~500 ms kernel ring buffer absorbs scheduler/GC jitter cleanly.
            val bufferBytes = maxOf(minBuf * 2, rate)
            val record = runCatching {
                AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
            }.getOrNull() ?: continue
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                if (preferredInput != null) {
                    runCatching { record.setPreferredDevice(preferredInput) }
                }
                return record to rate
            }
            runCatching { record.release() }
        }
        return null
    }

    private data class Effects(
        val ns: NoiseSuppressor?,
        val aec: AcousticEchoCanceler?,
        val agc: AutomaticGainControl?,
        val nsActive: Boolean,
        val agcActive: Boolean
    )

    private fun attachEffects(sessionId: Int): Effects {
        if (sessionId <= 0) return Effects(null, null, null, nsActive = false, agcActive = false)
        val ns = runCatching {
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sessionId)?.apply { enabled = true } else null
        }.getOrNull()
        val aec = runCatching {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sessionId)?.apply { enabled = true } else null
        }.getOrNull()
        val agc = runCatching {
            if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(sessionId) else null
        }.getOrNull()
        val nsOn = runCatching { ns?.enabled == true }.getOrDefault(false)
        val agcOn = runCatching { agc?.enabled == true }.getOrDefault(false)
        return Effects(ns, aec, agc, nsActive = nsOn, agcActive = agcOn)
    }

    private fun releaseEffects(effects: Effects) {
        runCatching { effects.ns?.release() }
        runCatching { effects.aec?.release() }
        runCatching { effects.agc?.release() }
    }

    private fun cleanupRouting(
        am: AudioManager?,
        focusRequest: AudioFocusRequest?,
        commDeviceSet: Boolean
    ) {
        if (am != null && commDeviceSet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            clearCommDeviceApi31(am)
        }
        if (am != null && focusRequest != null) {
            runCatching { am.abandonAudioFocusRequest(focusRequest) }
        }
    }

    @TargetApi(Build.VERSION_CODES.S)
    private fun clearCommDeviceApi31(am: AudioManager) {
        runCatching { am.clearCommunicationDevice() }
    }
}
