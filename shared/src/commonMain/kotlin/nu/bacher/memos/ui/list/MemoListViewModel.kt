package nu.bacher.memos.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nu.bacher.memos.data.api.MemoDto
import nu.bacher.memos.data.api.MemoState
import nu.bacher.memos.data.db.LocalMemoName
import nu.bacher.memos.data.db.MemoDao
import nu.bacher.memos.data.db.ReminderEntity
import nu.bacher.memos.data.repo.MemoRepository
import nu.bacher.memos.data.repo.ReminderRepository
import nu.bacher.memos.data.settings.LayoutPreferences
import nu.bacher.memos.data.settings.MemoLayout

/**
 * UI state for the list screen.
 *
 * Two paging modes:
 *  - Empty query → DAO + RemoteMediator path (offline-first, cached).
 *  - Non-empty query → direct-API search path (online-only, server filters).
 *
 * The two modes are mutually exclusive — when the user types, we swap the
 * underlying paging stream entirely; clearing the query swaps back. The tag
 * chip set is always derived from the cached memos so it stays meaningful
 * regardless of which mode is active (and the selected tag is folded into
 * the server filter when searching).
 *
 * Orthogonal to both is [State.showArchived], which flips the cached path
 * between active and archived memos. Search deliberately ignores it and keeps
 * spanning both — an explicit query is how you find an old archived note.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, FlowPreview::class)
class MemoListViewModel(
    private val memoRepo: MemoRepository,
    private val reminderRepo: ReminderRepository,
    private val layoutPreferences: LayoutPreferences,
    private val memoDao: MemoDao,
) : ViewModel() {

    data class Row(
        val memo: MemoDto,
        val reminder: ReminderEntity?,
        /** True when this memo has an unsynced create/update/delete queued. */
        val pendingSync: Boolean = false,
        /** True when the queued write was abandoned and won't be retried. */
        val syncFailed: Boolean = false,
    )
    data class State(
        val tags: List<String> = emptyList(),
        val query: String = "",
        val selectedTag: String? = null,
        val layout: MemoLayout = MemoLayout.GRID,
        /** Cached path shows archived memos instead of active ones. */
        val showArchived: Boolean = false,
        /** Search results came from the cache because the server was unreachable. */
        val searchOffline: Boolean = false,
    )

    private val query = MutableStateFlow("")
    private val selectedTag = MutableStateFlow<String?>(null)
    private val showArchived = MutableStateFlow(false)
    private val _selectedNames = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Names of memos currently selected via long-press; empty when not in
     * selection mode. Toggling adds/removes individual memos so the user can
     * pick a batch for bulk delete/archive. Kept separate from [state] so
     * toggling it doesn't churn the combine pipeline that feeds tag chips.
     */
    val selectedNames: StateFlow<Set<String>> = _selectedNames.asStateFlow()
    private val reminderMap: Flow<Map<String, ReminderEntity>> =
        reminderRepo.observeAll()
            .map { list -> list.associateBy { it.memoName } }
            .distinctUntilChanged()
    private val pendingNames: Flow<Set<String>> = memoRepo.pendingNames
    private val syncFailedNames: Flow<Set<String>> = memoRepo.syncFailedNames

    /**
     * Tag set is derived from the *cached* memos (whatever pages are in the
     * DAO right now). New tags appear as the user pages further; old ones
     * stay sticky until a refresh clears them. We accept this — a separate
     * cache-wide tag table would be the alternative.
     */
    private val cachedTags: Flow<List<String>> = memoDao.observeAll().map { entities ->
        entities.asSequence()
            .flatMap { entity ->
                if (entity.tagsCsv.isNotEmpty()) {
                    entity.tagsCsv.split(',').asSequence()
                } else {
                    extractTags(entity.content).asSequence()
                }
            }
            .distinct()
            .sortedBy { it.lowercase() }
            .toList()
    }
        // Room emits on its own dispatcher but the map runs in the collector's
        // context — without this the regex sweep over every cached memo's
        // content would run on the main thread, on every cache write.
        .flowOn(Dispatchers.Default)
        .distinctUntilChanged()

    /**
     * Debounced query for the paging stream. Empty queries propagate
     * immediately (clearing search shouldn't lag); non-empty queries wait
     * [SEARCH_DEBOUNCE_MS] so we don't fire a request on every keystroke.
     */
    private val debouncedQuery: Flow<String> = query
        .debounce { q -> if (q.isBlank()) 0L else SEARCH_DEBOUNCE_MS }
        .distinctUntilChanged()

    /**
     * The active search, or null while the query is empty. Shared so [memos]
     * and [State.searchOffline] observe the *same* stream — deriving them
     * separately would build two Pagers and issue every search twice.
     */
    private val searchStream: Flow<MemoRepository.SearchStream?> =
        combine(debouncedQuery, selectedTag) { q, tag ->
            if (q.isBlank()) null else memoRepo.searchMemos(q, tag)
        }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /** Whether the active search is being answered from the cache. */
    private val searchOffline: Flow<Boolean> =
        searchStream.flatMapLatest { it?.servedFromCache ?: flowOf(false) }

    val state: StateFlow<State> = combine(
        query,
        selectedTag,
        cachedTags,
        layoutPreferences.layoutFlow,
        showArchived,
    ) { q, tag, allTags, layout, archived ->
        State(
            tags = allTags,
            query = q,
            selectedTag = tag.takeIf { it == null || it in allTags },
            layout = layout,
            showArchived = archived,
        )
    }.combine(searchOffline) { s, offline ->
        s.copy(searchOffline = offline)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        State(layout = layoutPreferences.read()),
    )

    /**
     * Paged memos for the screen. Combines the (debounced) query, selected
     * tag, reminder map, and pending-sync set; rebuilds the paging stream
     * whenever the query mode flips. cachedIn lets the screen survive config
     * changes.
     */
    /**
     * What the paging stream is built from. Collapsed to a value so an input
     * the active mode ignores can't tear the stream down: while a search is
     * running the server filter already owns tag and state, so toggling either
     * must not cancel and re-issue the query.
     */
    private data class PageKey(
        val search: MemoRepository.SearchStream?,
        val archived: Boolean,
        val tag: String?,
    )

    private val pageKey: Flow<PageKey> =
        combine(searchStream, showArchived, selectedTag) { search, archived, tag ->
            if (search != null) PageKey(search, archived = false, tag = null)
            else PageKey(null, archived, tag)
        }.distinctUntilChanged()

    val memos: Flow<PagingData<Row>> =
        combine(
            pageKey,
            reminderMap,
            pendingNames,
            syncFailedNames,
        ) { key, reminders, pending, failed ->
            // The cached path is already scoped to one lifecycle state by the
            // DAO and the mediator; only [tag] is applied client-side, because
            // the server doesn't know about tags. Search spans both states on
            // purpose — an explicit query is how you find an archived note.
            val source: Flow<PagingData<MemoDto>> = key.search?.pages
                ?: memoRepo.memosPagingData(key.archived).map { paging ->
                    paging.filter { memo -> tagMatches(memo, key.tag) }
                }
            source.map { paging ->
                paging.map { memo ->
                    Row(
                        memo = memo,
                        reminder = reminders[memo.name],
                        pendingSync = memo.name in pending,
                        syncFailed = memo.name in failed,
                    )
                }
            }
        }.flatMapLatest { it }.cachedIn(viewModelScope)

    init {
        // Cold-start flush — if the previous session left actions queued,
        // try to push them now that we're online (or fail fast and stay
        // queued).
        viewModelScope.launch { trySyncPending() }
    }

    /**
     * Trigger a flush of the offline queue. Safe to call repeatedly — a
     * no-op when the queue is empty. Callers: MainActivity.onResume.
     */
    fun syncPending() {
        viewModelScope.launch { trySyncPending() }
    }

    private suspend fun trySyncPending() {
        try {
            memoRepo.syncPending()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline / transient — leave actions queued.
        }
    }

    fun setQuery(q: String) {
        query.value = q
    }

    fun setSelectedTag(tag: String?) {
        selectedTag.value = tag
    }

    /**
     * Switches the cached list between active and archived memos. Clears any
     * live selection — the available bulk actions differ between the two
     * views, so carrying a selection across would leave the top bar offering
     * "Archive" on already-archived memos.
     */
    fun setShowArchived(show: Boolean) {
        if (showArchived.value == show) return
        _selectedNames.value = emptySet()
        showArchived.value = show
    }

    fun setLayout(layout: MemoLayout) {
        layoutPreferences.setLayout(layout)
    }

    fun toggleSelection(name: String) {
        _selectedNames.update { current ->
            if (name in current) current - name else current + name
        }
    }

    fun clearSelection() {
        _selectedNames.value = emptySet()
    }

    /**
     * Delete every selected memo and exit selection mode. No-op when nothing
     * is selected. Errors are swallowed per-memo — the repo applies the
     * optimistic cache write either way and queues the network call for
     * later when offline.
     */
    fun deleteSelected() = onSelection(skipLocal = false) { memoRepo.delete(it) }

    /**
     * Applies [op] to each selected memo, then exits selection mode. Failures
     * are swallowed per-memo: the repo has already applied the optimistic
     * cache write and queued the network call for replay.
     *
     * [skipLocal] drops memos whose CREATE is still queued — the server has
     * never heard of them, so state and pin edits have nothing to act on.
     * Delete is the exception: it can drop the queued CREATE outright.
     */
    private fun onSelection(skipLocal: Boolean = true, op: suspend (String) -> Unit) =
        onSelection(skipLocal, prepare = { }, op = { name, _ -> op(name) })

    /**
     * As above, but [prepare] runs once over the whole batch first and its
     * result is handed to every [op] — for decisions that must be made before
     * any write lands (which way a bulk pin should go, say).
     */
    private fun <T> onSelection(
        skipLocal: Boolean = true,
        prepare: suspend (List<String>) -> T,
        op: suspend (String, T) -> Unit,
    ) {
        val selected = _selectedNames.value
        val names = if (skipLocal) selected.filterNot(LocalMemoName::isLocal) else selected.toList()
        _selectedNames.value = emptySet()
        if (names.isEmpty()) return
        viewModelScope.launch {
            val prepared = prepare(names)
            for (name in names) {
                try {
                    op(name, prepared)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Already applied locally; the sync queue will retry.
                }
            }
        }
    }

    /**
     * Archive every selected memo by flipping its state to ARCHIVED. Temp
     * memos (still queued for create) are skipped — there's nothing on the
     * server to archive yet, and the user would have to wait for the create
     * to flush before archiving meaningfully.
     */
    fun archiveSelected() = onSelection { memoRepo.setState(it, MemoState.ARCHIVED) }

    /** Restores every selected memo to the active list. */
    fun unarchiveSelected() = onSelection { memoRepo.setState(it, MemoState.NORMAL) }

    /**
     * Pins the selected memos, or unpins them when they're *all* already
     * pinned — one button that does the obvious thing either way. Pinned state
     * is read from the cache rather than tracked alongside the selection so
     * this stays correct after a refresh reorders things underneath.
     *
     * Temp memos are skipped: memos' create API carries no `pinned` field, so
     * there's nothing to pin until the queued CREATE lands.
     */
    fun togglePinSelected() = onSelection(
        // One COUNT decides the direction for the whole batch, rather than a
        // point lookup per memo.
        prepare = { names -> memoDao.unpinnedCount(names) > 0 },
        op = { name, pin -> memoRepo.setPinned(name, pin) },
    )

    private fun tagMatches(memo: MemoDto, tag: String?): Boolean {
        if (tag == null) return true
        val tags = if (memo.tags.isNotEmpty()) memo.tags else extractTags(memo.content)
        return tag in tags
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}

private val TAG_REGEX = Regex("""(?<![\w/])#([\p{L}\p{N}_\-/]+)""")

private fun extractTags(content: String): List<String> =
    TAG_REGEX.findAll(content).map { it.groupValues[1] }.distinct().toList()
