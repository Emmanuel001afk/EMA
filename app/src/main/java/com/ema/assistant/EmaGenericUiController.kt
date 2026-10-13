package com.ema.assistant

import android.os.Handler
import android.os.Looper

/**
 * Generic UI control built on EMA's screen layer.
 *
 * Verification policy: a successful click is a success. The previous version
 * demanded that the whole accessible UI dump change within 650ms and reported
 * failure otherwise, which made EMA announce "could not verify" for actions that
 * had plainly worked — toggles, likes, checkboxes, send buttons, and any control
 * whose effect is not a new screen. That false failure then caused retries and,
 * in message flows, a second attempt to send.
 *
 * What is still checked honestly: whether the screen changed, and whether text
 * actually landed in a field. Those are reported as extra information, not as
 * failure.
 */
object EmaGenericUiController {
    private val main = Handler(Looper.getMainLooper())

    fun inspect(): String = EmaAccessibilityService.inspectCurrentUi()

    fun tap(target: String, callback: (Boolean, String) -> Unit) {
        val trimmed = target.trim()
        if (trimmed.isBlank()) {
            callback(false, "Tell me what to tap.")
            return
        }
        val unavailable = EmaAccessibilityService.availabilityProblem()
        if (unavailable != null) {
            callback(false, unavailable)
            return
        }

        val before = inspect()
        EmaAccessibilityService.activateWithVisualFallback(trimmed) { ok ->
            if (!ok) {
                callback(false, "I could not find \"$trimmed\" in this screen's controls.")
                return@activateWithVisualFallback
            }
            main.postDelayed({
                val changed = runCatching {
                    val after = inspect()
                    after.isNotBlank() && after != before
                }.getOrDefault(false)
                // A click that Android accepted is a success even when the screen
                // looks unchanged; the detail simply says what was observable.
                callback(
                    true,
                    if (changed) "Activated $trimmed and the screen changed."
                    else "Activated $trimmed."
                )
            }, 500L)
        }
    }

    fun type(text: String, callback: (Boolean, String) -> Unit) {
        if (text.isBlank()) {
            callback(false, "Tell me what to type.")
            return
        }
        val unavailable = EmaAccessibilityService.availabilityProblem()
        if (unavailable != null) {
            callback(false, unavailable)
            return
        }

        val ok = EmaAccessibilityService.setFocusedText(text)
        main.postDelayed({
            val actual = runCatching { EmaAccessibilityService.focusedEditableText() }.getOrDefault("")
            val verified = ok && actual == text
            callback(
                ok,
                when {
                    verified -> "Entered the text and verified the field value."
                    ok -> "Entered the text. The field value could not be read back, which is normal for some apps."
                    else -> "I could not access an editable field on this screen."
                }
            )
        }, 300L)
    }

    fun scroll(direction: String, callback: (Boolean, String) -> Unit) {
        val unavailable = EmaAccessibilityService.availabilityProblem()
        if (unavailable != null) {
            callback(false, unavailable)
            return
        }
        val before = inspect()
        val ok = EmaAccessibilityService.scroll(direction)
        main.postDelayed({
            val changed = runCatching { inspect() != before }.getOrDefault(false)
            callback(
                ok,
                when {
                    ok && changed -> "Scrolled $direction."
                    ok -> "Asked this screen to scroll $direction, but its content did not change."
                    else -> "I could not scroll this screen."
                }
            )
        }, 400L)
    }

    fun runSteps(steps: List<((Boolean, String) -> Unit) -> Unit>, done: (Boolean, String) -> Unit) {
        fun next(index: Int, last: String) {
            if (index >= steps.size) {
                done(true, if (last.isBlank()) "Done." else last)
                return
            }
            steps[index] { ok, result ->
                if (!ok) done(false, result) else next(index + 1, result)
            }
        }
        next(0, "")
    }
}
