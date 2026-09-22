package nu.bacher.memos.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nu.bacher.memos.data.auth.AuthStore
import nu.bacher.memos.data.repo.ErrorKind
import nu.bacher.memos.data.repo.MemoRepository
import nu.bacher.memos.data.repo.classify

class LoginViewModel(
    private val authStore: AuthStore,
    private val memoRepo: MemoRepository,
) : ViewModel() {

    /**
     * User-facing error buckets, same contract as
     * [nu.bacher.memos.ui.edit.MemoEditViewModel.EditError]: the screen owns
     * the wording, so raw exception messages (Ktor/JVM flavoured, unlocalized)
     * never reach the UI.
     */
    enum class LoginError {
        URL_NOT_HTTPS,
        TOKEN_REQUIRED,
        NETWORK,
        /** Reached the server; it rejected the token. */
        AUTH,
        BUSY,
        SERVER,
        GENERIC,
    }

    data class State(
        val serverUrl: String = "",
        val token: String = "",
        val loading: Boolean = false,
        val error: LoginError? = null,
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    fun onUrlChange(v: String) = _state.update { it.copy(serverUrl = v, error = null) }
    fun onTokenChange(v: String) = _state.update { it.copy(token = v, error = null) }

    fun submit(onSuccess: () -> Unit) {
        val current = _state.value
        val rawUrl = current.serverUrl.trim().trimEnd('/')
        val token = current.token.trim()

        // URL schemes are case-insensitive (RFC 3986). Normalize the scheme
        // before the check so "HTTPS://…" isn't wrongly rejected.
        val url = if (rawUrl.startsWith("https://", ignoreCase = true)) {
            "https://" + rawUrl.substring("https://".length)
        } else {
            rawUrl
        }

        if (!url.startsWith("https://")) {
            _state.update { it.copy(error = LoginError.URL_NOT_HTTPS) }
            return
        }
        if (token.isBlank()) {
            _state.update { it.copy(error = LoginError.TOKEN_REQUIRED) }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            // Verify with a one-off request before saving — saving fires the
            // AuthStore listener and would otherwise navigate the user away
            // from this screen mid-verify.
            memoRepo.verifyCreds(url, token)
                .onSuccess {
                    authStore.save(url, token)
                    _state.update { it.copy(loading = false) }
                    onSuccess()
                }
                .onFailure { t ->
                    _state.update { it.copy(loading = false, error = t.toLoginError()) }
                }
        }
    }

    private fun Throwable.toLoginError(): LoginError = when (classify()) {
        ErrorKind.NETWORK -> LoginError.NETWORK
        ErrorKind.AUTH -> LoginError.AUTH
        ErrorKind.RATE_LIMIT -> LoginError.BUSY
        ErrorKind.SERVER -> LoginError.SERVER
        ErrorKind.OTHER -> LoginError.GENERIC
    }
}
