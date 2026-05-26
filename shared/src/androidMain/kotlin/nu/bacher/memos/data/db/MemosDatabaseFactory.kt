package nu.bacher.memos.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

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
