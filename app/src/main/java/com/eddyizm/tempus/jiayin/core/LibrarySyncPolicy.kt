package com.eddyizm.tempus.jiayin.core

/** A foreground check, never a periodic wakeup. Zero days means manual only. */
data class LibrarySyncPolicy(val days: Int = 30, val lastSuccess: Long = 0, val lastFailure: Long = 0) {
    val intervalMs get() = days.coerceAtLeast(0) * 86_400_000L
    fun due(now: Long): Boolean = (lastSuccess <= 0 || (days > 0 && now - lastSuccess >= intervalMs)) &&
        (lastFailure <= 0 || now - lastFailure >= 6 * 3_600_000L)
}
