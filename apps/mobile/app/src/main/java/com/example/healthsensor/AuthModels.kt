package com.example.healthsensor

import org.json.JSONObject

data class AuthUser(
    val id: String,
    val email: String,
    val role: String,
    val status: String,
    val mustChangePassword: Boolean
)

data class AuthTokens(
    val user: AuthUser,
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
    val consent: ConsentSelection? = null
)

data class ConsentSelection(
    val tos: Boolean,
    val privacy: Boolean,
    val sensitive: Boolean,
    val biosignal: Boolean,
    val aiAnalysis: Boolean,
    val cameraRppg: Boolean = false,
    val faceVideoRetention: Boolean = false,
    val voice: Boolean = false,
    val notification: Boolean,
    val reportGeneration: Boolean,
    val tosVersion: String,
    val privacyVersion: String,
    val consentFormVersion: String
) {
    init {
        require(tos && privacy && sensitive) { "required consents must be accepted" }
        require(tosVersion.isNotBlank()) { "tosVersion is required" }
        require(privacyVersion.isNotBlank()) { "privacyVersion is required" }
        require(consentFormVersion.isNotBlank()) { "consentFormVersion is required" }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("tos", tos)
        .put("privacy", privacy)
        .put("sensitive", sensitive)
        .put("biosignal", biosignal)
        .put("aiAnalysis", aiAnalysis)
        .put("cameraRppg", cameraRppg)
        .put("faceVideoRetention", faceVideoRetention)
        .put("voice", voice)
        .put("notification", notification)
        .put("reportGeneration", reportGeneration)
        .put("tosVersion", tosVersion)
        .put("privacyVersion", privacyVersion)
        .put("consentFormVersion", consentFormVersion)

    companion object {
        fun fromJson(value: JSONObject?): ConsentSelection? {
            value ?: return null
            return runCatching {
                ConsentSelection(
                    tos = value.optBoolean("tos", false),
                    privacy = value.optBoolean("privacy", false),
                    sensitive = value.optBoolean("sensitive", false),
                    biosignal = value.optBoolean("biosignal", false),
                    aiAnalysis = value.optBoolean("aiAnalysis", false),
                    cameraRppg = value.optBoolean("cameraRppg", false),
                    faceVideoRetention = value.optBoolean("faceVideoRetention", false),
                    voice = value.optBoolean("voice", false),
                    notification = value.optBoolean("notification", false),
                    reportGeneration = value.optBoolean("reportGeneration", false),
                    tosVersion = value.optString("tosVersion"),
                    privacyVersion = value.optString("privacyVersion"),
                    consentFormVersion = value.optString("consentFormVersion")
                )
            }.getOrNull()
        }
    }
}

data class PatientSignupRequest(
    val email: String,
    val password: String,
    val name: String? = null,
    val birthYear: Int? = null,
    val gender: String? = null,
    val consent: ConsentSelection
) {
    fun toJson(): String = JSONObject()
        .put("email", email.trim())
        .put("password", password)
        .put("consent", consent.toJson())
        .apply {
            name?.trim()?.takeIf(String::isNotEmpty)?.let { put("name", it) }
            birthYear?.let { put("birthYear", it) }
            gender?.trim()?.takeIf(String::isNotEmpty)?.let { put("gender", it) }
        }
        .toString()
}

data class LoginRequest(val email: String, val password: String) {
    fun toJson(): String = JSONObject()
        .put("email", email.trim())
        .put("password", password)
        .toString()
}

object AuthResponseParser {
    fun parse(body: String): AuthTokens {
        val root = JSONObject(body)
        val userObject = root.requireObject("user")
        return AuthTokens(
            user = AuthUser(
                id = userObject.requireString("id"),
                email = userObject.requireString("email"),
                role = userObject.requireString("role"),
                status = userObject.requireString("status"),
                mustChangePassword = userObject.optBoolean("mustChangePassword", false)
            ),
            accessToken = root.requireString("accessToken"),
            refreshToken = root.requireString("refreshToken"),
            expiresInSeconds = root.requirePositiveLong("expiresIn"),
            consent = ConsentSelection.fromJson(
                root.optJSONObject("consent") ?: userObject.optJSONObject("consent")
            )
        )
    }

    private fun JSONObject.requireObject(key: String): JSONObject =
        optJSONObject(key) ?: throw IllegalArgumentException("$key missing")

    private fun JSONObject.requireString(key: String): String =
        optString(key).trim().takeIf(String::isNotEmpty)
            ?: throw IllegalArgumentException("$key missing")

    private fun JSONObject.requirePositiveLong(key: String): Long {
        if (!has(key) || isNull(key)) throw IllegalArgumentException("$key missing")
        val value = get(key)
        val parsed = when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
        return parsed?.takeIf { it > 0L }
            ?: throw IllegalArgumentException("$key must be positive")
    }
}
