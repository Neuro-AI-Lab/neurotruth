package com.example.healthsensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthModelsTest {
    @Test
    fun parsesTokenResponseAndForcedPasswordChange() {
        val parsed = AuthResponseParser.parse(authResponse("access-1", "refresh-1", true))

        assertEquals("patient-1", parsed.user.id)
        assertEquals("patient", parsed.user.role)
        assertTrue(parsed.user.mustChangePassword)
        assertEquals("access-1", parsed.accessToken)
        assertEquals("refresh-1", parsed.refreshToken)
        assertEquals(900L, parsed.expiresInSeconds)
    }

    @Test
    fun parsesOptionalConsentFromAuthResponse() {
        val body = authResponse("access-1", "refresh-1", false).dropLast(1) + """,
          "consent": {
            "tos": true,
            "privacy": true,
            "sensitive": true,
            "biosignal": true,
            "aiAnalysis": false,
            "notification": false,
            "reportGeneration": true,
            "tosVersion": "v1",
            "privacyVersion": "v1",
            "consentFormVersion": "v1"
          }
        }"""

        val parsed = AuthResponseParser.parse(body)

        assertTrue(parsed.consent?.biosignal == true)
        assertFalse(parsed.consent?.aiAnalysis == true)
    }

    @Test
    fun signupBodyCarriesRequiredAndOptionalConsent() {
        val body = PatientSignupRequest(
            email = " patient@example.com ",
            password = "correct horse battery staple",
            name = "환자",
            consent = ConsentSelection(
                tos = true,
                privacy = true,
                sensitive = true,
                biosignal = true,
                aiAnalysis = true,
                voice = true,
                notification = false,
                reportGeneration = true,
                tosVersion = "tos-v1",
                privacyVersion = "privacy-v1",
                consentFormVersion = "consent-v1"
            )
        ).toJson()

        assertTrue(body.contains("patient@example.com"))
        assertTrue(body.contains("\"biosignal\":true"))
        assertTrue(body.contains("\"reportGeneration\":true"))
        assertTrue(body.contains("\"voice\":true"))
    }

    private fun authResponse(access: String, refresh: String, mustChange: Boolean): String = """
        {
          "user": {
            "id": "patient-1",
            "email": "patient@example.com",
            "role": "patient",
            "status": "active",
            "mustChangePassword": $mustChange
          },
          "accessToken": "$access",
          "refreshToken": "$refresh",
          "expiresIn": 900
        }
    """.trimIndent()
}
