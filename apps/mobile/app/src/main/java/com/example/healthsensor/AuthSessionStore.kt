package com.example.healthsensor

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface AuthSessionStore {
    fun loadRefreshToken(): String?
    fun saveRefreshToken(refreshToken: String)
    fun clear()
}

class InMemoryAuthSessionStore(initialRefreshToken: String? = null) : AuthSessionStore {
    var refreshToken: String? = initialRefreshToken
        private set

    override fun loadRefreshToken(): String? = refreshToken

    override fun saveRefreshToken(refreshToken: String) {
        require(refreshToken.isNotBlank()) { "refresh token is blank" }
        this.refreshToken = refreshToken
    }

    override fun clear() {
        refreshToken = null
    }
}

/** Stores only the refresh credential; access JWTs remain in process memory. */
class KeystoreAuthSessionStore(
    context: Context,
    private val keyAlias: String = DEFAULT_KEY_ALIAS
) : AuthSessionStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    @Synchronized
    override fun loadRefreshToken(): String? {
        val iv = preferences.getString(KEY_IV, null) ?: return null
        val ciphertext = preferences.getString(KEY_CIPHERTEXT, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(TAG_LENGTH_BITS, Base64.decode(iv, Base64.NO_WRAP))
            )
            String(
                cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)),
                Charsets.UTF_8
            ).takeIf(String::isNotBlank)
                ?: error("decrypted refresh token is blank")
        }.getOrElse {
            clear()
            null
        }
    }

    @Synchronized
    override fun saveRefreshToken(refreshToken: String) {
        require(refreshToken.isNotBlank()) { "refresh token is blank" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(refreshToken.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .commit()
    }

    @Synchronized
    override fun clear() {
        preferences.edit().remove(KEY_IV).remove(KEY_CIPHERTEXT).commit()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "auth_session_secure"
        private const val KEY_IV = "refresh_iv"
        private const val KEY_CIPHERTEXT = "refresh_ciphertext"
        private const val DEFAULT_KEY_ALIAS = "neurotruth_refresh_v1"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_LENGTH_BITS = 128
    }
}

interface StringKeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

class SharedPreferencesStringStore(private val preferences: SharedPreferences) : StringKeyValueStore {
    override fun get(key: String): String? = preferences.getString(key, null)
    override fun put(key: String, value: String) {
        preferences.edit().putString(key, value).commit()
    }
    override fun remove(key: String) {
        preferences.edit().remove(key).commit()
    }
}

class InMemoryStringStore : StringKeyValueStore {
    private val values = mutableMapOf<String, String>()
    override fun get(key: String): String? = values[key]
    override fun put(key: String, value: String) { values[key] = value }
    override fun remove(key: String) { values.remove(key) }
}

/**
 * Persists one UUID for a logical upload until the caller marks it complete.
 * Recreating this class or retrying with the same upload key returns the same ID.
 */
class StableClientWindowIdStore(
    private val store: StringKeyValueStore,
    private val idFactory: () -> String = { UUID.randomUUID().toString() }
) {
    @Synchronized
    fun getOrCreate(uploadKey: String): String {
        require(uploadKey.isNotBlank()) { "uploadKey is blank" }
        val preferenceKey = keyFor(uploadKey)
        store.get(preferenceKey)?.let { existing ->
            runCatching { UUID.fromString(existing) }.getOrNull()?.let { return it.toString() }
        }
        val created = UUID.fromString(idFactory()).toString()
        store.put(preferenceKey, created)
        return created
    }

    @Synchronized
    fun markCompleted(uploadKey: String) {
        require(uploadKey.isNotBlank()) { "uploadKey is blank" }
        store.remove(keyFor(uploadKey))
    }

    private fun keyFor(uploadKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(uploadKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return "window_$digest"
    }

    companion object {
        fun from(context: Context): StableClientWindowIdStore {
            val preferences = context.applicationContext.getSharedPreferences(
                "sensor_window_ids",
                Context.MODE_PRIVATE
            )
            return StableClientWindowIdStore(SharedPreferencesStringStore(preferences))
        }
    }
}
