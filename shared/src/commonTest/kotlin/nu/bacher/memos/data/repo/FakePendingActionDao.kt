package nu.bacher.memos.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import nu.bacher.memos.data.db.PendingActionDao
import nu.bacher.memos.data.db.PendingActionEntity

/**
 * In-memory fake of [PendingActionDao] for repository tests. Auto-assigns
 * ids on insert and emits ordered-by-createdAt rows from [getAll], matching
 * Room's behavior on the real DAO.
 */
class FakePendingActionDao : PendingActionDao {
    private val state = MutableStateFlow<List<PendingActionEntity>>(emptyList())
    private var nextId = 1L

    val rows: List<PendingActionEntity> get() = state.value

    override suspend fun getAll(): List<PendingActionEntity> =
        state.value.sortedWith(compareBy({ it.createdAtEpochMs }, { it.id }))

    override fun observePendingNames(): Flow<List<String>> =
        state.map { rows -> rows.map { it.memoName }.distinct() }

    override suspend fun findFirst(type: String, memoName: String): PendingActionEntity? =
        state.value
            .filter { it.type == type && it.memoName == memoName }
            .minWithOrNull(compareBy({ it.createdAtEpochMs }, { it.id }))

    override suspend fun insert(action: PendingActionEntity): Long {
        val id = if (action.id == 0L) nextId++ else action.id.also { if (it >= nextId) nextId = it + 1 }
        val stored = action.copy(id = id)
        state.update { current -> current.filterNot { it.id == id } + stored }
        return id
    }

    override suspend fun update(action: PendingActionEntity) {
        state.update { current -> current.map { if (it.id == action.id) action else it } }
    }

    override suspend fun deleteById(id: Long) {
        state.update { current -> current.filterNot { it.id == id } }
    }

    override suspend fun deleteByMemoName(memoName: String) {
        state.update { current -> current.filterNot { it.memoName == memoName } }
    }

    override suspend fun clear() {
        state.value = emptyList()
    }
}
