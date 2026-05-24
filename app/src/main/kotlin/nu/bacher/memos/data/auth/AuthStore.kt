package nu.bacher.memos.data.auth

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Stores the memos server URL + access token.
 *
 * We use a plain SharedPreferences file rather than EncryptedSharedPreferences:
 * 1. the file lives in app-private storage (other apps can't read it without
 *    root), and the filesystem itself is encrypted at rest on every supported
 *    Android version (FBE has been mandatory since Android 7);
 * 2. AndroidX's security-crypto is deprecated with no replacement, and its
 *    1.1.0 release has been known to silently fail decryption mid-session,
 *    which manifested as the app spontaneously "logging out" the user.
 *
 * The file is excluded from cloud backup / device transfer (see backup_rules.xml
 * and data_extraction_rules.xml).
 */
@Singleton
class AuthStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    data class Config(val serverUrl: String, val token: String)

    fun read(): Config? {
        val url = prefs.getString(KEY_URL, null)?.takeIf { it.isNotBlank() } ?: return null
        val token = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        return Config(url, token)
    }

    val config: Flow<Config?> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(read())
        }
        trySend(read())
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun save(serverUrl: String, token: String) {
        prefs.edit()
            .putString(KEY_URL, serverUrl.trimEnd('/'))
            .putString(KEY_TOKEN, token)
            .commit()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "memos_auth_prefs"
        const val KEY_URL = "server_url"
        const val KEY_TOKEN = "access_token"
    }
}
