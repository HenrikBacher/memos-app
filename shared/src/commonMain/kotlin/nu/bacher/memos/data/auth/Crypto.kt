package nu.bacher.memos.data.auth

/**
 * Cryptographic primitives PKCE needs that kotlin stdlib doesn't carry in
 * common code. Kept as top-level expect *functions* rather than an
 * expect class — classes are still beta (see the `-Xexpect-actual-classes`
 * flag in this module's build file) and there's no state to hold.
 */

/** [size] bytes from the platform CSPRNG. Never `kotlin.random.Random` — these back an auth secret. */
expect fun secureRandomBytes(size: Int): ByteArray

expect fun sha256(input: ByteArray): ByteArray
