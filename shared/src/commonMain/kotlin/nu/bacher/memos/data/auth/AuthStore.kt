package nu.bacher.memos.data.auth

import com.russhwolf.settings.ExperimentalSettingsApi
import com.russhwolf.settings.ObservableSettings
import com.russhwolf.settings.coroutines.getStringOrNullFlow
import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Stores the memos server URL + access token via multiplatform-settings.
 *
 * The token is encrypted at rest via [SecretCipher] (Tink AEAD with an
 * Android Keystore master key on Android). The URL is stored as-is — it's
 * not sensitive and we need it to render the login screen state.
 *
 * If decryption fails (legacy plaintext from before encryption was wired, a
 * Keystore reset, or a corrupt envelope) [read] and [config] return null so
 * the user is sent back through login rather than crashing.
 *
 * [read] is called on every HTTP request (the Ktor DefaultRequest block
 * resolves the bearer per request), so we cache the last decrypted result
 * keyed by the encrypted ciphertext — the [SettingsValues]/cipher pair is
 * an authoritative cache key, and we re-decrypt only when the stored
 * ciphertext actually changes. Tink AEAD on the hot path was visibly
 * dominating request setup.
 */
@OptIn(ExperimentalSettingsApi::class)
class AuthStore(
    private val settings: ObservableSettings,
    private val cipher: SecretCipher,
) {

    data class Config(val serverUrl: String, val token: String)

    private data class CacheEntry(val urlSource: String, val tokenSource: String, val config: Config)

    @Volatile
    private var cache: CacheEntry? = null

    fun read(): Config? {
        val url = settings.getStringOrNull(KEY_URL)?.takeIf { it.isNotBlank() } ?: return null
        val encrypted = settings.getStringOrNull(KEY_TOKEN)?.takeIf { it.isNotBlank() } ?: return null
        return decryptOrCached(url, encrypted)
    }

    val config: Flow<Config?> = combine(
        settings.getStringOrNullFlow(KEY_URL),
        settings.getStringOrNullFlow(KEY_TOKEN),
    ) { url, encrypted ->
        if (url.isNullOrBlank() || encrypted.isNullOrBlank()) return@combine null
        decryptOrCached(url, encrypted)
    }.distinctUntilChanged()

    fun save(serverUrl: String, token: String) {
        cache = null
        settings.putString(KEY_URL, serverUrl.trimEnd('/'))
        settings.putString(KEY_TOKEN, cipher.encrypt(token))
    }

    fun clear() {
        cache = null
        // Only remove auth keys — settings.clear() would wipe unrelated
        // preferences (layout, etc.) that should survive logout.
        settings.remove(KEY_URL)
        settings.remove(KEY_TOKEN)
    }

    private fun decryptOrCached(url: String, encrypted: String): Config? {
        cache?.takeIf { it.urlSource == url && it.tokenSource == encrypted }?.let { return it.config }
        val token = cipher.decrypt(encrypted) ?: return null
        val config = Config(url, token)
        cache = CacheEntry(url, encrypted, config)
        return config
    }

    private companion object {
        const val KEY_URL = "server_url"
        const val KEY_TOKEN = "access_token"
    }
}
