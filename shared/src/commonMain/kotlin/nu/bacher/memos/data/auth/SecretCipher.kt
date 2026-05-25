package nu.bacher.memos.data.auth

interface SecretCipher {
    fun encrypt(plaintext: String): String
    fun decrypt(ciphertext: String): String?
}
