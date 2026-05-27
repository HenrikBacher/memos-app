package nu.bacher.memos.data.repo

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import kotlinx.serialization.builtins.ListSerializer
import nu.bacher.memos.data.api.AttachmentDto
import nu.bacher.memos.data.api.MemoDto
import nu.bacher.memos.data.api.MemosApi
import nu.bacher.memos.data.api.MemosJson
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
 * REFRESH clears the table inside [MemoDao.replaceAll]'s transaction so the
 * UI never sees an empty intermediate state. APPEND extends the existing
 * [MemoEntity.orderInList] sequence so the DAO's ORDER BY stays stable.
 */
@OptIn(ExperimentalPagingApi::class)
class MemosRemoteMediator(
    private val api: MemosApi,
    private val dao: MemoDao,
) : RemoteMediator<Int, MemoEntity>() {

    @Volatile
    private var nextPageToken: String? = null

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
            val response = api.listMemos(pageToken = pageToken)
            val now = currentTimeMillis()

            if (loadType == LoadType.REFRESH) {
                dao.replaceAll(
                    response.memos.mapIndexed { i, dto -> dto.toEntity(i, now) },
                )
            } else {
                dao.appendAll(response.memos.map { it.toEntity(orderInList = 0, cachedAtEpochMs = now) })
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

internal val AttachmentListSerializer = ListSerializer(AttachmentDto.serializer())

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
        attachmentsJson = if (attachments.isEmpty()) ""
        else MemosJson.encodeToString(AttachmentListSerializer, attachments),
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
        attachments = if (attachmentsJson.isEmpty()) emptyList()
        else MemosJson.decodeFromString(AttachmentListSerializer, attachmentsJson),
    )
