package nu.bacher.memos.data.repo

import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map as mapFlow
import nu.bacher.memos.data.api.AttachmentCreate
import nu.bacher.memos.data.api.AttachmentDto
import nu.bacher.memos.data.api.AttachmentRef
import nu.bacher.memos.data.api.CreateAttachmentRequest
import nu.bacher.memos.data.api.CreateMemoRequest
import nu.bacher.memos.data.api.MemoDto
import nu.bacher.memos.data.api.MemosApi
import nu.bacher.memos.data.api.UpdateMemoRequest
import nu.bacher.memos.data.api.memoUid
import nu.bacher.memos.data.db.MemoDao
import nu.bacher.memos.util.currentTimeMillis

/**
 * Offline-first memos store. The Room cache is the UI's source of truth so the
 * list renders instantly on cold start; pages are fetched by
 * [MemosRemoteMediator] (driven by [memosPagingData]), and create/update/delete
 * write to the cache first, then push to the server with a rollback on failure.
 *
 * Server-side ordering is preserved via `orderInList` — the API's sort rules
 * (pinned, displayTime, etc.) shift with memos versions, so we don't try to
 * recreate them locally.
 */
@OptIn(ExperimentalPagingApi::class)
class MemoRepository(
    private val api: MemosApi,
    private val dao: MemoDao,
    private val verifyClientFactory: (serverUrl: String, token: String) -> HttpClient,
) {

    /**
     * Stream of memos paged from the local cache and refilled by the
     * RemoteMediator on demand. Construct one Pager per call so each consumer
     * gets its own paging state; the DAO/PagingSource is shared.
     */
    val memosPagingData: Flow<PagingData<MemoDto>>
        get() = Pager(
            config = PagingConfig(
                pageSize = SERVER_PAGE_SIZE,
                // Match initialLoadSize to one server page so the first
                // network call delivers a full screen without the mediator
                // immediately issuing an APPEND.
                initialLoadSize = SERVER_PAGE_SIZE,
                prefetchDistance = SERVER_PAGE_SIZE / 2,
                enablePlaceholders = false,
            ),
            remoteMediator = MemosRemoteMediator(api = api, dao = dao),
            pagingSourceFactory = { dao.pagingSource() },
        ).flow.mapFlow { it.map { entity -> entity.toDto() } }

    suspend fun get(name: String): MemoDto {
        val memo = api.getMemo(name.memoUid())
        val cached = dao.get(memo.name)
        dao.upsert(memo.toEntity(cached?.orderInList ?: Int.MAX_VALUE, currentTimeMillis()))
        return memo
    }

    /**
     * Optimistic create. The new memo lands at the top of the local cache
     * with a client-side temp id; the API call follows, and on success the
     * temp row is replaced with the server-issued row. On failure the temp
     * row is removed and the exception propagates.
     *
     * The reminder table doesn't reference temp ids (MemoEditViewModel holds
     * the reminder in memory until the real memo name comes back), so the
     * temp row never has dangling foreign references.
     */
    suspend fun create(
        content: String,
        visibility: String = "PRIVATE",
        attachments: List<AttachmentDto> = emptyList(),
    ): MemoDto {
        val tempName = "memos/local-${currentTimeMillis()}"
        val now = currentTimeMillis()
        val tempDto = MemoDto(
            name = tempName,
            content = content,
            visibility = visibility,
            attachments = attachments,
            createTime = null,
            displayTime = null,
        )
        dao.insertAtTop(tempDto.toEntity(0, now))
        return try {
            val saved = api.createMemo(
                CreateMemoRequest(
                    content = content,
                    visibility = visibility,
                    attachments = attachments.takeIf { it.isNotEmpty() }
                        ?.map { AttachmentRef(name = it.name) },
                ),
            )
            // Swap temp row for the server row, keeping order=0.
            dao.delete(tempName)
            dao.insertAtTop(saved.toEntity(0, currentTimeMillis()))
            saved
        } catch (t: Throwable) {
            dao.delete(tempName)
            throw t
        }
    }

    /**
     * Optimistic update. Cache is updated immediately; on API failure the
     * prior entity is restored. Read-modify-write is racy in principle (a
     * concurrent refresh could overwrite the cache in between), but the UI
     * doesn't drive concurrent edits to the same memo, so we accept that.
     */
    suspend fun update(
        name: String,
        content: String,
        visibility: String? = null,
        attachments: List<AttachmentDto>? = null,
    ): MemoDto {
        val prior = dao.get(name)
            ?: error("update($name): no cached entity to update")
        val now = currentTimeMillis()
        val optimistic = prior.copy(
            content = content,
            visibility = visibility ?: prior.visibility,
            attachmentsJson = attachments?.let { encodeAttachments(it) } ?: prior.attachmentsJson,
            cachedAtEpochMs = now,
        )
        dao.upsert(optimistic)
        return try {
            val saved = api.updateMemo(
                name.memoUid(),
                UpdateMemoRequest(
                    content = content,
                    visibility = visibility,
                    attachments = attachments?.map { AttachmentRef(name = it.name) },
                ),
            )
            dao.upsert(saved.toEntity(prior.orderInList, currentTimeMillis()))
            saved
        } catch (t: Throwable) {
            dao.upsert(prior)
            throw t
        }
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

    /** Optimistic delete — cache row goes first, restored if the API throws. */
    suspend fun delete(name: String) {
        val prior = dao.get(name) ?: run {
            api.deleteMemo(name.memoUid())
            return
        }
        dao.delete(name)
        try {
            api.deleteMemo(name.memoUid())
        } catch (t: Throwable) {
            dao.upsert(prior)
            throw t
        }
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

    private companion object {
        const val SERVER_PAGE_SIZE = 50
    }
}

private fun encodeAttachments(attachments: List<AttachmentDto>): String =
    if (attachments.isEmpty()) ""
    else nu.bacher.memos.data.api.MemosJson.encodeToString(
        kotlinx.serialization.builtins.ListSerializer(AttachmentDto.serializer()),
        attachments,
    )
