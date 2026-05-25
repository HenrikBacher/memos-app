package nu.bacher.memos.data.db

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

    @Query("SELECT * FROM memos WHERE name = :name LIMIT 1")
    suspend fun get(name: String): MemoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memo: MemoEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(memos: List<MemoEntity>)

    @Query("DELETE FROM memos WHERE name = :name")
    suspend fun delete(name: String)

    @Query("DELETE FROM memos")
    suspend fun clear()

    @Query("UPDATE memos SET orderInList = orderInList + 1 WHERE name != :exceptName")
    suspend fun shiftOrderExcept(exceptName: String)

    /**
     * Replaces the entire cache with [memos], deleting rows that no longer
     * exist on the server. Runs in one transaction so the list flow never
     * emits an empty intermediate state.
     */
    @Transaction
    suspend fun replaceAll(memos: List<MemoEntity>) {
        clear()
        upsertAll(memos)
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
