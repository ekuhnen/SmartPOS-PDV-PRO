package com.plugpdv.pdv.utils

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class RememberedCredentials(val login: String, val password: String)

/** Device-local credential convenience storage; password is never persisted plaintext. */
class RememberedCredentialsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun load(): RememberedCredentials? {
        if (!isEnabled()) return null
        val login = prefs.getString(KEY_LOGIN, null)?.takeIf { it.isNotBlank() } ?: return null
        val encrypted = prefs.getString(KEY_PASSWORD, null) ?: return null
        return try {
            RememberedCredentials(login, decrypt(encrypted))
        } catch (_: Exception) {
            clear()
            null
        }
    }

    fun save(login: String, password: String): Boolean {
        if (login.isBlank() || password.isBlank()) return false
        return try {
            prefs.edit().putBoolean(KEY_ENABLED, true).putString(KEY_LOGIN, login)
                .putString(KEY_PASSWORD, encrypt(password)).commit()
        } catch (_: Exception) {
            clear()
            false
        }
    }

    fun clear() = prefs.edit().clear().apply()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", ANDROID_KEYSTORE).apply {
            init(android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val payload = Base64.decode(value, Base64.NO_WRAP)
        require(payload.size > IV_LENGTH)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, payload.copyOfRange(0, IV_LENGTH)))
        return String(cipher.doFinal(payload.copyOfRange(IV_LENGTH, payload.size)), StandardCharsets.UTF_8)
    }

    private companion object {
        const val PREFS = "remembered_credentials"
        const val KEY_ENABLED = "enabled"
        const val KEY_LOGIN = "login"
        const val KEY_PASSWORD = "encrypted_password"
        const val KEY_ALIAS = "PlugPdvRememberedCredentials"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}
