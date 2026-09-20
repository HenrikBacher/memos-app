@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package nu.bacher.memos.data.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * DTOs for the SSO sign-in path: `IdentityProviderService.ListIdentityProviders`,
 * `AuthService.SignIn` with SSO credentials, and
 * `UserService.CreatePersonalAccessToken`.
 *
 * memos serves these through grpc-gateway, which emits camelCase JSON — the
 * snake_case [JsonNames] aliases are belt-and-braces for instances running
 * behind a gateway configured with `OrigName: true`, the same defensive move
 * [MemoDto] makes for `resources`/`attachments`.
 */

@Serializable
data class ListIdentityProvidersResponse(
    @JsonNames("identity_providers")
    val identityProviders: List<IdentityProviderDto> = emptyList(),
)

@Serializable
data class IdentityProviderDto(
    /** Resource name, `identity-providers/{idp}` — this is what SignIn wants. */
    val name: String = "",
    /** `OAUTH2` is the only type memos implements; anything else we skip. */
    val type: String = "",
    val title: String = "",
    val config: IdentityProviderConfigDto? = null,
)

@Serializable
data class IdentityProviderConfigDto(
    @JsonNames("oauth2_config")
    val oauth2Config: OAuth2ConfigDto? = null,
)

/**
 * Only the fields the app needs to build an authorization request. The
 * server also carries `clientSecret` and the token/userinfo URLs, but those
 * are its business — it performs the code exchange itself.
 */
@Serializable
data class OAuth2ConfigDto(
    @JsonNames("client_id")
    val clientId: String = "",
    @JsonNames("auth_url")
    val authUrl: String = "",
    val scopes: List<String> = emptyList(),
)

@Serializable
data class SsoSignInRequest(
    val ssoCredentials: SsoCredentials,
)

/** No defaults: `MemosJson` sets `encodeDefaults = false` and every field is required. */
@Serializable
data class SsoCredentials(
    val idpName: String,
    val code: String,
    val redirectUri: String,
    val codeVerifier: String,
)

@Serializable
data class SignInResponse(
    val user: SignedInUserDto? = null,
    @JsonNames("access_token")
    val accessToken: String = "",
)

@Serializable
data class SignedInUserDto(
    /** Resource name, `users/{id}` — the parent for the access-token call. */
    val name: String = "",
)

@Serializable
data class CreatePersonalAccessTokenRequest(
    val parent: String,
    val description: String,
    /** 0 means "never expires", which is what this app's bearer-only auth needs. */
    val expiresInDays: Int,
)

@Serializable
data class CreatePersonalAccessTokenResponse(
    /** Returned exactly once, on creation. */
    val token: String = "",
)
