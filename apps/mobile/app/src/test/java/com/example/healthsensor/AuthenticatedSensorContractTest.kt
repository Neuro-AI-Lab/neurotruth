package com.example.healthsensor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticatedSensorContractTest {
    @Test
    fun sensorPayloadContainsStableUuidClientWindowId() {
        val id = "997e082b-0471-4695-ae63-bb05657a9359"
        val payload = ServerWindowPayload(
            clientWindowId = id,
            sessionId = "local-session",
            sessionStartedAtMs = 100L,
            sequence = 1L,
            sentAtMs = 200L,
            windowStartMs = 100L,
            windowEndMs = 20_100L,
            windowMs = 20_000L,
            samples = listOf(ServerSensorSample("EDA", 100L, 0.5f)),
            sync = ServerWindowSync(
                mode = "fixed_grid",
                fillMode = "hold",
                ppgHz = 25,
                ppgSamplesPerChannel = 500,
                edaHz = 1,
                edaSamples = 20
            )
        )

        val json = JSONObject(payload.toJson())
        assertEquals(id, json.getString("clientWindowId"))
        assertFalse(json.has("sessionId"))
        assertEquals("fixed_grid", json.getJSONObject("sync").getString("mode"))
        assertEquals(20_000L, json.getLong("windowMs"))
        assertEquals(500, json.getJSONObject("sync").getInt("ppgSamplesPerChannel"))
        assertEquals(20, json.getJSONObject("sync").getInt("edaSamples"))
    }

    @Test
    fun consentGatesUploadAndPredictionIndependently() {
        val user = AuthUser("patient-1", "patient@example.com", "patient", "active", false)
        val biosignalOnly = consent(biosignal = true, ai = false)
        val fullyEnabled = consent(biosignal = true, ai = true).copy(reportGeneration = true)

        assertTrue(MobileAuthState(user, biosignalOnly).canUploadBiosignal)
        assertFalse(MobileAuthState(user, biosignalOnly).canReceiveAiPrediction)
        assertTrue(MobileAuthState(user, fullyEnabled).canReceiveAiPrediction)
        assertFalse(MobileAuthState(user, null).canUploadBiosignal)
        assertTrue(MobileAuthState(user, fullyEnabled).canGenerateReport)
        assertFalse(MobileAuthState(user, fullyEnabled.copy(reportGeneration = false)).canGenerateReport)
    }

    @Test
    fun notificationConsentSuppressesPresentationWithoutSuppressingAiPrediction() {
        val user = AuthUser("patient-1", "patient@example.com", "patient", "active", false)
        val withoutNotification = consent(biosignal = true, ai = true).copy(notification = false)
        val withNotification = withoutNotification.copy(notification = true)
        val suppressedState = MobileAuthState(user, withoutNotification)
        val allowedState = MobileAuthState(user, withNotification)

        assertTrue(suppressedState.canReceiveAiPrediction)
        assertFalse(suppressedState.canNotify)
        assertFalse(NotificationPresentationPolicy.shouldPresent(suppressedState.canNotify, alertClaimed = true))
        assertTrue(NotificationPresentationPolicy.suppressWatchPresentation(suppressedState.canNotify, interventionActive = false))
        assertTrue(allowedState.canReceiveAiPrediction)
        assertTrue(allowedState.canNotify)
        assertTrue(NotificationPresentationPolicy.shouldPresent(allowedState.canNotify, alertClaimed = true))
        assertFalse(NotificationPresentationPolicy.suppressWatchPresentation(allowedState.canNotify, interventionActive = false))
    }

    @Test
    fun voiceConsentGatesOnlyVoiceInput() {
        val user = AuthUser("patient-1", "patient@example.com", "patient", "active", false)
        assertFalse(MobileAuthState(user, consent(true, true)).canUseVoice)
        assertTrue(MobileAuthState(user, consent(true, true).copy(voice = true)).canUseVoice)
    }

    @Test
    fun logoutStopsMonitoringBeforeClearingRuntime() {
        val calls = mutableListOf<String>()
        LogoutCleanupPolicy.execute(
            stopMonitoring = { calls += "stop" },
            clearRuntime = { calls += "clear" }
        )
        assertEquals(listOf("stop", "clear"), calls)
    }

    @Test
    fun revokedCredentialsStopWorkAndClearAllLocalAuthStateInOrder() {
        val calls = mutableListOf<String>()
        CredentialRevocationCleanupPolicy.execute(
            stopMonitoring = { calls += "stop" },
            clearConversationSessions = { calls += "sessions" },
            clearCredentials = { calls += "credentials" },
            clearRuntime = { calls += "runtime" }
        )

        assertEquals(listOf("stop", "sessions", "credentials", "runtime"), calls)
    }

    private fun consent(biosignal: Boolean, ai: Boolean) = ConsentSelection(
        tos = true,
        privacy = true,
        sensitive = true,
        biosignal = biosignal,
        aiAnalysis = ai,
        notification = false,
        reportGeneration = false,
        tosVersion = "v1",
        privacyVersion = "v1",
        consentFormVersion = "v1"
    )
}
