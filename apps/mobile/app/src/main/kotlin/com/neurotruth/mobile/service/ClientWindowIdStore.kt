package com.neurotruth.mobile.service

import android.content.Context
import java.util.UUID

/**
 * Supplies the `clientWindowId` for a `(session, sequence)` pair.
 *
 * A retry must re-send a byte-identical payload under the *same* id, and the id has to survive
 * process death — minting a fresh UUID at send time would turn every post-restart retry into a new
 * window on the server, or into a 409 if the content differed at all.
 *
 * Implementations are keyed on `ClientWindowKey.of(session, sequence)`.
 */
interface ClientWindowIdStore {
    fun idFor(key: String): String
    fun release(key: String)
    fun clear()
}

/**
 * SharedPreferences-backed [ClientWindowIdStore].
 *
 * A `clientWindowId` is an opaque idempotency key, not patient data, so ordinary preferences are the
 * right store — the Keystore-backed one is reserved for tokens, chat bodies and the dashboard cache.
 *
 * The same file also holds the monitoring session id and the next sequence number, because all
 * three have to be recovered together for a post-restart retry to reproduce its original id.
 */
class PersistentClientWindowIdStore(context: Context) : ClientWindowIdStore {

    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    override fun idFor(key: String): String {
        preferences.getString(key, null)?.let { return it }
        val generated = UUID.randomUUID().toString()
        preferences.edit().putString(key, generated).commit()
        return generated
    }

    @Synchronized
    override fun release(key: String) {
        preferences.edit().remove(key).commit()
    }

    @Synchronized
    override fun clear() {
        preferences.edit().clear().commit()
    }

    /** The monitoring session id, created once and reused until [clear]. */
    @Synchronized
    fun sessionId(): String {
        preferences.getString(KEY_SESSION, null)?.let { return it }
        val generated = UUID.randomUUID().toString()
        preferences.edit().putString(KEY_SESSION, generated).commit()
        return generated
    }

    @Synchronized
    fun sessionStartedAtMs(defaultValue: Long): Long {
        val stored = preferences.getLong(KEY_SESSION_STARTED_AT, 0L)
        if (stored > 0L) return stored
        preferences.edit().putLong(KEY_SESSION_STARTED_AT, defaultValue).commit()
        return defaultValue
    }

    @Synchronized
    fun nextSequence(): Long {
        val next = preferences.getLong(KEY_SEQUENCE, 0L)
        preferences.edit().putLong(KEY_SEQUENCE, next + 1L).commit()
        return next
    }

    private companion object {
        const val PREFERENCES = "neurotruth_sensor_windows"
        const val KEY_SESSION = "monitoring_session_id"
        const val KEY_SESSION_STARTED_AT = "monitoring_session_started_at"
        const val KEY_SEQUENCE = "next_sequence"
    }
}
