package com.neurotruth.mobile.data

import com.neurotruth.mobile.core.ConsentSelection
import com.neurotruth.mobile.core.ConsentVersions
import com.neurotruth.mobile.core.net.ApiEndpoints
import com.neurotruth.mobile.core.net.ApiHttpException
import com.neurotruth.mobile.core.net.ApiRequest
import com.neurotruth.mobile.core.net.AuthenticatedApiClient
import org.json.JSONObject

/** `GET /api/me` — the public user plus the latest consent snapshot. */
data class MeSnapshot(
    val userId: String,
    val email: String,
    val name: String?,
    val consent: ConsentSelection?,
) {
    /** NT-04 falls back to the email local part when signup carried no display name. */
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() } ?: email.substringBefore('@')
}

class ProfileRepository(
    private val client: AuthenticatedApiClient,
    private val endpoints: ApiEndpoints,
) {

    fun me(): MeSnapshot {
        val response = client.execute(ApiRequest("GET", endpoints.me))
        if (!response.isSuccessful) throw ApiHttpException(response.statusCode, response.body)
        return parse(response.body)
    }

    /**
     * Routes to NT-03 rather than Home.
     *
     * A bumped version string must force re-consent, so this defers to [ConsentVersions]. Treating
     * "required consents satisfied" as merely `tos && privacy && sensitive` would silently keep
     * stale consent across a policy change. A snapshot missing any required consent parses to null,
     * which the same call reports as requiring consent.
     */
    fun requiresConsent(snapshot: MeSnapshot): Boolean =
        ConsentVersions.requiresReconsent(snapshot.consent)

    private fun parse(body: String): MeSnapshot {
        val root = JSONObject(body)
        val user = root.optJSONObject("user") ?: root
        val consentJson = root.optJSONObject("consent")
            ?: user.optJSONObject("consent")
            ?: root.optJSONObject("latestConsent")
        return MeSnapshot(
            userId = user.optString("id"),
            email = user.optString("email"),
            name = if (user.isNull("name")) null else user.optString("name").ifBlank { null },
            consent = parseConsent(consentJson),
        )
    }

    /**
     * Returns null when the snapshot is absent or fails [ConsentSelection]'s required-consent
     * invariant, which is exactly the case that has to route back to NT-03.
     */
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
                tosVersion = json.optString("tosVersion", "").ifBlank { "0" },
                privacyVersion = json.optString("privacyVersion", "").ifBlank { "0" },
                consentFormVersion = json.optString("consentFormVersion", "").ifBlank { "0" },
            )
        }.getOrNull()
    }
}
