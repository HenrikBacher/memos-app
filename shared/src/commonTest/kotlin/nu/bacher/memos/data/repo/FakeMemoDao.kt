package nu.bacher.memos.data.repo

import androidx.paging.PagingSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import nu.bacher.memos.data.db.MemoDao
import nu.bacher.memos.data.db.MemoEntity
import nu.bacher.memos.data.db.WidgetMemoRow

/**
 * In-memory fake of [MemoDao] for repository tests. Uses [MutableStateFlow]
 * so `observeAll()` emits on changes the same way Room's flow does.
 *
 * The interface's `@Transaction` default bodies (`replaceAll`, `appendAll`,
 * `insertAtTop`) are inherited, not overridden, so the fake exercises the same
 * composition of primitives that real Room runs — including `replaceAll`
 * delegating to [deleteSynced], which is what keeps unsynced temp rows alive
 * across a refresh.
 */
class FakeMemoDao : MemoDao {
    private val state = MutableStateFlow<List<MemoEntity>>(emptyList())

    override fun observeAll(): Flow<List<MemoEntity>> =
        state.map { rows -> rows.sortedBy { it.orderInList } }

    override suspend fun getAll(): List<MemoEntity> =
        state.value.sortedBy { it.orderInList }

    override fun observeWidgetMemos(limit: Int): Flow<List<WidgetMemoRow>> =
        state.map { rows -> rows.widgetRows(limit) }

    override suspend fun widgetMemos(limit: Int): List<WidgetMemoRow> =
        state.value.widgetRows(limit)

    override fun pagingSource(archived: Boolean): PagingSource<Int, MemoEntity> =
        // Repository tests don't exercise paging; a fake PagingSource here
        // would just be dead code. Tests that need this can override per
        // case.
        throw NotImplementedError("pagingSource() not used by these tests")

    override suspend fun get(name: String): MemoEntity? =
        state.value.firstOrNull { it.name == name }

    override suspend fun nextOrderIndex(): Int =
        state.value.maxOfOrNull { it.orderInList }?.let { it + 1 } ?: 0

    override suspend fun unpinnedCount(names: List<String>): Int =
        state.value.count { it.name in names && !it.pinned }

    override suspend fun tempRowsOlderThan(tempPrefix: String, cutoff: Long): List<MemoEntity> =
        state.value
            .filter {
                it.name.startsWith(tempPrefix) && it.cachedAtEpochMs < cutoff && !it.syncFailed
            }
            .sortedBy { it.orderInList }

    override suspend fun keptTempRows(tempPrefix: String, archived: Boolean): List<MemoEntity> =
        state.value
            .filter { it.name.startsWith(tempPrefix) && it.isArchived() == archived }
            .sortedBy { it.orderInList }

    override fun observeSyncFailedNames(): Flow<List<String>> =
        state.map { rows -> rows.filter { it.syncFailed }.map { it.name } }

    override suspend fun setSyncFailed(name: String, failed: Boolean) {
        state.update { current ->
            current.map { if (it.name == name) it.copy(syncFailed = failed) else it }
        }
    }

    override suspend fun searchCached(query: String, tag: String?, limit: Int): List<MemoEntity> {
        // The real query is SQL LIKE with an ESCAPE clause; the fake does a
        // plain substring match, which is enough for the repository tests
        // that exercise the offline-search fallback.
        val needle = query.replace("\\", "")
        return state.value
            .filter { needle.isEmpty() || it.content.contains(needle) }
            .filter { row ->
                tag == null ||
                    row.tagsCsv.split(',').contains(tag) ||
                    row.content.contains("#" + tag)
            }
            .sortedBy { it.orderInList }
            .take(limit)
    }

    override suspend fun deleteSynced(tempPrefix: String, archived: Boolean) {
        state.update { current ->
            current.filter { row ->
                row.name.startsWith(tempPrefix) || row.isArchived() != archived
            }
        }
    }

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

private fun List<MemoEntity>.widgetRows(limit: Int): List<WidgetMemoRow> =
    asSequence()
        .filter { !it.isArchived() }
        .sortedBy { it.orderInList }
        .take(limit)
        .map { WidgetMemoRow(it.name, it.content) }
        .toList()

/** Mirrors the DAO's `COALESCE(state, 'NORMAL') = 'ARCHIVED'`. */
private fun MemoEntity.isArchived(): Boolean = (state ?: "NORMAL") == "ARCHIVED"
