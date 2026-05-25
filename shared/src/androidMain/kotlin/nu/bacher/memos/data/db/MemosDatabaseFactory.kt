package nu.bacher.memos.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

/**
 * Builds the Room database on Android. We use [BundledSQLiteDriver] so the
 * SQLite version is fixed across devices (instead of inheriting whatever the
 * OS ships) and so the same driver path works if the :shared module ever
 * grows an iOS target. Reminders are ephemeral (re-set by the user), so a
 * destructive fallback on schema mismatch is acceptable — and necessary on
 * existing installs that still carry the old user_version stamped by the
 * previous Room-via-KAPT setup.
 */
fun createMemosDatabase(context: Context): MemosDatabase =
    Room.databaseBuilder<MemosDatabase>(
        context = context.applicationContext,
        name = context.getDatabasePath("memos.db").absolutePath,
    )
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
        .build()
