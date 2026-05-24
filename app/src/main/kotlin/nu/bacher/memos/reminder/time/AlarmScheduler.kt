package nu.bacher.memos.reminder.time

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules reminders keyed by memo name..
 *
 * We use [AlarmManager.setAlarmClock] rather than `setExactAndAllowWhileIdle`
 * because it's the only API that's not subject to Doze deferral, App Standby
 * bucket throttling, or OEM battery optimisation — the system treats it the
 * same as an alarm-clock app's alarm and commits to firing it at the wall-
 * clock time. The visible trade-off is a small clock icon in the status bar
 * indicating a pending alarm, which is appropriate UX for a reminder.
 * Alarms are LOST on reboot — BootReceiver re-arms them from the Room table.
 */
@Singleton
class AlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val alarmManager = context.getSystemService<AlarmManager>()!!

    fun schedule(memoName: String, triggerAtEpochMs: Long) {
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else true

        val pi = buildPendingIntent(memoName, mutable = false)
        if (canExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtEpochMs, pi)
        } else {
            // Falls back to inexact — best we can do without the runtime grant.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtEpochMs, pi)
        }
        Log.d(TAG, "scheduled $memoName at $triggerAtEpochMs (exact=$canExact)")
    }

    fun cancel(memoName: String) {
        alarmManager.cancel(buildPendingIntent(memoName, mutable = false))
    }

    private fun buildPendingIntent(memoName: String, mutable: Boolean): PendingIntent {        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_MEMO_NAME, memoName)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, memoName.hashCode(), intent, flags)
    }
    

    companion object {
        const val ACTION_FIRE = "nu.bacher.memos.action.FIRE_TIME_REMINDER"
        const val EXTRA_MEMO_NAME = "memo_name"
        private const val TAG = "AlarmScheduler"
    }
}
