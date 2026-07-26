package com.neurotruth.mobile.data

import com.neurotruth.mobile.core.SensorWindowContract
import com.neurotruth.mobile.core.net.FixedGridResampler
import com.neurotruth.mobile.core.net.SensorChannel
import com.neurotruth.mobile.core.net.SensorSample
import com.neurotruth.mobile.service.SensorSampleRepository

/** Display-only live Watch traces. Raw repository and upload samples are never changed. */
data class LivePpgTrace(
    val samples: List<PpgSample>,
    val edaSamples: List<PpgSample>,
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
 * Pure windowing, fixed-grid interpolation, and per-channel display normalization.
 *
 * Kept free of Android and of the buffer singleton so channel isolation, empty cases, and freshness
 * are covered by JVM unit tests.
 */
object LivePpgWindow {

    /** The same 20-second span and grids used by the existing sensor upload contract. */
    const val WINDOW_MS: Long = SensorWindowContract.WINDOW_MS
    const val PPG_POINTS: Int = SensorWindowContract.PPG_SAMPLES_PER_CHANNEL
    const val EDA_POINTS: Int = SensorWindowContract.EDA_SAMPLES

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
     * Builds independent PPG_GREEN and EDA traces, or null when neither channel is fresh.
     * A missing channel remains an empty list and is never fabricated as a zero waveform.
     */
    fun build(
        samples: List<SensorSample>,
        nowMs: Long,
        windowMs: Long = WINDOW_MS,
        staleAfterMs: Long = STALE_AFTER_MS,
    ): LivePpgTrace? {
        val windowStart = nowMs - windowMs
        val ppgLatest = freshLatest(samples, SensorChannel.PPG_GREEN, nowMs, staleAfterMs)
        val edaLatest = freshLatest(samples, SensorChannel.EDA, nowMs, staleAfterMs)
        if (ppgLatest == null && edaLatest == null) return null

        val ppg = if (ppgLatest == null) {
            emptyList()
        } else {
            displayGrid(
                samples,
                SensorChannel.PPG_GREEN,
                windowStart,
                SensorWindowContract.PPG_SAMPLE_INTERVAL_MS,
                PPG_POINTS,
            )
        }
        val eda = if (edaLatest == null) {
            emptyList()
        } else {
            displayGrid(
                samples,
                SensorChannel.EDA,
                windowStart,
                SensorWindowContract.EDA_SAMPLE_INTERVAL_MS,
                EDA_POINTS,
            )
        }
        if (ppg.isEmpty() && eda.isEmpty()) return null

        return LivePpgTrace(
            samples = ppg,
            edaSamples = eda,
            latestAtMs = listOfNotNull(ppgLatest, edaLatest).max(),
        )
    }

    private fun freshLatest(
        samples: List<SensorSample>,
        sensor: String,
        nowMs: Long,
        staleAfterMs: Long,
    ): Long? = samples.asSequence()
        .filter { it.sensor == sensor && it.value.isFinite() && it.timestampMs <= nowMs }
        .maxOfOrNull(SensorSample::timestampMs)
        ?.takeIf { isFresh(it, nowMs, staleAfterMs) }

    private fun displayGrid(
        samples: List<SensorSample>,
        sensor: String,
        windowStartMs: Long,
        intervalMs: Long,
        count: Int,
    ): List<PpgSample> {
        val finite = samples.filter { it.value.isFinite() }
        val grid = FixedGridResampler.resample(finite, sensor, windowStartMs, intervalMs, count)
        if (grid.isEmpty()) return emptyList()
        val minimum = grid.minOf(SensorSample::value)
        val amplitude = grid.maxOf(SensorSample::value) - minimum
        return grid.map { sample ->
            PpgSample(
                atMs = sample.timestampMs,
                value = if (amplitude > 0f) (sample.value - minimum) / amplitude else 0f,
            )
        }
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
            samples = repository.samplesIn(
                nowMs - LivePpgWindow.WINDOW_MS -
                    SensorWindowContract.EDA_SAMPLE_INTERVAL_MS * 2,
                nowMs,
            ),
            nowMs = nowMs,
        )
}
