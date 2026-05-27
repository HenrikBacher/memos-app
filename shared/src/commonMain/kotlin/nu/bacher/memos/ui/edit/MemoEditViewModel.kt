package nu.bacher.memos.ui.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nu.bacher.memos.data.api.AttachmentDto
import nu.bacher.memos.data.api.memoUid
import nu.bacher.memos.data.auth.AuthStore
import nu.bacher.memos.data.db.ReminderEntity
import nu.bacher.memos.data.repo.MemoRepository
import nu.bacher.memos.data.repo.ReminderRepository
import nu.bacher.memos.util.currentTimeMillis

class MemoEditViewModel(
    private val memoRepo: MemoRepository,
    private val reminderRepo: ReminderRepository,
    private val authStore: AuthStore,
) : ViewModel() {

    data class State(
        val memoName: String? = null,
        val content: String = "",
        val visibility: String = VISIBILITY_PRIVATE,
        val attachments: List<AttachmentDto> = emptyList(),
        val reminder: ReminderEntity? = null,
        val loading: Boolean = true,
        val saving: Boolean = false,
        /** True while an attachment upload is in flight. */
        val uploading: Boolean = false,
        val error: String? = null,
        val finished: Boolean = false,
        /** False renders content as markdown (links clickable); true opens the editor. */
        val isEditing: Boolean = false,
        /**
         * Snapshot of the memo at load time. Compared against the current
         * state to compute [isDirty]; new memos start with an empty baseline
         * so the "is anything entered yet" question has the same answer.
         */
        val original: Snapshot = Snapshot(),
    )

    /**
     * Subset of [State] used for dirty-checking — only the user-editable
     * fields. Excludes loading/saving/error/finished, which would otherwise
     * make every transient state count as a change.
     */
    data class Snapshot(
        val content: String = "",
        val visibility: String = VISIBILITY_PRIVATE,
        val attachments: List<AttachmentDto> = emptyList(),
        val reminderTriggerAtEpochMs: Long? = null,
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    fun load(memoName: String?, initialContent: String? = null, startInEditMode: Boolean = false) {
        viewModelScope.launch {
            if (memoName == null) {
                val content = initialContent.orEmpty()
                _state.value = State(
                    loading = false,
                    content = content,
                    isEditing = true,
                    // New-memo baseline matches what the user starts with —
                    // pre-populated shared text is "already entered" so a
                    // straight back press isn't treated as discarding work
                    // the user didn't author.
                    original = Snapshot(content = content),
                )
                return@launch
            }
            _state.update { it.copy(loading = true, memoName = memoName) }
            runCatching {
                val memo = memoRepo.get(memoName)
                val rem = reminderRepo.get(memoName)
                val visibility = memo.visibility.ifBlank { VISIBILITY_PRIVATE }
                _state.value = State(
                    memoName = memo.name,
                    content = memo.content,
                    visibility = visibility,
                    attachments = memo.attachments,
                    reminder = rem,
                    loading = false,
                    isEditing = startInEditMode,
                    original = Snapshot(
                        content = memo.content,
                        visibility = visibility,
                        attachments = memo.attachments,
                        reminderTriggerAtEpochMs = rem?.triggerAtEpochMs,
                    ),
                )
            }.onFailure { t ->
                if (t is CancellationException) throw t
                _state.update { it.copy(loading = false, error = t.message) }
            }
        }
    }

    /**
     * Whether the current state differs from the snapshot captured at load.
     * Used by the screen to decide whether back should warn-then-discard.
     */
    fun isDirty(): Boolean {
        val s = _state.value
        val o = s.original
        return s.content != o.content ||
            s.visibility != o.visibility ||
            s.attachments != o.attachments ||
            s.reminder?.triggerAtEpochMs != o.reminderTriggerAtEpochMs
    }

    fun setContent(text: String) = _state.update { it.copy(content = text) }

    fun setEditing(editing: Boolean) = _state.update { it.copy(isEditing = editing) }

    fun setVisibility(visibility: String) {
        if (visibility !in ALL_VISIBILITIES) return
        _state.update { it.copy(visibility = visibility) }
    }

    fun addAttachment(bytes: ByteArray, filename: String, type: String) {
        if (bytes.size > MAX_ATTACHMENT_BYTES) {
            _state.update { it.copy(error = ERROR_FILE_TOO_LARGE) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(uploading = true, error = null) }
            runCatching {
                memoRepo.uploadAttachment(
                    bytes = bytes,
                    filename = filename,
                    type = type,
                    memoName = _state.value.memoName,
                )
            }.fold(
                onSuccess = { attachment ->
                    _state.update { it.copy(uploading = false, attachments = it.attachments + attachment) }
                },
                onFailure = { t ->
                    if (t is CancellationException) throw t
                    _state.update { it.copy(uploading = false, error = t.message) }
                },
            )
        }
    }

    fun removeAttachment(name: String) {
        _state.update { s -> s.copy(attachments = s.attachments.filterNot { it.name == name }) }
    }

    fun save() {
        val s = _state.value
        if (s.content.isBlank() && s.memoName == null && s.attachments.isEmpty()) {
            _state.update { it.copy(finished = true) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            runCatching {
                val saved = if (s.memoName == null) {
                    memoRepo.create(
                        content = s.content,
                        visibility = s.visibility,
                        attachments = s.attachments,
                    )
                } else {
                    memoRepo.update(
                        name = s.memoName,
                        content = s.content,
                        visibility = s.visibility,
                        attachments = s.attachments,
                    )
                }
                // Reminder set against a placeholder "new" memo — re-key it
                // under the real memo name now that we have one.
                if (s.memoName == null && s.reminder != null) {
                    reminderRepo.setTimeReminder(saved.name, s.reminder.triggerAtEpochMs)
                }
                _state.update { it.copy(saving = false, finished = true) }
            }.onFailure { t ->
                if (t is CancellationException) throw t
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
                if (t is CancellationException) throw t
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

    /**
     * The memos web URL for the current memo, or null if the memo is unsaved
     * or the auth config has been cleared. Callers fire ACTION_SEND with this.
     * The link only resolves for non-PRIVATE memos; that's a server-side ACL
     * concern, so we don't gate the share button on visibility here.
     */
    fun shareUrl(): String? {
        val name = _state.value.memoName ?: return null
        val server = authStore.read()?.serverUrl ?: return null
        return "${server.trimEnd('/')}/m/${name.memoUid()}"
    }

    fun clearReminder() {
        viewModelScope.launch {
            val name = _state.value.memoName
            if (name != null) reminderRepo.clear(name)
            _state.update { it.copy(reminder = null) }
        }
    }

    companion object {
        const val VISIBILITY_PRIVATE = "PRIVATE"
        const val VISIBILITY_PROTECTED = "PROTECTED"
        const val VISIBILITY_PUBLIC = "PUBLIC"
        val ALL_VISIBILITIES = listOf(VISIBILITY_PRIVATE, VISIBILITY_PROTECTED, VISIBILITY_PUBLIC)

        /** 20 MB — base64 JSON encoding inflates this ~33% on the wire. */
        const val MAX_ATTACHMENT_BYTES: Int = 20 * 1024 * 1024
        const val ERROR_FILE_TOO_LARGE = "File too large (max 20 MB)"
    }
}
