package nu.bacher.memos.data.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

/**
 * Disposable cache of server state. Everything here can be re-fetched, which
 * is what licenses the destructive-migration fallback in `MemosDatabaseFactory`.
 * User data that cannot be re-fetched belongs in [RemindersDatabase] instead —
 * do not add it here.
 */
@Database(
    entities = [MemoEntity::class, PendingActionEntity::class],
    version = 6,
    exportSchema = false,
)
@ConstructedBy(MemosDatabaseConstructor::class)
abstract class MemosDatabase : RoomDatabase() {
    abstract fun memoDao(): MemoDao
    abstract fun pendingActionDao(): PendingActionDao
}

// The Room compiler (via KSP) generates the actual implementations of this
// constructor on each Kotlin Multiplatform target. The expect-object pattern
// is required by Room KMP — see https://developer.android.com/kotlin/multiplatform/room.
@Suppress("NO_ACTUAL_FOR_EXPECT", "KotlinNoActualForExpect")
expect object MemosDatabaseConstructor : RoomDatabaseConstructor<MemosDatabase> {
    override fun initialize(): MemosDatabase
}
