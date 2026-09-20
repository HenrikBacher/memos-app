package nu.bacher.memos.data.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

/**
 * Reminders live in their own database, apart from the memo cache.
 *
 * A reminder is the one thing this app stores that the server has never heard
 * of — losing it loses user intent that cannot be re-fetched. [MemosDatabase]
 * is a disposable cache and deliberately falls back to a destructive migration
 * (see `MemosDatabaseFactory`), which would take the reminders table with it
 * on every schema bump. Separating them means cache-schema churn can never
 * touch user data, and this database can insist on real migrations.
 */
@Database(
    entities = [ReminderEntity::class],
    version = 1,
    exportSchema = false,
)
@ConstructedBy(RemindersDatabaseConstructor::class)
abstract class RemindersDatabase : RoomDatabase() {
    abstract fun reminderDao(): ReminderDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT", "KotlinNoActualForExpect")
expect object RemindersDatabaseConstructor : RoomDatabaseConstructor<RemindersDatabase> {
    override fun initialize(): RemindersDatabase
}
