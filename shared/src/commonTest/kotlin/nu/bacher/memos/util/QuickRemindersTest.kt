package nu.bacher.memos.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * Lock in the day-of-week math for the quick-pick reminder buttons.
 *
 * The roll-forward rules are the part most likely to regress when someone
 * "simplifies" the code later — keep the today-is-target cases here so the
 * intent is visible.
 */
class QuickRemindersTest {

    private val zone = TimeZone.of("Europe/Copenhagen")

    private fun atNoon(year: Int, month: Int, day: Int): Long =
        LocalDateTime(year, month, day, 12, 0).toInstant(zone).toEpochMilliseconds()

    private fun eightAm(year: Int, month: Int, day: Int): Long =
        LocalDateTime(year, month, day, 8, 0).toInstant(zone).toEpochMilliseconds()

    // 2025-06-15 was a Sunday. Use that as a stable anchor.
    private val sundayNoon = atNoon(2025, 6, 15)
    private val mondayNoon = atNoon(2025, 6, 16)
    private val tuesdayNoon = atNoon(2025, 6, 17)
    private val fridayNoon = atNoon(2025, 6, 20)
    private val saturdayNoon = atNoon(2025, 6, 21)

    @Test
    fun tomorrow_is_next_calendar_day_at_eight_am() {
        assertEquals(eightAm(2025, 6, 18), QuickReminders.tomorrow(tuesdayNoon, zone))
    }

    @Test
    fun tomorrow_crosses_month_boundary() {
        val june30Noon = atNoon(2025, 6, 30)
        assertEquals(eightAm(2025, 7, 1), QuickReminders.tomorrow(june30Noon, zone))
    }

    @Test
    fun next_weekend_from_midweek_is_upcoming_saturday() {
        assertEquals(eightAm(2025, 6, 21), QuickReminders.nextWeekend(tuesdayNoon, zone))
    }

    @Test
    fun next_weekend_from_friday_is_tomorrow() {
        assertEquals(eightAm(2025, 6, 21), QuickReminders.nextWeekend(fridayNoon, zone))
    }

    @Test
    fun next_weekend_from_saturday_rolls_forward_a_full_week() {
        // Picking "next weekend" on a Saturday should not collapse to a
        // few-hours-from-now reminder.
        assertEquals(eightAm(2025, 6, 28), QuickReminders.nextWeekend(saturdayNoon, zone))
    }

    @Test
    fun next_weekend_from_sunday_is_the_following_saturday() {
        assertEquals(eightAm(2025, 6, 21), QuickReminders.nextWeekend(sundayNoon, zone))
    }

    @Test
    fun next_week_from_tuesday_is_the_following_monday() {
        assertEquals(eightAm(2025, 6, 23), QuickReminders.nextWeek(tuesdayNoon, zone))
    }

    @Test
    fun next_week_from_sunday_is_the_very_next_day() {
        assertEquals(eightAm(2025, 6, 16), QuickReminders.nextWeek(sundayNoon, zone))
    }

    @Test
    fun next_week_from_monday_rolls_forward_a_full_week() {
        // Picking "next week" on a Monday should land seven days out — not
        // today.
        assertEquals(eightAm(2025, 6, 23), QuickReminders.nextWeek(mondayNoon, zone))
    }
}
