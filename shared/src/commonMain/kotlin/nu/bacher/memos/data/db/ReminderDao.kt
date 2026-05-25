package nu.bacher.memos.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders")
    fun observeAll(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders")
    suspend fun getAll(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE memoName = :memoName LIMIT 1")
    suspend fun get(memoName: String): ReminderEntity?

    @Query("SELECT * FROM reminders WHERE memoName = :memoName LIMIT 1")
    fun observe(memoName: String): Flow<ReminderEntity?>

    /**
     * Inserts (or replaces, on a unique-index conflict) and returns the
     * auto-assigned row id. Callers use the id as the PendingIntent request
     * code so each reminder gets a stable, unique alarm slot.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reminder: ReminderEntity): Long

    @Query("DELETE FROM reminders WHERE memoName = :memoName")
    suspend fun delete(memoName: String)
}
