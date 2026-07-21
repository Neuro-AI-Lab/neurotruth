package com.neurotruth.mobile.core

enum class WatchConnectionState {
    CONNECTED,
    DISCONNECTED,
    CHECKING,
    ERROR,
    ;

    /** Only a confirmed disconnection enables the camera action. */
    val isConfirmedDisconnected: Boolean
        get() = this == DISCONNECTED
}

/**
 * Turns raw connected-node queries into a Home-facing state.
 *
 * An empty node list is the only signal for disconnection, and it must be observed twice in a row
 * at least [CONFIRM_WINDOW_MS] apart before Home acts on it. A failed query is [ERROR], never
 * disconnection — treating it as disconnected would enable camera capture while a Watch is in fact
 * streaming.
 */
class WatchConnectionTracker(
    private val confirmWindowMs: Long = CONFIRM_WINDOW_MS,
) {
    private var firstEmptyAtMs: Long? = null
    private var state: WatchConnectionState = WatchConnectionState.CHECKING

    @Synchronized
    fun current(): WatchConnectionState = state

    @Synchronized
    fun onNodesQueried(nowMs: Long, connectedNodeCount: Int): WatchConnectionState {
        state = if (connectedNodeCount > 0) {
            firstEmptyAtMs = null
            WatchConnectionState.CONNECTED
        } else {
            val firstSeen = firstEmptyAtMs
            if (firstSeen == null) {
                firstEmptyAtMs = nowMs
                WatchConnectionState.CHECKING
            } else if (nowMs - firstSeen >= confirmWindowMs) {
                WatchConnectionState.DISCONNECTED
            } else {
                WatchConnectionState.CHECKING
            }
        }
        return state
    }

    @Synchronized
    fun onQueryFailed(): WatchConnectionState {
        firstEmptyAtMs = null
        state = WatchConnectionState.ERROR
        return state
    }

    /** A peer event is a hint to re-query, not an authoritative state change. */
    @Synchronized
    fun onPeerEvent(): WatchConnectionState {
        firstEmptyAtMs = null
        state = WatchConnectionState.CHECKING
        return state
    }

    companion object {
        const val CONFIRM_WINDOW_MS: Long = 3_000L
    }
}

/** Why the Home camera action is enabled or not. */
sealed class CameraAvailability {
    object Available : CameraAvailability()
    data class Blocked(val reason: String) : CameraAvailability()
}

object CameraActionPolicy {
    const val REASON_WATCH_CONNECTED: String = "Watch 연결을 해제해야 카메라로 측정할 수 있어요."
    const val REASON_WATCH_UNKNOWN: String = "Watch 연결 상태를 확인하고 있어요."
    const val REASON_CONSENT: String = "설정에서 얼굴 측정 관련 동의를 켜 주세요."
    const val REASON_PERMISSION: String = "카메라 권한이 필요해요."
    const val REASON_SERVICE: String = "얼굴 측정 서비스를 지금 사용할 수 없어요."

    fun resolve(
        watchState: WatchConnectionState,
        gates: ConsentGates,
        cameraPermissionGranted: Boolean,
        rppgServiceReady: Boolean,
    ): CameraAvailability = when {
        watchState == WatchConnectionState.CONNECTED -> CameraAvailability.Blocked(REASON_WATCH_CONNECTED)
        !watchState.isConfirmedDisconnected -> CameraAvailability.Blocked(REASON_WATCH_UNKNOWN)
        !gates.canCaptureRppg -> CameraAvailability.Blocked(REASON_CONSENT)
        !cameraPermissionGranted -> CameraAvailability.Blocked(REASON_PERMISSION)
        !rppgServiceReady -> CameraAvailability.Blocked(REASON_SERVICE)
        else -> CameraAvailability.Available
    }
}
