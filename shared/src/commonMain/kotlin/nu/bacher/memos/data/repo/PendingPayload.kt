package nu.bacher.memos.data.repo

import kotlinx.serialization.Serializable

/**
 * Stored payloads for queued write actions. One @Serializable type per
 * action variant — we pick the right serializer from
 * [nu.bacher.memos.data.db.PendingActionEntity.type] rather than using
 * polymorphic JSON, so the on-disk format stays simple and the type column
 * can be queried directly (e.g. "find pending CREATE for this temp name").
 */
object PendingPayload {
    @Serializable
    data class Create(
        val content: String,
        val visibility: String,
        /** Attachment resource names — re-resolved at sync time. */
        val attachmentNames: List<String>,
    )

    @Serializable
    data class Update(
        val content: String,
        val visibility: String?,
        /** Null means "don't touch attachments on the server"; empty means "clear them". */
        val attachmentNames: List<String>?,
    )
}

enum class PendingActionType(val storedValue: String) {
    CREATE("CREATE"),
    UPDATE("UPDATE"),
    DELETE("DELETE"),
    ;

    companion object {
        fun fromStored(value: String): PendingActionType? =
            entries.firstOrNull { it.storedValue == value }
    }
}
