package nu.bacher.memos.reminder.time

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import nu.bacher.memos.BuildConfig
import nu.bacher.memos.R
import nu.bacher.memos.data.db.MemoDao
import nu.bacher.memos.data.repo.ReminderRepository
import nu.bacher.memos.reminder.notify.NotificationHelper
import nu.bacher.memos.util.currentTimeMillis
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class AlarmReceiver : BroadcastReceiver(), KoinComponent {

    private val memoDao: MemoDao by inject()
    private val reminderRepo: ReminderRepository by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (BuildConfig.DEBUG) Log.d(TAG, "onReceive action=${intent.action}")
        val memoName = intent.getStringExtra(AlarmScheduler.EXTRA_MEMO_NAME) ?: return
        when (intent.action) {
            AlarmScheduler.ACTION_FIRE -> handleFire(context, memoName)
            AlarmScheduler.ACTION_SNOOZE -> handleSnooze(context, memoName)
        }
    }

    private fun handleFire(context: Context, memoName: String) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Read the snippet from the local cache, never the network.
                // goAsync() gives this receiver on the order of ten seconds;
                // a request that stalls would blow that budget and the user
                // would simply never get the reminder they asked for.
                val snippet = runCatching { memoDao.get(memoName)?.content }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.take(SNIPPET_CHARS)
                    ?: context.getString(R.string.notification_reminder_fallback)
                NotificationHelper.show(context, memoName, snippet)
                reminderRepo.clear(memoName)
            } finally {
                pending.finish()
            }
        }
    }

    private fun handleSnooze(context: Context, memoName: String) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Re-arm 24h out. setTimeReminder upserts a fresh reminder row
                // and schedules a new exact alarm with that row's id as the
                // request code, so this works whether or not a row still
                // exists from the original schedule.
                reminderRepo.setTimeReminder(memoName, currentTimeMillis() + SNOOZE_INTERVAL_MS)
                NotificationManagerCompat.from(context).cancel(memoName.hashCode())
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "AlarmReceiver"
        const val SNIPPET_CHARS = 140
        const val SNOOZE_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}
