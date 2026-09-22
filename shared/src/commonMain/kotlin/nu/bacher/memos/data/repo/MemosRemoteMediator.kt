package nu.bacher.memos.data.repo

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import kotlinx.serialization.builtins.ListSerializer
import nu.bacher.memos.data.api.AttachmentDto
import nu.bacher.memos.data.api.MemoDto
import nu.bacher.memos.data.api.MemoState
import nu.bacher.memos.data.api.MemosApi
import nu.bacher.memos.data.api.MemosJson
import nu.bacher.memos.data.db.LocalMemoName
import nu.bacher.memos.data.db.MemoDao
import nu.bacher.memos.data.db.MemoEntity
import nu.bacher.memos.util.currentTimeMillis

/**
 * Pages memos from the server into the Room cache. The DAO's [MemoDao.pagingSource]
 * is the UI's source of truth — this mediator just keeps the cache fresh and
 * appends older pages on demand.
 *
 * The server's pagination is opaque-token-based ([io.ktor.client.HttpClient]
 * sees `nextPageToken` on each [io.ktor.client.HttpClient.get]). We don't try
 * to translate that into integer page keys; instead [nextPageToken] is held in
 * the mediator instance and reset on REFRESH. The trade-off is that a process
 * death loses the token — but Paging triggers a REFRESH on the next attach
 * anyway, so that's fine.
 *
 * REFRESH swaps the server-backed rows inside [MemoDao.replaceAll]'s
 * transaction so the UI never sees an empty intermediate state; unsynced
 * temp rows survive it (see that method). APPEND extends the existing
 * [MemoEntity.orderInList] sequence so the DAO's ORDER BY stays stable.
 */
@OptIn(ExperimentalPagingApi::class)
class MemosRemoteMediator(
    private val api: MemosApi,
    private val dao: MemoDao,
    /**
     * Which lifecycle state to page. memos' ListMemos returns only active
     * memos unless asked otherwise, so the archive view has to say so on the
     * wire — filtering the response client-side would page forever through
     * memos that are never archived.
     */
    private val archived: Boolean,
) : RemoteMediator<Int, MemoEntity>() {

    @Volatile
    private var nextPageToken: String? = null

    /**
     * memos v1 filter expression selecting the state this mediator pages.
     * Same CEL-ish grammar (and the same quoting) as `buildSearchFilter`.
     * [archived] is a constructor val, so this can't change per load.
     */
    private val stateFilter: String =
        "state == " + quote(if (archived) MemoState.ARCHIVED else MemoState.NORMAL)

    override suspend fun initialize(): InitializeAction =
        // Always refresh on attach: the cache may be stale across launches and
        // pull-to-refresh is the only other refresh trigger.
        InitializeAction.LAUNCH_INITIAL_REFRESH

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, MemoEntity>,
    ): MediatorResult {
        val pageToken: String? = when (loadType) {
            LoadType.REFRESH -> null
            LoadType.PREPEND ->
                // We only paginate forward — the top of the list is the
                // newest page, set by REFRESH.
                return MediatorResult.Success(endOfPaginationReached = true)
            LoadType.APPEND -> {
                val token = nextPageToken
                if (token.isNullOrBlank()) {
                    return MediatorResult.Success(endOfPaginationReached = true)
                }
                token
            }
        }

        return try {
            val response = api.listMemos(pageToken = pageToken, filter = stateFilter)
            val now = currentTimeMillis()

            // Both DAO entry points assign orderInList themselves, so the
            // value here is a placeholder either way.
            val entities = response.memos.map { it.toEntity(orderInList = 0, cachedAtEpochMs = now) }
            if (loadType == LoadType.REFRESH) {
                // A refresh of one state must not evict the other's cached
                // rows — the two views share the table.
                dao.replaceAll(
                    memos = entities,
                    tempPrefix = LocalMemoName.PREFIX,
                    archived = archived,
                )
            } else {
                dao.appendAll(entities)
            }

            nextPageToken = response.nextPageToken?.takeIf { it.isNotBlank() }
            MediatorResult.Success(endOfPaginationReached = nextPageToken == null)
        } catch (t: Throwable) {
            // Don't reset nextPageToken — a transient error shouldn't lose the
            // cursor; the next attempt can pick up where we left off.
            MediatorResult.Error(t)
        }
    }
}

private val AttachmentListSerializer = ListSerializer(AttachmentDto.serializer())

/** [MemoEntity.attachmentsJson] encoding: empty string for no attachments. */
internal fun encodeAttachments(attachments: List<AttachmentDto>): String =
    if (attachments.isEmpty()) "" else MemosJson.encodeToString(AttachmentListSerializer, attachments)

internal fun decodeAttachments(json: String): List<AttachmentDto> =
    if (json.isEmpty()) emptyList() else MemosJson.decodeFromString(AttachmentListSerializer, json)

internal fun MemoDto.toEntity(orderInList: Int, cachedAtEpochMs: Long): MemoEntity =
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
        attachmentsJson = encodeAttachments(attachments),
        orderInList = orderInList,
        cachedAtEpochMs = cachedAtEpochMs,
    )

internal fun MemoEntity.toDto(): MemoDto =
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
        attachments = decodeAttachments(attachmentsJson),
    )
