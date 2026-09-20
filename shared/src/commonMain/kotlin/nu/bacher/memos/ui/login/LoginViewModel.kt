package nu.bacher.memos.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nu.bacher.memos.data.auth.AuthStore
import nu.bacher.memos.data.auth.SsoAuthenticator
import nu.bacher.memos.data.repo.ErrorKind
import nu.bacher.memos.data.repo.MemoRepository
import nu.bacher.memos.data.repo.classify

class LoginViewModel(
    private val authStore: AuthStore,
    private val memoRepo: MemoRepository,
    private val sso: SsoAuthenticator,
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

        /** Server answered, but has no OAuth2 identity provider set up. */
        SSO_NOT_CONFIGURED,

        /** Server is too old to offer the SSO sign-in flow at all. */
        SSO_UNSUPPORTED,

        /** The user declined consent at the identity provider. */
        SSO_DENIED,

        /** Redirect didn't match the request we sent, or arrived with nothing pending. */
        SSO_FAILED,

        /** The user closed the auth tab without finishing. */
        SSO_CANCELLED,

        /** No browser on the device could open the authorization page. */
        SSO_NO_BROWSER,
    }

    data class State(
        val serverUrl: String = "",
        val token: String = "",
        val loading: Boolean = false,
        val error: LoginError? = null,
        /** SSO discovery or code exchange is in flight. */
        val ssoLoading: Boolean = false,
        /** Non-empty only while the user is choosing between several providers. */
        val ssoProviders: List<SsoAuthenticator.Provider> = emptyList(),
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    /**
     * Authorization URLs for the screen to open in a Custom Tab. An event
     * rather than state: opening a browser twice for one tap is worse than
     * dropping the second, and a URL that lingered in state would re-fire on
     * every recomposition after a config change.
     */
    private val _authorizationUrls = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val authorizationUrls = _authorizationUrls.asSharedFlow()

    /**
     * The authorization we're waiting on a redirect for. Lives here rather
     * than in saved state on purpose — if the process dies mid-browser the
     * PKCE verifier is gone and the pending code is worthless, so the redirect
     * must fail loudly ([LoginError.SSO_FAILED]) instead of being replayed
     * against a verifier we can no longer prove we generated.
     */
    private var pending: SsoAuthenticator.Authorization? = null

    fun onUrlChange(v: String) = _state.update { it.copy(serverUrl = v, error = null) }
    fun onTokenChange(v: String) = _state.update { it.copy(token = v, error = null) }

    fun submit(onSuccess: () -> Unit) {
        val current = _state.value
        val url = normalizedUrl(current.serverUrl)
        val token = current.token.trim()

        if (url == null) {
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

    /**
     * Ask the server which identity providers it offers, then either open the
     * only one or put the choice to the user. Discovery is deferred to this
     * tap rather than run on every URL keystroke — it's a request to a host
     * the user is still typing.
     */
    fun startSso() {
        val url = normalizedUrl(_state.value.serverUrl)
        if (url == null) {
            _state.update { it.copy(error = LoginError.URL_NOT_HTTPS) }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(ssoLoading = true, error = null, ssoProviders = emptyList()) }
            runCatching { sso.listProviders(url) }
                .onSuccess { providers ->
                    when (providers.size) {
                        0 -> _state.update {
                            it.copy(ssoLoading = false, error = LoginError.SSO_NOT_CONFIGURED)
                        }
                        1 -> {
                            _state.update { it.copy(ssoLoading = false) }
                            openAuthorization(providers.single())
                        }
                        else -> _state.update {
                            it.copy(ssoLoading = false, ssoProviders = providers)
                        }
                    }
                }
                .onFailure { t ->
                    _state.update { it.copy(ssoLoading = false, error = t.toLoginError()) }
                }
        }
    }

    fun onProviderSelected(provider: SsoAuthenticator.Provider) {
        _state.update { it.copy(ssoProviders = emptyList()) }
        openAuthorization(provider)
    }

    fun onProviderPickerDismissed() = _state.update { it.copy(ssoProviders = emptyList()) }

    /** The screen couldn't hand the URL to any browser. */
    fun onBrowserUnavailable() {
        pending = null
        _state.update { it.copy(error = LoginError.SSO_NO_BROWSER) }
    }

    /**
     * The auth tab closed without delivering a redirect.
     *
     * Deliberately non-destructive: on a browser that doesn't implement Auth
     * Tab the flow degrades to a plain Custom Tab, the redirect arrives as an
     * Intent instead, and the tab *also* reports cancelled once it goes away.
     * So this only speaks up while an authorization is still outstanding, and
     * leaves [pending] alone — a redirect that lands afterwards still
     * completes, clearing the error as it goes.
     */
    fun onSsoCancelled() {
        if (pending == null) return
        _state.update { it.copy(ssoLoading = false, error = LoginError.SSO_CANCELLED) }
    }

    /** The auth tab ended in a state that carried no usable redirect. */
    fun onSsoUnusableResult() {
        if (pending == null) return
        _state.update { it.copy(ssoLoading = false, error = LoginError.SSO_FAILED) }
    }

    /**
     * Handle the redirect the identity provider sent back. Mirrors [submit]'s
     * contract: on success the token is saved and [onSuccess] fires.
     */
    fun onSsoRedirect(uri: String, onSuccess: () -> Unit) {
        val authorization = pending
        val url = normalizedUrl(_state.value.serverUrl)
        // Consume it either way — an authorization code is single-use, so a
        // retry has to start a fresh authorization.
        pending = null

        if (authorization == null || url == null) {
            _state.update { it.copy(ssoLoading = false, error = LoginError.SSO_FAILED) }
            return
        }

        when (val redirect = sso.parseRedirect(uri)) {
            is SsoAuthenticator.Redirect.Denied ->
                _state.update { it.copy(ssoLoading = false, error = LoginError.SSO_DENIED) }

            SsoAuthenticator.Redirect.Malformed ->
                _state.update { it.copy(ssoLoading = false, error = LoginError.SSO_FAILED) }

            is SsoAuthenticator.Redirect.Code -> {
                if (redirect.state != authorization.state) {
                    // Someone else's redirect, or a replay. Never exchange it.
                    _state.update { it.copy(ssoLoading = false, error = LoginError.SSO_FAILED) }
                    return
                }
                exchange(url, authorization, redirect.code, onSuccess)
            }
        }
    }

    private fun exchange(
        url: String,
        authorization: SsoAuthenticator.Authorization,
        code: String,
        onSuccess: () -> Unit,
    ) = viewModelScope.launch {
        _state.update { it.copy(ssoLoading = true, error = null) }
        runCatching { sso.exchangeForAccessToken(url, authorization, code) }
            .onSuccess { token ->
                authStore.save(url, token)
                _state.update { it.copy(ssoLoading = false) }
                onSuccess()
            }
            .onFailure { t ->
                _state.update { it.copy(ssoLoading = false, error = t.toLoginError()) }
            }
    }

    private fun openAuthorization(provider: SsoAuthenticator.Provider) {
        val authorization = sso.authorize(provider)
        pending = authorization
        _authorizationUrls.tryEmit(authorization.authorizationUrl)
    }

    /**
     * The typed URL as we'll actually use it, or null if it isn't usable.
     * URL schemes are case-insensitive (RFC 3986), so the scheme is normalized
     * before the check and "HTTPS://…" isn't wrongly rejected.
     */
    private fun normalizedUrl(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        val url = if (trimmed.startsWith("https://", ignoreCase = true)) {
            "https://" + trimmed.substring("https://".length)
        } else {
            trimmed
        }
        return url.takeIf { it.startsWith("https://") }
    }

    private fun Throwable.toLoginError(): LoginError = when {
        this is SsoAuthenticator.UnsupportedServerException -> LoginError.SSO_UNSUPPORTED
        else -> when (classify()) {
            ErrorKind.NETWORK -> LoginError.NETWORK
            ErrorKind.AUTH -> LoginError.AUTH
            ErrorKind.RATE_LIMIT -> LoginError.BUSY
            ErrorKind.SERVER -> LoginError.SERVER
            ErrorKind.OTHER -> LoginError.GENERIC
        }
    }
}
