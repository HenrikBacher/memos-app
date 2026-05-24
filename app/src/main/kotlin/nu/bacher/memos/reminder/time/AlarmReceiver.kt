package nu.bacher.memos.reminder.time

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import nu.bacher.memos.data.repo.MemoRepository
import nu.bacher.memos.data.repo.ReminderRepository
import nu.bacher.memos.reminder.notify.NotificationHelper
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class AlarmReceiver : BroadcastReceiver(), KoinComponent {

    private val memoRepo: MemoRepository by inject()
    private val reminderRepo: ReminderRepository by inject()

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive action=${intent.action}")
        if (intent.action != AlarmScheduler.ACTION_FIRE) return
        val memoName = intent.getStringExtra(AlarmScheduler.EXTRA_MEMO_NAME) ?: return
        val pending = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val snippet = runCatching { memoRepo.get(memoName).content }
                    .getOrNull()
                    ?.take(140)
                    ?: "Reminder"
                NotificationHelper.show(context, memoName, snippet)
                reminderRepo.clear(memoName)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "AlarmReceiver"
    }
}
