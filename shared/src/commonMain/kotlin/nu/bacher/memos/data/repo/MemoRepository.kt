package nu.bacher.memos.data.repo

import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map as mapFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import nu.bacher.memos.data.api.AttachmentDto
import nu.bacher.memos.data.api.AttachmentRef
import nu.bacher.memos.data.api.AttachmentSource
import nu.bacher.memos.data.api.CreateMemoRequest
import nu.bacher.memos.data.api.MemoDto
import nu.bacher.memos.data.api.MemosApi
import nu.bacher.memos.data.api.MemosJson
import nu.bacher.memos.data.api.UpdateMemoRequest
import nu.bacher.memos.data.api.memoUid
import nu.bacher.memos.data.db.LocalMemoName
import nu.bacher.memos.data.db.MemoDao
import nu.bacher.memos.data.db.PendingActionDao
import nu.bacher.memos.data.db.PendingActionEntity
import nu.bacher.memos.util.currentTimeMillis

/**
 * Offline-first memos store. The Room cache is the UI's source of truth so the
 * list renders instantly on cold start; pages are fetched by
 * [MemosRemoteMediator] (driven by [memosPagingData]), and create/update/delete
 * write to the cache first, then push to the server.
 *
 * When the API call comes back with a retriable failure (network outage, 5xx),
 * the optimistic write *stays* in the cache and the action is queued in
 * `pending_actions` for later replay by [syncPending]. Non-retriable failures
 * (4xx) still roll back and rethrow — the server is telling us the change can
 * never succeed, so the user needs to know.
 *
 * Server-side ordering is preserved via `orderInList` — the API's sort rules
 * (pinned, displayTime, etc.) shift with memos versions, so we don't try to
 * recreate them locally.
 */
@OptIn(ExperimentalPagingApi::class)
class MemoRepository(
    private val api: MemosApi,
    private val dao: MemoDao,
    private val pendingActionDao: PendingActionDao,
    private val verifyClientFactory: (serverUrl: String, token: String) -> HttpClient,
) {

    /**
     * Stream of memo names that have an unsynced action pending. The list
     * screen uses this to paint a "Sync pending" badge on the matching cards.
     */
    val pendingNames: Flow<Set<String>>
        get() = pendingActionDao.observePendingNames().mapFlow { it.toSet() }

    /**
     * Memos whose queued write was abandoned and will not be retried. The list
     * badges these so an unsyncable memo doesn't read as saved.
     */
    val syncFailedNames: Flow<Set<String>>
        get() = dao.observeSyncFailedNames().mapFlow { it.toSet() }

    // Match initialLoadSize to one server page so the first network call
    // delivers a full screen without the mediator immediately issuing an APPEND.
    private val pagingConfig = PagingConfig(
        pageSize = SERVER_PAGE_SIZE,
        initialLoadSize = SERVER_PAGE_SIZE,
        prefetchDistance = SERVER_PAGE_SIZE / 2,
        enablePlaceholders = false,
    )

    /**
     * Stream of memos paged from the local cache and refilled by the
     * RemoteMediator on demand. Construct one Pager per call so each consumer
     * gets its own paging state; the DAO/PagingSource is shared.
     *
     * [archived] selects the lifecycle state end to end — the DAO query, the
     * server filter, and which rows a refresh may evict — so both views page
     * properly instead of one filtering the other's results away.
     */
    fun memosPagingData(archived: Boolean = false): Flow<PagingData<MemoDto>> =
        Pager(
            config = pagingConfig,
            remoteMediator = MemosRemoteMediator(api = api, dao = dao, archived = archived),
            pagingSourceFactory = { dao.pagingSource(archived) },
        ).flow.mapFlow { it.map { entity -> entity.toDto() } }

    /**
     * Server-side search. Bypasses Room — search results are not cached
     * because we have no good way to invalidate them as the query changes.
     * Returns paged memos that match [query] (and optionally [tag], as a
     * conjunctive filter against the server).
     *
     * The filter is built with the v1 CEL-ish syntax memos uses:
     *   `content_search == ["query"] && tag in ["tag"]`
     * Older self-hosted servers without `content_search` will throw on the
     * filter — the paging stream surfaces that as an error and the user
     * recovers by clearing the query.
     *
     * When the server can't be reached at all, the first page falls back to a
     * LIKE scan of the cache instead of erroring out, so search still works on
     * a plane. Cached results are necessarily partial (the cache only holds
     * pages already loaded), so the returned [SearchStream] carries a flag the
     * UI can use to say so — scoped to this one stream rather than parked on
     * the repository, so it can't go stale or be read by the wrong consumer.
     */
    fun searchMemos(query: String, tag: String? = null): SearchStream {
        val filter = buildSearchFilter(query, tag)
        val escapedQuery = escapeLike(query.trim())
        val escapedTag = tag?.takeIf { it.isNotBlank() }?.let(::escapeLike)
        val servedFromCache = MutableStateFlow(false)
        val pages = Pager(
            config = pagingConfig,
            pagingSourceFactory = {
                MemoApiPagingSource(
                    api = api,
                    filter = filter,
                    offlineFallback = {
                        dao.searchCached(
                            query = escapedQuery,
                            tag = escapedTag,
                            limit = CACHED_SEARCH_LIMIT,
                        ).map { it.toDto() }
                    },
                    onServedFromCache = { servedFromCache.value = it },
                )
            },
        ).flow
        return SearchStream(pages = pages, servedFromCache = servedFromCache.asStateFlow())
    }

    /**
     * A search's page stream plus whether it is currently being answered from
     * the cache. The flag's lifetime matches the stream's, so a new search
     * starts clean without anyone having to reset it.
     */
    data class SearchStream(
        val pages: Flow<PagingData<MemoDto>>,
        val servedFromCache: StateFlow<Boolean>,
    )

    suspend fun get(name: String): MemoDto {
        val memo = api.getMemo(name.memoUid())
        val cached = dao.get(memo.name)
        dao.upsert(memo.toEntity(cached?.orderInList ?: Int.MAX_VALUE, currentTimeMillis()))
        return memo
    }

    /**
     * Optimistic create. The new memo lands at the top of the local cache
     * with a client-side temp id; the API call follows, and on success the
     * temp row is replaced with the server-issued row.
     *
     * On a retriable failure (network outage, 5xx) the temp row stays put
     * and the action is enqueued; the temp DTO is returned so the caller's
     * "save succeeded" UX still fires. On a non-retriable failure (4xx) the
     * temp row is removed and the exception propagates.
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
        val tempName = "${LocalMemoName.PREFIX}${currentTimeMillis()}"
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
            // Swap temp row for the server row, keeping order=0. Run under
            // NonCancellable so a cancellation between API success and DAO
            // write can't leave the cache with the temp row + no server row.
            withContext(NonCancellable) {
                dao.delete(tempName)
                dao.insertAtTop(saved.toEntity(0, currentTimeMillis()))
            }
            saved
        } catch (t: Throwable) {
            if (t.isRetriable()) {
                // Keep the temp row; queue the create for replay. We hand
                // back the temp DTO so the caller sees a "successful" save.
                withContext(NonCancellable) {
                    enqueuePendingCreate(tempName, content, visibility, attachments)
                }
                tempDto
            } else {
                // NonCancellable so the rollback still runs if the API call
                // was cancelled mid-flight — otherwise the temp row leaks.
                withContext(NonCancellable) { dao.delete(tempName) }
                throw t
            }
        }
    }

    /**
     * Optimistic update. Cache is updated immediately; on API failure the
     * prior entity is restored (non-retriable) or the action is queued for
     * replay (retriable).
     *
     * If [name] is a temp name (the original CREATE is still pending), the
     * edit is folded into the queued CREATE instead of issuing a hopeless
     * PATCH against a server resource that doesn't exist yet.
     *
     * Read-modify-write is racy in principle (a concurrent refresh could
     * overwrite the cache in between), but the UI doesn't drive concurrent
     * edits to the same memo, so we accept that.
     */
    suspend fun update(
        name: String,
        content: String? = null,
        visibility: String? = null,
        state: String? = null,
        pinned: Boolean? = null,
        attachments: List<AttachmentDto>? = null,
    ): MemoDto {
        if (LocalMemoName.isLocal(name)) {
            return mergeIntoPendingCreate(name, content, visibility, state, pinned, attachments)
        }
        val prior = dao.get(name)
            ?: error("update($name): no cached entity to update")
        val now = currentTimeMillis()
        val optimistic = prior.copy(
            content = content ?: prior.content,
            visibility = visibility ?: prior.visibility,
            state = state ?: prior.state,
            pinned = pinned ?: prior.pinned,
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
                    state = state,
                    pinned = pinned,
                    attachments = attachments?.map { AttachmentRef(name = it.name) },
                ),
            )
            withContext(NonCancellable) {
                dao.upsert(saved.toEntity(prior.orderInList, currentTimeMillis()))
            }
            saved
        } catch (t: Throwable) {
            if (t.isRetriable()) {
                withContext(NonCancellable) {
                    enqueuePendingUpdate(name, content, visibility, state, pinned, attachments)
                }
                optimistic.toDto()
            } else {
                withContext(NonCancellable) { dao.upsert(prior) }
                throw t
            }
        }
    }

    /**
     * Convenience wrapper for state-only edits. Archiving a temp-named memo
     * isn't supported (the memo doesn't exist on the server yet) — callers
     * should filter those out at the UI layer.
     */
    suspend fun setState(name: String, state: String): MemoDto = update(name = name, state = state)

    /**
     * Convenience wrapper for pin-only edits. Like [setState], pinning a
     * temp-named memo only sticks locally until the queued CREATE lands —
     * memos' CreateMemoRequest carries no `pinned` field.
     */
    suspend fun setPinned(name: String, pinned: Boolean): MemoDto =
        update(name = name, pinned = pinned)

    /**
     * Uploads [source] as an attachment. When [memoName] is non-null the server
     * links the attachment to that memo immediately; for new memos pass null
     * here and reference the returned attachment by name in the next
     * [create] call.
     *
     * The file is streamed through a base64-encoding JSON envelope and never
     * materialised (see [nu.bacher.memos.data.api.StreamingAttachmentContent]).
     * Taking an [AttachmentSource] rather than a ByteArray is what makes that
     * true end-to-end: a 20 MB upload now peaks at the ~85 KiB chunk buffer
     * instead of the file itself.
     */
    suspend fun uploadAttachment(
        source: AttachmentSource,
        memoName: String? = null,
    ): AttachmentDto = try {
        api.createAttachment(source = source, memo = memoName)
    } catch (t: Throwable) {
        // Unlike memo writes, there is no queue to fall back on: the bytes sit
        // behind a URI we hold no durable claim to. Name that outcome here,
        // where the policy lives, so callers map a type instead of each
        // re-deriving it from a generic network error.
        if (t.isRetriable()) throw AttachmentUploadUnavailable(t) else throw t
    }

    /**
     * Optimistic delete. Cache row goes first; on API failure the row is
     * restored (non-retriable) or the action is queued for replay (retriable).
     *
     * If [name] is a temp name (CREATE still pending), we just drop the
     * pending CREATE and the cache row — no API call needed, the memo never
     * reached the server.
     */
    suspend fun delete(name: String) {
        if (LocalMemoName.isLocal(name)) {
            withContext(NonCancellable) {
                pendingActionDao.deleteByMemoName(name)
                dao.delete(name)
            }
            return
        }
        val prior = dao.get(name) ?: run {
            api.deleteMemo(name.memoUid())
            return
        }
        dao.delete(name)
        try {
            api.deleteMemo(name.memoUid())
        } catch (t: Throwable) {
            if (t.isRetriable()) {
                withContext(NonCancellable) { enqueuePendingDelete(name) }
            } else {
                withContext(NonCancellable) { dao.upsert(prior) }
                throw t
            }
        }
    }

    /**
     * Flush queued create/update/delete actions in FIFO order. On retriable
     * failure for a single action we stop processing (so we don't hammer an
     * offline server) and leave the rest of the queue intact for the next
     * sync. On non-retriable failure we drop the action (server has
     * permanently rejected it) — the optimistic cache write stays as the
     * user's record of what they intended, and the next refresh will
     * reconcile against the server.
     *
     * A 5xx that recurs across [MAX_SYNC_ATTEMPTS] sync rounds is treated as
     * poison and dropped too — otherwise one payload the server always chokes
     * on blocks every action queued behind it forever. Network failures don't
     * count toward the cap: an unreachable server says nothing about the
     * action, and offline stretches would otherwise eat the budget.
     *
     * Safe to call concurrently with user-driven create/update/delete:
     * actions are loaded once at the top, so a new enqueue mid-flush just
     * gets picked up by the next sync.
     */
    suspend fun syncPending() {
        val queued = pendingActionDao.getAll()
        // Re-read only if the sweep actually enqueued something; the common
        // case costs one query, not two.
        val actions = if (reconcileOrphanTempCreates(queued)) pendingActionDao.getAll() else queued
        if (actions.isEmpty()) return

        for (action in actions) {
            val type = PendingActionType.fromStored(action.type) ?: run {
                // Unknown type — corrupt row, drop it (and its temp row, which
                // would otherwise be re-adopted by the orphan sweep).
                dropAction(action)
                continue
            }
            val outcome = runCatching { applyPending(action, type) }
            if (outcome.isSuccess) {
                pendingActionDao.deleteById(action.id)
                continue
            }
            val error = outcome.exceptionOrNull()!!
            if (error is CancellationException) throw error
            if (!error.isRetriable()) {
                // Server says no — drop and move on.
                dropAction(action)
                continue
            }
            // Transient. Only server-reached failures (5xx) count toward the
            // poison cap; see burnsSyncBudget.
            val attempts =
                if (error.burnsSyncBudget()) action.attempts + 1 else action.attempts
            if (attempts >= MAX_SYNC_ATTEMPTS) {
                // Poison — drop it and let the rest of the queue through.
                dropAction(action)
                continue
            }
            pendingActionDao.update(
                action.copy(
                    attempts = attempts,
                    lastAttemptEpochMs = currentTimeMillis(),
                    lastError = error.message,
                ),
            )
            break
        }
    }

    /**
     * Re-queues temp-named cache rows that have no matching pending action.
     * [create] inserts the optimistic temp row before the API call and only
     * enqueues in its failure handler — if the process dies in between (most
     * plausibly the share flow's invisible activity being killed mid-request),
     * the temp row survives in Room looking like a synced memo but will never
     * reach the server.
     *
     * Only rows older than [ORPHAN_MIN_AGE_MS] are swept so we can't race an
     * in-flight [create] (temp row inserted, API call still running, nothing
     * enqueued yet) into a duplicate CREATE.
     */
    private suspend fun reconcileOrphanTempCreates(queued: List<PendingActionEntity>): Boolean {
        val queuedNames = queued.mapTo(mutableSetOf()) { it.memoName }
        val cutoff = currentTimeMillis() - ORPHAN_MIN_AGE_MS
        val orphans = dao.tempRowsOlderThan(LocalMemoName.PREFIX, cutoff)
            .filter { it.name !in queuedNames }
        for (row in orphans) {
            val attachments = if (row.attachmentsJson.isEmpty()) emptyList()
            else MemosJson.decodeFromString(AttachmentListSerializer, row.attachmentsJson)
            enqueuePendingCreate(row.name, row.content, row.visibility, attachments)
        }
        return orphans.isNotEmpty()
    }

    /**
     * Abandons a queued action for good.
     *
     * A temp-named CREATE leaves its cache row in place — it is the user's
     * only copy of what they wrote — but flags it [MemoEntity.syncFailed].
     * Without the flag [reconcileOrphanTempCreates] would adopt the row (a
     * temp row with no queued action) and re-enqueue the very CREATE we just
     * abandoned, on every sync, forever. Deleting the row instead would break
     * the loop by throwing the memo away, which is not ours to do.
     *
     * (Before temp rows survived [MemoDao.replaceAll], the next refresh wiped
     * the row and hid both problems.)
     *
     * Other action types leave the cache alone: an UPDATE or DELETE the server
     * rejected still has a real server-backed row behind it, and the next
     * refresh reconciles it.
     */
    private suspend fun dropAction(action: PendingActionEntity) {
        pendingActionDao.deleteById(action.id)
        if (LocalMemoName.isLocal(action.memoName)) {
            dao.setSyncFailed(action.memoName, true)
        }
    }

    private suspend fun applyPending(action: PendingActionEntity, type: PendingActionType) {
        when (type) {
            PendingActionType.CREATE -> {
                val payload = MemosJson.decodeFromString(
                    PendingPayload.Create.serializer(),
                    action.payloadJson,
                )
                val saved = api.createMemo(
                    CreateMemoRequest(
                        content = payload.content,
                        visibility = payload.visibility,
                        attachments = payload.attachmentNames.takeIf { it.isNotEmpty() }
                            ?.map { AttachmentRef(name = it) },
                    ),
                )
                withContext(NonCancellable) {
                    dao.delete(action.memoName)
                    dao.insertAtTop(saved.toEntity(0, currentTimeMillis()))
                }
            }
            PendingActionType.UPDATE -> {
                val payload = MemosJson.decodeFromString(
                    PendingPayload.Update.serializer(),
                    action.payloadJson,
                )
                val saved = api.updateMemo(
                    action.memoName.memoUid(),
                    UpdateMemoRequest(
                        content = payload.content,
                        visibility = payload.visibility,
                        state = payload.state,
                        pinned = payload.pinned,
                        attachments = payload.attachmentNames?.map { AttachmentRef(name = it) },
                    ),
                )
                withContext(NonCancellable) {
                    val prior = dao.get(action.memoName)
                    dao.upsert(saved.toEntity(prior?.orderInList ?: Int.MAX_VALUE, currentTimeMillis()))
                }
            }
            PendingActionType.DELETE -> {
                api.deleteMemo(action.memoName.memoUid())
            }
        }
    }

    private suspend fun enqueuePendingCreate(
        tempName: String,
        content: String,
        visibility: String,
        attachments: List<AttachmentDto>,
    ) {
        val payload = PendingPayload.Create(
            content = content,
            visibility = visibility,
            attachmentNames = attachments.map { it.name },
        )
        pendingActionDao.insert(
            PendingActionEntity(
                type = PendingActionType.CREATE.storedValue,
                memoName = tempName,
                payloadJson = MemosJson.encodeToString(
                    PendingPayload.Create.serializer(),
                    payload,
                ),
                createdAtEpochMs = currentTimeMillis(),
            ),
        )
    }

    private suspend fun enqueuePendingUpdate(
        name: String,
        content: String?,
        visibility: String?,
        state: String?,
        pinned: Boolean?,
        attachments: List<AttachmentDto>?,
    ) {
        // Collapse repeated UPDATEs on the same memo: merge the prior queued
        // payload with the new one so partial edits don't get lost (e.g. a
        // queued content-only edit followed by a state-only archive should
        // replay as a single UPDATE that carries both).
        val prior = pendingActionDao.findFirst(PendingActionType.UPDATE.storedValue, name)
            ?.let { row ->
                MemosJson.decodeFromString(PendingPayload.Update.serializer(), row.payloadJson)
                    .also { pendingActionDao.deleteById(row.id) }
            }
        val payload = PendingPayload.Update(
            content = content ?: prior?.content,
            visibility = visibility ?: prior?.visibility,
            state = state ?: prior?.state,
            pinned = pinned ?: prior?.pinned,
            attachmentNames = attachments?.map { it.name } ?: prior?.attachmentNames,
        )
        pendingActionDao.insert(
            PendingActionEntity(
                type = PendingActionType.UPDATE.storedValue,
                memoName = name,
                payloadJson = MemosJson.encodeToString(
                    PendingPayload.Update.serializer(),
                    payload,
                ),
                createdAtEpochMs = currentTimeMillis(),
            ),
        )
    }

    private suspend fun enqueuePendingDelete(name: String) {
        // A queued UPDATE would replay after the DELETE and 404 — drop any
        // prior pending action for this memo so the DELETE stands alone.
        // (A CREATE here would mean a temp-named memo, which delete() already
        // short-circuits before reaching this path, but the broad sweep is
        // still cheap insurance against future paths that don't.)
        pendingActionDao.deleteByMemoName(name)
        pendingActionDao.insert(
            PendingActionEntity(
                type = PendingActionType.DELETE.storedValue,
                memoName = name,
                payloadJson = MemosJson.encodeToString(String.serializer(), name),
                createdAtEpochMs = currentTimeMillis(),
            ),
        )
    }

    /**
     * Folds an [update] call on a temp-named memo into the queued CREATE.
     * Without this, editing an unsynced memo would issue a PATCH against a
     * resource the server has never heard of and immediately 404.
     */
    private suspend fun mergeIntoPendingCreate(
        tempName: String,
        content: String?,
        visibility: String?,
        state: String?,
        pinned: Boolean?,
        attachments: List<AttachmentDto>?,
    ): MemoDto {
        val prior = dao.get(tempName)
            ?: error("update($tempName): no cached temp entity to merge into")
        val newContent = content ?: prior.content
        val newVisibility = visibility ?: prior.visibility
        val newState = state ?: prior.state
        val priorAttachments = if (prior.attachmentsJson.isEmpty()) emptyList()
        else MemosJson.decodeFromString(AttachmentListSerializer, prior.attachmentsJson)
        val newAttachments = attachments ?: priorAttachments

        val optimistic = prior.copy(
            content = newContent,
            visibility = newVisibility,
            state = newState,
            pinned = pinned ?: prior.pinned,
            attachmentsJson = encodeAttachments(newAttachments),
            cachedAtEpochMs = currentTimeMillis(),
            // Re-queuing below makes this live again; editing an abandoned
            // memo is the user's way to retry it.
            syncFailed = false,
        )
        withContext(NonCancellable) {
            dao.upsert(optimistic)
            // Replace the prior pending CREATE payload so the queued action
            // carries the latest content/visibility/attachments. State and
            // pinned are intentionally not propagated to the CREATE payload —
            // memos' CreateMemoRequest has neither field, so archiving or
            // pinning an unsynced memo only sticks locally until the create
            // lands.
            pendingActionDao.findFirst(PendingActionType.CREATE.storedValue, tempName)?.let {
                pendingActionDao.deleteById(it.id)
            }
            enqueuePendingCreate(tempName, newContent, newVisibility, newAttachments)
        }
        return optimistic.toDto()
    }

    /** Wipe the local cache. Called on logout — also drops queued actions
     * since they're tied to the credentials we're throwing away. */
    suspend fun clearCache() {
        pendingActionDao.clear()
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

    companion object {
        private const val SERVER_PAGE_SIZE = 50
        /** Server-failure replay rounds before a queued action is dropped as poison. */
        internal const val MAX_SYNC_ATTEMPTS = 5
        /** Minimum temp-row age before the orphan sweep may re-queue it. */
        internal const val ORPHAN_MIN_AGE_MS = 5 * 60_000L
        /** Cap on rows returned by the offline search fallback. */
        internal const val CACHED_SEARCH_LIMIT = 200
    }
}

/**
 * Thrown when an attachment upload fails for a reason that *would* have been
 * queued for replay had it been a memo write. There is no attachment queue, so
 * the caller must tell the user the upload simply didn't happen.
 */
class AttachmentUploadUnavailable(cause: Throwable) : Exception(cause)

/**
 * Escapes the LIKE metacharacters in a user-typed search term so `50%` matches
 * a literal "50%" rather than "50" followed by anything. Pairs with the
 * `ESCAPE '\'` clause on [MemoDao.searchCached].
 */
internal fun escapeLike(raw: String): String =
    raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

private fun encodeAttachments(attachments: List<AttachmentDto>): String =
    if (attachments.isEmpty()) ""
    else MemosJson.encodeToString(AttachmentListSerializer, attachments)

/**
 * Builds the memos v1 `filter` query expression for a search. Returns null
 * when neither [query] nor [tag] is set — caller should just not send the
 * parameter.
 *
 * The format is CEL-ish: terms are joined with `&&`. Strings are double-quoted
 * with `\` and `"` escaped so a query like `say "hi"` becomes
 * `content_search == ["say \"hi\""]` on the wire.
 */
internal fun buildSearchFilter(query: String, tag: String?): String? {
    val parts = mutableListOf<String>()
    if (query.isNotBlank()) parts += "content_search == [${quote(query.trim())}]"
    if (!tag.isNullOrBlank()) parts += "tag in [${quote(tag)}]"
    return if (parts.isEmpty()) null else parts.joinToString(" && ")
}

private fun quote(s: String): String {
    val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"$escaped\""
}
