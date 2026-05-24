package nu.bacher.memos.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reminders")
data class ReminderEntity(
    /** memo resource name, e.g. "memos/abc123". One reminder per memo. */
    @PrimaryKey val memoName: String,
    val triggerAtEpochMs: Long,
    val createdAtEpochMs: Long,
)
