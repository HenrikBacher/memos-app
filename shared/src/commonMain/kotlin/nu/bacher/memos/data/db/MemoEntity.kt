package nu.bacher.memos.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached memo. The server is still source of truth — this row is rewritten on
 * every refresh. `orderInList` preserves the server's sort so we don't have to
 * recreate it from `pinned`/`displayTime` (which would diverge from the API's
 * own ordering rules).
 */
@Entity(tableName = "memos")
data class MemoEntity(
    @PrimaryKey val name: String,
    val uid: String?,
    val content: String,
    val visibility: String,
    val state: String?,
    val pinned: Boolean,
    val createTime: String?,
    val updateTime: String?,
    val displayTime: String?,
    val creator: String?,
    /** Comma-separated tag names; empty string when none. */
    val tagsCsv: String,
    /** JSON-encoded List<AttachmentDto>; empty string when none. */
    val attachmentsJson: String,
    val orderInList: Int,
    val cachedAtEpochMs: Long,
)
