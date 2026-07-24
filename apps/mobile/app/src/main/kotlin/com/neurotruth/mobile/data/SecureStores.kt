package com.neurotruth.mobile.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import com.neurotruth.mobile.core.NoticeVersionStore
import com.neurotruth.mobile.core.net.RefreshTokenStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM through the Android Keystore, over ordinary SharedPreferences.
 *
 * The backend keeps this class of data encrypted at rest, so the client must not store it weaker.
 * Anything sensitive — the refresh token, a pending chat body, the dashboard cache — goes through
 * here rather than plain preferences.
 *
 * A decrypt failure clears the entry instead of throwing: a rotated or invalidated Keystore key
 * should log the user out, not brick the app.
 */
class KeystoreSecureStore(
    context: Context,
    preferencesName: String,
    private val keyAlias: String,
) {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    @Synchronized
    fun putString(key: String, value: String): Boolean = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(ivKey(key), Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(valueKey(key), Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .commit()
    }.getOrDefault(false)

    @Synchronized
    fun getString(key: String): String? {
        val iv = preferences.getString(ivKey(key), null) ?: return null
        val ciphertext = preferences.getString(valueKey(key), null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_LENGTH_BITS, Base64.decode(iv, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrElse {
            remove(key)
            null
        }
    }

    @Synchronized
    fun remove(key: String) {
        preferences.edit().remove(ivKey(key)).remove(valueKey(key)).commit()
    }

    @Synchronized
    fun clear() {
        preferences.edit().clear().commit()
    }

    private fun ivKey(key: String) = "${key}_iv"

    private fun valueKey(key: String) = "${key}_value"

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128
    }
}

class KeystoreRefreshTokenStore(context: Context) : RefreshTokenStore {
    private val store = KeystoreSecureStore(context, "neurotruth_auth", "neurotruth_refresh_v1")

    override fun loadRefreshToken(): String? = store.getString(KEY)

    override fun saveRefreshToken(refreshToken: String): Boolean = store.putString(KEY, refreshToken)

    override fun clear() = store.clear()

    private companion object {
        const val KEY = "refresh_token"
    }
}

/** The acknowledged product-notice version. Device-local; it never leaves the phone. */
class DeviceNoticeVersionStore(context: Context) : NoticeVersionStore {
    private val preferences =
        context.applicationContext.getSharedPreferences("neurotruth_notice", Context.MODE_PRIVATE)

    override fun load(): String? = preferences.getString(KEY, null)

    override fun save(version: String): Boolean =
        preferences.edit().putString(KEY, version).commit()

    private companion object {
        const val KEY = "acknowledged_version"
    }
}

/** Holds the pending chat body, which is the patient's verbatim words about a craving episode. */
class PendingChatStore(context: Context) {
    private val store = KeystoreSecureStore(context, "neurotruth_chat", "neurotruth_chat_v1")

    fun save(clientMessageId: String, content: String, inputModality: String): Boolean =
        store.putString(
            KEY,
            // JSON, not a space-joined string: a space in the id or modality would have shifted the
            // fields and mislabelled the stored message content.
            JSONObject()
                .put("id", clientMessageId)
                .put("modality", inputModality)
                .put("content", content)
                .toString(),
        )

    /** Returns (clientMessageId, inputModality, content). */
    fun load(): Triple<String, String, String>? {
        val raw = store.getString(KEY) ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val id = json.optString("id")
        if (id.isBlank()) return null
        return Triple(id, json.optString("modality"), json.optString("content"))
    }

    fun clear() = store.remove(KEY)

    private companion object {
        const val KEY = "pending_message"
    }
}
