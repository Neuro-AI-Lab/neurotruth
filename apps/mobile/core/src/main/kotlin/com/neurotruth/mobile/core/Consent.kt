package com.neurotruth.mobile.core

import org.json.JSONObject

/**
 * An immutable consent snapshot.
 *
 * The three version strings are required by the server with `minLength = 1` on both signup and
 * `POST /api/me/consents`. No endpoint publishes the currently required versions, so they ship as
 * app constants ([ConsentVersions]) and are compared against the stored snapshot after login.
 */
data class ConsentSelection(
    val tos: Boolean,
    val privacy: Boolean,
    val sensitive: Boolean,
    val biosignal: Boolean,
    val aiAnalysis: Boolean,
    val notification: Boolean,
    val reportGeneration: Boolean,
    val voice: Boolean = false,
    val cameraRppg: Boolean = false,
    val faceVideoRetention: Boolean = false,
    val tosVersion: String = ConsentVersions.TOS,
    val privacyVersion: String = ConsentVersions.PRIVACY,
    val consentFormVersion: String = ConsentVersions.CONSENT_FORM,
) {
    init {
        require(tos && privacy && sensitive) {
            "tos, privacy and sensitive consent are mandatory"
        }
        require(tosVersion.isNotBlank() && privacyVersion.isNotBlank() && consentFormVersion.isNotBlank()) {
            "consent version strings must not be blank"
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("tos", tos)
        .put("privacy", privacy)
        .put("sensitive", sensitive)
        .put("biosignal", biosignal)
        .put("voice", voice)
        .put("aiAnalysis", aiAnalysis)
        .put("notification", notification)
        .put("reportGeneration", reportGeneration)
        .put("cameraRppg", cameraRppg)
        .put("faceVideoRetention", faceVideoRetention)
        .put("tosVersion", tosVersion)
        .put("privacyVersion", privacyVersion)
        .put("consentFormVersion", consentFormVersion)
}

object ConsentVersions {
    const val TOS: String = "1.0"
    const val PRIVACY: String = "1.0"
    const val CONSENT_FORM: String = "1.0"

    /**
     * A bumped version must force re-consent. Treating "required consents satisfied" as merely
     * `tos && privacy && sensitive` would silently keep stale consent across a policy change.
     */
    fun requiresReconsent(snapshot: ConsentSelection?): Boolean {
        if (snapshot == null) return true
        return snapshot.tosVersion != TOS ||
            snapshot.privacyVersion != PRIVACY ||
            snapshot.consentFormVersion != CONSENT_FORM
    }
}

/** Feature gates. Withdrawing an optional consent blocks new use; it does not delete past data. */
data class ConsentGates(val consent: ConsentSelection?) {
    val canUploadBiosignal: Boolean
        get() = consent?.biosignal == true

    val canReceiveAiPrediction: Boolean
        get() = canUploadBiosignal && consent?.aiAnalysis == true

    val canCaptureRppg: Boolean
        get() = canReceiveAiPrediction &&
            consent?.cameraRppg == true &&
            consent.faceVideoRetention

    val canUseVoice: Boolean
        get() = consent?.voice == true

    val canGenerateReport: Boolean
        get() = consent?.reportGeneration == true

    /** Suppresses alert presentation only. The user may still open chat or the AUQ manually. */
    val canNotify: Boolean
        get() = consent?.notification == true
}

const val PRODUCT_NOTICE_VERSION: String = "2026-07-21-v1"

interface NoticeVersionStore {
    fun load(): String?
    fun save(version: String): Boolean
}

class ProductNoticePolicy(
    private val store: NoticeVersionStore,
    private val currentVersion: String = PRODUCT_NOTICE_VERSION,
) {
    fun requiresAcknowledgement(): Boolean = store.load() != currentVersion

    fun acknowledge(): Boolean = store.save(currentVersion)
}
