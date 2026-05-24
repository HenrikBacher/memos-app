package nu.bacher.memos.data.repo

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import nu.bacher.memos.data.db.ReminderDao
import nu.bacher.memos.data.db.ReminderEntity
import nu.bacher.memos.reminder.time.AlarmScheduler

@Singleton
class ReminderRepository @Inject constructor(
    private val dao: ReminderDao,
    private val alarmScheduler: AlarmScheduler,
) {
    fun observe(memoName: String): Flow<ReminderEntity?> = dao.observe(memoName)
    fun observeAll(): Flow<List<ReminderEntity>> = dao.observeAll()
    suspend fun get(memoName: String): ReminderEntity? = dao.get(memoName)

    suspend fun setTimeReminder(memoName: String, triggerAtEpochMs: Long) {
        dao.upsert(ReminderEntity(memoName = memoName, triggerAtEpochMs = triggerAtEpochMs))
        alarmScheduler.schedule(memoName, triggerAtEpochMs)
    }

    suspend fun clear(memoName: String) {
        alarmScheduler.cancel(memoName)
        dao.delete(memoName)
    }

    /** Called by BootReceiver — alarms don't survive reboot. */
    suspend fun rescheduleAll() {
        val now = System.currentTimeMillis()
        for (r in dao.getAll()) {
            if (r.triggerAtEpochMs > now) {
                alarmScheduler.schedule(r.memoName, r.triggerAtEpochMs)
            } else {
                // Past-due reminders that we missed during reboot — drop them.
                dao.delete(r.memoName)
            }
        }
    }
}
