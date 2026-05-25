package nu.bacher.memos.data.api

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import nu.bacher.memos.data.auth.AuthStore

val MemosJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}

/**
 * Builds the configured Ktor HttpClient used by [MemosApi]. The base URL is
 * resolved per-request from [AuthStore] — Retrofit's "fixed base URL" model
 * doesn't fit here because the user picks their server at runtime. We
 * configure DefaultRequest with a URL that points at the user's server and a
 * bearer token header.
 *
 * If no credentials are present we throw a clear IllegalStateException
 * instead of silently letting the request go to a bogus URL.
 */
fun buildMemosHttpClient(
    engine: HttpClientEngineFactory<*>,
    authStore: AuthStore,
    enableLogging: Boolean = false,
): HttpClient = HttpClient(engine) {
    expectSuccess = true
    // Don't follow redirects: a 30x to a host other than the user's Memos
    // server would otherwise re-issue the request with the bearer token
    // attached. Memos doesn't redirect in normal operation; fail loudly if
    // the server starts doing so.
    followRedirects = false

    install(ContentNegotiation) { json(MemosJson) }

    if (enableLogging) {
        install(Logging) {
            level = LogLevel.INFO
            sanitizeHeader { header -> header == HttpHeaders.Authorization }
        }
    }

    install(DefaultRequest) {
        val config = authStore.read()
            ?: error("Not logged in to memos — open the app and sign in again")
        val parsed = URLBuilder().takeFrom(config.serverUrl.trimEnd('/'))
        url {
            protocol = parsed.protocol
            host = parsed.host
            if (parsed.port != URLProtocol.HTTP.defaultPort &&
                parsed.port != URLProtocol.HTTPS.defaultPort
            ) {
                port = parsed.port
            }
        }
        header(HttpHeaders.Authorization, "Bearer ${config.token}")
        header(HttpHeaders.Accept, "application/json")
    }
}

/**
 * Build a one-shot HttpClient against a specific server/token, used by login
 * to verify creds before saving them. Bypasses [AuthStore].
 */
fun buildVerificationClient(
    engine: HttpClientEngineFactory<*>,
    serverUrl: String,
    token: String,
): HttpClient = HttpClient(engine) {
    expectSuccess = true
    followRedirects = false
    install(ContentNegotiation) { json(MemosJson) }
    install(DefaultRequest) {
        val parsed = URLBuilder().takeFrom(serverUrl.trimEnd('/'))
        url {
            protocol = parsed.protocol
            host = parsed.host
            if (parsed.port != URLProtocol.HTTP.defaultPort &&
                parsed.port != URLProtocol.HTTPS.defaultPort
            ) {
                port = parsed.port
            }
        }
        header(HttpHeaders.Authorization, "Bearer $token")
        header(HttpHeaders.Accept, "application/json")
    }
}
