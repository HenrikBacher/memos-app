package nu.bacher.memos.ui

import com.russhwolf.settings.MapSettings
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import nu.bacher.memos.data.auth.AuthStore
import nu.bacher.memos.data.auth.SsoAuthenticator
import nu.bacher.memos.data.repo.jsonHeaders
import nu.bacher.memos.data.repo.testHttpClient
import nu.bacher.memos.data.repo.testMemoRepository
import nu.bacher.memos.ui.login.LoginViewModel

/**
 * The SSO half of the login screen. The browser round trip can't be exercised
 * here, so these pin down everything on either side of it: what we ask the
 * identity provider for, and what we do with what comes back.
 */
class LoginViewModelSsoTest {

    @BeforeTest
    fun installMainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun removeMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun a_server_with_no_providers_says_so_rather_than_failing_generically() = runTest {
        val vm = vm(ssoEngine(providers = """{"identityProviders":[]}""")).first

        vm.onUrlChange(SERVER)
        vm.startSso()

        assertEquals(LoginViewModel.LoginError.SSO_NOT_CONFIGURED, vm.awaitError())
    }

    @Test
    fun a_server_without_the_endpoint_is_reported_as_too_old() = runTest {
        val vm = vm(ssoEngine(providersStatus = HttpStatusCode.NotFound)).first

        vm.onUrlChange(SERVER)
        vm.startSso()

        assertEquals(LoginViewModel.LoginError.SSO_UNSUPPORTED, vm.awaitError())
    }

    @Test
    fun a_lone_provider_opens_straight_away_with_pkce_and_our_redirect_uri() = runTest {
        val vm = vm(ssoEngine()).first
        val url = collectAuthorizationUrl(vm)

        vm.onUrlChange(SERVER)
        vm.startSso()

        val authorizationUrl = url.await()
        assertTrue(
            authorizationUrl.startsWith("https://idp.example.com/authorize?"),
            "should authorize against the provider's own auth_url: $authorizationUrl",
        )
        // The IdP recomputes the challenge from the verifier we send at the
        // exchange, so a mismatch here fails only at the very end of the flow.
        assertTrue(
            authorizationUrl.contains("code_challenge=${expectedChallenge()}"),
            "missing the S256 challenge for the pinned verifier: $authorizationUrl",
        )
        assertTrue(authorizationUrl.contains("code_challenge_method=S256"))
        assertTrue(authorizationUrl.contains("state=$TEST_STATE"))
        assertTrue(authorizationUrl.contains("client_id=client-0"))
        assertTrue(
            authorizationUrl.contains("redirect_uri=" + encoded(SsoAuthenticator.REDIRECT_URI)),
            "redirect_uri must survive encoding intact: $authorizationUrl",
        )
        // Scopes are space-delimited per RFC 6749, then form-encoded.
        assertTrue(authorizationUrl.contains("scope=openid+profile"))
        assertNull(vm.state.value.error)
    }

    @Test
    fun several_providers_put_the_choice_to_the_user_before_opening_anything() = runTest {
        val vm = vm(ssoEngine(providers = identityProvidersJson("Okta", "Keycloak"))).first
        val url = collectAuthorizationUrl(vm)

        vm.onUrlChange(SERVER)
        vm.startSso()

        val providers = vm.state.first { it.ssoProviders.isNotEmpty() }.ssoProviders
        assertEquals(listOf("Okta", "Keycloak"), providers.map { it.title })
        assertTrue(url.isActive, "nothing should open until the user picks")

        vm.onProviderSelected(providers[1])
        assertTrue(url.await().contains("client_id=client-1"))
        assertTrue(vm.state.value.ssoProviders.isEmpty(), "picker should close on selection")
    }

    @Test
    fun a_completed_redirect_stores_the_minted_access_token() = runTest {
        val engine = ssoEngine()
        val (vm, authStore) = vm(engine)
        val url = collectAuthorizationUrl(vm)

        vm.onUrlChange(SERVER)
        vm.startSso()
        url.await()

        val loggedIn = CompletableDeferred<Unit>()
        vm.onSsoRedirect(redirect(code = "auth-code", state = TEST_STATE)) { loggedIn.complete(Unit) }
        loggedIn.await()

        // The stored secret is the personal access token, not the short-lived
        // session token the signin call handed back — that swap is the whole
        // reason the rest of the app needs no refresh handling.
        assertEquals("pat-value", authStore.read()?.token)
        assertEquals(SERVER, authStore.read()?.serverUrl)

        val paths = engine.requestHistory.map { it.url.encodedPath }
        assertTrue(
            paths.any { it.endsWith("/users/1/personalAccessTokens") },
            "expected a token mint against the signed-in user, got $paths",
        )
    }

    @Test
    fun a_redirect_whose_state_does_not_match_is_never_exchanged() = runTest {
        val engine = ssoEngine()
        val (vm, authStore) = vm(engine)
        val url = collectAuthorizationUrl(vm)

        vm.onUrlChange(SERVER)
        vm.startSso()
        url.await()
        val afterAuthorize = engine.requestHistory.size

        vm.onSsoRedirect(redirect(code = "auth-code", state = "somebody-elses-state")) {
            fail("a mismatched state must not sign anyone in")
        }

        assertEquals(LoginViewModel.LoginError.SSO_FAILED, vm.awaitError())
        assertEquals(
            afterAuthorize,
            engine.requestHistory.size,
            "an unsolicited code must not reach the server at all",
        )
        assertNull(authStore.read())
    }

    @Test
    fun a_declined_consent_reads_as_declined_not_as_a_failure() = runTest {
        val vm = vm(ssoEngine()).first
        val url = collectAuthorizationUrl(vm)

        vm.onUrlChange(SERVER)
        vm.startSso()
        url.await()

        vm.onSsoRedirect("${SsoAuthenticator.REDIRECT_URI}?error=access_denied") {
            fail("a denied consent must not sign anyone in")
        }

        assertEquals(LoginViewModel.LoginError.SSO_DENIED, vm.awaitError())
    }

    @Test
    fun a_redirect_arriving_with_nothing_pending_fails_instead_of_replaying() = runTest {
        val (vm, authStore) = vm(ssoEngine())

        vm.onUrlChange(SERVER)
        // No startSso: this is the shape of a redirect landing after process
        // death, where the PKCE verifier that proves the code is ours is gone.
        vm.onSsoRedirect(redirect(code = "auth-code", state = TEST_STATE)) {
            fail("a code we can't prove we requested must not sign anyone in")
        }

        assertEquals(LoginViewModel.LoginError.SSO_FAILED, vm.state.value.error)
        assertNull(authStore.read())
    }

    @Test
    fun cancelling_the_auth_tab_only_speaks_up_while_a_request_is_outstanding() = runTest {
        val vm = vm(ssoEngine()).first

        // Nothing in flight — a cancel here is the Custom Tab fallback closing
        // after its redirect already completed the flow, and must stay quiet.
        vm.onSsoCancelled()
        assertNull(vm.state.value.error)

        val url = collectAuthorizationUrl(vm)
        vm.onUrlChange(SERVER)
        vm.startSso()
        url.await()

        vm.onSsoCancelled()
        assertEquals(LoginViewModel.LoginError.SSO_CANCELLED, vm.state.value.error)
    }

    @Test
    fun sso_refuses_a_plaintext_server_url_without_touching_the_network() = runTest {
        var calls = 0
        val vm = vm(MockEngine { calls++; respondError(HttpStatusCode.OK) }).first

        vm.onUrlChange("http://memos.example.com")
        vm.startSso()

        assertEquals(LoginViewModel.LoginError.URL_NOT_HTTPS, vm.state.value.error)
        assertEquals(0, calls, "an invalid URL must not be sent anywhere")
    }

    private fun redirect(code: String, state: String) =
        "${SsoAuthenticator.REDIRECT_URI}?code=$code&state=$state"

    /** The S256 challenge for the pinned [TEST_VERIFIER]. */
    private fun expectedChallenge() = nu.bacher.memos.data.auth.Pkce.challengeFor(TEST_VERIFIER)

    /** Ktor form-encodes query values; `:` and `/` come back percent-escaped. */
    private fun encoded(value: String) =
        value.replace(":", "%3A").replace("/", "%2F")

    /**
     * Subscribe to the authorization-URL event before anything can emit.
     * The flow has no replay, so a collector registered after [startSso] would
     * miss it entirely — hence the eager dispatcher.
     */
    private fun TestScope.collectAuthorizationUrl(vm: LoginViewModel): CompletableDeferred<String> {
        val url = CompletableDeferred<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            url.complete(vm.authorizationUrls.first())
        }
        return url
    }

    private suspend fun LoginViewModel.awaitError(): LoginViewModel.LoginError =
        state.first { it.error != null }.error!!

    private fun vm(engine: MockEngine): Pair<LoginViewModel, AuthStore> {
        val authStore = AuthStore(MapSettings(), PlaintextSecretCipher)
        val repo = testMemoRepository(engine, verifyClient = testHttpClient(engine))
        return LoginViewModel(authStore, repo, testSsoAuthenticator(engine)) to authStore
    }

    /** Routes the three SSO calls off one engine, the way one server would. */
    private fun ssoEngine(
        providers: String = identityProvidersJson("Okta"),
        providersStatus: HttpStatusCode = HttpStatusCode.OK,
        signIn: String = """{"user":{"name":"users/1"},"accessToken":"session-jwt"}""",
        token: String = """{"token":"pat-value"}""",
    ) = MockEngine { request ->
        val path = request.url.encodedPath
        when {
            path.endsWith("/identity-providers") ->
                if (providersStatus == HttpStatusCode.OK) {
                    respond(providers, HttpStatusCode.OK, jsonHeaders())
                } else {
                    respondError(providersStatus)
                }

            path.endsWith("/auth/signin") -> respond(signIn, HttpStatusCode.OK, jsonHeaders())
            path.endsWith("/personalAccessTokens") ->
                respond(token, HttpStatusCode.OK, jsonHeaders())

            else -> respondError(HttpStatusCode.NotFound)
        }
    }

    private companion object {
        const val SERVER = "https://memos.example.com"
    }
}
