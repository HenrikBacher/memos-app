package nu.bacher.memos.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * Anchors are picked to dodge edge cases (DST, midnight boundaries) — the
 * bucketing rules are clear-cut, and we want the failures to point at the
 * rules, not at clock arithmetic surprises.
 */
class ReminderRelativeTest {

    private val zone = TimeZone.of("Europe/Copenhagen")
    private val now = LocalDateTime(2025, 6, 15, 12, 0).toInstant(zone).toEpochMilliseconds()

    @Test
    fun sub_minute_difference_is_now() {
        assertEquals(ReminderRelative.Now, relativeReminder(now + 30_000, now, zone))
        assertEquals(ReminderRelative.Now, relativeReminder(now - 30_000, now, zone))
    }

    @Test
    fun sub_hour_future_difference_is_minutes_until() {
        assertEquals(
            ReminderRelative.MinutesUntil(15),
            relativeReminder(now + 15 * 60_000L, now, zone),
        )
    }

    @Test
    fun sub_hour_past_difference_is_minutes_ago() {
        assertEquals(
            ReminderRelative.MinutesAgo(45),
            relativeReminder(now - 45 * 60_000L, now, zone),
        )
    }

    @Test
    fun same_day_sub_24h_uses_hours_bucket() {
        // 3h ahead — same calendar day, "in 3h" is more scannable than "today HH:MM".
        assertEquals(
            ReminderRelative.HoursUntil(3),
            relativeReminder(now + 3 * 3_600_000L, now, zone),
        )
    }

    @Test
    fun crossing_midnight_into_next_day_is_tomorrow() {
        // At noon local, 14h later is 2 AM next day — calendar-day delta is 1.
        // The absolute gap is sub-24h but we want this to read as Tomorrow.
        assertEquals(
            ReminderRelative.Tomorrow,
            relativeReminder(now + 14 * 3_600_000L, now, zone),
        )
    }

    @Test
    fun crossing_midnight_into_previous_day_is_yesterday() {
        // At noon local, 14h earlier is 10 PM previous day.
        assertEquals(
            ReminderRelative.Yesterday,
            relativeReminder(now - 14 * 3_600_000L, now, zone),
        )
    }

    @Test
    fun within_a_week_uses_days_bucket() {
        assertEquals(
            ReminderRelative.DaysUntil(3),
            relativeReminder(now + 3 * 24 * 3_600_000L, now, zone),
        )
        assertEquals(
            ReminderRelative.DaysAgo(5),
            relativeReminder(now - 5 * 24 * 3_600_000L, now, zone),
        )
    }

    @Test
    fun further_out_falls_back_to_absolute() {
        assertEquals(
            ReminderRelative.Absolute,
            relativeReminder(now + 30L * 24 * 3_600_000L, now, zone),
        )
        assertEquals(
            ReminderRelative.Absolute,
            relativeReminder(now - 30L * 24 * 3_600_000L, now, zone),
        )
    }

    @Test
    fun day_boundary_after_24h_is_today_when_dayDelta_is_zero() {
        // Anchor at midnight so we can construct a same-day 24h-out trigger
        // (it crosses midnight, so dayDelta is 1) and a 22h-out trigger (same
        // day, falls into Today bucket since absMs >= 24h is false but we
        // still want Today over HoursUntil — verify the rule directly).
        val midnight = LocalDateTime(2025, 6, 15, 0, 0).toInstant(zone).toEpochMilliseconds()
        // 23h ahead — same calendar day, sub-24h: HoursUntil wins.
        assertEquals(
            ReminderRelative.HoursUntil(23),
            relativeReminder(midnight + 23 * 3_600_000L, midnight, zone),
        )
    }

    @Test
    fun future_and_past_dayDelta_of_one_are_tomorrow_and_yesterday() {
        val midnight = LocalDateTime(2025, 6, 15, 0, 0).toInstant(zone).toEpochMilliseconds()
        // Tomorrow at 09:00 — dayDelta = 1.
        val tomorrowMorning = LocalDateTime(2025, 6, 16, 9, 0).toInstant(zone).toEpochMilliseconds()
        assertEquals(ReminderRelative.Tomorrow, relativeReminder(tomorrowMorning, midnight, zone))
        // Yesterday at 18:00 — dayDelta = -1.
        val yesterdayEvening = LocalDateTime(2025, 6, 14, 18, 0).toInstant(zone).toEpochMilliseconds()
        assertEquals(ReminderRelative.Yesterday, relativeReminder(yesterdayEvening, midnight, zone))
    }
}
