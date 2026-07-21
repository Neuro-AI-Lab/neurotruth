package com.neurotruth.mobile.data

import com.neurotruth.mobile.core.SensorWindowContract
import com.neurotruth.mobile.core.net.SensorChannel
import com.neurotruth.mobile.core.net.SensorSample
import com.neurotruth.mobile.service.SensorSampleRepository

/** A live PPG_GREEN trace. [latestAtMs] is what freshness is judged on, never the render time. */
data class LivePpgTrace(
    val samples: List<PpgSample>,
    val latestAtMs: Long,
)

/**
 * What the live half of the PPG section can show right now.
 *
 * [Waiting] and [Unavailable] are distinct so the copy can say which is true, and neither is ever
 * drawn as a flat zero line. A buffer that has stopped advancing degrades to [Waiting] rather than
 * continuing to present old samples as a live trace.
 */
sealed interface LivePpgState {
    /** No confirmed watch connection. */
    object Unavailable : LivePpgState

    /** Connected or still checking, but nothing fresh has arrived. */
    object Waiting : LivePpgState

    data class Streaming(val trace: LivePpgTrace) : LivePpgState
}

/**
 * Pure windowing and downsampling for the live trace.
 *
 * Kept free of Android and of the buffer singleton so the 512 cap, the empty case and the staleness
 * rule are covered by JVM unit tests.
 */
object LivePpgWindow {

    /** The same display cap the server applies to a stored preview, so the two read alike. */
    const val MAX_POINTS: Int = 512

    /** The 20-second span the upload contract already windows on: 25 Hz × 20 s = 500 samples. */
    const val WINDOW_MS: Long = SensorWindowContract.WINDOW_MS

    /**
     * Two watch flushes' worth of slack. Past this the newest sample is no longer "live" and the
     * section stops claiming it is, rather than leaving a frozen waveform on screen.
     */
    const val STALE_AFTER_MS: Long = 3_000L

    fun isFresh(latestAtMs: Long?, nowMs: Long, staleAfterMs: Long = STALE_AFTER_MS): Boolean {
        if (latestAtMs == null) return false
        val age = nowMs - latestAtMs
        // A sample from the near future is clock skew, not staleness; only the past is judged.
        return age <= staleAfterMs
    }

    /**
     * Evenly spaced index selection, matching how the server downsamples a stored preview. Fewer
     * points than the cap are returned untouched — no padding and no interpolation.
     */
    fun downsample(samples: List<PpgSample>, maxPoints: Int = MAX_POINTS): List<PpgSample> {
        require(maxPoints > 1) { "maxPoints must be greater than 1" }
        if (samples.size <= maxPoints) return samples
        val last = samples.size - 1
        return (0 until maxPoints).map { index ->
            samples[Math.round(index.toDouble() * last / (maxPoints - 1)).toInt()]
        }
    }

    /**
     * Builds the trace from raw watch samples, or returns null when there is nothing live to draw.
     *
     * Only [SensorChannel.PPG_GREEN] is plotted: it is the channel the waveform is specified against,
     * and mixing channels of different scales onto one axis would misrepresent all of them.
     */
    fun build(
        samples: List<SensorSample>,
        nowMs: Long,
        windowMs: Long = WINDOW_MS,
        maxPoints: Int = MAX_POINTS,
        staleAfterMs: Long = STALE_AFTER_MS,
    ): LivePpgTrace? {
        val windowStart = nowMs - windowMs
        val green = samples
            .asSequence()
            .filter { it.sensor == SensorChannel.PPG_GREEN }
            .filter { it.timestampMs in windowStart..nowMs }
            .sortedBy(SensorSample::timestampMs)
            .map { PpgSample(atMs = it.timestampMs, value = it.value) }
            .toList()
        if (green.isEmpty()) return null

        val latest = green.last().atMs
        if (!isFresh(latest, nowMs, staleAfterMs)) return null

        return LivePpgTrace(samples = downsample(green, maxPoints), latestAtMs = latest)
    }
}

/**
 * Reads the live watch buffer for the dashboard.
 *
 * The buffer is owned by the services layer, which the Data Layer listener feeds; this only reads a
 * bounded recent window from it. Nothing is copied, cached or persisted — each poll rebuilds the
 * trace from scratch and the previous one is dropped.
 */
class LivePpgSource(
    private val repository: SensorSampleRepository = SensorSampleRepository,
) {
    fun trace(nowMs: Long): LivePpgTrace? =
        LivePpgWindow.build(
            samples = repository.samplesIn(nowMs - LivePpgWindow.WINDOW_MS, nowMs),
            nowMs = nowMs,
        )
}
