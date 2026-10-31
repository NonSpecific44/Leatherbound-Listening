package com.leatherbound.listening.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts Plex/Jellyfin access tokens with a key held in the Android Keystore. The key never leaves
 * the device, so a database restored from a cloud backup holds nothing usable; you sign in again.
 * Stored URLs carry [PLACEHOLDER] instead of the token and are filled in with [fill] when used.
 */
object TokenVault {
    const val PLACEHOLDER = "{token}"
    private const val PREFIX = "enc1:"
    private const val ALIAS = "leatherbound_tokens"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    fun isEncrypted(stored: String) = stored.startsWith(PREFIX)

    fun encrypt(plain: String): String {
        if (plain.isEmpty() || isEncrypted(plain)) return plain
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return PREFIX + Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    /** Returns "" if the token can't be decrypted (for example after restoring onto a new phone). */
    fun decrypt(stored: String): String {
        if (!isEncrypted(stored)) return stored
        return runCatching {
            val bytes = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES))
        }.getOrDefault("")
    }

    fun fill(url: String, token: String) = url.replace(PLACEHOLDER, token)
    fun strip(url: String, token: String) = if (token.isEmpty()) url else url.replace(token, PLACEHOLDER)
}
