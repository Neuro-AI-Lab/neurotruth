package com.neurotruth.mobile.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAndConsentTest {

    private fun failure(retryable: Boolean, attemptsRemaining: Int) = DialogueFailure(
        code = "dialogue_provider_error",
        clientMessageId = "8b79722a-bda0-45a4-a1e4-c87dc944dc9c",
        userMessageId = "user-1",
        retryable = retryable,
        attemptsRemaining = attemptsRemaining,
    )

    @Test
    fun `a provider failure keeps the same message for exactly one retry`() {
        val pending = ChatRetryPolicy.pending(
            failure(retryable = true, attemptsRemaining = 1),
            clientMessageId = "8b79722a-bda0-45a4-a1e4-c87dc944dc9c",
            content = "회의가 끝난 뒤 갑자기 술 생각이 났어요.",
        )
        assertNotNull(pending)
        assertEquals("8b79722a-bda0-45a4-a1e4-c87dc944dc9c", pending!!.clientMessageId)
        assertEquals("회의가 끝난 뒤 갑자기 술 생각이 났어요.", pending.content)
        assertEquals(INPUT_MODALITY_TEXT, pending.inputModality)
    }

    @Test
    fun `an exhausted or non retryable failure offers no retry`() {
        assertNull(ChatRetryPolicy.pending(failure(true, 0), "id", "본문"))
        assertNull(ChatRetryPolicy.pending(failure(false, 3), "id", "본문"))
        assertNull(ChatRetryPolicy.pending(null, "id", "본문"))
    }

    @Test
    fun `a voice transcript retries on the same path as text`() {
        val pending = ChatRetryPolicy.pending(
            failure(true, 1),
            clientMessageId = "id",
            content = "인식된 문장",
            inputModality = INPUT_MODALITY_VOICE,
        )
        assertEquals(INPUT_MODALITY_VOICE, pending!!.inputModality)
    }

    @Test
    fun `only text and voice are valid modalities`() {
        assertEquals("text", requireInputModality(INPUT_MODALITY_TEXT))
        assertEquals("voice", requireInputModality(INPUT_MODALITY_VOICE))
        assertThrows(IllegalArgumentException::class.java) { requireInputModality("audio") }
    }

    @Test
    fun `chat read timeout falls back to sixty minutes`() {
        assertEquals(60, ChatReadTimeoutPolicy.storedOrDefault(null))
        assertEquals(60, ChatReadTimeoutPolicy.storedOrDefault(0))
        assertEquals(60, ChatReadTimeoutPolicy.storedOrDefault(1_441))
        assertEquals(30, ChatReadTimeoutPolicy.storedOrDefault(30))
        assertEquals(60_000, ChatReadTimeoutPolicy.toMillis(1))
    }

    @Test
    fun `mandatory consents cannot be declined`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConsentSelection(
                tos = true, privacy = false, sensitive = true,
                biosignal = true, aiAnalysis = true, notification = true, reportGeneration = true,
            )
        }
    }

    @Test
    fun `the consent snapshot carries the three required version strings`() {
        val consent = ConsentSelection(
            tos = true, privacy = true, sensitive = true,
            biosignal = true, aiAnalysis = true, notification = true, reportGeneration = true,
        )
        val json = JSONObject(consent.toJson().toString())
        assertEquals("1.0", json.getString("tosVersion"))
        assertEquals("1.0", json.getString("privacyVersion"))
        assertEquals("1.0", json.getString("consentFormVersion"))
        assertTrue(json.has("cameraRppg"))
        assertTrue(json.has("faceVideoRetention"))
    }

    @Test
    fun `a bumped policy version forces re-consent`() {
        val stale = ConsentSelection(
            tos = true, privacy = true, sensitive = true,
            biosignal = true, aiAnalysis = true, notification = true, reportGeneration = true,
            privacyVersion = "0.9",
        )
        assertTrue(ConsentVersions.requiresReconsent(stale))
        assertTrue(ConsentVersions.requiresReconsent(null))

        val current = stale.copy(privacyVersion = ConsentVersions.PRIVACY)
        assertFalse(ConsentVersions.requiresReconsent(current))
    }

    @Test
    fun `camera capture needs all four consents`() {
        val base = ConsentSelection(
            tos = true, privacy = true, sensitive = true,
            biosignal = true, aiAnalysis = true, notification = true, reportGeneration = true,
            cameraRppg = true, faceVideoRetention = true,
        )
        assertTrue(ConsentGates(base).canCaptureRppg)
        assertFalse(ConsentGates(base.copy(faceVideoRetention = false)).canCaptureRppg)
        assertFalse(ConsentGates(base.copy(cameraRppg = false)).canCaptureRppg)
        assertFalse(ConsentGates(base.copy(aiAnalysis = false)).canCaptureRppg)
        assertFalse(ConsentGates(base.copy(biosignal = false)).canCaptureRppg)
    }

    @Test
    fun `notification consent gates presentation but not prediction`() {
        val consent = ConsentSelection(
            tos = true, privacy = true, sensitive = true,
            biosignal = true, aiAnalysis = true, notification = false, reportGeneration = true,
        )
        val gates = ConsentGates(consent)
        assertFalse(gates.canNotify)
        assertTrue(gates.canReceiveAiPrediction)
    }

    @Test
    fun `the product notice is re-shown when its version changes`() {
        val store = object : NoticeVersionStore {
            var value: String? = null
            override fun load(): String? = value
            override fun save(version: String): Boolean {
                value = version
                return true
            }
        }
        val policy = ProductNoticePolicy(store)
        assertTrue(policy.requiresAcknowledgement())
        policy.acknowledge()
        assertFalse(policy.requiresAcknowledgement())

        store.value = "2020-01-01-v0"
        assertTrue(policy.requiresAcknowledgement())
    }
}
