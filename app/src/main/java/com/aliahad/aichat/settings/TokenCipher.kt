package com.aliahad.aichat.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class TokenCipher(context: Context) {
    private val preferences = context.getSharedPreferences("secure_settings", Context.MODE_PRIVATE)

    fun saveToken(rawToken: String) {
        val token = rawToken.trim()
        require(token.startsWith("hf_")) { "Hugging Face tokens start with hf_" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, v2Key())
        preferences.edit()
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(cipher.doFinal(token.toByteArray()), Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putBoolean(KEY_MIGRATED_V2, true)
            .apply()
    }

    fun readToken(): String? {
        val encrypted = preferences.getString(KEY_CIPHERTEXT, null) ?: return null
        val iv = preferences.getString(KEY_IV, null) ?: return null
        if (!preferences.getBoolean(KEY_MIGRATED_V2, false)) {
            migrateLegacyToken(encrypted, iv)?.let { return it }
            // Migration incomplete — typically the device is locked, because using the
            // v2 key is gated on an unlocked device. The legacy key predates that gate
            // so it stays usable, and rotation retries on the next read.
            legacyKey()?.let { return decrypt(encrypted, iv, it) }
            return null
        }
        return decrypt(encrypted, iv, v2Key())
    }

    fun hasToken(): Boolean = readToken() != null

    fun maskedToken(): String? = readToken()?.let { token ->
        if (token.length <= 10) "hf_••••" else "${token.take(5)}••••${token.takeLast(4)}"
    }

    fun clearToken() {
        preferences.edit().clear().apply()
    }

    /**
     * One-time rotation from the legacy keystore alias (usable while the device is
     * locked) to the v2 alias that requires an unlocked device. Returns the token when
     * rotation completed, or null to signal the caller should fall back to the legacy
     * key — rotation simply retries on the next read.
     */
    private fun migrateLegacyToken(encrypted: String, iv: String): String? {
        val legacy = legacyKey() ?: return null
        val token = decrypt(encrypted, iv, legacy) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        return runCatching {
            cipher.init(Cipher.ENCRYPT_MODE, v2Key())
            preferences.edit()
                .putString(
                    KEY_CIPHERTEXT,
                    Base64.encodeToString(cipher.doFinal(token.toByteArray()), Base64.NO_WRAP),
                )
                .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putBoolean(KEY_MIGRATED_V2, true)
                .apply()
            runCatching { keyStore().deleteEntry(LEGACY_KEY_ALIAS) }
            token
        }.getOrNull()
    }

    private fun decrypt(encrypted: String, iv: String, key: SecretKey): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
        )
        cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)).decodeToString()
    }.getOrNull()

    private fun legacyKey(): SecretKey? =
        keyStore().getKey(LEGACY_KEY_ALIAS, null) as? SecretKey

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun v2Key(): SecretKey {
        val keyStore = keyStore()
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUnlockedDeviceRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "aichat_hugging_face_token_v2"
        const val LEGACY_KEY_ALIAS = "aichat_hugging_face_token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_CIPHERTEXT = "ciphertext"
        const val KEY_IV = "iv"
        const val KEY_MIGRATED_V2 = "migrated_v2"
    }
}
