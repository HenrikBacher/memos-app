package nu.bacher.memos.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import nu.bacher.memos.data.db.MemoDao
import nu.bacher.memos.data.db.MemoEntity

/**
 * In-memory fake of [MemoDao] for repository tests. Uses [MutableStateFlow]
 * so `observeAll()` emits on changes the same way Room's flow does.
 *
 * The interface's `@Transaction replaceAll(...)` default body calls
 * [clear] + [upsertAll]; we let that pass through so the fake exercises the
 * same code path real Room runs in production.
 */
class FakeMemoDao : MemoDao {
    private val state = MutableStateFlow<List<MemoEntity>>(emptyList())

    override fun observeAll(): Flow<List<MemoEntity>> =
        state.map { rows -> rows.sortedBy { it.orderInList } }

    override suspend fun getAll(): List<MemoEntity> =
        state.value.sortedBy { it.orderInList }

    override suspend fun get(name: String): MemoEntity? =
        state.value.firstOrNull { it.name == name }

    override suspend fun upsert(memo: MemoEntity) {
        state.update { current -> current.filterNot { it.name == memo.name } + memo }
    }

    override suspend fun upsertAll(memos: List<MemoEntity>) {
        memos.forEach { upsert(it) }
    }

    override suspend fun delete(name: String) {
        state.update { current -> current.filterNot { it.name == name } }
    }

    override suspend fun clear() {
        state.value = emptyList()
    }

    override suspend fun shiftOrderExcept(exceptName: String) {
        state.update { current ->
            current.map { row ->
                if (row.name == exceptName) row else row.copy(orderInList = row.orderInList + 1)
            }
        }
    }
}
