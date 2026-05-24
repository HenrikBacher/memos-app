package nu.bacher.memos.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
)

@Serializable
data class CreateMemoRequest(
    val content: String,
    val visibility: String = "PRIVATE",
)

@Serializable
data class UpdateMemoRequest(
    val content: String? = null,
    val visibility: String? = null,
    val pinned: Boolean? = null,
    val state: String? = null,
)

@Serializable
data class ListMemosResponse(
    val memos: List<MemoDto> = emptyList(),
    val nextPageToken: String? = null,
)

/** Extract the uid from a resource name like "memos/abc123" → "abc123". */
fun String.memoUid(): String = substringAfter('/', this)
