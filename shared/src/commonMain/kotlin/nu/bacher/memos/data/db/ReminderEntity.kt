package nu.bacher.memos.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One reminder per memo (enforced by the unique index on [memoName]).
 *
 * [id] is the stable PendingIntent request code we hand AlarmManager —
 * Room auto-assigns it on insert. The previous design hashed [memoName]
 * for the request code, but hashCode collisions across two different memo
 * names would silently overwrite each other's alarms; the autoGenerate id
 * gives every reminder a unique slot.
 */
@Entity(
    tableName = "reminders",
    indices = [Index(value = ["memoName"], unique = true)],
)
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    /** memo resource name, e.g. "memos/abc123". */
    val memoName: String,
    val triggerAtEpochMs: Long,
    val createdAtEpochMs: Long,
)
