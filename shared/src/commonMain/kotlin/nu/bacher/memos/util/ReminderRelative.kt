package nu.bacher.memos.util

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime

/**
 * Bucketed relative description of a reminder time. The :app layer renders
 * this into a localized string; the bucketing logic lives here so it stays
 * deterministic and testable (no Android Context, no resources).
 *
 * Buckets are picked so the result is scannable at a glance:
 *  - sub-hour differences as minutes,
 *  - sub-day differences as hours,
 *  - same calendar day as [Today],
 *  - ±1 calendar day as [Tomorrow] / [Yesterday],
 *  - within a week as "in N days" / "N days ago",
 *  - further out as [Absolute] — caller formats with platform DateUtils.
 *
 * The day-relative buckets carry no time-of-day payload — the caller has the
 * original epoch millis already and is best-placed to format the clock time
 * with platform locale + 12/24h settings.
 */
sealed class ReminderRelative {
    data object Now : ReminderRelative()
    data class MinutesUntil(val minutes: Int) : ReminderRelative()
    data class MinutesAgo(val minutes: Int) : ReminderRelative()
    data class HoursUntil(val hours: Int) : ReminderRelative()
    data class HoursAgo(val hours: Int) : ReminderRelative()
    data object Today : ReminderRelative()
    data object Tomorrow : ReminderRelative()
    data object Yesterday : ReminderRelative()
    data class DaysUntil(val days: Int) : ReminderRelative()
    data class DaysAgo(val days: Int) : ReminderRelative()
    /** Caller falls back to absolute date formatting. */
    data object Absolute : ReminderRelative()
}

/**
 * Bucketize the gap between [triggerAtEpochMs] and [nowEpochMs] in [zone].
 * The two epochs go in, a [ReminderRelative] comes out — no I/O, no clock
 * reads, fully testable.
 */
fun relativeReminder(
    triggerAtEpochMs: Long,
    nowEpochMs: Long,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): ReminderRelative {
    val deltaMs = triggerAtEpochMs - nowEpochMs
    val absMs = if (deltaMs < 0) -deltaMs else deltaMs

    val oneMinute = 60_000L
    if (absMs < oneMinute) return ReminderRelative.Now

    val oneHour = 60 * oneMinute
    if (absMs < oneHour) {
        val minutes = (absMs / oneMinute).toInt()
        return if (deltaMs >= 0) ReminderRelative.MinutesUntil(minutes)
        else ReminderRelative.MinutesAgo(minutes)
    }

    val triggerDate = Instant.fromEpochMilliseconds(triggerAtEpochMs).toLocalDateTime(zone).date
    val nowDate = Instant.fromEpochMilliseconds(nowEpochMs).toLocalDateTime(zone).date

    // Calendar-day delta so "tomorrow at 12:30 AM" reads as Tomorrow even if
    // the absolute gap is only a couple of hours across midnight.
    val dayDelta = nowDate.daysUntil(triggerDate)

    when (dayDelta) {
        0 -> {
            // Same calendar day — prefer HoursUntil/Ago over Today when the
            // gap is still sub-day so "in 3h" reads better than "today 14:00".
            val oneDay = 24 * oneHour
            if (absMs < oneDay) {
                val hours = (absMs / oneHour).toInt()
                return if (deltaMs >= 0) ReminderRelative.HoursUntil(hours)
                else ReminderRelative.HoursAgo(hours)
            }
            return ReminderRelative.Today
        }
        1 -> return ReminderRelative.Tomorrow
        -1 -> return ReminderRelative.Yesterday
    }

    if (dayDelta in 2..6) return ReminderRelative.DaysUntil(dayDelta)
    if (dayDelta in -6..-2) return ReminderRelative.DaysAgo(-dayDelta)

    return ReminderRelative.Absolute
}
