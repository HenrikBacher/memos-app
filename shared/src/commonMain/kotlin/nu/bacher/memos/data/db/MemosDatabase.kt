package nu.bacher.memos.data.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

@Database(
    entities = [ReminderEntity::class, MemoEntity::class],
    version = 4,
    exportSchema = false,
)
@ConstructedBy(MemosDatabaseConstructor::class)
abstract class MemosDatabase : RoomDatabase() {
    abstract fun reminderDao(): ReminderDao
    abstract fun memoDao(): MemoDao
}

// The Room compiler (via KSP) generates the actual implementations of this
// constructor on each Kotlin Multiplatform target. The expect-object pattern
// is required by Room KMP — see https://developer.android.com/kotlin/multiplatform/room.
@Suppress("NO_ACTUAL_FOR_EXPECT", "KotlinNoActualForExpect")
expect object MemosDatabaseConstructor : RoomDatabaseConstructor<MemosDatabase> {
    override fun initialize(): MemosDatabase
}
