package com.example.healthsensor

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSessionManagerTest {
    private val sessionId = "1b38d6d4-b27f-4d61-bf7d-4579ca4a4bdf"

    @Test
    fun persistedActiveSessionResumesWithoutDuplicateCreate() = runBlocking {
        val api = FakeSessionApi(sessionId)
        val store = MemoryConversationStore()
        ConversationSessionManager(api, store) { 3_600_000L }.ensure("patient-1")
        ConversationSessionManager(api, store) { 3_600_000L }.ensure("patient-1")

        assertEquals(1, api.createCalls)
        assertEquals(1, api.getCalls)
    }

    @Test
    fun alertSessionForwardsTypeAndTriggerAlertId() = runBlocking {
        val api = FakeSessionApi(sessionId)
        ConversationSessionManager(api, MemoryConversationStore()) { 3_600_000L }
            .start("patient-1", "alert_checkin", "33333333-3333-4333-8333-333333333333")

        assertEquals("alert_checkin", api.lastSessionType)
        assertEquals("33333333-3333-4333-8333-333333333333", api.lastTriggerAlertId)
    }

    @Test
    fun terminalTimeoutIsRetainedAndAReplacementCanStart() = runBlocking {
        val newId = "2b38d6d4-b27f-4d61-bf7d-4579ca4a4bdf"
        val api = FakeSessionApi(newId).apply { getResponse = session(sessionId, "abandoned", "abandoned") }
        val store = MemoryConversationStore().apply { save("patient-1", session(sessionId)) }

        val created = ConversationSessionManager(api, store) { 3_600_000L }.ensure("patient-1")

        assertEquals(newId, created.id)
        assertEquals("abandoned", store.reportSession?.status)
    }

    @Test
    fun manualFinishRetainsCompletedSessionAndReportStatusOnly() = runBlocking {
        val api = FakeSessionApi(sessionId)
        val store = MemoryConversationStore().apply { save("patient-1", session(sessionId)) }
        val manager = ConversationSessionManager(api, store) { 3_600_000L }

        val finished = manager.finish("patient-1")
        val report = manager.getReport("patient-1")

        assertEquals("completed", finished?.status)
        assertEquals(sessionId, store.reportSession?.id)
        assertEquals("ready", report.status)
        assertTrue(report.completed)
    }

    @Test
    fun skippedOptionalAuqMakesNoAssessmentRequest() {
        val api = FakeSessionApi(sessionId)

        assertFalse(OptionalAuqPolicy.shouldPostAssessment(OptionalAuqChoice.SKIP))
        assertTrue(OptionalAuqPolicy.shouldPostAssessment(OptionalAuqChoice.COMPLETE))
        assertEquals(0, api.assessmentCalls)
    }

    @Test
    fun logoutClearRemovesActiveAndReportTargets() {
        val store = MemoryConversationStore().apply {
            save("patient-1", session(sessionId))
            saveReportTarget("patient-1", session(sessionId, "completed", "completed"))
        }
        ConversationSessionManager(FakeSessionApi(sessionId), store) { 3_600_000L }.clear()

        assertNull(store.session)
        assertNull(store.reportSession)
    }

    private fun session(id: String, status: String = "in_progress", phase: String = "free_dialogue") =
        ConversationSession(
            id = id,
            status = status,
            interactionPhase = phase,
            assistantText = "안녕하세요",
            safety = null,
            activeInterventions = emptyList(),
            stateSnapshot = null,
            reportStatus = if (status == "completed") "pending" else "not_started",
            inactivityTimeoutSeconds = 3600,
            legacy = false,
            startedAtMs = 100L,
            updatedAtMs = 200L,
            endedAtMs = if (status == "in_progress") null else 200L
        )
}

private class MemoryConversationStore : ConversationSessionStore {
    var owner: String? = null
    var session: ConversationSession? = null
    var reportOwner: String? = null
    var reportSession: ConversationSession? = null
    override fun load(ownerId: String) = session?.takeIf { owner == ownerId }
    override fun save(ownerId: String, session: ConversationSession) { owner = ownerId; this.session = session }
    override fun loadReportTarget(ownerId: String) = reportSession?.takeIf { reportOwner == ownerId }
    override fun saveReportTarget(ownerId: String, session: ConversationSession) { reportOwner = ownerId; reportSession = session }
    override fun clearActive() { owner = null; session = null }
    override fun clear() { clearActive(); reportOwner = null; reportSession = null }
}

private class FakeSessionApi(private val id: String) : SessionApi {
    var createCalls = 0
    var getCalls = 0
    var assessmentCalls = 0
    var lastSessionType: String? = null
    var lastTriggerAlertId: String? = null
    var getResponse: ConversationSession? = null

    override fun create(sessionType: String, triggerAlertId: String?): ConversationSession {
        createCalls += 1
        lastSessionType = sessionType
        lastTriggerAlertId = triggerAlertId
        return active(id)
    }

    override fun get(sessionId: String): ConversationSession {
        getCalls += 1
        return getResponse ?: active(sessionId)
    }

    override fun postMessage(
        sessionId: String,
        clientMessageId: String?,
        content: String,
        readTimeoutMs: Int,
        inputModality: String
    ) =
        SessionMessageResponse(
            "77777777-7777-4777-8777-777777777777",
            "88888888-8888-4888-8888-888888888888",
            "ok",
            "free_dialogue",
            SessionSafety("clear", emptyList(), emptyList()),
            emptyList(),
            null,
            "not_started",
            3600
        )

    override fun postAssessment(sessionId: String, result: StateCheckResult) { assessmentCalls += 1 }
    override fun finish(sessionId: String) = active(sessionId).copy(status = "completed", interactionPhase = "completed", reportStatus = "pending", endedAtMs = 300L)
    override fun requestReport(sessionId: String) = SessionReportResponse(null, null, "pending", null, null)
    override fun getReport(sessionId: String) = SessionReportResponse("22222222-2222-4222-8222-222222222222", 1, "ready", 100L, 200L)

    private fun active(value: String) = ConversationSession(
        value, "in_progress", "free_dialogue", "안녕하세요", null, emptyList(), null,
        "not_started", 3600, false, 100L, 200L
    )
}
