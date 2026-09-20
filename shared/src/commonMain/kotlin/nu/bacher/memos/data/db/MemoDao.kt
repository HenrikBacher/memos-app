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
     * Paging source backing the list screen. Same ORDER BY as [observeAll] so
     * the local cache renders in the server's chosen order (pinned/displayTime
     * etc. are baked into [MemoEntity.orderInList] at insert time).
     */
    @Query("SELECT * FROM memos ORDER BY orderInList ASC")
    fun pagingSource(): PagingSource<Int, MemoEntity>

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
            "ORDER BY orderInList ASC",
    )
    suspend fun tempRowsOlderThan(tempPrefix: String, cutoff: Long): List<MemoEntity>

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

    /** Deletes every row *except* the client-side temp rows. See [replaceAll]. */
    @Query("DELETE FROM memos WHERE name NOT LIKE :tempPrefix || '%'")
    suspend fun deleteSynced(tempPrefix: String)

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
     * After [deleteSynced] the only rows left *are* those temp rows, so
     * [getAll] reads them back without needing a second prefix-scoped query.
     */
    @Transaction
    suspend fun replaceAll(memos: List<MemoEntity>, tempPrefix: String) {
        deleteSynced(tempPrefix)
        val temps = getAll()
        upsertAll(temps.mapIndexed { i, row -> row.copy(orderInList = i) })
        upsertAll(memos.mapIndexed { i, m -> m.copy(orderInList = temps.size + i) })
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
