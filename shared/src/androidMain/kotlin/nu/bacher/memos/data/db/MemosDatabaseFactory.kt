package nu.bacher.memos.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

/**
 * Builds the Room database on Android. We use [BundledSQLiteDriver] so the
 * SQLite version is fixed across devices (instead of inheriting whatever the
 * OS ships) and so the same driver path works if the :shared module ever
 * grows an iOS target.
 *
 * v4 → v5 has a real migration ([MIGRATION_4_5]) that adds the
 * `pending_actions` table — keeps the user's cached memos and reminders
 * intact across the upgrade. The destructive fallback is still here for
 * anything older than v4 (legacy user_version stamps from the pre-KMP
 * Room-via-KAPT era) and for any downgrade.
 */
fun createMemosDatabase(context: Context): MemosDatabase =
    Room.databaseBuilder<MemosDatabase>(
        context = context.applicationContext,
        name = context.getDatabasePath("memos.db").absolutePath,
    )
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .addMigrations(MIGRATION_4_5)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
        .build()
