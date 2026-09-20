package nu.bacher.memos.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

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
 *
 * Reminders used to live in [MemosDatabase], where its destructive-migration
 * fallback destroyed them on every cache schema bump. The legacy rows are
 * rescued in the creation callback: creating this file *is* the "already
 * imported" marker, and Room runs the callback before any query on this
 * database returns — so a reader racing startup (notably `BootReceiver`
 * re-arming alarms straight after boot) cannot observe an empty table and
 * conclude there is nothing to schedule.
 */
fun createRemindersDatabase(context: Context): RemindersDatabase {
    val legacyPath = context.applicationContext.getDatabasePath(MEMOS_DB).absolutePath
    return Room.databaseBuilder<RemindersDatabase>(
        context = context.applicationContext,
        name = context.applicationContext.getDatabasePath(REMINDERS_DB).absolutePath,
    )
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .addCallback(LegacyReminderImport(legacyPath))
        .build()
}

/**
 * Copies reminders out of the pre-split database the first time this one is
 * created.
 *
 * Reads with plain SQLite: Room must not open the legacy file, because that
 * is what triggers its destructive migration. Row ids are preserved so alarms
 * already scheduled against them still match.
 *
 * Every failure is swallowed — a throw here would abort database creation,
 * and the worst case without the import is the behaviour users already had.
 */
private class LegacyReminderImport(private val legacyPath: String) : RoomDatabase.Callback() {
    override fun onCreate(connection: SQLiteConnection) {
        val rows = readLegacyReminders(legacyPath)
        if (rows.isEmpty()) return
        runCatching {
            connection.prepare(
                "INSERT OR IGNORE INTO reminders (id, memoName, triggerAtEpochMs, createdAtEpochMs) " +
                    "VALUES (?, ?, ?, ?)",
            ).use { stmt ->
                for (row in rows) {
                    stmt.bindInt(1, row.id)
                    stmt.bindText(2, row.memoName)
                    stmt.bindLong(3, row.triggerAtEpochMs)
                    stmt.bindLong(4, row.createdAtEpochMs)
                    stmt.step()
                    stmt.reset()
                }
            }
            Log.i(TAG, "imported ${rows.size} legacy reminder(s)")
        }.onFailure { Log.w(TAG, "legacy reminder import failed", it) }
    }
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
private const val TAG = "MemosDatabaseFactory"
