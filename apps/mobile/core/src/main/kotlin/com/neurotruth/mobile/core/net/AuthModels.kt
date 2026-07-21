package com.neurotruth.mobile.core.net

import com.neurotruth.mobile.core.ConsentSelection
import org.json.JSONObject

data class AuthUser(
    val id: String,
    val email: String,
    val role: String,
    val status: String,
    val mustChangePassword: Boolean = false,
) {
    /** Home falls back to the email local part when no display name was given. */
    val fallbackDisplayName: String get() = email.substringBefore('@')
}

data class AuthTokens(
    val user: AuthUser,
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
    val consent: ConsentSelection? = null,
)

object AuthResponseParser {
    fun parse(body: String): AuthTokens {
        val root = JSONObject(body)
        val userJson = root.optJSONObject("user")
            ?: throw ApiHttpException(200, "missing user")

        val user = AuthUser(
            id = requireNonBlank(userJson, "id"),
            email = requireNonBlank(userJson, "email"),
            role = requireNonBlank(userJson, "role"),
            status = userJson.optString("status", "active").ifBlank { "active" },
            mustChangePassword = userJson.optBoolean("mustChangePassword", false),
        )

        val expiresIn = when (val raw = root.opt("expiresIn")) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull() ?: 0L
            else -> 0L
        }
        require(expiresIn > 0) { "expiresIn must be positive" }

        return AuthTokens(
            user = user,
            accessToken = requireNonBlank(root, "accessToken"),
            refreshToken = requireNonBlank(root, "refreshToken"),
            expiresInSeconds = expiresIn,
            consent = parseConsent(root.optJSONObject("consent") ?: userJson.optJSONObject("consent")),
        )
    }

    private fun parseConsent(json: JSONObject?): ConsentSelection? {
        if (json == null) return null
        return runCatching {
            ConsentSelection(
                tos = json.optBoolean("tos"),
                privacy = json.optBoolean("privacy"),
                sensitive = json.optBoolean("sensitive"),
                biosignal = json.optBoolean("biosignal"),
                aiAnalysis = json.optBoolean("aiAnalysis"),
                notification = json.optBoolean("notification"),
                reportGeneration = json.optBoolean("reportGeneration"),
                voice = json.optBoolean("voice"),
                cameraRppg = json.optBoolean("cameraRppg"),
                faceVideoRetention = json.optBoolean("faceVideoRetention"),
                tosVersion = json.optString("tosVersion", "1.0").ifBlank { "1.0" },
                privacyVersion = json.optString("privacyVersion", "1.0").ifBlank { "1.0" },
                consentFormVersion = json.optString("consentFormVersion", "1.0").ifBlank { "1.0" },
            )
        }.getOrNull()
    }

    private fun requireNonBlank(json: JSONObject, key: String): String {
        val value = json.optString(key, "")
        require(value.isNotBlank()) { "$key must not be blank" }
        return value
    }
}

/**
 * Signup carries the consent snapshot in the same call.
 *
 * The password reaches this object straight from the NT-02 form and is never written anywhere else.
 */
data class PatientSignupRequest(
    val email: String,
    val password: String,
    val consent: ConsentSelection,
    val name: String? = null,
    val birthYear: Int? = null,
    val gender: String? = null,
) {
    init {
        require(email.isNotBlank()) { "email must not be blank" }
        require(password.length >= MIN_PASSWORD_LENGTH) {
            "password must be at least $MIN_PASSWORD_LENGTH characters"
        }
    }

    fun toJson(): String {
        val json = JSONObject()
            .put("email", email)
            .put("password", password)
            .put("consent", consent.toJson())
        name?.takeIf { it.isNotBlank() }?.let { json.put("name", it) }
        birthYear?.let { json.put("birthYear", it) }
        gender?.takeIf { it.isNotBlank() }?.let { json.put("gender", it) }
        return json.toString()
    }

    companion object {
        const val MIN_PASSWORD_LENGTH: Int = 12
    }
}

object LoginRequest {
    fun toJson(email: String, password: String, device: String? = null): String {
        val json = JSONObject().put("email", email).put("password", password)
        device?.takeIf { it.isNotBlank() }?.let { json.put("device", it) }
        return json.toString()
    }
}
