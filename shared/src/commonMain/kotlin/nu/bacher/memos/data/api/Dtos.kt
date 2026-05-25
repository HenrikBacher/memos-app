@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package nu.bacher.memos.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * memos v1 API DTOs. Targets memos 0.22+ where resources are addressed by
 * resource names like "memos/{uid}". Most fields are optional because the
 * upstream proto evolves and we don't want unknown fields to fail
 * deserialization (the Json instance is configured ignoreUnknownKeys).
 */

@Serializable
data class MemoDto(
    val name: String = "",
    val uid: String? = null,
    val content: String = "",
    val visibility: String = "PRIVATE",
    val state: String? = null,
    val pinned: Boolean = false,
    @SerialName("createTime") val createTime: String? = null,
    @SerialName("updateTime") val updateTime: String? = null,
    @SerialName("displayTime") val displayTime: String? = null,
    val creator: String? = null,
    val tags: List<String> = emptyList(),
    // memos renamed `resources` → `attachments` around 0.22; the alias keeps
    // older self-hosted servers working without a manual version bump.
    @JsonNames("resources")
    val attachments: List<AttachmentDto> = emptyList(),
)

/**
 * File attached to a memo. Server provides either a `name` like
 * "attachments/{id}" served from the memos server, or an `externalLink` for
 * resources stored elsewhere (S3, etc.).
 */
@Serializable
data class AttachmentDto(
    val name: String = "",
    @SerialName("createTime") val createTime: String? = null,
    val filename: String = "",
    /** MIME type (e.g. "image/png", "application/pdf"). */
    val type: String = "",
    val size: Long = 0,
    val externalLink: String? = null,
    /** Parent memo resource name (when included). */
    val memo: String? = null,
)

@Serializable
data class CreateMemoRequest(
    val content: String,
    val visibility: String = "PRIVATE",
    /** Reference existing attachments by name so the server links them on create. */
    val attachments: List<AttachmentRef>? = null,
)

@Serializable
data class UpdateMemoRequest(
    val content: String? = null,
    val visibility: String? = null,
    val pinned: Boolean? = null,
    val state: String? = null,
    /** Authoritative attachment list — server reconciles links/unlinks against this. */
    val attachments: List<AttachmentRef>? = null,
)

/** A bare reference to an attachment by resource name (e.g. "attachments/abc"). */
@Serializable
data class AttachmentRef(val name: String)

@Serializable
data class ListMemosResponse(
    val memos: List<MemoDto> = emptyList(),
    val nextPageToken: String? = null,
)

/** Extract the uid from a resource name like "memos/abc123" → "abc123". */
fun String.memoUid(): String = substringAfter('/', this)
