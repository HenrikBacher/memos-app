package nu.bacher.memos.data.auth

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.parseQueryString
import io.ktor.http.takeFrom
import nu.bacher.memos.data.api.CreatePersonalAccessTokenRequest
import nu.bacher.memos.data.api.CreatePersonalAccessTokenResponse
import nu.bacher.memos.data.api.ListIdentityProvidersResponse
import nu.bacher.memos.data.api.SignInResponse
import nu.bacher.memos.data.api.SsoCredentials
import nu.bacher.memos.data.api.SsoSignInRequest

/**
 * Drives OAuth2/SSO sign-in against a memos server, ending in a personal
 * access token this app can store like any hand-pasted one.
 *
 * Why it ends in a PAT rather than the session it starts with: SignIn returns
 * a deliberately short-lived access token plus a refresh token in an HttpOnly
 * cookie. Honouring that would mean a cookie jar, a refresh-on-401
 * interceptor, and a new "auth expired" bucket in the offline queue's retry
 * classification — all to reach the same place a PAT already is. So the last
 * step of the flow mints a never-expiring PAT using the session token and
 * hands *that* to [AuthStore]. Every layer downstream (the DefaultRequest
 * bearer, MemoRepository.syncPending, the widget) stays untouched and cannot
 * tell how the user signed in.
 *
 * The token carries a recognizable [TOKEN_DESCRIPTION] so the user can find
 * and revoke it under Settings -> My Account. Signing out of the app clears
 * local storage but cannot revoke it server-side, because [AuthStore]
 * deliberately stores only the secret, not its resource name.
 *
 * The three server calls:
 *  1. GET  api/v1/identity-providers — unauthenticated, on the server's public allowlist.
 *  2. POST api/v1/auth/signin with ssoCredentials — also unauthenticated.
 *  3. POST api/v1/{user}/personalAccessTokens — bearing the session token from (2).
 */
class SsoAuthenticator(
    private val publicClientFactory: (serverUrl: String) -> HttpClient,
    private val bearerClientFactory: (serverUrl: String, token: String) -> HttpClient,
    private val redirectUri: String = REDIRECT_URI,
    private val newVerifier: () -> String = Pkce::newVerifier,
    private val newState: () -> String = Pkce::newState,
) {

    /** An OAuth2 provider the server offers, flattened to what the app needs. */
    data class Provider(
        /** Resource name, identity-providers/{idp}. */
        val name: String,
        val title: String,
        val clientId: String,
        val authUrl: String,
        val scopes: List<String>,
    )

    /** An in-flight authorization. Held by the ViewModel until the redirect lands. */
    data class Authorization(
        val idpName: String,
        val authorizationUrl: String,
        val state: String,
        val codeVerifier: String,
    )

    /** Outcome of parsing the redirect back from the IdP. */
    sealed interface Redirect {
        data class Code(val code: String, val state: String) : Redirect

        /** The IdP reported a failure — most often the user declining consent. */
        data class Denied(val error: String) : Redirect
        data object Malformed : Redirect
    }

    /** Thrown when the server predates this API surface. */
    class UnsupportedServerException(message: String) : Exception(message)

    /**
     * OAuth2 providers configured on [serverUrl]. An empty list means the
     * instance has SSO switched off, which is a different thing from not
     * supporting it — the caller tells the user which.
     */
    suspend fun listProviders(serverUrl: String): List<Provider> {
        val client = publicClientFactory(serverUrl)
        val response: ListIdentityProvidersResponse = try {
            client.get("api/v1/identity-providers").body()
        } catch (e: ClientRequestException) {
            // A 404 here is the version signal: older memos exposed neither
            // this route nor SSO credentials on SignIn, so there is no flow to
            // fall back to.
            if (e.response.status.value == 404) {
                throw UnsupportedServerException("No identity-providers endpoint on $serverUrl")
            }
            throw e
        } finally {
            client.close()
        }

        return response.identityProviders.mapNotNull { idp ->
            if (idp.type != OAUTH2_TYPE) return@mapNotNull null
            val oauth2 = idp.config?.oauth2Config ?: return@mapNotNull null
            if (oauth2.authUrl.isBlank() || oauth2.clientId.isBlank()) return@mapNotNull null
            Provider(
                name = idp.name,
                // Servers aren't required to set a title; the resource id is a
                // poor label but beats an empty button.
                title = idp.title.ifBlank { idp.name.substringAfterLast('/') },
                clientId = oauth2.clientId,
                authUrl = oauth2.authUrl,
                scopes = oauth2.scopes,
            )
        }
    }

    /** Build the authorization URL to open in a browser. Generates fresh PKCE material. */
    fun authorize(provider: Provider): Authorization {
        val verifier = newVerifier()
        val state = newState()
        val url = URLBuilder().takeFrom(provider.authUrl).apply {
            parameters.append("response_type", "code")
            parameters.append("client_id", provider.clientId)
            parameters.append("redirect_uri", redirectUri)
            parameters.append("state", state)
            parameters.append("code_challenge", Pkce.challengeFor(verifier))
            parameters.append("code_challenge_method", "S256")
            if (provider.scopes.isNotEmpty()) {
                parameters.append("scope", provider.scopes.joinToString(" "))
            }
        }.buildString()

        return Authorization(
            idpName = provider.name,
            authorizationUrl = url,
            state = state,
            codeVerifier = verifier,
        )
    }

    /**
     * Pull code/state/error out of the redirect. Read off the query string
     * alone rather than through [io.ktor.http.Url] — the redirect carries a
     * custom scheme and there's no reason to make a URL parser adjudicate it.
     */
    fun parseRedirect(uri: String): Redirect {
        val params = parseQueryString(uri.substringAfter('?', ""))
        params["error"]?.takeIf { it.isNotBlank() }?.let { return Redirect.Denied(it) }
        val code = params["code"]?.takeIf { it.isNotBlank() } ?: return Redirect.Malformed
        val state = params["state"]?.takeIf { it.isNotBlank() } ?: return Redirect.Malformed
        return Redirect.Code(code, state)
    }

    /**
     * Trade [code] for a long-lived personal access token. Returns the token
     * value; the caller stores it exactly as it would a pasted one.
     */
    suspend fun exchangeForAccessToken(
        serverUrl: String,
        authorization: Authorization,
        code: String,
    ): String {
        val session = signIn(serverUrl, authorization, code)
        val user = session.user?.name?.takeIf { it.isNotBlank() }
            ?: throw UnsupportedServerException("SignIn returned no user for $serverUrl")
        if (session.accessToken.isBlank()) {
            throw UnsupportedServerException("SignIn returned no access token for $serverUrl")
        }
        return createPersonalAccessToken(serverUrl, session.accessToken, user)
    }

    private suspend fun signIn(
        serverUrl: String,
        authorization: Authorization,
        code: String,
    ): SignInResponse {
        val client = publicClientFactory(serverUrl)
        return try {
            client.post("api/v1/auth/signin") {
                contentType(ContentType.Application.Json)
                setBody(
                    SsoSignInRequest(
                        ssoCredentials = SsoCredentials(
                            idpName = authorization.idpName,
                            code = code,
                            // Must byte-match the redirect_uri sent to the IdP —
                            // memos forwards it into the token exchange and the
                            // provider compares the two.
                            redirectUri = redirectUri,
                            codeVerifier = authorization.codeVerifier,
                        ),
                    ),
                )
            }.body()
        } finally {
            client.close()
        }
    }

    private suspend fun createPersonalAccessToken(
        serverUrl: String,
        sessionToken: String,
        user: String,
    ): String {
        val client = bearerClientFactory(serverUrl, sessionToken)
        val response: CreatePersonalAccessTokenResponse = try {
            client.post("api/v1/$user/personalAccessTokens") {
                contentType(ContentType.Application.Json)
                setBody(
                    CreatePersonalAccessTokenRequest(
                        parent = user,
                        description = TOKEN_DESCRIPTION,
                        expiresInDays = 0,
                    ),
                )
            }.body()
        } catch (e: ClientRequestException) {
            if (e.response.status.value == 404) {
                throw UnsupportedServerException("No personalAccessTokens endpoint on $serverUrl")
            }
            throw e
        } finally {
            client.close()
        }

        return response.token.takeIf { it.isNotBlank() }
            ?: throw UnsupportedServerException("Server issued an empty access token")
    }

    companion object {
        /**
         * Scheme half of [REDIRECT_URI]. Handed to `AuthTabIntent.launch` so
         * the browser knows which redirect ends the flow, and declared on
         * MainActivity in AndroidManifest.xml for browsers that fall back to a
         * plain Custom Tab.
         */
        const val REDIRECT_SCHEME = "nu.bacher.memos"

        const val REDIRECT_HOST = "oauth2redirect"

        /**
         * Must byte-match the <data> element on MainActivity in
         * AndroidManifest.xml, and be registered as a redirect URI on the
         * OAuth app the memos instance is configured against.
         */
        const val REDIRECT_URI = "$REDIRECT_SCHEME://$REDIRECT_HOST"

        /** Shown in the memos web UI's token list, so the user can revoke it. */
        const val TOKEN_DESCRIPTION = "Memos for Android (SSO)"

        private const val OAUTH2_TYPE = "OAUTH2"
    }
}
