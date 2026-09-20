package nu.bacher.memos.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoDao {
    @Query("SELECT * FROM memos ORDER BY orderInList ASC")
    fun observeAll(): Flow<List<MemoEntity>>

    @Query("SELECT * FROM memos ORDER BY orderInList ASC")
    suspend fun getAll(): List<MemoEntity>

    /**
     * Name + content of the most recent active memos, for the home-screen
     * widget. A projection with a LIMIT rather than the whole table: the
     * widget renders a handful of rows, and the observing query runs for the
     * lifetime of the process on every cache write.
     */
    @Query(
        "SELECT name, content FROM memos WHERE COALESCE(state, 'NORMAL') != 'ARCHIVED' " +
            "ORDER BY orderInList ASC LIMIT :limit",
    )
    fun observeWidgetMemos(limit: Int): Flow<List<WidgetMemoRow>>

    @Query(
        "SELECT name, content FROM memos WHERE COALESCE(state, 'NORMAL') != 'ARCHIVED' " +
            "ORDER BY orderInList ASC LIMIT :limit",
    )
    suspend fun widgetMemos(limit: Int): List<WidgetMemoRow>

    /**
     * Paging source backing the list screen, scoped to one lifecycle state.
     * Same ORDER BY as [observeAll] so the local cache renders in the server's
     * chosen order (pinned/displayTime etc. are baked into
     * [MemoEntity.orderInList] at insert time).
     *
     * Splitting archived from active here rather than filtering the loaded
     * `PagingData` keeps every emitted page full: a client-side filter can
     * empty a page entirely, and Paging only requests more pages in response
     * to item access — so an all-filtered first page reads as "no results"
     * and never pages forward.
     *
     * `state` is nullable on rows cached before the column carried a value;
     * those are active memos.
     */
    @Query(
        "SELECT * FROM memos WHERE (COALESCE(state, 'NORMAL') = 'ARCHIVED') = :archived " +
            "ORDER BY orderInList ASC",
    )
    fun pagingSource(archived: Boolean): PagingSource<Int, MemoEntity>

    @Query("SELECT * FROM memos WHERE name = :name LIMIT 1")
    suspend fun get(name: String): MemoEntity?

    @Query("SELECT COALESCE(MAX(orderInList) + 1, 0) FROM memos")
    suspend fun nextOrderIndex(): Int

    /**
     * How many of [names] are not pinned. Lets a bulk pin decide its direction
     * with one query instead of a point lookup per selected memo.
     */
    @Query("SELECT COUNT(*) FROM memos WHERE name IN (:names) AND pinned = 0")
    suspend fun unpinnedCount(names: List<String>): Int

    /**
     * Temp rows older than [cutoff] — the orphan sweep's input. Scoped in SQL
     * rather than filtered from [getAll] so the sweep doesn't pull the whole
     * cache into memory on every sync.
     */
    @Query(
        "SELECT * FROM memos WHERE name LIKE :tempPrefix || '%' AND cachedAtEpochMs < :cutoff " +
            "AND syncFailed = 0 ORDER BY orderInList ASC",
    )
    suspend fun tempRowsOlderThan(tempPrefix: String, cutoff: Long): List<MemoEntity>

    /**
     * Temp rows belonging to one lifecycle partition — what survives
     * [deleteSynced] and has to keep its place at the top. Scoped to the
     * partition so a refresh of one view never renumbers the other's rows.
     */
    @Query(
        "SELECT * FROM memos WHERE name LIKE :tempPrefix || '%' " +
            "AND (COALESCE(state, 'NORMAL') = 'ARCHIVED') = :archived ORDER BY orderInList ASC",
    )
    suspend fun keptTempRows(tempPrefix: String, archived: Boolean): List<MemoEntity>

    /** Names of rows whose queued write was abandoned. Drives the list badge. */
    @Query("SELECT name FROM memos WHERE syncFailed != 0")
    fun observeSyncFailedNames(): Flow<List<String>>

    @Query("UPDATE memos SET syncFailed = :failed WHERE name = :name")
    suspend fun setSyncFailed(name: String, failed: Boolean)

    /**
     * Offline fallback for search — a LIKE scan of the cached content, used
     * when the server-side search can't be reached. [query] must already have
     * its LIKE wildcards escaped (see `MemoRepository.escapeLike`).
     *
     * [tag] matches either the server-supplied tag list or an inline `#tag` in
     * the content, mirroring the client-side `tagMatches` the cached list path
     * uses. This is approximate by design: the cache only holds pages the user
     * has already loaded.
     */
    @Query(
        "SELECT * FROM memos WHERE content LIKE '%' || :query || '%' ESCAPE '\\' " +
            "AND (:tag IS NULL " +
            "OR ',' || tagsCsv || ',' LIKE '%,' || :tag || ',%' ESCAPE '\\' " +
            "OR content LIKE '%#' || :tag || '%' ESCAPE '\\') " +
            "ORDER BY orderInList ASC LIMIT :limit",
    )
    suspend fun searchCached(query: String, tag: String?, limit: Int): List<MemoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memo: MemoEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(memos: List<MemoEntity>)

    @Query("DELETE FROM memos WHERE name = :name")
    suspend fun delete(name: String)

    @Query("DELETE FROM memos")
    suspend fun clear()

    /**
     * Deletes the server-backed rows of one lifecycle state, leaving the
     * client-side temp rows (and the other state's rows) alone. See
     * [replaceAll].
     */
    @Query(
        "DELETE FROM memos WHERE name NOT LIKE :tempPrefix || '%' " +
            "AND (COALESCE(state, 'NORMAL') = 'ARCHIVED') = :archived",
    )
    suspend fun deleteSynced(tempPrefix: String, archived: Boolean)

    @Query("UPDATE memos SET orderInList = orderInList + 1 WHERE name != :exceptName")
    suspend fun shiftOrderExcept(exceptName: String)

    /**
     * Replaces the server-backed rows with [memos], deleting rows that no
     * longer exist on the server. Runs in one transaction so the list flow
     * never emits an empty intermediate state.
     *
     * Rows named with [tempPrefix] are *kept*: they are optimistic creates that
     * have not reached the server yet, so they aren't in [memos] and a blind
     * `DELETE FROM memos` would drop the user's unsynced writes on the floor —
     * permanently, for a row the orphan sweep hasn't adopted yet. They're
     * renumbered to the top and the server page continues the sequence after
     * them, matching where `insertAtTop` originally put them.
     *
     * [archived] scopes the swap to one lifecycle state: the active and
     * archived views page independently over the same table, so refreshing
     * one must not evict the other's cached rows.
     *
     * Ordering is rebuilt over the surviving rows so the sequence stays
     * monotonic for the paging cursor, with temp rows kept at the top where
     * `insertAtTop` put them.
     */
    @Transaction
    suspend fun replaceAll(memos: List<MemoEntity>, tempPrefix: String, archived: Boolean) {
        deleteSynced(tempPrefix, archived)
        val kept = getAll()
        upsertAll(kept.mapIndexed { i, row -> row.copy(orderInList = i) })
        upsertAll(memos.mapIndexed { i, m -> m.copy(orderInList = kept.size + i) })
    }

    /**
     * Appends [memos] after the existing rows, continuing the [orderInList]
     * sequence so the paging cursor stays monotonic. Called by the
     * RemoteMediator on APPEND loads.
     */
    @Transaction
    suspend fun appendAll(memos: List<MemoEntity>) {
        if (memos.isEmpty()) return
        val base = nextOrderIndex()
        upsertAll(memos.mapIndexed { i, m -> m.copy(orderInList = base + i) })
    }

    /**
     * Inserts [memo] at the top of the list, shifting every other row's
     * [MemoEntity.orderInList] down by one. The next `refresh()` will
     * reconcile if the server's ordering disagrees.
     */
    @Transaction
    suspend fun insertAtTop(memo: MemoEntity) {
        shiftOrderExcept(memo.name)
        upsert(memo.copy(orderInList = 0))
    }
}
