package nu.bacher.memos.ui

import io.ktor.client.engine.mock.MockEngine
import nu.bacher.memos.data.auth.SsoAuthenticator
import nu.bacher.memos.data.repo.testHttpClient

/**
 * [SsoAuthenticator] over a MockEngine, with the PKCE verifier and state
 * pinned so a test can assert on the authorization URL and replay a redirect
 * without reaching for the generated values.
 */
internal fun testSsoAuthenticator(
    engine: MockEngine,
    verifier: String = TEST_VERIFIER,
    state: String = TEST_STATE,
): SsoAuthenticator = SsoAuthenticator(
    // The same engine backs both: MockEngine dispatches on the request, so one
    // handler can answer discovery, signin and the token call in turn.
    publicClientFactory = { testHttpClient(engine) },
    bearerClientFactory = { _, _ -> testHttpClient(engine) },
    newVerifier = { verifier },
    newState = { state },
)

internal const val TEST_VERIFIER = "test-verifier-value"
internal const val TEST_STATE = "test-state-value"

/** A single OAuth2 provider, as ListIdentityProviders returns it. */
internal fun identityProvidersJson(vararg titles: String): String {
    val entries = titles.mapIndexed { index, title ->
        """
        {
          "name": "identity-providers/${index + 1}",
          "type": "OAUTH2",
          "title": "$title",
          "config": {
            "oauth2Config": {
              "clientId": "client-$index",
              "authUrl": "https://idp.example.com/authorize",
              "scopes": ["openid", "profile"]
            }
          }
        }
        """.trimIndent()
    }
    return """{"identityProviders":[${entries.joinToString(",")}]}"""
}
