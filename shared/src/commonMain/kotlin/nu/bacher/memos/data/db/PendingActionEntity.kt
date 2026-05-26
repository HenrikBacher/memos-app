package nu.bacher.memos.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Write operation queued for retry. Inserted by [nu.bacher.memos.data.repo.MemoRepository]
 * when an API call fails with a retriable error (network down, 5xx); flushed
 * by `MemoRepository.syncPending()`.
 *
 * Order matters — actions are applied FIFO so dependent writes (e.g. an
 * UPDATE on a memo whose CREATE is still pending) resolve in the order the
 * user issued them. The composite `(type, memoName)` index lets the merge
 * paths (edit-while-pending, delete-while-pending) find the matching CREATE
 * row without scanning the whole table.
 *
 * [type] is a plain string rather than an enum so Room schema migrations
 * don't choke on enum reordering; the repository encodes it from
 * [nu.bacher.memos.data.repo.PendingActionType].
 */
@Entity(
    tableName = "pending_actions",
    indices = [Index(value = ["type", "memoName"])],
)
data class PendingActionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** "CREATE", "UPDATE", or "DELETE". */
    val type: String,
    /** Temp name (e.g. "memos/local-...") for CREATE, server name otherwise. */
    val memoName: String,
    /** JSON-encoded payload — shape determined by [type]. */
    val payloadJson: String,
    val createdAtEpochMs: Long,
    val attempts: Int = 0,
    val lastAttemptEpochMs: Long? = null,
    /** Last error message — diagnostic only, not load-bearing. */
    val lastError: String? = null,
)
