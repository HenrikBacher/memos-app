package nu.bacher.memos.data.api

import com.russhwolf.settings.MapSettings
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import nu.bacher.memos.data.auth.AuthStore
import nu.bacher.memos.ui.PlaintextSecretCipher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The Coil client fetches arbitrary URLs out of memo markdown, so the bearer
 * token must only ride along to the memos server's exact origin.
 */
class ImageHttpClientTest {

    private val authStore = AuthStore(MapSettings(), PlaintextSecretCipher)
        .also { it.save("https://memos.example.com", "tok") }

    /** Authorization header seen per requested URL. */
    private val seen = mutableMapOf<String, String?>()

    private val engine = MockEngine { request ->
        seen[request.url.toString()] = request.headers[HttpHeaders.Authorization]
        if (request.url.encodedPath == "/redirect") {
            respond(
                "",
                HttpStatusCode.Found,
                headersOf(HttpHeaders.Location, "https://cdn.example.net/img.png"),
            )
        } else {
            respond("", HttpStatusCode.OK)
        }
    }

    private val client = buildImageHttpClient(
        object : HttpClientEngineFactory<HttpClientEngineConfig> {
            override fun create(block: HttpClientEngineConfig.() -> Unit): HttpClientEngine = engine
        },
        authStore,
    )

    @Test
    fun attaches_token_for_the_server_origin() = runTest {
        client.get("https://memos.example.com/file/attachments/1/a.png")
        assertEquals("Bearer tok", seen.values.single())
    }

    @Test
    fun withholds_token_from_another_port_on_the_same_host() = runTest {
        client.get("https://memos.example.com:8443/a.png")
        assertNull(seen.values.single())
    }

    @Test
    fun withholds_token_from_plain_http_on_the_same_host() = runTest {
        client.get("http://memos.example.com/a.png")
        assertNull(seen.values.single())
    }

    @Test
    fun withholds_token_from_other_hosts() = runTest {
        client.get("https://cdn.example.net/a.png")
        assertNull(seen.values.single())
    }

    @Test
    fun drops_token_when_a_redirect_leaves_the_server() = runTest {
        client.get("https://memos.example.com/redirect")
        assertEquals("Bearer tok", seen["https://memos.example.com/redirect"])
        assertNull(seen["https://cdn.example.net/img.png"])
    }
}
