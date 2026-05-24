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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reminder: ReminderEntity)

    @Query("DELETE FROM reminders WHERE memoName = :memoName")
    suspend fun delete(memoName: String)
}
