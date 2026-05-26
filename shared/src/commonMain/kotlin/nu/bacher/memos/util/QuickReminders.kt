package nu.bacher.memos.util

import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Epoch-millis helpers for the reminder sheet's quick-pick buttons.
 *
 * All targets land at 08:00 local in [zone]. Pure functions of [nowEpochMs]
 * so the day-of-week math and roll-forward rules stay testable without
 * mocking a clock.
 *
 * Roll-forward semantics: "next weekend" and "next week" never resolve to
 * *today*. If today is the target day, we skip seven days. This matches the
 * user's intent — picking "Next week" on a Monday should not silently fire
 * the reminder a few hours later.
 */
object QuickReminders {

    /** Tomorrow at 08:00 local. */
    fun tomorrow(nowEpochMs: Long, zone: TimeZone): Long =
        atEight(today(nowEpochMs, zone).plus(1, DateTimeUnit.DAY), zone)

    /**
     * Upcoming Saturday at 08:00 local. If today is Saturday the pick rolls
     * forward to next Saturday — see class kdoc for the why.
     */
    fun nextWeekend(nowEpochMs: Long, zone: TimeZone): Long =
        atEight(nextDayOfWeek(today(nowEpochMs, zone), DayOfWeek.SATURDAY), zone)

    /**
     * Upcoming Monday at 08:00 local. Today-is-Monday rolls forward to the
     * following Monday.
     */
    fun nextWeek(nowEpochMs: Long, zone: TimeZone): Long =
        atEight(nextDayOfWeek(today(nowEpochMs, zone), DayOfWeek.MONDAY), zone)

    /**
     * The next date strictly after [from] whose [DayOfWeek] is [target].
     * Returns [from] + 7 days when [from] already matches.
     */
    internal fun nextDayOfWeek(from: LocalDate, target: DayOfWeek): LocalDate {
        val diff = (target.isoDayNumber - from.dayOfWeek.isoDayNumber + 7) % 7
        return from.plus(if (diff == 0) 7 else diff, DateTimeUnit.DAY)
    }

    private fun today(nowEpochMs: Long, zone: TimeZone): LocalDate =
        Instant.fromEpochMilliseconds(nowEpochMs).toLocalDateTime(zone).date

    private fun atEight(date: LocalDate, zone: TimeZone): Long =
        LocalDateTime(date, EIGHT_AM).toInstant(zone).toEpochMilliseconds()

    private val EIGHT_AM = LocalTime(8, 0)
}
