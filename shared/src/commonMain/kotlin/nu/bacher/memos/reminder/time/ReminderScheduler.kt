package nu.bacher.memos.reminder.time

/**
 * Platform-specific scheduling of wall-clock reminders. Implementations are
 * expected to survive process death but NOT reboot (callers re-arm pending
 * reminders on boot from the persisted store).
 */
interface ReminderScheduler {
    fun schedule(memoName: String, triggerAtEpochMs: Long)
    fun cancel(memoName: String)
}
