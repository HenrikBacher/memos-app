package nu.bacher.memos.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.map
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nu.bacher.memos.data.api.MemoDto
import nu.bacher.memos.data.auth.AuthStore
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
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, FlowPreview::class)
class MemoListViewModel(
    private val memoRepo: MemoRepository,
    private val reminderRepo: ReminderRepository,
    private val authStore: AuthStore,
    private val layoutPreferences: LayoutPreferences,
    private val memoDao: nu.bacher.memos.data.db.MemoDao,
) : ViewModel() {

    data class Row(
        val memo: MemoDto,
        val reminder: ReminderEntity?,
        /** True when this memo has an unsynced create/update/delete queued. */
        val pendingSync: Boolean = false,
    )
    data class State(
        val tags: List<String> = emptyList(),
        val query: String = "",
        val selectedTag: String? = null,
        val layout: MemoLayout = MemoLayout.GRID,
    )

    private val query = MutableStateFlow("")
    private val selectedTag = MutableStateFlow<String?>(null)
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
    }.distinctUntilChanged()

    val state: kotlinx.coroutines.flow.StateFlow<State> = combine(
        query,
        selectedTag,
        cachedTags,
        layoutPreferences.layoutFlow,
    ) { q, tag, allTags, layout ->
        State(
            tags = allTags,
            query = q,
            selectedTag = tag.takeIf { it == null || it in allTags },
            layout = layout,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        State(layout = layoutPreferences.read()),
    )

    /**
     * Debounced query for the paging stream. Empty queries propagate
     * immediately (clearing search shouldn't lag); non-empty queries wait
     * [SEARCH_DEBOUNCE_MS] so we don't fire a request on every keystroke.
     */
    private val debouncedQuery: Flow<String> = query
        .debounce { q -> if (q.isBlank()) 0L else SEARCH_DEBOUNCE_MS }
        .distinctUntilChanged()

    /**
     * Paged memos for the screen. Combines the (debounced) query, selected
     * tag, reminder map, and pending-sync set; rebuilds the paging stream
     * whenever the query mode flips. cachedIn lets the screen survive config
     * changes.
     */
    val memos: Flow<PagingData<Row>> =
        combine(debouncedQuery, selectedTag, reminderMap, pendingNames) { q, tag, reminders, pending ->
            Quad(q, tag, reminders, pending)
        }.flatMapLatest { (q, tag, reminders, pending) ->
            val source: Flow<PagingData<MemoDto>> = if (q.isBlank()) {
                // Cached path — server doesn't know about [tag], so apply it
                // client-side over loaded pages. Archived memos are filtered
                // here too so the main list stays focused on active memos
                // (search results still surface archived; that's intentional
                // — explicit search is the way to find old archived notes).
                memoRepo.memosPagingData.map { paging ->
                    paging.filter { memo -> memo.state != STATE_ARCHIVED && tagMatches(memo, tag) }
                }
            } else {
                // Server-side search. Tag is sent in the filter so the
                // server narrows results before paging.
                memoRepo.searchMemosPagingData(q, tag)
            }
            source.map { paging ->
                paging.map { memo ->
                    Row(memo, reminders[memo.name], pendingSync = memo.name in pending)
                }
            }
        }.cachedIn(viewModelScope)

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
    fun deleteSelected() {
        val names = _selectedNames.value
        if (names.isEmpty()) return
        _selectedNames.value = emptySet()
        viewModelScope.launch {
            for (name in names) {
                try {
                    memoRepo.delete(name)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Optimistic delete already applied; sync queue will retry.
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
    fun archiveSelected() {
        val names = _selectedNames.value.filterNot { it.startsWith(MemoRepository.TEMP_NAME_PREFIX) }
        if (names.isEmpty()) {
            _selectedNames.value = emptySet()
            return
        }
        _selectedNames.value = emptySet()
        viewModelScope.launch {
            for (name in names) {
                try {
                    memoRepo.setState(name, STATE_ARCHIVED)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Optimistic state change already applied; sync queue will retry.
                }
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            memoRepo.clearCache()
        }
        authStore.clear()
    }

    private fun tagMatches(memo: MemoDto, tag: String?): Boolean {
        if (tag == null) return true
        val tags = if (memo.tags.isNotEmpty()) memo.tags else extractTags(memo.content)
        return tag in tags
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
        const val STATE_ARCHIVED = "ARCHIVED"
    }

    /** Local 4-tuple — Kotlin has no built-in Quadruple. */
    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}

private val TAG_REGEX = Regex("""(?<![\w/])#([\p{L}\p{N}_\-/]+)""")

private fun extractTags(content: String): List<String> =
    TAG_REGEX.findAll(content).map { it.groupValues[1] }.distinct().toList()
