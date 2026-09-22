package nu.bacher.memos.ui

import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail
import nu.bacher.memos.data.api.MemosApi
import nu.bacher.memos.data.api.MemosJson
import nu.bacher.memos.data.auth.AuthStore
import nu.bacher.memos.data.repo.FakeMemoDao
import nu.bacher.memos.data.repo.FakePendingActionDao
import nu.bacher.memos.data.repo.testHttpClient
import nu.bacher.memos.data.repo.testMemoRepository
import nu.bacher.memos.data.repo.jsonHeaders
import nu.bacher.memos.data.repo.MemoRepository
import nu.bacher.memos.ui.login.LoginViewModel

/**
 * The login screen is the one place a user can be stranded by a bad message,
 * so the mapping from failure to typed error is worth pinning down.
 */
class LoginViewModelTest {

    // viewModelScope dispatches on Main, which doesn't exist in a unit test.
    // Unconfined so work launched by submit() runs eagerly and the assertions
    // can read the resulting state directly.
    @BeforeTest
    fun installMainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun removeMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun rejects_a_non_https_url_without_touching_the_network() = runTest {
        var calls = 0
        val vm = vm(MockEngine { calls++; respondError(HttpStatusCode.OK) })

        vm.onUrlChange("http://memos.example.com")
        vm.onTokenChange("tok")
        vm.submit(onSuccess = { fail("should not succeed") })

        assertEquals(LoginViewModel.LoginError.URL_NOT_HTTPS, vm.state.value.error)
        assertEquals(0, calls, "an invalid URL must not be sent anywhere")
    }

    @Test
    fun accepts_an_uppercase_scheme() = runTest {
        val vm = vm(MockEngine { respond("""{"memos":[]}""", HttpStatusCode.OK, jsonHeaders()) })

        vm.onUrlChange("HTTPS://memos.example.com")
        vm.onTokenChange("tok")
        val succeeded = CompletableDeferred<Unit>()
        vm.submit(onSuccess = { succeeded.complete(Unit) })

        // submit() does real async work; await the outcome rather than
        // assuming it finished by the time submit() returned.
        succeeded.await()
        assertNull(vm.state.value.error)
    }

    @Test
    fun requires_a_token() = runTest {
        val vm = vm(MockEngine { respondError(HttpStatusCode.OK) })

        vm.onUrlChange("https://memos.example.com")
        vm.onTokenChange("   ")
        vm.submit(onSuccess = { fail("should not succeed") })

        assertEquals(LoginViewModel.LoginError.TOKEN_REQUIRED, vm.state.value.error)
    }

    @Test
    fun maps_a_rejected_token_to_auth_not_a_generic_failure() = runTest {
        val vm = vm(MockEngine { respondError(HttpStatusCode.Unauthorized) })

        vm.onUrlChange("https://memos.example.com")
        vm.onTokenChange("bad")
        vm.submit(onSuccess = { fail("should not succeed") })

        assertEquals(LoginViewModel.LoginError.AUTH, vm.awaitError())
    }

    @Test
    fun maps_rate_limiting_to_busy() = runTest {
        val vm = vm(MockEngine { respondError(HttpStatusCode.TooManyRequests) })

        vm.onUrlChange("https://memos.example.com")
        vm.onTokenChange("tok")
        vm.submit(onSuccess = { fail("should not succeed") })

        assertEquals(LoginViewModel.LoginError.BUSY, vm.awaitError())
    }

    @Test
    fun editing_a_field_clears_the_previous_error() = runTest {
        val vm = vm(MockEngine { respondError(HttpStatusCode.Unauthorized) })

        vm.onUrlChange("https://memos.example.com")
        vm.onTokenChange("bad")
        vm.submit(onSuccess = {})
        assertEquals(LoginViewModel.LoginError.AUTH, vm.awaitError())

        vm.onTokenChange("better")
        assertNull(vm.state.value.error)
    }

    /** Suspends until submit() has produced an error. runTest bounds the wait. */
    private suspend fun LoginViewModel.awaitError(): LoginViewModel.LoginError =
        state.first { it.error != null }.error!!

    private fun vm(engine: MockEngine): LoginViewModel {
        val client = testHttpClient(engine)
        val authStore = AuthStore(MapSettings(), PlaintextSecretCipher)
        val repo = testMemoRepository(engine, verifyClient = client)
        return LoginViewModel(authStore, repo)
    }

}
