package im.flume.hearth.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Navidrome password, only needed for playlist photos (Navidrome's own API has no token login).
 * Encrypted with an AES key that never leaves the Android Keystore; only ciphertext and IV are stored.
 */
class PasswordVault(
    context: Context,
    prefsName: String = "vault",
    /** Keystore alias of the AES key; each vault has its own. */
    private val alias: String = "hearth-navidrome-password",
) {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    val hasPassword: Boolean get() = prefs.contains(KEY_DATA)

    fun read(): String? = runCatching {
        val data = prefs.getString(KEY_DATA, null) ?: return null
        val iv = prefs.getString(KEY_IV, null) ?: return null
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull() // A lost or replaced key just means asking for the password again.

    fun save(password: String) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        prefs.edit {
            putString(KEY_DATA, Base64.encodeToString(data, Base64.NO_WRAP))
            putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
        }
    }

    fun clear() {
        prefs.edit { clear() }
        runCatching { KeyStore.getInstance(STORE).apply { load(null) }.deleteEntry(alias) }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(STORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val STORE = "AndroidKeyStore"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val KEY_DATA = "password"
        const val KEY_IV = "iv"
    }
}
