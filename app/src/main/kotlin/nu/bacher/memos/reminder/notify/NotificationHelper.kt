package nu.bacher.memos.reminder.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import nu.bacher.memos.MainActivity
import nu.bacher.memos.R
import nu.bacher.memos.reminder.time.AlarmReceiver
import nu.bacher.memos.reminder.time.AlarmScheduler

object NotificationHelper {
    const val CHANNEL_REMINDERS = "memo_reminders"
    private const val TAG = "NotificationHelper"
    /** XOR salt so the snooze PendingIntent doesn't collide with the open-memo PI. */
    private const val SNOOZE_REQUEST_CODE_SALT = 0x534E5A45

    fun createChannels(context: Context) {
        val nm = context.getSystemService<NotificationManager>() ?: return
        if (nm.getNotificationChannel(CHANNEL_REMINDERS) != null) return

        val channel = NotificationChannel(
            CHANNEL_REMINDERS,
            context.getString(R.string.notification_channel_reminders),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notification_channel_reminders_desc)
            enableVibration(true)
        }
        nm.createNotificationChannel(channel)
    }

    fun show(context: Context, memoName: String, snippet: String) {
        // Channel creation is normally done in Application.onCreate(), but the
        // process can be cold-started straight into the AlarmReceiver — in that
        // case Application.onCreate() runs first too, but be defensive.
        createChannels(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.w(TAG, "POST_NOTIFICATIONS not granted — skipping notification for $memoName")
                return
            }
        }

        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) {
            Log.w(TAG, "Notifications disabled for app — skipping notification for $memoName")
            return
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_MEMO_NAME, memoName)
        }
        val pi = PendingIntent.getActivity(
            context,
            memoName.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val snoozeIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmScheduler.ACTION_SNOOZE
            putExtra(AlarmScheduler.EXTRA_MEMO_NAME, memoName)
        }
        // Distinct request code from the activity-open PI above (which also
        // uses memoName.hashCode()) — otherwise PendingIntent.getBroadcast
        // would overwrite the activity PI's extras since the intent matcher
        // ignores extras.
        val snoozePi = PendingIntent.getBroadcast(
            context,
            memoName.hashCode() xor SNOOZE_REQUEST_CODE_SALT,
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(snippet)
            .setStyle(NotificationCompat.BigTextStyle().bigText(snippet))
            .setContentIntent(pi)
            .addAction(
                R.drawable.ic_notification,
                context.getString(R.string.notification_snooze_24h),
                snoozePi,
            )
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        try {
            nm.notify(memoName.hashCode(), notification)
            Log.d(TAG, "posted notification for $memoName")
        } catch (se: SecurityException) {
            Log.w(TAG, "notify() rejected by system for $memoName", se)
        }
    }
}
