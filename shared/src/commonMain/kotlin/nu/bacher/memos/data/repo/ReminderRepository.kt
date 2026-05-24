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

    suspend fun setTimeReminder(memoName: String, triggerAtEpochMs: Long) {
        dao.upsert(
            ReminderEntity(
                memoName = memoName,
                triggerAtEpochMs = triggerAtEpochMs,
                createdAtEpochMs = currentTimeMillis(),
            ),
        )
        scheduler.schedule(memoName, triggerAtEpochMs)
    }

    suspend fun clear(memoName: String) {
        scheduler.cancel(memoName)
        dao.delete(memoName)
    }

    /** Called on boot — alarms don't survive reboot. */
    suspend fun rescheduleAll() {
        val now = currentTimeMillis()
        for (r in dao.getAll()) {
            if (r.triggerAtEpochMs > now) {
                scheduler.schedule(r.memoName, r.triggerAtEpochMs)
            } else {
                // Past-due reminders missed during reboot — drop them.
                dao.delete(r.memoName)
            }
        }
    }
}
