package nu.bacher.memos.ui.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nu.bacher.memos.data.db.ReminderEntity
import nu.bacher.memos.data.repo.MemoRepository
import nu.bacher.memos.data.repo.ReminderRepository
import nu.bacher.memos.util.currentTimeMillis

class MemoEditViewModel(
    private val memoRepo: MemoRepository,
    private val reminderRepo: ReminderRepository,
) : ViewModel() {

    data class State(
        val memoName: String? = null,
        val content: String = "",
        val reminder: ReminderEntity? = null,
        val loading: Boolean = true,
        val saving: Boolean = false,
        val error: String? = null,
        val finished: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    fun load(memoName: String?, initialContent: String? = null) {
        viewModelScope.launch {
            if (memoName == null) {
                _state.value = State(loading = false, content = initialContent.orEmpty())
                return@launch
            }
            _state.update { it.copy(loading = true, memoName = memoName) }
            runCatching {
                val memo = memoRepo.get(memoName)
                val rem = reminderRepo.get(memoName)
                _state.value = State(
                    memoName = memo.name,
                    content = memo.content,
                    reminder = rem,
                    loading = false,
                )
            }.onFailure { t ->
                _state.update { it.copy(loading = false, error = t.message) }
            }
        }
    }

    fun setContent(text: String) = _state.update { it.copy(content = text) }

    fun save() {
        val s = _state.value
        if (s.content.isBlank() && s.memoName == null) {
            _state.update { it.copy(finished = true) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            runCatching {
                val saved = if (s.memoName == null) {
                    memoRepo.create(s.content)
                } else {
                    memoRepo.update(s.memoName, s.content)
                }
                // Reminder set against a placeholder "new" memo — re-key it
                // under the real memo name now that we have one.
                if (s.memoName == null && s.reminder != null) {
                    reminderRepo.setTimeReminder(saved.name, s.reminder.triggerAtEpochMs)
                }
                _state.update { it.copy(saving = false, finished = true) }
            }.onFailure { t ->
                _state.update { it.copy(saving = false, error = t.message) }
            }
        }
    }

    fun delete() {
        val name = _state.value.memoName ?: run {
            _state.update { it.copy(finished = true) }; return
        }
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            runCatching {
                reminderRepo.clear(name)
                memoRepo.delete(name)
                _state.update { it.copy(saving = false, finished = true) }
            }.onFailure { t ->
                _state.update { it.copy(saving = false, error = t.message) }
            }
        }
    }

    fun setTimeReminder(epochMs: Long) {
        viewModelScope.launch {
            val name = _state.value.memoName
            if (name != null) {
                reminderRepo.setTimeReminder(name, epochMs)
                _state.update { it.copy(reminder = reminderRepo.get(name)) }
            } else {
                // No memo id yet — hold the reminder in state until save().
                _state.update {
                    it.copy(
                        reminder = ReminderEntity(
                            memoName = "",
                            triggerAtEpochMs = epochMs,
                            createdAtEpochMs = currentTimeMillis(),
                        ),
                    )
                }
            }
        }
    }

    fun clearReminder() {
        viewModelScope.launch {
            val name = _state.value.memoName
            if (name != null) reminderRepo.clear(name)
            _state.update { it.copy(reminder = null) }
        }
    }
}
