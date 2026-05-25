package nu.bacher.memos.data.auth

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager

/**
 * Tink-backed AEAD encryption for short secrets (currently the memos auth
 * token). Tink generates an AES-256-GCM data-encryption keyset, persists it
 * in a dedicated SharedPreferences file, and wraps that keyset with a master
 * key generated in the Android Keystore (TEE-backed on devices that have
 * one). Plaintext keys never touch app memory in usable form.
 *
 * Decrypt returns null on any failure — corrupt envelope, legacy plaintext
 * from before encryption was wired, or a Keystore master key invalidated by
 * an OS-level credential reset. Callers must treat null as "no token" and
 * force re-login rather than crash.
 */
class TinkSecretCipher(context: Context) : SecretCipher {

    private val aead: Aead by lazy {
        AndroidKeysetManager.Builder()
            .withSharedPref(context.applicationContext, KEYSET_NAME, KEYSET_PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://$MASTER_KEY_ALIAS")
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    override fun encrypt(plaintext: String): String {
        val ct = aead.encrypt(plaintext.toByteArray(Charsets.UTF_8), null)
        return Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    override fun decrypt(ciphertext: String): String? = runCatching {
        val raw = Base64.decode(ciphertext, Base64.NO_WRAP)
        String(aead.decrypt(raw, null), Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        const val KEYSET_NAME = "memos_auth_keyset"
        const val KEYSET_PREFS = "memos_auth_keyset_prefs"
        const val MASTER_KEY_ALIAS = "memos_auth_master_key"

        init {
            AeadConfig.register()
        }
    }
}
