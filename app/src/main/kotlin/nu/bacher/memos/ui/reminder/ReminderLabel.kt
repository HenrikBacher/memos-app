package nu.bacher.memos.ui.reminder

import android.content.Context
import android.text.format.DateUtils
import nu.bacher.memos.R
import nu.bacher.memos.util.ReminderRelative
import nu.bacher.memos.util.relativeReminder

/**
 * Localised, human-readable label for a reminder firing at [triggerAtEpochMs].
 * Buckets the gap with [relativeReminder] and renders the bucket via string
 * resources; the [ReminderRelative.Absolute] tail falls back to platform
 * DateUtils so locale settings keep working for far-future dates.
 */
fun reminderLabel(
    context: Context,
    triggerAtEpochMs: Long,
    nowEpochMs: Long = System.currentTimeMillis(),
): String {
    val timeOfDay = { DateUtils.formatDateTime(context, triggerAtEpochMs, DateUtils.FORMAT_SHOW_TIME) }
    return when (val r = relativeReminder(triggerAtEpochMs, nowEpochMs)) {
        ReminderRelative.Now -> context.getString(R.string.reminder_now)
        is ReminderRelative.MinutesUntil ->
            context.getString(R.string.reminder_in_minutes, r.minutes)
        is ReminderRelative.MinutesAgo ->
            context.getString(R.string.reminder_minutes_ago, r.minutes)
        is ReminderRelative.HoursUntil ->
            context.getString(R.string.reminder_in_hours, r.hours)
        is ReminderRelative.HoursAgo ->
            context.getString(R.string.reminder_hours_ago, r.hours)
        ReminderRelative.Today ->
            context.getString(R.string.reminder_today_at, timeOfDay())
        ReminderRelative.Tomorrow ->
            context.getString(R.string.reminder_tomorrow_at, timeOfDay())
        ReminderRelative.Yesterday ->
            context.getString(R.string.reminder_yesterday_at, timeOfDay())
        is ReminderRelative.DaysUntil ->
            context.getString(R.string.reminder_in_days, r.days)
        is ReminderRelative.DaysAgo ->
            context.getString(R.string.reminder_days_ago, r.days)
        ReminderRelative.Absolute -> DateUtils.formatDateTime(
            context,
            triggerAtEpochMs,
            DateUtils.FORMAT_SHOW_DATE or
                DateUtils.FORMAT_SHOW_TIME or
                DateUtils.FORMAT_SHOW_YEAR or
                DateUtils.FORMAT_ABBREV_ALL,
        )
    }
}
