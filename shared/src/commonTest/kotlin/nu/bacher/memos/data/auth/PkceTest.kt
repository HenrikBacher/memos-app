package nu.bacher.memos.data.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The challenge has to match what the identity provider recomputes from the
 * verifier, and a wrong answer only shows up as an opaque server-side
 * rejection at the end of a browser round trip. So it's pinned to the worked
 * example in RFC 7636 Appendix B rather than to our own output.
 */
class PkceTest {

    @Test
    fun challenge_matches_the_rfc_7636_worked_example() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"

        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challengeFor(verifier))
    }

    @Test
    fun verifier_is_url_safe_and_within_the_length_rfc_7636_allows() {
        val verifier = Pkce.newVerifier()

        // Section 4.1: 43..128 characters from the unreserved set. 32 random
        // bytes base64url-encode to exactly 43.
        assertEquals(43, verifier.length)
        assertTrue(
            verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' },
            "verifier goes in a query parameter unescaped: $verifier",
        )
    }

    @Test
    fun verifiers_do_not_repeat() {
        val verifiers = List(50) { Pkce.newVerifier() }

        assertEquals(50, verifiers.toSet().size, "each authorization needs fresh PKCE material")
    }

    @Test
    fun state_is_url_safe() {
        val state = Pkce.newState()

        assertTrue(state.isNotEmpty())
        assertTrue(
            state.all { it.isLetterOrDigit() || it == '-' || it == '_' },
            "state goes in a query parameter unescaped: $state",
        )
    }
}
