package nu.bacher.memos.data.repo

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.builtins.ListSerializer
import nu.bacher.memos.data.api.AttachmentCreate
import nu.bacher.memos.data.api.AttachmentDto
import nu.bacher.memos.data.api.AttachmentRef
import nu.bacher.memos.data.api.CreateAttachmentRequest
import nu.bacher.memos.data.api.CreateMemoRequest
import nu.bacher.memos.data.api.MemoDto
import nu.bacher.memos.data.api.MemosApi
import nu.bacher.memos.data.api.MemosJson
import nu.bacher.memos.data.api.UpdateMemoRequest
import nu.bacher.memos.data.api.memoUid
import nu.bacher.memos.data.db.MemoDao
import nu.bacher.memos.data.db.MemoEntity
import nu.bacher.memos.util.currentTimeMillis

/**
 * Offline-first memos store. The Room cache is the UI's source of truth so the
 * list renders instantly on cold start; [refresh] replaces the cache with the
 * server's view, and create/update/delete write through to both.
 *
 * Server-side ordering is preserved via `orderInList` — the API's sort rules
 * (pinned, displayTime, etc.) shift with memos versions, so we don't try to
 * recreate them locally.
 */
class MemoRepository(
    private val api: MemosApi,
    private val dao: MemoDao,
    private val verifyClientFactory: (serverUrl: String, token: String) -> HttpClient,
) {
    val memos: Flow<List<MemoDto>> = dao.observeAll().map { rows -> rows.map(MemoEntity::toDto) }

    private companion object {
        const val MAX_REFRESH_PAGES = 200
    }

    suspend fun refresh(): Result<Unit> = runCatching {
        val all = mutableListOf<MemoDto>()
        var token: String? = null
        var iterations = 0
        do {
            val response = api.listMemos(pageToken = token)
            all += response.memos
            token = response.nextPageToken?.takeIf { it.isNotBlank() }
            iterations++
            // Safety cap — prevent a buggy server from pulling pages forever.
            // 200 pages at the default pageSize=50 is 10k memos, well beyond
            // what fits in memory comfortably anyway.
        } while (token != null && iterations < MAX_REFRESH_PAGES)
        val now = currentTimeMillis()
        dao.replaceAll(all.mapIndexed { idx, dto -> dto.toEntity(idx, now) })
    }

    suspend fun get(name: String): MemoDto {
        val memo = api.getMemo(name.memoUid())
        val cached = dao.get(memo.name)
        dao.upsert(memo.toEntity(cached?.orderInList ?: Int.MAX_VALUE, currentTimeMillis()))
        return memo
    }

    suspend fun create(
        content: String,
        visibility: String = "PRIVATE",
        attachments: List<AttachmentDto> = emptyList(),
    ): MemoDto {
        val memo = api.createMemo(
            CreateMemoRequest(
                content = content,
                visibility = visibility,
                attachments = attachments.takeIf { it.isNotEmpty() }
                    ?.map { AttachmentRef(name = it.name) },
            ),
        )
        // New memo lands at the top — shift existing rows down. Single
        // UPDATE + upsert, transactionally, instead of rewriting the table.
        dao.insertAtTop(memo.toEntity(0, currentTimeMillis()))
        return memo
    }

    suspend fun update(
        name: String,
        content: String,
        visibility: String? = null,
        attachments: List<AttachmentDto>? = null,
    ): MemoDto {
        val updated = api.updateMemo(
            name.memoUid(),
            UpdateMemoRequest(
                content = content,
                visibility = visibility,
                attachments = attachments?.map { AttachmentRef(name = it.name) },
            ),
        )
        val cached = dao.get(updated.name)
        dao.upsert(updated.toEntity(cached?.orderInList ?: Int.MAX_VALUE, currentTimeMillis()))
        return updated
    }

    /**
     * Uploads [bytes] as an attachment. When [memoName] is non-null the server
     * links the attachment to that memo immediately; for new memos pass null
     * here and reference the returned attachment by name in the next
     * [create] call.
     */
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun uploadAttachment(
        bytes: ByteArray,
        filename: String,
        type: String,
        memoName: String? = null,
    ): AttachmentDto {
        val encoded = Base64.encode(bytes)
        return api.createAttachment(
            CreateAttachmentRequest(
                AttachmentCreate(
                    filename = filename,
                    type = type,
                    content = encoded,
                    memo = memoName,
                ),
            ),
        )
    }

    suspend fun delete(name: String) {
        api.deleteMemo(name.memoUid())
        dao.delete(name)
    }

    /** Wipe the local cache. Called on logout. */
    suspend fun clearCache() {
        dao.clear()
    }

    /**
     * Verifies a *candidate* server URL + token by listing memos directly,
     * without going through the AuthStore-backed default client. Used by login
     * so we can validate creds before saving them — avoids saving bad creds and
     * the navigation race where the settings listener would otherwise flip
     * isAuthenticated before verify completes.
     */
    suspend fun verifyCreds(serverUrl: String, token: String): Result<Unit> = runCatching {
        val client = verifyClientFactory(serverUrl, token)
        try {
            // Probing the memos-list endpoint with pageSize=1 — the older
            // /auth/status endpoint was removed in memos 0.22+, and listing
            // memos is the canonical "does this token work" check.
            // expectSuccess=true on the client converts a non-2xx into a throw.
            client.get("api/v1/memos") { parameter("pageSize", 1) }
        } finally {
            client.close()
        }
    }
}

private val AttachmentListSerializer = ListSerializer(AttachmentDto.serializer())

private fun MemoDto.toEntity(orderInList: Int, cachedAtEpochMs: Long): MemoEntity =
    MemoEntity(
        name = name,
        uid = uid,
        content = content,
        visibility = visibility,
        state = state,
        pinned = pinned,
        createTime = createTime,
        updateTime = updateTime,
        displayTime = displayTime,
        creator = creator,
        tagsCsv = tags.joinToString(","),
        attachmentsJson = if (attachments.isEmpty()) "" else MemosJson.encodeToString(AttachmentListSerializer, attachments),
        orderInList = orderInList,
        cachedAtEpochMs = cachedAtEpochMs,
    )

private fun MemoEntity.toDto(): MemoDto =
    MemoDto(
        name = name,
        uid = uid,
        content = content,
        visibility = visibility,
        state = state,
        pinned = pinned,
        createTime = createTime,
        updateTime = updateTime,
        displayTime = displayTime,
        creator = creator,
        tags = if (tagsCsv.isEmpty()) emptyList() else tagsCsv.split(','),
        attachments = if (attachmentsJson.isEmpty()) emptyList()
        else MemosJson.decodeFromString(AttachmentListSerializer, attachmentsJson),
    )
