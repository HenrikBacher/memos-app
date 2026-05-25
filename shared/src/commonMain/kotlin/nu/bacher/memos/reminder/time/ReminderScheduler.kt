package nu.bacher.memos.reminder.time

/**
 * Platform-specific scheduling of wall-clock reminders. Implementations are
 * expected to survive process death but NOT reboot (callers re-arm pending
 * reminders on boot from the persisted store).
 *
 * [requestCode] is the caller-supplied identifier the platform layer uses to
 * key the underlying alarm (e.g. Android's PendingIntent request code). Two
 * different memos must always have different request codes; callers are
 * responsible for picking unique values per reminder (typically the
 * autoGenerate row id from the Room reminder table).
 */
interface ReminderScheduler {
    fun schedule(memoName: String, triggerAtEpochMs: Long, requestCode: Int)
    fun cancel(requestCode: Int)
}
