package nu.bacher.memos.data.auth

import kotlin.io.encoding.Base64

/**
 * PKCE (RFC 7636) material for the SSO authorization request.
 *
 * Memos exchanges the authorization code server-side with its own client
 * secret, so the app isn't a public OAuth client in the usual sense. PKCE
 * still earns its place: the redirect comes back over a custom scheme that
 * another app on the device could in principle also claim, and the verifier
 * makes an intercepted code useless on its own.
 *
 * Nothing cryptographic is implemented here — [Base64], [sha256] and
 * [secureRandomBytes] all delegate to the stdlib or the platform.
 */
object Pkce {

    /**
     * Unpadded base64url, per RFC 7636 §4.1 — the encoded value goes in a
     * query parameter and `=` padding has no business there.
     */
    private val encoding = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    /** 32 random bytes → 43 base64url chars, inside RFC 7636's 43..128 range. */
    fun newVerifier(): String = encoding.encode(secureRandomBytes(32))

    /** Opaque value echoed back by the IdP so an unsolicited redirect can be rejected. */
    fun newState(): String = encoding.encode(secureRandomBytes(16))

    /** `S256` challenge: base64url(SHA-256(ASCII(verifier))). */
    fun challengeFor(verifier: String): String =
        encoding.encode(sha256(verifier.encodeToByteArray()))
}
