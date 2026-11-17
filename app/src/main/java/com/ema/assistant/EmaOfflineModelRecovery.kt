package com.ema.assistant

import java.io.IOException

/** One local repair attempt for unreadable installed models, never an unbounded retry. */
object EmaOfflineModelRecovery {
    fun <T> load(load: () -> T, reinstallFromBundledAssets: () -> Unit): T {
        return try {
            load()
        } catch (first: IOException) {
            try {
                reinstallFromBundledAssets()
                load()
            } catch (second: Exception) {
                if (second !== first) second.addSuppressed(first)
                throw second
            }
        }
    }
}
