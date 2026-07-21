package com.neurotruth.mobile.service

import android.util.Log
import com.neurotruth.mobile.core.ClientWindowKey
import com.neurotruth.mobile.core.OfflineWindowQueue
import com.neurotruth.mobile.core.SensorWindowContract
import com.neurotruth.mobile.core.UploadFailureAction
import com.neurotruth.mobile.core.UploadFailurePolicy
import com.neurotruth.mobile.core.net.ApiEndpoints
import com.neurotruth.mobile.core.net.ApiRequest
import com.neurotruth.mobile.core.net.AuthenticatedApiClient
import com.neurotruth.mobile.core.net.AuthenticationRequiredException
import com.neurotruth.mobile.core.net.FixedGridResampler
import com.neurotruth.mobile.core.net.SensorChannel
import com.neurotruth.mobile.core.net.SensorWindowPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives the sensor upload cadence of PRD §5.1: a 20-second warm-up, then the latest 20-second
 * window every 10 seconds.
 *
 * The phone owns the fixed grid. The watch emits at its native tracker rates and this class
 * resamples to exactly 500 PPG samples per channel at 40 ms spacing and 20 EDA samples at 1000 ms
 * spacing through [FixedGridResampler], which is what the payload's `sync` block declares.
 *
 * Failure handling is not decided here — [UploadFailurePolicy] resolves the status code and this
 * class only carries the action out. Retries reuse the persisted `clientWindowId` and a
 * byte-identical body, and they are bounded by [OfflineWindowQueue] (60 windows, oldest evicted
 * first) because each window carries 1,500 PPG samples plus 20 EDA samples.
 */
class SensorWindowScheduler(
    private val apiClient: AuthenticatedApiClient,
    private val endpoints: ApiEndpoints,
    private val windowIdStore: PersistentClientWindowIdStore,
    private val listener: Listener,
    private val scope: CoroutineScope,
    private val repository: SensorSampleRepository = SensorSampleRepository,
    private val queue: OfflineWindowQueue<SensorWindowPayload> = OfflineWindowQueue(),
    private val now: () -> Long = System::currentTimeMillis,
) {

    interface Listener {
        /** The upload succeeded; the body may carry a prediction and alert metadata. */
        fun onWindowUploaded(responseBody: String)

        /** Windows were evicted from the bounded retry queue. */
        fun onWindowsDropped(notice: String)

        /** Refresh is exhausted: tear down and return to login. */
        fun onAuthenticationRequired()

        /** Consent or validation rejected the window; stop hammering the server. */
        fun onUploadPaused(statusCode: Int)
    }

    @Volatile
    private var job: Job? = null

    @Volatile
    private var paused = false

    fun start() {
        if (job?.isActive == true) return
        paused = false
        job = scope.launch(Dispatchers.IO) {
            // The first window cannot exist before 20 seconds of samples do.
            delay(SensorWindowContract.WARM_UP_MS)
            while (isActive) {
                if (!paused) {
                    runCatching { tick() }
                        .onFailure { Log.w(TAG, "윈도우 업로드 주기 실패: ${it.message}") }
                }
                delay(SensorWindowContract.UPLOAD_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Ends the monitoring session: the next start mints a new session id and sequence. */
    fun reset() {
        stop()
        queue.clear()
        windowIdStore.clear()
        repository.clear()
    }

    private fun tick() {
        drainQueue()
        if (paused) return
        val payload = buildWindow() ?: return
        upload(payload)
    }

    /** Queued windows go first so the server sees them in the order they were measured. */
    private fun drainQueue() {
        while (!paused) {
            val queued = queue.peek() ?: return
            if (!upload(queued, fromQueue = true)) return
        }
    }

    private fun buildWindow(): SensorWindowPayload? {
        val windowEndMs = now()
        val windowStartMs = windowEndMs - SensorWindowContract.WINDOW_MS
        if (!repository.hasRequiredChannelsIn(windowStartMs, windowEndMs)) return null

        // A little slack before the window start gives the resampler a bracketing sample to
        // interpolate from at index 0 instead of edge-holding the whole first interval.
        val raw = repository.samplesIn(
            windowStartMs - SensorWindowContract.EDA_SAMPLE_INTERVAL_MS * 2,
            windowEndMs,
        )

        val samples = buildList {
            for (channel in PPG_CHANNELS) {
                addAll(
                    FixedGridResampler.resample(
                        samples = raw,
                        sensor = channel,
                        windowStartMs = windowStartMs,
                        intervalMs = SensorWindowContract.PPG_SAMPLE_INTERVAL_MS,
                        count = SensorWindowContract.PPG_SAMPLES_PER_CHANNEL,
                    ),
                )
            }
            addAll(
                FixedGridResampler.resample(
                    samples = raw,
                    sensor = SensorChannel.EDA,
                    windowStartMs = windowStartMs,
                    intervalMs = SensorWindowContract.EDA_SAMPLE_INTERVAL_MS,
                    count = SensorWindowContract.EDA_SAMPLES,
                ),
            )
        }
        if (samples.isEmpty()) return null

        val sequence = windowIdStore.nextSequence()
        val key = ClientWindowKey.of(windowIdStore.sessionId(), sequence)
        return SensorWindowPayload(
            clientWindowId = windowIdStore.idFor(key),
            sessionStartedAtMs = windowIdStore.sessionStartedAtMs(windowStartMs),
            sequence = sequence,
            sentAtMs = now(),
            windowStartMs = windowStartMs,
            windowEndMs = windowEndMs,
            samples = samples,
        )
    }

    /** Returns true when this payload is settled — uploaded or deliberately dropped. */
    private fun upload(payload: SensorWindowPayload, fromQueue: Boolean = false): Boolean {
        val response = try {
            apiClient.execute(
                ApiRequest(
                    method = "POST",
                    url = endpoints.sensorWindows,
                    body = payload.toJson(),
                ),
            )
        } catch (error: AuthenticationRequiredException) {
            if (!fromQueue) enqueue(payload)
            listener.onAuthenticationRequired()
            return false
        }

        if (response.isSuccessful) {
            if (fromQueue) queue.poll()
            windowIdStore.release(ClientWindowKey.of(windowIdStore.sessionId(), payload.sequence))
            listener.onWindowUploaded(response.body)
            return true
        }

        return when (UploadFailurePolicy.resolve(response.statusCode)) {
            UploadFailureAction.AUTHENTICATION_REQUIRED -> {
                if (!fromQueue) enqueue(payload)
                listener.onAuthenticationRequired()
                false
            }

            UploadFailureAction.DROP_AND_CONTINUE -> {
                // The server already holds this window with different content. Only this one goes.
                if (fromQueue) queue.poll()
                true
            }

            UploadFailureAction.RETRY_SAME_PAYLOAD -> {
                if (!fromQueue) enqueue(payload)
                false
            }

            UploadFailureAction.PAUSE_AND_DROP -> {
                if (fromQueue) queue.poll()
                paused = true
                listener.onUploadPaused(response.statusCode)
                false
            }
        }
    }

    private fun enqueue(payload: SensorWindowPayload) {
        if (!queue.offer(payload)) {
            listener.onWindowsDropped(OfflineWindowQueue.DROPPED_NOTICE)
        }
    }

    private companion object {
        const val TAG = "SensorWindowScheduler"

        val PPG_CHANNELS = listOf(
            SensorChannel.PPG_GREEN,
            SensorChannel.PPG_IR,
            SensorChannel.PPG_RED,
        )
    }
}
