package nu.bacher.memos.data.repo

import kotlinx.coroutines.flow.Flow
import nu.bacher.memos.data.db.ReminderDao
import nu.bacher.memos.data.db.ReminderEntity
import nu.bacher.memos.reminder.time.ReminderScheduler
import nu.bacher.memos.util.currentTimeMillis

class ReminderRepository(
    private val dao: ReminderDao,
    private val scheduler: ReminderScheduler,
) {
    fun observe(memoName: String): Flow<ReminderEntity?> = dao.observe(memoName)
    fun observeAll(): Flow<List<ReminderEntity>> = dao.observeAll()
    suspend fun get(memoName: String): ReminderEntity? = dao.get(memoName)

    /**
     * Replaces any existing reminder for [memoName] with a new one firing at
     * [triggerAtEpochMs]. The PendingIntent request code is the new Room row
     * id; if a prior reminder existed under a different id we cancel its
     * alarm explicitly so the OS doesn't fire the stale one.
     */
    suspend fun setTimeReminder(memoName: String, triggerAtEpochMs: Long) {
        val existing = dao.get(memoName)
        existing?.let { scheduler.cancel(it.id) }
        val newId = dao.upsert(
            ReminderEntity(
                memoName = memoName,
                triggerAtEpochMs = triggerAtEpochMs,
                createdAtEpochMs = currentTimeMillis(),
            ),
        ).toInt()
        scheduler.schedule(memoName, triggerAtEpochMs, newId)
    }

    suspend fun clear(memoName: String) {
        val existing = dao.get(memoName) ?: return
        scheduler.cancel(existing.id)
        dao.delete(memoName)
    }

    /** Called on boot — alarms don't survive reboot. */
    suspend fun rescheduleAll() {
        val now = currentTimeMillis()
        for (r in dao.getAll()) {
            if (r.triggerAtEpochMs > now) {
                scheduler.schedule(r.memoName, r.triggerAtEpochMs, r.id)
            } else {
                // Past-due reminders missed during reboot — drop them.
                dao.delete(r.memoName)
            }
        }
    }
}
