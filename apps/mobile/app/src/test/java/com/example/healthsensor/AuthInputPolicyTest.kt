package com.example.healthsensor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthInputPolicyTest {
    @Test
    fun signupRequiresAllMandatoryConsentsAndTwelveCharacterPassword() {
        assertTrue(
            AuthInputPolicy.canSignup(
                "patient@example.com",
                "password1234",
                tos = true,
                privacy = true,
                sensitive = true
            )
        )
        assertFalse(
            AuthInputPolicy.canSignup(
                "patient@example.com",
                "password1234",
                tos = true,
                privacy = false,
                sensitive = true
            )
        )
        assertFalse(
            AuthInputPolicy.canSignup(
                "patient@example.com",
                "short",
                tos = true,
                privacy = true,
                sensitive = true
            )
        )
    }

    @Test
    fun derivesApiBaseFromExistingSensorConfiguration() {
        val base = AuthInputPolicy.apiBaseUrl(
            ServerConfig(
                sensorPostUrl = "http://192.168.0.20:8000/api/sensor-windows",
                predictionSseUrl = "http://192.168.0.20:8000/api/predictions/stream"
            )
        )
        assertTrue(base == "http://192.168.0.20:8000")
    }
}
