package nu.bacher.memos.data.auth

import java.security.MessageDigest
import java.security.SecureRandom

actual fun secureRandomBytes(size: Int): ByteArray =
    ByteArray(size).also { SecureRandom().nextBytes(it) }

actual fun sha256(input: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(input)
