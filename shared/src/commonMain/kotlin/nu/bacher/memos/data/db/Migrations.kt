package nu.bacher.memos.data.db

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * v4 → v5: add the `pending_actions` table that backs the offline write
 * queue in MemoRepository.
 *
 * v4 carried only `memos` + `reminders`; we add the new table without
 * touching either. Anything older than v4 still hits the destructive
 * fallback in [createMemosDatabase] (and on Android in MemosDatabaseFactory)
 * — those installs predate the SQLDelight detour and never had a proper
 * schema chain.
 *
 * The SQL mirrors what Room would have generated for [PendingActionEntity]:
 *  - `INTEGER PRIMARY KEY AUTOINCREMENT` for the autoGenerate Long PK
 *    (SQLite makes the PK column implicitly NOT NULL — Room's schema
 *    validator matches by PRAGMA, not by literal CREATE text)
 *  - `NOT NULL` on every non-nullable primitive
 *  - no constraint on the two nullable columns
 *  - the index name follows Room's `index_<table>_<cols>` convention so
 *    the runtime schema check sees the same index it would have created
 *    from scratch
 */
val MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `pending_actions` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT,
                `type` TEXT NOT NULL,
                `memoName` TEXT NOT NULL,
                `payloadJson` TEXT NOT NULL,
                `createdAtEpochMs` INTEGER NOT NULL,
                `attempts` INTEGER NOT NULL,
                `lastAttemptEpochMs` INTEGER,
                `lastError` TEXT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_pending_actions_type_memoName` " +
                "ON `pending_actions` (`type`, `memoName`)",
        )
    }
}
