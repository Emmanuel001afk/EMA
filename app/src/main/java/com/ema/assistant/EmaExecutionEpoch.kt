package com.ema.assistant

import java.util.concurrent.atomic.AtomicLong

/** A new command never makes an old callback valid again. Odd epochs are live. */
class EmaExecutionEpoch {
    private val epoch = AtomicLong(0)
    fun begin(): Long = epoch.updateAndGet { if (it % 2L == 0L) it + 1L else it + 2L }
    fun invalidate() { epoch.updateAndGet { if (it % 2L != 0L) it + 1L else it + 2L } }
    fun token(): Long = epoch.get()
    fun isCurrent(token: Long): Boolean = token % 2L != 0L && epoch.get() == token
    fun isActive(): Boolean = epoch.get() % 2L != 0L
}
