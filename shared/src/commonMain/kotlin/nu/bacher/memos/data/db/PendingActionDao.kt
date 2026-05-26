package nu.bacher.memos.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingActionDao {
    @Query("SELECT * FROM pending_actions ORDER BY createdAtEpochMs ASC, id ASC")
    suspend fun getAll(): List<PendingActionEntity>

    /**
     * Stream of pending action *memo names* — what the list screen needs to
     * paint per-card "sync pending" badges without re-serialising each row's
     * payload on every emission. DISTINCT keeps duplicate UPDATEs from
     * doubling up the result set.
     */
    @Query("SELECT DISTINCT memoName FROM pending_actions")
    fun observePendingNames(): Flow<List<String>>

    @Query(
        "SELECT * FROM pending_actions WHERE type = :type AND memoName = :memoName " +
            "ORDER BY createdAtEpochMs ASC, id ASC LIMIT 1",
    )
    suspend fun findFirst(type: String, memoName: String): PendingActionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(action: PendingActionEntity): Long

    @Update
    suspend fun update(action: PendingActionEntity)

    @Query("DELETE FROM pending_actions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM pending_actions WHERE memoName = :memoName")
    suspend fun deleteByMemoName(memoName: String)

    @Query("DELETE FROM pending_actions")
    suspend fun clear()
}
