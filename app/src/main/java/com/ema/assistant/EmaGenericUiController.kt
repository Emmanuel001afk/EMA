package com.ema.assistant

import android.os.Handler
import android.os.Looper

object EmaGenericUiController {
    private val main = Handler(Looper.getMainLooper())

    fun inspect(): String = EmaAccessibilityService.inspectCurrentUi()

    fun tap(target: String, callback: (Boolean, String) -> Unit) {
        val before = inspect()
        EmaAccessibilityService.activateWithVisualFallback(target) { ok ->
            if (!ok) {
                callback(false, "I could not find or activate $target through the accessible or visual screen controls.")
                return@activateWithVisualFallback
            }
            main.postDelayed({
                val after = inspect()
                val changed = after.isNotBlank() && after != before
                if (changed) {
                    callback(true, "Activated $target and verified the foreground UI changed.")
                } else {
                    // A successful Accessibility click is not proof that the requested
                    // operation completed. Never report success without observable evidence.
                    callback(false, "Activated $target, but EMA could not verify the resulting state.")
                }
            }, 650L)
        }
    }

    fun type(text: String, callback: (Boolean, String) -> Unit) {
        val ok = EmaAccessibilityService.setFocusedText(text)
        main.postDelayed({
            inspect()
            callback(ok, if (ok) "Entered the text and re-inspected the screen." else "I could not access an editable field.")
        }, 300L)
    }

    fun scroll(direction: String, callback: (Boolean, String) -> Unit) {
        val before = inspect()
        val ok = EmaAccessibilityService.scroll(direction)
        main.postDelayed({
            val after = inspect()
            callback(ok, if (ok && after != before) "Scrolled $direction and verified the accessible UI changed." else if (ok) "Scrolled $direction." else "I could not scroll the accessible UI.")
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
