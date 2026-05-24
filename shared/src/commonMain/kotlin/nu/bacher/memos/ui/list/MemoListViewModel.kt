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

class MemoListViewModel(
    private val memoRepo: MemoRepository,
    reminderRepo: ReminderRepository,
    private val authStore: AuthStore,
) : ViewModel() {

    data class Row(val memo: MemoDto, val reminder: ReminderEntity?)
    data class State(
        val rows: List<Row> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
    )

    private val loading = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    val state = combine(
        memoRepo.memos,
        reminderRepo.observeAll(),
        loading,
        error,
    ) { memos, reminders, isLoading, err ->
        val map = reminders.associateBy { it.memoName }
        State(
            rows = memos.map { Row(it, map[it.name]) },
            loading = isLoading,
            error = err,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State(loading = true))

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            loading.value = true
            error.value = null
            memoRepo.refresh().onFailure { error.value = it.message }
            loading.value = false
        }
    }

    fun logout() {
        authStore.clear()
    }
}
