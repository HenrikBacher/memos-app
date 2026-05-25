package nu.bacher.memos.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
 * The memo list flows through Paging3 ([memos]) — the in-memory list state
 * that used to live in [State] is gone. [State] now only carries the UI's
 * derived data: the layout, the search query, the tag chip set + selection,
 * and the per-memo reminder lookup map. Filtering happens by composing the
 * query + selectedTag with the paging flow and applying [filter] on the
 * resulting [PagingData] — so it only matches against pages already loaded.
 * This is the documented trade-off of the loaded-pages-only search model.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MemoListViewModel(
    private val memoRepo: MemoRepository,
    private val reminderRepo: ReminderRepository,
    private val authStore: AuthStore,
    private val layoutPreferences: LayoutPreferences,
    private val memoDao: nu.bacher.memos.data.db.MemoDao,
) : ViewModel() {

    data class Row(val memo: MemoDto, val reminder: ReminderEntity?)
    data class State(
        val tags: List<String> = emptyList(),
        val query: String = "",
        val selectedTag: String? = null,
        val layout: MemoLayout = MemoLayout.GRID,
    )

    private val query = MutableStateFlow("")
    private val selectedTag = MutableStateFlow<String?>(null)
    private val reminderMap: Flow<Map<String, ReminderEntity>> =
        reminderRepo.observeAll().map { list -> list.associateBy { it.memoName } }

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
     * Paged memos for the screen. Combines the raw paging stream with the
     * query, selected tag, and reminder map so each emission carries the
     * up-to-date filtering + reminder badges. cachedIn allows the screen to
     * survive config changes without re-fetching.
     */
    val memos: Flow<PagingData<Row>> =
        combine(query, selectedTag, reminderMap) { q, tag, reminders ->
            Triple(q, tag, reminders)
        }.flatMapLatest { (q, tag, reminders) ->
            memoRepo.memosPagingData.map { paging ->
                paging
                    .filter { memo -> matchesFilters(memo, q, tag) }
                    .map { memo -> Row(memo, reminders[memo.name]) }
            }
        }.cachedIn(viewModelScope)

    fun setQuery(q: String) {
        query.value = q
    }

    fun setSelectedTag(tag: String?) {
        selectedTag.value = tag
    }

    fun setLayout(layout: MemoLayout) {
        layoutPreferences.setLayout(layout)
    }

    fun logout() {
        viewModelScope.launch {
            memoRepo.clearCache()
        }
        authStore.clear()
    }

    private fun matchesFilters(memo: MemoDto, q: String, tag: String?): Boolean {
        if (tag != null) {
            val tags = if (memo.tags.isNotEmpty()) memo.tags else extractTags(memo.content)
            if (tag !in tags) return false
        }
        if (q.isNotBlank() && !memo.content.contains(q, ignoreCase = true)) return false
        return true
    }
}

private val TAG_REGEX = Regex("""(?<![\w/])#([\p{L}\p{N}_\-/]+)""")

private fun extractTags(content: String): List<String> =
    TAG_REGEX.findAll(content).map { it.groupValues[1] }.distinct().toList()
