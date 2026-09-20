package nu.bacher.memos.ui

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import nu.bacher.memos.data.auth.SecretCipher
import nu.bacher.memos.data.db.ReminderDao
import nu.bacher.memos.data.db.ReminderEntity
import nu.bacher.memos.data.repo.ReminderRepository
import nu.bacher.memos.reminder.time.ReminderScheduler

/** Identity cipher — encrypting at rest is TinkSecretCipher's job, not a VM's. */
internal object PlaintextSecretCipher : SecretCipher {
    override fun encrypt(plaintext: String): String = plaintext
    override fun decrypt(ciphertext: String): String? = ciphertext
}

/** In-memory [ReminderDao], auto-assigning ids the way Room does. */
internal class FakeReminderDao : ReminderDao {
    private val state = MutableStateFlow<List<ReminderEntity>>(emptyList())
    private var nextId = 1

    override fun observeAll(): Flow<List<ReminderEntity>> = state

    override suspend fun getAll(): List<ReminderEntity> = state.value

    override suspend fun get(memoName: String): ReminderEntity? =
        state.value.firstOrNull { it.memoName == memoName }

    override fun observe(memoName: String): Flow<ReminderEntity?> =
        state.map { rows -> rows.firstOrNull { it.memoName == memoName } }

    override suspend fun upsert(reminder: ReminderEntity): Long {
        val id = if (reminder.id == 0) nextId++ else reminder.id
        val stored = reminder.copy(id = id)
        state.update { current -> current.filterNot { it.memoName == reminder.memoName } + stored }
        return id.toLong()
    }

    override suspend fun delete(memoName: String) {
        state.update { current -> current.filterNot { it.memoName == memoName } }
    }
}

/** Records what would have been scheduled, without touching AlarmManager. */
internal class RecordingReminderScheduler : ReminderScheduler {
    val scheduled = mutableListOf<Triple<String, Long, Int>>()
    val cancelled = mutableListOf<Int>()

    override fun schedule(memoName: String, triggerAtEpochMs: Long, requestCode: Int) {
        scheduled += Triple(memoName, triggerAtEpochMs, requestCode)
    }

    override fun cancel(requestCode: Int) {
        cancelled += requestCode
    }
}

internal fun testReminderRepository(
    dao: ReminderDao = FakeReminderDao(),
    scheduler: ReminderScheduler = RecordingReminderScheduler(),
): ReminderRepository = ReminderRepository(dao, scheduler)
