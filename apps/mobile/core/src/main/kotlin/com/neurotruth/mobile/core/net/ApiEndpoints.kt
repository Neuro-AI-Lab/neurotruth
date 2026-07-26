package com.neurotruth.mobile.core.net

import java.util.UUID

/**
 * Every URL the app is allowed to call.
 *
 * The app talks only to the NeuroTruth backend. It never calls DGX directly and never exposes its
 * address. Administrator routes are web-console only and are deliberately absent.
 */
class ApiEndpoints(baseUrl: String) {
    val base: String

    init {
        require(baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) {
            "baseUrl must be an absolute http(s) URL"
        }
        base = baseUrl.trimEnd('/')
    }

    val patientSignup: String get() = "$base/api/auth/patient/signup"
    val login: String get() = "$base/api/auth/login"
    val refresh: String get() = "$base/api/auth/refresh"
    val logout: String get() = "$base/api/auth/logout"
    val changePassword: String get() = "$base/api/auth/change-password"

    val me: String get() = "$base/api/me"
    val consents: String get() = "$base/api/me/consents"

    val sensorWindows: String get() = "$base/api/sensor-windows"
    val predictionStream: String get() = "$base/api/predictions/stream"

    val sessions: String get() = "$base/api/sessions"
    val sttStatus: String get() = "$base/api/stt/status"

    val rppgStatus: String get() = "$base/api/rppg/status"
    val rppgJobs: String get() = "$base/api/rppg/jobs"

    fun session(sessionId: String): String = "$base/api/sessions/${uuid(sessionId)}"
    fun messages(sessionId: String): String = "${session(sessionId)}/messages"
    fun assessments(sessionId: String): String = "${session(sessionId)}/assessments"
    fun transcriptions(sessionId: String): String = "${session(sessionId)}/transcriptions"
    fun finish(sessionId: String): String = "${session(sessionId)}/finish"

    fun rppgJob(jobId: String): String = "$base/api/rppg/jobs/${uuid(jobId)}"
    fun retryRppgJob(jobId: String): String = "${rppgJob(jobId)}/retry"

    fun ppgPreview(predictionId: String): String =
        "$base/api/me/predictions/${uuid(predictionId)}/ppg-preview"

    fun cravingProbabilitySeries(range: String): String =
        "$base/api/me/craving-probability-series?range=${encode(range)}"

    fun cravingDashboard(timezone: String, eventRange: String, auqRange: String): String =
        "$base/api/me/craving-dashboard" +
            "?timezone=${encode(timezone)}" +
            "&eventRange=${encode(eventRange)}" +
            "&auqRange=${encode(auqRange)}"

    fun cravingCalendar(timezone: String, view: String, anchor: String): String =
        "$base/api/me/craving-calendar" +
            "?timezone=${encode(timezone)}" +
            "&view=${encode(view)}" +
            "&anchor=${encode(anchor)}"

    /** Malformed ids fail here rather than reaching the network as a 404 or a path injection. */
    private fun uuid(value: String): String = UUID.fromString(value).toString()

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
}
