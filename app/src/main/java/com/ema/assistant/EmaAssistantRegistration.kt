package com.ema.assistant

import android.content.Context
import android.speech.RecognitionService

/**
 * Whether EMA is really registered as Android's digital assistant.
 *
 * The previous status was a guess. It is now derived from three observable
 * facts: the assistant role is held, the voice-interaction service is the
 * active one according to the framework, and a recognition service for this
 * package is actually declared. The pure [describe] decision is unit tested.
 */
object EmaAssistantRegistration {

    data class Status(
        val roleHeld: Boolean,
        val serviceActive: Boolean,
        val recognitionServiceDeclared: Boolean
    ) {
        val fullyRegistered: Boolean get() = roleHeld && serviceActive && recognitionServiceDeclared
    }

    fun describe(status: Status): String = when {
        !status.roleHeld -> "not enabled"
        !status.recognitionServiceDeclared -> "role held, but the recognition service is not declared"
        !status.serviceActive -> "enabled; service is not active yet"
        else -> "enabled and active"
    }

    fun declared(context: Context): Boolean = runCatching {
        context.packageManager.queryIntentServices(
            android.content.Intent(RecognitionService.SERVICE_INTERFACE), 0
        ).any { it.serviceInfo?.packageName == context.packageName }
    }.getOrDefault(false)

    fun current(context: Context, roleHeld: Boolean, serviceActive: Boolean): Status =
        Status(roleHeld, serviceActive, declared(context))
}
