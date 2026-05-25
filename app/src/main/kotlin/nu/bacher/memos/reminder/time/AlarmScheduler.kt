package nu.bacher.memos.reminder.time

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.getSystemService

/**
 * Android implementation of [ReminderScheduler] backed by AlarmManager.
 *
 * We use [AlarmManager.setExactAndAllowWhileIdle] when the user has granted
 * SCHEDULE_EXACT_ALARM — this is the only API that escapes Doze deferral and
 * App Standby throttling for non-alarm-clock apps. Falls back to
 * setAndAllowWhileIdle without the grant. Alarms are LOST on reboot —
 * BootReceiver re-arms them from the Room reminders table.
 *
 * [requestCode] is the caller's stable identifier for the alarm (the Room
 * row id of the reminder). Used as both the PendingIntent request code and
 * an Intent extra so the receiver can look the reminder up.
 */
class AlarmScheduler(private val context: Context) : ReminderScheduler {
    private val alarmManager = context.getSystemService<AlarmManager>()!!

    override fun schedule(memoName: String, triggerAtEpochMs: Long, requestCode: Int) {
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else true

        val pi = buildPendingIntent(memoName, requestCode)
        if (canExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtEpochMs, pi)
        } else {
            // Falls back to inexact — best we can do without the runtime grant.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtEpochMs, pi)
        }
        Log.d(TAG, "scheduled $memoName at $triggerAtEpochMs (exact=$canExact, code=$requestCode)")
    }

    override fun cancel(requestCode: Int) {
        // PendingIntent.getBroadcast with FLAG_NO_CREATE returns null if no
        // matching PendingIntent exists; cancel is a no-op in that case.
        // The memoName/action in the Intent template just need to match the
        // values used at schedule() — the request code is the lookup key.
        val pi = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, AlarmReceiver::class.java).apply { action = ACTION_FIRE },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarmManager.cancel(pi)
        pi.cancel()
    }

    private fun buildPendingIntent(memoName: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_MEMO_NAME, memoName)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_FIRE = "nu.bacher.memos.action.FIRE_TIME_REMINDER"
        const val EXTRA_MEMO_NAME = "memo_name"
        private const val TAG = "AlarmScheduler"
    }
}
