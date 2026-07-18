package com.example.healthsensor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PhoneMonitoringService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val serverUploader = ServerUploader()
    private lateinit var apiClient: AuthenticatedApiClient
    private lateinit var windowIdStore: StableClientWindowIdStore
    private lateinit var predictionSender: PhonePredictionSender
    private lateinit var alertNotifier: CravingAlertNotifier

    private var observedSessionId = PhoneMonitoringState.currentSession().sessionId
    private var uploadSequence = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var autoCommunicationJob: Job? = null
    private var predictionJob: Job? = null
    private var pendingUpload: ServerWindowPayload? = null
    private val allHrRaw = mutableListOf<Pair<Long, Float>>()
    private val allPpgRaw = mutableListOf<Pair<Long, Float>>()
    private val allPpgIrRaw = mutableListOf<Pair<Long, Float>>()
    private val allPpgRedRaw = mutableListOf<Pair<Long, Float>>()
    private val allEdaRaw = mutableListOf<Pair<Long, Float>>()
    private val allAccelXRaw = mutableListOf<Pair<Long, Float>>()
    private val allAccelYRaw = mutableListOf<Pair<Long, Float>>()
    private val allAccelZRaw = mutableListOf<Pair<Long, Float>>()
    private val allSkinTempRaw = mutableListOf<Pair<Long, Float>>()

    override fun onCreate() {
        super.onCreate()
        predictionSender = PhonePredictionSender(this)
        alertNotifier = CravingAlertNotifier(this)
        apiClient = MobileApiProvider.get(this)
        windowIdStore = StableClientWindowIdStore.from(this)
        PhoneMonitoringState.ensureConfig(ServerConfig.load(this))
        startForeground(NOTIFICATION_ID, buildNotification("백그라운드 모니터링 중"))
        acquireWakeLock()
        PhoneMonitoringState.isServiceRunning.value = true
        PhoneMonitoringState.serviceStatus.value = "백그라운드 모니터링 중"
        startCollectors()
        startUploadLoop()
        observePredictionReceiverState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!MobileAuthRuntime.canUpload()) {
            stopSelf()
            return START_NOT_STICKY
        }
        PhoneMonitoringState.isServiceRunning.value = true
        PhoneMonitoringState.serviceStatus.value = "백그라운드 모니터링 중"
        return START_STICKY
    }

    override fun onDestroy() {
        PhoneMonitoringState.isServiceRunning.value = false
        PhoneMonitoringState.serviceStatus.value = "백그라운드 서비스 중지됨"
        predictionJob?.cancel()
        autoCommunicationJob?.cancel()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startCollectors() {
        scope.launch { SensorRepository.hrFlow.collect { (ts, v) -> appendSample(allHrRaw, ts, v) } }
        scope.launch { SensorRepository.ppgFlow.collect { (ts, v) -> appendSample(allPpgRaw, ts, v) } }
        scope.launch { SensorRepository.ppgIrFlow.collect { (ts, v) -> appendSample(allPpgIrRaw, ts, v) } }
        scope.launch { SensorRepository.ppgRedFlow.collect { (ts, v) -> appendSample(allPpgRedRaw, ts, v) } }
        scope.launch { SensorRepository.edaFlow.collect { (ts, v) -> appendSample(allEdaRaw, ts, v) } }
        scope.launch { SensorRepository.accelXFlow.collect { (ts, v) -> appendSample(allAccelXRaw, ts, v) } }
        scope.launch { SensorRepository.accelYFlow.collect { (ts, v) -> appendSample(allAccelYRaw, ts, v) } }
        scope.launch { SensorRepository.accelZFlow.collect { (ts, v) -> appendSample(allAccelZRaw, ts, v) } }
        scope.launch { SensorRepository.skinTempFlow.collect { (ts, v) -> appendSample(allSkinTempRaw, ts, v) } }
    }

    private fun appendSample(target: MutableList<Pair<Long, Float>>, timestamp: Long, value: Float) {
        ensureActiveSession()
        target.add(timestamp to value)
        trimOldSamples(target)
        scheduleAutoCommunicationStart()
    }

    private fun trimOldSamples(target: MutableList<Pair<Long, Float>>) {
        val newest = target.lastOrNull()?.first ?: return
        val cutoff = newest - RETAIN_RAW_MS
        while (target.isNotEmpty() && target.first().first < cutoff) {
            target.removeAt(0)
        }
    }

    private fun scheduleAutoCommunicationStart() {
        if (autoCommunicationJob != null) return
        PhoneMonitoringState.uploadStatus.value = "취득 시작 감지: 20초 후 자동 전송"
        PhoneMonitoringState.predictionStatus.value = "취득 시작 감지: 20초 후 자동 수신"
        autoCommunicationJob = scope.launch {
            delay(AUTO_COMMUNICATION_DELAY_MS)
            if (PhoneMonitoringState.serverUrl.value.isNotBlank() && MobileAuthRuntime.canUpload()) {
                PhoneMonitoringState.isUploadEnabled.value = true
                PhoneMonitoringState.uploadStatus.value = "자동 전송 시작: 취득 20초 경과"
            } else {
                PhoneMonitoringState.uploadStatus.value = "자동 전송 보류: 서버 POST URL이 비어 있음"
            }

            if (PhoneMonitoringState.predictionUrl.value.isNotBlank() && MobileAuthRuntime.canReceivePredictions()) {
                PhoneMonitoringState.isPredictionReceiverEnabled.value = true
            } else {
                PhoneMonitoringState.predictionStatus.value = "자동 수신 보류: 예측 수신 URL이 비어 있음"
            }
        }
    }

    private fun startUploadLoop() {
        scope.launch {
            while (isActive) {
                delay(SERVER_UPLOAD_INTERVAL_MS)
                if (
                    !PhoneMonitoringState.isUploadEnabled.value ||
                    !MobileAuthRuntime.canUpload() ||
                    PhoneMonitoringState.isCameraPauseActive.value
                ) continue

                val url = PhoneMonitoringState.serverUrl.value
                if (url.isBlank()) {
                    PhoneMonitoringState.uploadStatus.value = "전송 대기: 서버 URL 없음"
                    continue
                }

                val payload = pendingUpload ?: buildServerPayload()
                if (payload == null) {
                    PhoneMonitoringState.uploadStatus.value = "전송 대기: PPG/EDA 첫 샘플 대기 중"
                    continue
                }

                PhoneMonitoringState.rememberUpload(payload)
                PhoneMonitoringState.uploadStatus.value = "백그라운드 전송 중: ${payload.samples.size} samples"
                val attemptStartedAtMs = System.currentTimeMillis()
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        serverUploader.postWindowAuthenticated(apiClient, url, payload)
                    }
                }.getOrElse { e ->
                    val authenticationFailure =
                        e is AuthenticationRequiredException ||
                            (e is ApiHttpException && e.statusCode == 401)
                    UploadResult(
                        false,
                        if (authenticationFailure) 401 else -1,
                        e.message ?: "unknown error"
                    )
                }
                PhoneMonitoringState.recordUploadLatency(
                    sessionId = payload.sessionId,
                    attemptStartedAtMs = attemptStartedAtMs
                )

                if (result.success) {
                    windowIdStore.markCompleted(uploadKey(payload))
                    pendingUpload = null
                } else {
                    when (UploadFailurePolicy.resolve(result.statusCode)) {
                        UploadFailureAction.AUTHENTICATION_REQUIRED -> {
                            handleAuthenticationFailure()
                            break
                        }
                        UploadFailureAction.DROP_AND_CONTINUE -> {
                            windowIdStore.markCompleted(uploadKey(payload))
                            pendingUpload = null
                        }
                        UploadFailureAction.PAUSE_AND_DROP -> {
                            windowIdStore.markCompleted(uploadKey(payload))
                            pendingUpload = null
                            PhoneMonitoringState.isUploadEnabled.value = false
                        }
                        UploadFailureAction.RETRY_SAME_PAYLOAD -> {
                            pendingUpload = payload
                        }
                    }
                }

                PhoneMonitoringState.uploadStatus.value = if (result.success) {
                    "백그라운드 전송 완료: #${payload.sequence}, ${payload.samples.size} samples"
                } else {
                    when (UploadFailurePolicy.resolve(result.statusCode)) {
                        UploadFailureAction.DROP_AND_CONTINUE ->
                            "전송 충돌 window 폐기: 다음 새 window로 계속합니다"
                        UploadFailureAction.PAUSE_AND_DROP ->
                            "전송 중지: 동의 또는 요청 설정을 확인해 주세요 (${result.statusCode})"
                        UploadFailureAction.AUTHENTICATION_REQUIRED ->
                            "전송 중지: 다시 로그인해 주세요"
                        UploadFailureAction.RETRY_SAME_PAYLOAD ->
                            "일시적 전송 실패: 동일 window 재시도 예정 (${result.statusCode})"
                    }
                }
            }
        }
    }

    private fun observePredictionReceiverState() {
        scope.launch {
            combine(
                PhoneMonitoringState.isPredictionReceiverEnabled,
                PhoneMonitoringState.predictionUrl
            ) { enabled, url -> enabled to url }
                .distinctUntilChanged()
                .collect { (enabled, url) ->
                    if (enabled && url.isNotBlank() && MobileAuthRuntime.canReceivePredictions()) {
                        startPredictionReceiver(url)
                    } else {
                        predictionJob?.cancel()
                        predictionJob = null
                        if (!enabled) PhoneMonitoringState.predictionStatus.value = "예측 수신 중지됨"
                    }
                }
        }
    }

    private fun startPredictionReceiver(url: String) {
        predictionJob?.cancel()
        predictionJob = scope.launch {
            while (isActive && PhoneMonitoringState.isPredictionReceiverEnabled.value) {
                PhoneMonitoringState.predictionStatus.value = "백그라운드 SSE 연결 중"
                val activeJob = coroutineContext[Job]
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        apiClient.executeAuthenticatedStream { token ->
                            serverUploader.listenPredictions(
                                url = url,
                                accessToken = token,
                                shouldContinue = {
                                    PhoneMonitoringState.isPredictionReceiverEnabled.value &&
                                        MobileAuthRuntime.canReceivePredictions() &&
                                        activeJob?.isActive == true
                                }
                            ) { prediction ->
                                if (PhoneMonitoringState.isCameraPauseActive.value) return@listenPredictions
                                val receivedAtMs = System.currentTimeMillis()
                                PhoneMonitoringState.recordPredictionLatency(prediction, receivedAtMs)
                                processPrediction(prediction)
                            }
                        }
                    }
                }
                if (!PhoneMonitoringState.isPredictionReceiverEnabled.value) break
                val failure = result.exceptionOrNull()
                if (failure is AuthenticationRequiredException || (failure is ApiHttpException && failure.statusCode == 401)) {
                    handleAuthenticationFailure()
                    break
                }
                val error = failure?.message ?: "stream closed"
                PhoneMonitoringState.predictionStatus.value = "백그라운드 SSE 재연결 대기: $error"
                delay(PREDICTION_RECONNECT_DELAY_MS)
            }
        }
    }

    private fun processPrediction(prediction: CravingPrediction) {
        val canNotify = MobileAuthRuntime.state.value.canNotify
        val action = AlertActionPolicy.resolve(prediction)
        val alertClaimed = canNotify && PhoneMonitoringState.registerAlertAction(prediction, action)
        PhoneMonitoringState.publishPrediction(prediction)
        if (NotificationPresentationPolicy.shouldPresent(canNotify, alertClaimed)) handleAlert(action)
        val sentToWatch = predictionSender.sendPrediction(
            prediction = prediction,
            suppressAlertPresentation = NotificationPresentationPolicy.suppressWatchPresentation(
                canNotify = canNotify,
                interventionActive = PhoneMonitoringState.isInterventionActive.value
            )
        )
        PhoneMonitoringState.predictionStatus.value = if (sentToWatch) {
            "백그라운드 예측 수신, 워치 전달 완료"
        } else {
            "백그라운드 예측 수신, 워치 미연결"
        }
    }

    private fun handleAlert(action: AlertAction) {
        when (action) {
            AlertAction.RECOMMEND -> alertNotifier.showClassOneAlert()
            AlertAction.REQUIRED -> {
                alertNotifier.showClassTwoAlert(launchScreen = false)
                alertNotifier.openStateCheckScreen()
            }
            AlertAction.NONE, AlertAction.COOLDOWN -> Unit
        }
    }

    private fun buildServerPayload(): ServerWindowPayload? {
        ensureActiveSession()
        val session = PhoneMonitoringState.currentSession()
        val windowEndMs = synchronizedWindowEnd() ?: return null
        val windowStartMs = windowEndMs - SERVER_WINDOW_MS

        val samples = buildList {
            addWindowSamples("HR", allHrRaw, windowStartMs, windowEndMs)
            addAll(resampleFixedGrid("PPG_GREEN", allPpgRaw, windowStartMs, PPG_SAMPLE_INTERVAL_MS, PPG_SAMPLE_COUNT))
            addAll(resampleFixedGrid("PPG_IR", allPpgIrRaw, windowStartMs, PPG_SAMPLE_INTERVAL_MS, PPG_SAMPLE_COUNT))
            addAll(resampleFixedGrid("PPG_RED", allPpgRedRaw, windowStartMs, PPG_SAMPLE_INTERVAL_MS, PPG_SAMPLE_COUNT))
            addAll(resampleFixedGrid("EDA", allEdaRaw, windowStartMs, EDA_SAMPLE_INTERVAL_MS, EDA_SAMPLE_COUNT))
            addWindowSamples("ACCEL_X", allAccelXRaw, windowStartMs, windowEndMs)
            addWindowSamples("ACCEL_Y", allAccelYRaw, windowStartMs, windowEndMs)
            addWindowSamples("ACCEL_Z", allAccelZRaw, windowStartMs, windowEndMs)
            addWindowSamples("SKIN_TEMP", allSkinTempRaw, windowStartMs, windowEndMs)
        }.sortedBy { it.timestampMs }

        if (samples.isEmpty()) return null
        uploadSequence += 1
        return ServerWindowPayload(
            clientWindowId = windowIdStore.getOrCreate(uploadKey(session.sessionId, uploadSequence)),
            sessionId = session.sessionId,
            sessionStartedAtMs = session.startedAtMs,
            sequence = uploadSequence,
            sentAtMs = System.currentTimeMillis(),
            windowStartMs = windowStartMs,
            windowEndMs = windowEndMs,
            windowMs = SERVER_WINDOW_MS,
            samples = samples,
            sync = ServerWindowSync(
                mode = "fixed_grid_ppg_25hz_eda_1hz_continuous",
                fillMode = "linear_interpolation_nearest_edge_hold",
                ppgHz = 25,
                ppgSamplesPerChannel = PPG_SAMPLE_COUNT,
                edaHz = 1,
                edaSamples = EDA_SAMPLE_COUNT
            )
        )
    }

    private fun synchronizedWindowEnd(): Long? {
        if (allPpgRaw.isEmpty() || allPpgIrRaw.isEmpty() || allPpgRedRaw.isEmpty() || allEdaRaw.isEmpty()) {
            return null
        }
        val latest = latestTimestamp() ?: return null
        return latest - (latest % EDA_SAMPLE_INTERVAL_MS)
    }

    private fun resampleFixedGrid(
        sensor: String,
        source: List<Pair<Long, Float>>,
        windowStartMs: Long,
        intervalMs: Long,
        count: Int
    ): List<ServerSensorSample> {
        val sorted = source.asSequence()
            .distinctBy { it.first }
            .sortedBy { it.first }
            .toList()
        if (sorted.isEmpty()) return emptyList()

        val samples = ArrayList<ServerSensorSample>(count)
        var cursor = 0
        repeat(count) { index ->
            val targetTs = windowStartMs + index * intervalMs
            while (cursor < sorted.lastIndex && sorted[cursor + 1].first <= targetTs) {
                cursor += 1
            }

            val before = sorted[cursor]
            val after = sorted.getOrNull(cursor + 1)
            val value = when {
                before.first == targetTs -> before.second
                before.first > targetTs -> before.second
                after == null -> before.second
                after.first == before.first -> before.second
                after.first - before.first > intervalMs * 3 -> {
                    val beforeDistance = targetTs - before.first
                    val afterDistance = after.first - targetTs
                    if (beforeDistance <= afterDistance) before.second else after.second
                }
                else -> {
                    val ratio = (targetTs - before.first).toFloat() / (after.first - before.first).toFloat()
                    before.second + (after.second - before.second) * ratio
                }
            }
            samples += ServerSensorSample(sensor, targetTs, value)
        }
        return samples
    }

    private fun MutableList<ServerSensorSample>.addWindowSamples(
        sensor: String,
        source: List<Pair<Long, Float>>,
        windowStartMs: Long,
        windowEndMs: Long
    ) {
        source.asReversed().asSequence()
            .takeWhile { (ts, _) -> ts >= windowStartMs }
            .filter { (ts, _) -> ts <= windowEndMs }
            .toList()
            .asReversed()
            .forEach { (ts, value) -> add(ServerSensorSample(sensor, ts, value)) }
    }

    private fun latestTimestamp(): Long? =
        listOfNotNull(
            allHrRaw.lastOrNull()?.first,
            allPpgRaw.lastOrNull()?.first,
            allPpgIrRaw.lastOrNull()?.first,
            allPpgRedRaw.lastOrNull()?.first,
            allEdaRaw.lastOrNull()?.first,
            allAccelXRaw.lastOrNull()?.first,
            allAccelYRaw.lastOrNull()?.first,
            allAccelZRaw.lastOrNull()?.first,
            allSkinTempRaw.lastOrNull()?.first
        ).maxOrNull()

    private fun ensureActiveSession() {
        val sessionId = PhoneMonitoringState.currentSession().sessionId
        if (sessionId == observedSessionId) return

        observedSessionId = sessionId
        uploadSequence = 0L
        pendingUpload = null
        allHrRaw.clear()
        allPpgRaw.clear()
        allPpgIrRaw.clear()
        allPpgRedRaw.clear()
        allEdaRaw.clear()
        allAccelXRaw.clear()
        allAccelYRaw.clear()
        allAccelZRaw.clear()
        allSkinTempRaw.clear()
    }

    private fun buildNotification(text: String): Notification {
        ensureChannel()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle("상태 모니터")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "백그라운드 모니터링",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "워치 데이터 수신, 서버 전송, 예측 수신 유지"
            }
        )
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HealthSensor:phone-monitor")
            .apply { acquire(60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
    }

    private fun uploadKey(payload: ServerWindowPayload): String =
        uploadKey(payload.sessionId, payload.sequence)

    private fun uploadKey(sessionId: String, sequence: Long): String =
        "service:$sessionId:$sequence"

    private fun handleAuthenticationFailure() {
        MobileAuthRuntime.signOut()
        apiClient.clearSession()
        PhoneMonitoringState.isUploadEnabled.value = false
        PhoneMonitoringState.isPredictionReceiverEnabled.value = false
        stopSelf()
    }

    companion object {
        private const val CHANNEL_ID = "phone_monitoring"
        private const val NOTIFICATION_ID = 3001
        private const val SERVER_WINDOW_MS = 20_000L
        private const val SERVER_UPLOAD_INTERVAL_MS = 10_000L
        private const val AUTO_COMMUNICATION_DELAY_MS = 20_000L
        private const val PREDICTION_RECONNECT_DELAY_MS = 2_000L
        private const val RETAIN_RAW_MS = 60_000L
        private const val PPG_SAMPLE_INTERVAL_MS = 40L
        private const val PPG_SAMPLE_COUNT = 500
        private const val EDA_SAMPLE_INTERVAL_MS = 1_000L
        private const val EDA_SAMPLE_COUNT = 20
        fun start(context: Context) {
            if (!MobileAuthRuntime.canUpload()) return
            val appContext = context.applicationContext
            val intent = Intent(appContext, PhoneMonitoringService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            PhoneMonitoringState.isUploadEnabled.value = false
            PhoneMonitoringState.isPredictionReceiverEnabled.value = false
            appContext.stopService(Intent(appContext, PhoneMonitoringService::class.java))
        }
    }
}
