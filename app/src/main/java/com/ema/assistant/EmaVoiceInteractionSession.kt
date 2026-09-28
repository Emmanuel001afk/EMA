package com.ema.assistant

import android.content.Context
import android.service.voice.VoiceInteractionSession

/**
 * Minimal native assistant session.
 *
 * The session is intentionally lightweight for now. EMA's deterministic
 * command engine and shared Central Hub AI client remain reusable from the
 * full app while this system-invocation surface is expanded.
 */
class EmaVoiceInteractionSession(context: Context) : VoiceInteractionSession(context)
