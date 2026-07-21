package com.example.healthsensor

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

data class PhoneSessionIdentity(
    val sessionId: String,
    val startedAtMs: Long
)

object PhoneMonitoringState {
    private const val MAX_LATENCY_POINTS = 500
    private const val MAX_SEQUENCE_CACHE = 200

    val serverUrl = MutableStateFlow("")
    val isUploadEnabled = MutableStateFlow(false)
    val uploadStatus = MutableStateFlow("서버 URL 입력 후 전송을 시작하세요")

    val predictionUrl = MutableStateFlow("")
    val isPredictionReceiverEnabled = MutableStateFlow(false)
    val predictionStatus = MutableStateFlow("예측 수신 URL 입력 후 수신을 시작하세요")

    /** Camera analysis pauses phone network application without stopping watch reception. */
    val isCameraPauseActive = MutableStateFlow(false)

    val isServiceRunning = MutableStateFlow(false)
    val serviceStatus = MutableStateFlow("백그라운드 서비스 대기")

    val latestPrediction = MutableStateFlow<CravingPrediction?>(null)
    val lastStateCheckSubmittedAtMs = MutableStateFlow(0L)

    private var activeSession = createSession()
    val activeSessionId = MutableStateFlow(activeSession.sessionId)
    val sessionStartedAtMs = MutableStateFlow(activeSession.startedAtMs)
    val latestServerSessionId = MutableStateFlow<String?>(null)
    val presentedAlertAction = MutableStateFlow(AlertAction.NONE)
    val alertActionVersion = MutableStateFlow(0L)
    val isInterventionActive = MutableStateFlow(false)
    private val claimedAlertEventKeys = LinkedHashSet<String>()

    val uploadLatencyPoints = MutableStateFlow<List<SensorPoint>>(emptyList())
    val predictionLatencyPoints = MutableStateFlow<List<SensorPoint>>(emptyList())
    val uploadLatencyStatus = MutableStateFlow("POST ?? ??")
    val predictionLatencyStatus = MutableStateFlow("?? ?? ?? ??")

    private val allUploadLatencyRaw = mutableListOf<Pair<Long, Float>>()
    private val allPredictionLatencyRaw = mutableListOf<Pair<Long, Float>>()
    private val uploadSentAtBySequence = LinkedHashMap<Long, Long>()
    private var latencyStartedAtMs = 0L

    fun ensureConfig(config: ServerConfig) {
        if (serverUrl.value.isBlank()) serverUrl.value = config.sensorPostUrl
        if (predictionUrl.value.isBlank()) predictionUrl.value = config.predictionSseUrl
    }

    @Synchronized
    fun publishPrediction(prediction: CravingPrediction): Boolean {
        val current = latestPrediction.value
        if (current != null && !shouldReplaceLatest(current, prediction)) return false
        prediction.sessionId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            latestServerSessionId.value = it
        }
        latestPrediction.value = prediction
        return true
    }

    private fun shouldReplaceLatest(current: CravingPrediction, candidate: CravingPrediction): Boolean {
        if (candidate.timestampMs != current.timestampMs) return candidate.timestampMs > current.timestampMs
        // At the same measurement time, the continuously monitored Watch result is authoritative.
        return current.source != "watch_sensor" || candidate.source == "watch_sensor"
    }

    @Synchronized
    fun currentSession(): PhoneSessionIdentity = activeSession

    @Synchronized
    fun interventionSessionId(): String =
        latestServerSessionId.value ?: activeSession.sessionId

    @Synchronized
    fun startNewSession(
        startedAtMs: Long = System.currentTimeMillis(),
        sessionId: String = newSessionId(startedAtMs)
    ): PhoneSessionIdentity {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        activeSession = PhoneSessionIdentity(sessionId.trim(), startedAtMs)
        activeSessionId.value = activeSession.sessionId
        sessionStartedAtMs.value = activeSession.startedAtMs
        latestServerSessionId.value = null
        latestPrediction.value = null
        lastStateCheckSubmittedAtMs.value = 0L
        claimedAlertEventKeys.clear()
        presentedAlertAction.value = AlertAction.NONE
        alertActionVersion.value = 0L
        isInterventionActive.value = false
        clearLatency()
        return activeSession
    }

    @Synchronized
    fun activateIntervention() {
        isInterventionActive.value = true
    }

    @Synchronized
    fun closeIntervention() {
        isInterventionActive.value = false
    }

    @Synchronized
    fun registerAlertAction(prediction: CravingPrediction, action: AlertAction): Boolean {
        presentedAlertAction.value = action
        if (action.suppressesUserFacingActions) return false

        val eventKey = alertEventKey(prediction, action)
        if (!claimedAlertEventKeys.add(eventKey)) return false

        // Keep the prediction/action auditable while preventing an active intervention
        // from being replaced by another AUQ or phone notification. The event remains
        // claimed so closing chat cannot replay a stale alert.
        if (isInterventionActive.value) return false

        alertActionVersion.value += 1L
        return true
    }

    @Synchronized
    fun rememberUpload(payload: ServerWindowPayload) {
        if (payload.sessionId != activeSession.sessionId) return
        uploadSentAtBySequence[payload.sequence] = payload.sentAtMs
        while (uploadSentAtBySequence.size > MAX_SEQUENCE_CACHE) {
            val firstKey = uploadSentAtBySequence.keys.firstOrNull() ?: break
            uploadSentAtBySequence.remove(firstKey)
        }
    }

    @Synchronized
    fun recordUploadLatency(
        sessionId: String,
        attemptStartedAtMs: Long,
        completedAtMs: Long = System.currentTimeMillis()
    ) {
        if (sessionId != activeSession.sessionId) return
        val latencyMs = completedAtMs - attemptStartedAtMs
        val value = latencyMs.coerceAtLeast(0L).toFloat()
        allUploadLatencyRaw.add(completedAtMs to value)
        uploadLatencyPoints.value = appendLatencyPoint(uploadLatencyPoints.value, completedAtMs, value)
        uploadLatencyStatus.value = "최근 POST 왕복: ${latencyMs.coerceAtLeast(0L)} ms"
    }

    @Synchronized
    fun recordPredictionLatency(prediction: CravingPrediction, receivedAtMs: Long = System.currentTimeMillis()) {
        val anchorMs = prediction.uploadSentAtMs
            ?: prediction.sequence?.let { uploadSentAtBySequence[it] }
            ?: if (prediction.hasServerTimestamp) prediction.timestampMs else null

        if (anchorMs == null) {
            predictionLatencyStatus.value = "?? timestamp ??"
            return
        }

        val latencyMs = receivedAtMs - anchorMs
        val value = latencyMs.coerceAtLeast(0L).toFloat()
        allPredictionLatencyRaw.add(receivedAtMs to value)
        predictionLatencyPoints.value = appendLatencyPoint(predictionLatencyPoints.value, receivedAtMs, value)
        predictionLatencyStatus.value = "?? ?? ??: ${latencyMs.coerceAtLeast(0L)} ms"
    }

    @Synchronized
    fun uploadLatencySnapshot(): List<Pair<Long, Float>> = allUploadLatencyRaw.toList()

    @Synchronized
    fun predictionLatencySnapshot(): List<Pair<Long, Float>> = allPredictionLatencyRaw.toList()

    @Synchronized
    fun clearLatency() {
        allUploadLatencyRaw.clear()
        allPredictionLatencyRaw.clear()
        uploadSentAtBySequence.clear()
        uploadLatencyPoints.value = emptyList()
        predictionLatencyPoints.value = emptyList()
        uploadLatencyStatus.value = "POST ?? ??"
        predictionLatencyStatus.value = "?? ?? ?? ??"
        latencyStartedAtMs = 0L
    }

    fun markStateCheckSubmitted(timestampMs: Long = System.currentTimeMillis()) {
        lastStateCheckSubmittedAtMs.value = timestampMs
    }

    fun resetStateCheckCooldown() {
        lastStateCheckSubmittedAtMs.value = 0L
    }

    private fun appendLatencyPoint(
        current: List<SensorPoint>,
        timestampMs: Long,
        value: Float
    ): List<SensorPoint> =
        (current + SensorPoint(timestampMs, value, latencyToX(timestampMs))).takeLast(MAX_LATENCY_POINTS)

    private fun latencyToX(timestampMs: Long): Float {
        if (latencyStartedAtMs == 0L) latencyStartedAtMs = timestampMs
        return ((timestampMs - latencyStartedAtMs) / 100f)
    }

    private fun alertEventKey(prediction: CravingPrediction, action: AlertAction): String {
        val sessionId = prediction.sessionId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: activeSession.sessionId
        return prediction.sequence?.let { "$sessionId:sequence:$it" }
            ?: "$sessionId:timestamp:${prediction.timestampMs}:action:${action.name}"
    }

    private fun createSession(): PhoneSessionIdentity {
        val startedAtMs = System.currentTimeMillis()
        return PhoneSessionIdentity(newSessionId(startedAtMs), startedAtMs)
    }

    private fun newSessionId(startedAtMs: Long): String =
        "$startedAtMs-${UUID.randomUUID()}"
}
