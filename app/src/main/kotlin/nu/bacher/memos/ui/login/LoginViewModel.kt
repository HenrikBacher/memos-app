package nu.bacher.memos.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nu.bacher.memos.data.auth.AuthStore
import nu.bacher.memos.data.repo.MemoRepository

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authStore: AuthStore,
    private val memoRepo: MemoRepository,
) : ViewModel() {

    data class State(
        val serverUrl: String = "",
        val token: String = "",
        val loading: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    fun onUrlChange(v: String) = _state.update { it.copy(serverUrl = v, error = null) }
    fun onTokenChange(v: String) = _state.update { it.copy(token = v, error = null) }

    fun submit(onSuccess: () -> Unit) {
        val current = _state.value
        val url = current.serverUrl.trim().trimEnd('/')
        val token = current.token.trim()

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            _state.update { it.copy(error = "URL must start with http:// or https://") }
            return
        }
        if (token.isBlank()) {
            _state.update { it.copy(error = "Token is required") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            // Verify with a one-off request before saving — saving fires the
            // AuthStore Flow listener and would otherwise navigate the user
            // away from this screen mid-verify.
            memoRepo.verifyCreds(url, token)
                .onSuccess {
                    authStore.save(url, token)
                    _state.update { it.copy(loading = false) }
                    onSuccess()
                }
                .onFailure { t ->
                    _state.update {
                        it.copy(loading = false, error = t.message ?: "Connection failed")
                    }
                }
        }
    }
}
