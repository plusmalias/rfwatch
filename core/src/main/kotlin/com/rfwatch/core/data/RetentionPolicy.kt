package com.rfwatch.core.data

/**
 * Time-based retention. The app records bystanders' devices, so data is purged after a
 * user-set window (default [DEFAULT_RETENTION_DAYS] days). This pure function computes the
 * cutoff and which records to keep; the :app Room layer applies it as a scheduled DELETE
 * and on every "wipe old data" action.
 */
object RetentionPolicy {

    const val DEFAULT_RETENTION_DAYS = 30
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    fun cutoffMs(nowMs: Long, retentionDays: Int): Long = nowMs - retentionDays.toLong() * DAY_MS

    /** Records with a timestamp older than the cutoff are dropped. */
    fun <T> purge(items: List<T>, nowMs: Long, retentionDays: Int, timestamp: (T) -> Long): List<T> {
        val cutoff = cutoffMs(nowMs, retentionDays)
        return items.filter { timestamp(it) >= cutoff }
    }

    /** Count that would be deleted — for a confirmation dialog. */
    fun <T> purgeCount(items: List<T>, nowMs: Long, retentionDays: Int, timestamp: (T) -> Long): Int {
        val cutoff = cutoffMs(nowMs, retentionDays)
        return items.count { timestamp(it) < cutoff }
    }
}
