package com.example.healthsensor

import android.content.Context
import com.google.android.gms.wearable.Wearable
import java.io.Closeable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class WatchConnectionState {
    CHECKING,
    CONNECTED,
    DISCONNECTED,
    ERROR
}

internal interface WatchNodeSource {
    fun query(onSuccess: (Int) -> Unit, onFailure: (Throwable) -> Unit)
}

private class WearableWatchNodeSource(context: Context) : WatchNodeSource {
    private val nodeClient = Wearable.getNodeClient(context.applicationContext)

    override fun query(onSuccess: (Int) -> Unit, onFailure: (Throwable) -> Unit) {
        nodeClient.connectedNodes
            .addOnSuccessListener { nodes -> onSuccess(nodes.size) }
            .addOnFailureListener(onFailure)
    }
}

internal object WatchPeerConnectionEvents {
    private val listeners = linkedSetOf<() -> Unit>()

    fun subscribe(listener: () -> Unit): Closeable {
        synchronized(listeners) { listeners += listener }
        return Closeable { synchronized(listeners) { listeners -= listener } }
    }

    fun publish() {
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { it() }
    }
}

internal enum class RppgAvailability {
    READY,
    CONSENT_REQUIRED,
    CHECKING,
    DISABLED,
    SERVICE_UNAVAILABLE,
    MODEL_NOT_READY
}

internal data class WatchRppgPresentation(
    val watchLabel: String,
    val guidance: String,
    val primaryAction: Boolean,
    val actionEnabled: Boolean,
    val actionLabel: String
)

internal object WatchRppgPresentationPolicy {
    fun resolve(
        watchState: WatchConnectionState,
        canCaptureRppg: Boolean,
        serviceStatus: RppgServiceStatus?
    ): WatchRppgPresentation {
        val availability = when {
            !canCaptureRppg -> RppgAvailability.CONSENT_REQUIRED
            serviceStatus == null -> RppgAvailability.CHECKING
            !serviceStatus.enabled -> RppgAvailability.DISABLED
            !serviceStatus.available -> RppgAvailability.SERVICE_UNAVAILABLE
            !serviceStatus.modelLoaded -> RppgAvailability.MODEL_NOT_READY
            else -> RppgAvailability.READY
        }
        val guidance = when (availability) {
            RppgAvailability.CONSENT_REQUIRED ->
                "얼굴 측정에는 생체신호·AI 분석·카메라 rPPG·얼굴 영상 보관 동의가 필요합니다."
            RppgAvailability.CHECKING ->
                "얼굴 측정 서비스 준비 상태를 확인하는 중입니다. 필요하면 상태를 다시 확인할 수 있습니다."
            RppgAvailability.DISABLED,
            RppgAvailability.SERVICE_UNAVAILABLE,
            RppgAvailability.MODEL_NOT_READY ->
                "현재 얼굴 측정을 사용할 수 없습니다. 상태를 다시 확인해 주세요."
            RppgAvailability.READY -> when (watchState) {
                WatchConnectionState.CONNECTED ->
                    "Watch 모니터링 중에는 얼굴 측정을 사용할 수 없습니다. Watch 연결을 해제한 뒤 다시 시도해 주세요."
                WatchConnectionState.DISCONNECTED ->
                    "Watch가 연결되지 않았습니다. 20초 얼굴 측정으로 현재 상태를 확인할 수 있습니다."
                WatchConnectionState.CHECKING ->
                    "Watch 연결을 확인한 뒤 얼굴 측정 사용 여부를 안내합니다."
                WatchConnectionState.ERROR ->
                    "Watch 연결 상태를 확인하지 못했습니다. 연결 상태를 다시 확인해 주세요."
            }
        }
        return WatchRppgPresentation(
            watchLabel = when (watchState) {
                WatchConnectionState.CHECKING -> "확인 중"
                WatchConnectionState.CONNECTED -> "연결됨"
                WatchConnectionState.DISCONNECTED -> "연결 안 됨"
                WatchConnectionState.ERROR -> "확인 실패"
            },
            guidance = guidance,
            primaryAction = watchState == WatchConnectionState.DISCONNECTED,
            actionEnabled = availability == RppgAvailability.READY &&
                watchState == WatchConnectionState.DISCONNECTED,
            actionLabel = when (availability) {
                RppgAvailability.READY -> when (watchState) {
                    WatchConnectionState.DISCONNECTED -> "20초 얼굴 측정 시작"
                    WatchConnectionState.CONNECTED -> "Watch 연결 중 측정 불가"
                    else -> "Watch 연결 확인 후 측정"
                }
                RppgAvailability.CONSENT_REQUIRED -> "동의 후 얼굴 측정"
                else -> "상태 다시 확인"
            }
        )
    }
}

internal class WatchConnectionMonitor(
    private val nodeSource: WatchNodeSource,
    peerEvents: ((() -> Unit) -> Closeable),
    private val onDisconnected: () -> Unit = {}
) : Closeable {
    constructor(context: Context, onDisconnected: () -> Unit = {}) : this(
        WearableWatchNodeSource(context),
        WatchPeerConnectionEvents::subscribe,
        onDisconnected
    )

    private val _state = MutableStateFlow(WatchConnectionState.CHECKING)
    val state: StateFlow<WatchConnectionState> = _state
    private val lock = Any()
    private var requestVersion = 0L
    private var closed = false
    private val peerSubscription = peerEvents(::refresh)

    init {
        refresh()
    }

    private fun refresh() {
        val version = synchronized(lock) {
            if (closed) return
            _state.value = WatchConnectionState.CHECKING
            ++requestVersion
        }
        nodeSource.query(
            onSuccess = { count -> complete(version, count) },
            onFailure = { fail(version) }
        )
    }

    private fun complete(version: Long, connectedNodeCount: Int) {
        val disconnected = synchronized(lock) {
            if (closed || version != requestVersion) return
            val isDisconnected = connectedNodeCount == 0
            _state.value = if (isDisconnected) {
                WatchConnectionState.DISCONNECTED
            } else {
                WatchConnectionState.CONNECTED
            }
            isDisconnected
        }
        if (disconnected) onDisconnected()
    }

    private fun fail(version: Long) {
        synchronized(lock) {
            if (!closed && version == requestVersion) _state.value = WatchConnectionState.ERROR
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            requestVersion += 1L
        }
        peerSubscription.close()
    }
}
