package com.ema.assistant

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/**
 * Applies EMA's voice choice to an initialised [TextToSpeech].
 *
 * Shared by the app screen, the voice overlay and background receivers so all
 * of them speak with the same voice, style, pitch and rate. Must only be
 * called after the engine's OnInitListener reported SUCCESS: before that,
 * `tts.voices` is empty (which is why the old settings list only ever showed
 * "Default voice").
 */
object EmaVoiceSetup {
    /**
     * The voice the user explicitly picked. Deliberately a new key: the old
     * "voice_name" was written automatically by a spinner callback (the
     * alphabetically first voice), so it does not represent a real choice.
     */
    const val PREF_VOICE = "voice_choice"
    const val PREF_STYLE = "voice_style"
    const val PREF_ALLOW_NETWORK = "voice_allow_network"

    fun info(voice: Voice): EmaVoiceSelector.VoiceInfo = EmaVoiceSelector.VoiceInfo(
        name = voice.name,
        locale = voice.locale.toLanguageTag(),
        quality = voice.quality,
        latency = voice.latency,
        requiresNetwork = voice.isNetworkConnectionRequired,
        notInstalled = voice.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true
    )

    fun voices(tts: TextToSpeech): List<Voice> = runCatching { tts.voices?.toList().orEmpty() }.getOrDefault(emptyList())

    /** Returns a short, truthful description of what is now in use. */
    fun apply(context: Context, tts: TextToSpeech): String {
        val prefs = context.getSharedPreferences("ema", Context.MODE_PRIVATE)
        val style = EmaVoiceSelector.Style.fromKey(prefs.getString(PREF_STYLE, null))
        val allowNetwork = prefs.getBoolean(PREF_ALLOW_NETWORK, false)
        val saved = prefs.getString(PREF_VOICE, "")
        runCatching {
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        }
        val all = voices(tts)
        val choice = EmaVoiceSelector.choose(all.map { info(it) }, saved, style, Locale.getDefault().toLanguageTag(), allowNetwork)
        val selected = choice.voice?.let { picked -> all.firstOrNull { it.name == picked.name } }
        var applied = false
        if (selected != null) {
            applied = runCatching { tts.setVoice(selected) == TextToSpeech.SUCCESS }.getOrDefault(false)
        }
        if (!applied) {
            runCatching { tts.language = Locale.getDefault() }
        }
        runCatching { tts.setPitch(style.pitch) }
        runCatching { tts.setSpeechRate(style.rate) }
        val description = if (applied) "Voice: ${choice.reason}" else "Voice: the phone's default voice"
        prefs.edit().putString("voice_status", description).apply()
        return description
    }
}
