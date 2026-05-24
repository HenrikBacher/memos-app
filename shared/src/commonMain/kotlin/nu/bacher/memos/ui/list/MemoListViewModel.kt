package nu.bacher.memos.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nu.bacher.memos.data.api.MemoDto
import nu.bacher.memos.data.auth.AuthStore
import nu.bacher.memos.data.db.ReminderEntity
import nu.bacher.memos.data.repo.MemoRepository
import nu.bacher.memos.data.repo.ReminderRepository
import nu.bacher.memos.data.settings.LayoutPreferences
import nu.bacher.memos.data.settings.MemoLayout

class MemoListViewModel(
    private val memoRepo: MemoRepository,
    reminderRepo: ReminderRepository,
    private val authStore: AuthStore,
    private val layoutPreferences: LayoutPreferences,
) : ViewModel() {

    data class Row(val memo: MemoDto, val reminder: ReminderEntity?)
    data class State(
        val rows: List<Row> = emptyList(),
        val tags: List<String> = emptyList(),
        val query: String = "",
        val selectedTag: String? = null,
        val layout: MemoLayout = MemoLayout.GRID,
        val loading: Boolean = false,
        val error: String? = null,
    )

    private val loading = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val query = MutableStateFlow("")
    private val selectedTag = MutableStateFlow<String?>(null)

    val state = combine(
        combine(memoRepo.memos, reminderRepo.observeAll()) { memos, reminders ->
            memos to reminders
        },
        combine(query, selectedTag) { q, t -> q to t },
        combine(loading, error) { l, e -> l to e },
        layoutPreferences.layoutFlow,
    ) { memosAndReminders, queryAndTag, loadingAndError, layout ->
        val (memos, reminders) = memosAndReminders
        val (q, tag) = queryAndTag
        val (isLoading, err) = loadingAndError

        val tagsForMemo: (MemoDto) -> List<String> = { memo ->
            if (memo.tags.isNotEmpty()) memo.tags else extractTags(memo.content)
        }

        val allTags = memos.flatMap(tagsForMemo).distinct().sorted()
        val reminderMap = reminders.associateBy { it.memoName }

        val filtered = memos
            .asSequence()
            .filter { memo ->
                tag == null || tagsForMemo(memo).contains(tag)
            }
            .filter { memo ->
                q.isBlank() || memo.content.contains(q, ignoreCase = true)
            }
            .map { Row(it, reminderMap[it.name]) }
            .toList()

        State(
            rows = filtered,
            tags = allTags,
            query = q,
            selectedTag = tag.takeIf { it == null || it in allTags },
            layout = layout,
            loading = isLoading,
            error = err,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        State(loading = true, layout = layoutPreferences.read()),
    )

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            loading.value = true
            error.value = null
            memoRepo.refresh().onFailure { error.value = it.message }
            loading.value = false
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

    fun logout() {
        viewModelScope.launch {
            memoRepo.clearCache()
        }
        authStore.clear()
    }
}

private val TAG_REGEX = Regex("""(?<![\w/])#([\p{L}\p{N}_\-/]+)""")

private fun extractTags(content: String): List<String> =
    TAG_REGEX.findAll(content).map { it.groupValues[1] }.distinct().toList()
