package com.ema.assistant

import android.os.Handler
import android.os.Looper

object EmaGenericUiController {
    private val main = Handler(Looper.getMainLooper())

    fun inspect(): String = EmaAccessibilityService.inspectCurrentUi()

    fun tap(target: String, callback: (Boolean, String) -> Unit) {
        val before = inspect()
        val ok = EmaAccessibilityService.activate(target)
        if (!ok) {
            callback(false, "I could not find an accessible control named $target.")
            return
        }
        main.postDelayed({
            val after = inspect()
            val changed = after.isNotBlank() && after != before
            callback(true, if (changed) "Activated $target and verified the screen changed." else "Activated $target; Android exposed no clear screen change to verify.")
        }, 450L)
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
