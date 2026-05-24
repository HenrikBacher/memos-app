package nu.bacher.memos.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ReminderEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class MemosDatabase : RoomDatabase() {
    abstract fun reminderDao(): ReminderDao
}
