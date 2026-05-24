package nu.bacher.memos.data.db

import android.content.Context
import androidx.room.Room

/**
 * Builds the Room database on Android. We use `Room.databaseBuilder` with a
 * Context so Room picks its default Android SQLite driver. Reminders are
 * ephemeral (re-set by the user), so a destructive fallback on schema
 * mismatch is acceptable — and necessary on existing installs that still
 * carry the old user_version stamped by the previous Room-via-KAPT setup.
 */
fun createMemosDatabase(context: Context): MemosDatabase =
    Room.databaseBuilder<MemosDatabase>(
        context = context.applicationContext,
        name = context.getDatabasePath("memos.db").absolutePath,
    )
        .fallbackToDestructiveMigration(dropAllTables = true)
        .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
        .build()
