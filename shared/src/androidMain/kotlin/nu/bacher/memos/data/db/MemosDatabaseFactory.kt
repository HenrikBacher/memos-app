package nu.bacher.memos.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The memo cache. Destructive migration is deliberate: existing installs may
 * carry the legacy Room v2 schema from before the SQLDelight detour, and a
 * downgrade-throw would crash on first launch. Everything in here is
 * re-fetchable, so dropping it costs the user nothing but a refresh.
 *
 * Reminders used to live in this database too, which quietly made every schema
 * bump destroy them — see [createRemindersDatabase].
 */
fun createMemosDatabase(context: Context): MemosDatabase =
    Room.databaseBuilder<MemosDatabase>(
        context = context.applicationContext,
        name = context.getDatabasePath(MEMOS_DB).absolutePath,
    )
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
        .build()

/**
 * Reminders. No destructive fallback — this is user data, so a future schema
 * change here has to come with a real migration rather than silently dropping
 * alarms the user set.
 */
fun createRemindersDatabase(context: Context): RemindersDatabase =
    Room.databaseBuilder<RemindersDatabase>(
        context = context.applicationContext,
        name = context.getDatabasePath(REMINDERS_DB).absolutePath,
    )
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

/**
 * Rescues reminders left in the legacy combined database, once per install.
 *
 * Before the split, reminders shared [MemosDatabase] and were destroyed by its
 * destructive-migration fallback on every schema bump. Rows are read with
 * plain SQLite (Room must not open the legacy file — that is what would
 * trigger the wipe) and written through [ReminderDao], preserving each row's
 * id so alarms already scheduled against it still match.
 *
 * Guarded end to end: the worst case on failure is the old behaviour, so this
 * must never take app startup down.
 */
suspend fun importLegacyReminders(context: Context, dao: ReminderDao) {
    val appContext = context.applicationContext
    val prefs = appContext.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
    if (prefs.getBoolean(KEY_REMINDERS_IMPORTED, false)) return

    val legacy = appContext.getDatabasePath(MEMOS_DB)
    if (legacy.exists()) {
        val rows = withContext(Dispatchers.IO) { readLegacyReminders(legacy.absolutePath) }
        for (row in rows) {
            runCatching { dao.upsert(row) }
                .onFailure { Log.w(TAG, "could not import reminder for ${row.memoName}", it) }
        }
        if (rows.isNotEmpty()) Log.i(TAG, "imported ${rows.size} legacy reminder(s)")
    }
    prefs.edit().putBoolean(KEY_REMINDERS_IMPORTED, true).apply()
}

/** Read-only peek at the legacy file. Absent table → empty list, not a crash. */
private fun readLegacyReminders(path: String): List<ReminderEntity> = runCatching {
    SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery(
            "SELECT id, memoName, triggerAtEpochMs, createdAtEpochMs FROM reminders",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        ReminderEntity(
                            id = c.getInt(0),
                            memoName = c.getString(1),
                            triggerAtEpochMs = c.getLong(2),
                            createdAtEpochMs = c.getLong(3),
                        ),
                    )
                }
            }
        }
    }
}.getOrElse {
    Log.i(TAG, "no legacy reminders to import: ${it.message}")
    emptyList()
}

private const val MEMOS_DB = "memos.db"
private const val REMINDERS_DB = "reminders.db"
private const val MIGRATION_PREFS = "memos_migration_prefs"
private const val KEY_REMINDERS_IMPORTED = "reminders_imported_v1"
private const val TAG = "MemosDatabaseFactory"
