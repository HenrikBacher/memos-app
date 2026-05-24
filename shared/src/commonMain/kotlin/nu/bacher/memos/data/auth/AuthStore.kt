package nu.bacher.memos.data.auth

import com.russhwolf.settings.ExperimentalSettingsApi
import com.russhwolf.settings.ObservableSettings
import com.russhwolf.settings.coroutines.getStringOrNullFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Stores the memos server URL + access token via multiplatform-settings.
 *
 * The Android binding uses a private SharedPreferences file. We deliberately
 * don't add encryption: the file lives in app-private storage (other apps
 * can't read it without root) and the filesystem is encrypted at rest. The
 * file is excluded from cloud backup / device transfer (see
 * backup_rules.xml and data_extraction_rules.xml).
 */
@OptIn(ExperimentalSettingsApi::class)
class AuthStore(private val settings: ObservableSettings) {

    data class Config(val serverUrl: String, val token: String)

    fun read(): Config? {
        val url = settings.getStringOrNull(KEY_URL)?.takeIf { it.isNotBlank() } ?: return null
        val token = settings.getStringOrNull(KEY_TOKEN)?.takeIf { it.isNotBlank() } ?: return null
        return Config(url, token)
    }

    val config: Flow<Config?> = combine(
        settings.getStringOrNullFlow(KEY_URL),
        settings.getStringOrNullFlow(KEY_TOKEN),
    ) { url, token ->
        if (url.isNullOrBlank() || token.isNullOrBlank()) null else Config(url, token)
    }.distinctUntilChanged()

    fun save(serverUrl: String, token: String) {
        settings.putString(KEY_URL, serverUrl.trimEnd('/'))
        settings.putString(KEY_TOKEN, token)
    }

    fun clear() {
        // Only remove auth keys — settings.clear() would wipe unrelated
        // preferences (layout, etc.) that should survive logout.
        settings.remove(KEY_URL)
        settings.remove(KEY_TOKEN)
    }

    private companion object {
        const val KEY_URL = "server_url"
        const val KEY_TOKEN = "access_token"
    }
}
