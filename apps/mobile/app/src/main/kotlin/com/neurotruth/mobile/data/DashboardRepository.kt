package com.neurotruth.mobile.data

import com.neurotruth.mobile.core.Auq
import com.neurotruth.mobile.core.CravingStage
import com.neurotruth.mobile.core.net.ApiEndpoints
import com.neurotruth.mobile.core.net.ApiHttpException
import com.neurotruth.mobile.core.net.ApiRequest
import com.neurotruth.mobile.core.net.AuthenticatedApiClient
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import org.json.JSONObject

/**
 * One 10-second bucket of `GET /api/me/craving-probability-series`.
 *
 * [predictionId] is read only when the server supplies it. The bucket is an average over a window,
 * so there is no id to invent when the field is absent — the PPG section says a measurement has to
 * be selected first rather than guessing one.
 */
data class CravingSeriesPoint(
    val atMs: Long,
    val probability: Float,
    val sampleCount: Int,
    val predictionId: String? = null,
) {
    val stage: CravingStage get() = CravingStage.of(probability)
}

/**
 * The recent-hour series.
 *
 * The server omits empty buckets and never interpolates them, so absence here is real absence. The
 * chart therefore consumes [segments] rather than [points]: a run breaks wherever two neighbouring
 * samples are more than one bucket apart, and the renderer draws no line across the break.
 */
data class CravingSeries(
    val range: String,
    val bucketSeconds: Int,
    val fromMs: Long,
    val toMs: Long,
    val points: List<CravingSeriesPoint>,
) {
    val hasData: Boolean get() = points.isNotEmpty()

    /** The latest real sample. The chart's end point must be this same sample, never a padded one. */
    val latest: CravingSeriesPoint? get() = points.lastOrNull()

    /** Points the PPG section can preview. Empty means there is no selection path at all. */
    val selectablePoints: List<CravingSeriesPoint>
        get() = points.filter { it.predictionId != null }

    val hasSelectablePoints: Boolean get() = points.any { it.predictionId != null }

    /**
     * Contiguous runs. Two points belong to the same run only when they are exactly one bucket
     * apart; anything wider is a measurement gap and stays a gap on screen.
     */
    fun segments(): List<List<CravingSeriesPoint>> {
        if (points.isEmpty()) return emptyList()
        val bucketMs = bucketSeconds * 1_000L
        val runs = ArrayList<List<CravingSeriesPoint>>()
        var current = ArrayList<CravingSeriesPoint>().apply { add(points.first()) }
        for (index in 1 until points.size) {
            val previous = points[index - 1]
            val point = points[index]
            val delta = point.atMs - previous.atMs
            if (delta in 1..bucketMs) {
                current.add(point)
            } else {
                runs.add(current)
                current = ArrayList<CravingSeriesPoint>().apply { add(point) }
            }
        }
        runs.add(current)
        return runs
    }
}

/** One local hour of raw stage counts for the count-scaled stacked bar. */
data class HourlyCravingBucket(
    val hour: Int,
    val localStartIso: String,
    val sampleCount: Int,
    val stageCounts: Map<CravingStage, Int>,
) {
    val countedSamples: Int get() = stageCounts.values.sum()

    val hasData: Boolean get() = sampleCount > 0 && countedSamples > 0

    val hourLabel: String get() = "%02d:00-%02d:59".format(hour, hour)
}

/**
 * One day of the craving-event chart.
 *
 * [hasPredictionData] is the whole point of this type: a day with no prediction at all and a day
 * whose predictions raised no alert are different facts and must not collapse into the same bar.
 */
data class DailyEventBucket(
    val localDate: String,
    val hasPredictionData: Boolean,
    val recommendCount: Int,
    val requiredCount: Int,
    val totalCount: Int,
) {
    /** No prediction ran — an empty span, never a zero. */
    val isNoData: Boolean get() = !hasPredictionData

    /** Predictions ran and none raised an alert — a real zero, drawn as a dot. */
    val isValidZero: Boolean get() = hasPredictionData && totalCount == 0
}

/**
 * One AUQ bucket on the 0..48 UI scale.
 *
 * [averageScore] prefers the server's raw-scale field and falls back to
 * `averageNormalizedScore * 48` only when the raw field is absent.
 */
data class AuqBucket(
    val label: String,
    val localKey: String,
    val hour: Int?,
    val averageScore: Float?,
    val sampleCount: Int,
    val usedNormalizedFallback: Boolean,
) {
    val hasData: Boolean get() = sampleCount > 0 && averageScore != null
}

data class CravingDashboard(
    val timezone: String,
    val generatedAtIso: String,
    val currentProbability: Float?,
    val currentAtIso: String?,
    val hourly: List<HourlyCravingBucket>,
    val eventRange: String,
    val events: List<DailyEventBucket>,
    val auqRange: String,
    val auqBucketUnit: String,
    val auq: List<AuqBucket>,
) {
    val hasHourlyData: Boolean get() = hourly.any { it.hasData }
    val hasEventData: Boolean get() = events.any { it.hasPredictionData }
    val hasAuqData: Boolean get() = auq.any { it.hasData }
}

/**
 * One hourly or daily bucket of the patient-selected local day, week, or month.
 *
 * The server includes empty buckets. [hasPredictionData] distinguishes a measurement-free bucket
 * from a measured bucket whose alert count is a valid zero.
 */
data class CravingCalendarBucket(
    val localKey: String,
    val label: String,
    val hasPredictionData: Boolean,
    val sampleCount: Int,
    val stageCounts: Map<CravingStage, Int>,
    val eventCount: Int,
    val auqAverageScore: Float?,
    val auqResponseCount: Int,
) {
    val countedSamples: Int get() = stageCounts.values.sum()
    val hasStageData: Boolean get() = hasPredictionData && countedSamples > 0
    val hasAuqData: Boolean get() = auqAverageScore != null && auqResponseCount > 0
}

data class CravingCalendar(
    val timezone: String,
    val view: String,
    val anchor: String,
    val bucketUnit: String,
    val localStartIso: String,
    val localEndIso: String,
    val buckets: List<CravingCalendarBucket>,
) {
    val hasStageData: Boolean get() = buckets.any { it.hasStageData }
    val hasAuqData: Boolean get() = buckets.any { it.hasAuqData }
}

/** `GET /api/me/craving-probability-series`. */
object CravingProbabilitySeriesParser {

    /** `range=1h` is 60 minutes of 10-second buckets, capped by the server at 360 points. */
    const val MAX_POINTS_1H: Int = 360

    fun parse(body: String): CravingSeries {
        val root = JSONObject(body)
        val bucketSeconds = root.optInt("bucketSeconds", 10).coerceAtLeast(1)
        val fromMs = epochMillisOrNull(root.optString("from")) ?: 0L
        val toMs = epochMillisOrNull(root.optString("to")) ?: 0L
        val array = root.optJSONArray("points")
        val points = ArrayList<CravingSeriesPoint>()
        for (index in 0 until (array?.length() ?: 0)) {
            val item = array?.optJSONObject(index) ?: continue
            val atMs = epochMillisOrNull(item.optString("at")) ?: continue
            if (item.isNull("averageCravingProbability")) continue
            val value = item.optDouble("averageCravingProbability")
            if (value.isNaN()) continue
            points.add(
                CravingSeriesPoint(
                    atMs = atMs,
                    probability = value.coerceIn(0.0, 1.0).toFloat(),
                    sampleCount = item.optInt("sampleCount", 0),
                    predictionId = stringOrNull(item, "predictionId"),
                ),
            )
        }
        points.sortBy(CravingSeriesPoint::atMs)
        val capped = if (points.size > MAX_POINTS_1H) {
            points.subList(points.size - MAX_POINTS_1H, points.size).toList()
        } else {
            points.toList()
        }
        return CravingSeries(
            range = root.optString("range", "1h"),
            bucketSeconds = bucketSeconds,
            fromMs = fromMs,
            toMs = toMs,
            points = capped,
        )
    }
}

/** `GET /api/me/craving-dashboard`. */
object CravingDashboardParser {

    fun parse(body: String): CravingDashboard {
        val root = JSONObject(body)
        val current = root.optJSONObject("currentCraving")
        return CravingDashboard(
            timezone = root.optString("timezone"),
            generatedAtIso = root.optString("generatedAt"),
            currentProbability = current?.let { floatOrNull(it, "probability") }
                ?.coerceIn(0f, 1f),
            currentAtIso = current?.let { stringOrNull(it, "at") },
            hourly = parseHourly(root.optJSONObject("hourlyCraving")),
            eventRange = root.optJSONObject("dailyEvents")?.optString("range").orEmpty()
                .ifBlank { "7d" },
            events = parseEvents(root.optJSONObject("dailyEvents")),
            auqRange = root.optJSONObject("auq")?.optString("range").orEmpty().ifBlank { "today" },
            auqBucketUnit = root.optJSONObject("auq")?.optString("bucketUnit").orEmpty()
                .ifBlank { "day" },
            auq = parseAuq(root.optJSONObject("auq")),
        )
    }

    private fun parseHourly(node: JSONObject?): List<HourlyCravingBucket> {
        val array = node?.optJSONArray("buckets") ?: return emptyList()
        val buckets = ArrayList<HourlyCravingBucket>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val localStart = item.optString("localStart")
            val counts = item.optJSONObject("stageCounts")
            val stageCounts = CravingStage.entries.associateWith { stage ->
                counts?.optInt(stage.stageCountsKey, 0) ?: 0
            }
            buckets.add(
                HourlyCravingBucket(
                    hour = hourOf(localStart) ?: index,
                    localStartIso = localStart,
                    sampleCount = item.optInt("sampleCount", 0),
                    stageCounts = stageCounts,
                ),
            )
        }
        return buckets
    }

    private fun parseEvents(node: JSONObject?): List<DailyEventBucket> {
        val array = node?.optJSONArray("buckets") ?: return emptyList()
        val buckets = ArrayList<DailyEventBucket>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val recommend = item.optInt("recommendCount", 0)
            val required = item.optInt("requiredCount", 0)
            buckets.add(
                DailyEventBucket(
                    localDate = item.optString("localDate"),
                    // Absent means the server could not say a prediction ran, which is the
                    // no-data case; it must never be read as "predictions ran, zero alerts".
                    hasPredictionData = item.optBoolean("hasPredictionData", false),
                    recommendCount = recommend,
                    requiredCount = required,
                    totalCount = item.optInt("totalCount", recommend + required),
                ),
            )
        }
        return buckets
    }

    private fun parseAuq(node: JSONObject?): List<AuqBucket> {
        val array = node?.optJSONArray("buckets") ?: return emptyList()
        val hourly = node.optString("bucketUnit") == "hour"
        val buckets = ArrayList<AuqBucket>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val localStart = item.optString("localStart")
            val localDate = item.optString("localDate")
            val raw = floatOrNull(item, "averageScore")
            val normalized = floatOrNull(item, "averageNormalizedScore")
            val fallback = raw == null && normalized != null
            val score = raw ?: normalized?.times(Auq.SCALE_MAX)
            val hour = if (hourly) hourOf(localStart) ?: index else null
            buckets.add(
                AuqBucket(
                    label = if (hourly) "%02d시".format(hour ?: index) else shortDate(localDate),
                    localKey = if (hourly) localStart else localDate,
                    hour = hour,
                    averageScore = score?.coerceIn(
                        Auq.SCALE_MIN.toFloat(),
                        Auq.SCALE_MAX.toFloat(),
                    ),
                    sampleCount = item.optInt("sampleCount", 0),
                    usedNormalizedFallback = fallback,
                ),
            )
        }
        return buckets
    }

    private fun shortDate(localDate: String): String {
        val parts = localDate.split('-')
        return if (parts.size == 3) "${parts[1].trimStart('0')}/${parts[2]}" else localDate
    }
}

/** `GET /api/me/craving-calendar`. */
object CravingCalendarParser {

    fun parse(body: String): CravingCalendar {
        val root = JSONObject(body)
        val bucketUnit = root.optString("bucketUnit")
        val period = root.optJSONObject("period")
        val array = root.optJSONArray("buckets")
        val buckets = ArrayList<CravingCalendarBucket>(array?.length() ?: 0)
        for (index in 0 until (array?.length() ?: 0)) {
            val item = array?.optJSONObject(index) ?: continue
            val localStart = stringOrNull(item, "localStart")
            val localDate = stringOrNull(item, "localDate")
            val localKey = localStart ?: localDate ?: continue
            val counts = item.optJSONObject("stageCounts")
            val stageCounts = CravingStage.entries.associateWith { stage ->
                counts?.optInt(stage.stageCountsKey, 0) ?: 0
            }
            val average = floatOrNull(item, "auqAverageScore")
                ?.coerceIn(Auq.SCALE_MIN.toFloat(), Auq.SCALE_MAX.toFloat())
            buckets.add(
                CravingCalendarBucket(
                    localKey = localKey,
                    label = if (bucketUnit == "hour") {
                        "%02d시".format(hourOf(localStart) ?: index)
                    } else {
                        shortDate(localDate.orEmpty())
                    },
                    hasPredictionData = item.optBoolean("hasPredictionData", false),
                    sampleCount = item.optInt("sampleCount", 0),
                    stageCounts = stageCounts,
                    eventCount = item.optInt("eventCount", 0).coerceAtLeast(0),
                    auqAverageScore = average,
                    auqResponseCount = item.optInt("auqResponseCount", 0).coerceAtLeast(0),
                ),
            )
        }
        return CravingCalendar(
            timezone = root.optString("timezone"),
            view = root.optString("view"),
            anchor = root.optString("anchor"),
            bucketUnit = bucketUnit,
            localStartIso = period?.optString("localStart").orEmpty(),
            localEndIso = period?.optString("localEnd").orEmpty(),
            buckets = buckets,
        )
    }

    private fun shortDate(localDate: String): String {
        val parts = localDate.split('-')
        return if (parts.size == 3) "${parts[1].trimStart('0')}/${parts[2]}" else localDate
    }
}

/** One display point of `GET /api/me/predictions/{predictionId}/ppg-preview`. */
data class PpgSample(val atMs: Long, val value: Float)

data class PpgPreview(
    val predictionId: String,
    val windowStartedAtMs: Long?,
    val windowEndedAtMs: Long?,
    val samplingHz: Float?,
    val samples: List<PpgSample>,
) {
    val minimum: Float get() = samples.minOf(PpgSample::value)
    val maximum: Float get() = samples.maxOf(PpgSample::value)
}

/**
 * The outcome of parsing a PPG preview.
 *
 * [Empty] and [Invalid] are deliberately distinct: an owned window with nothing to show is a no-data
 * message, while a payload that breaks the contract is an error. Neither is ever drawn as a flat
 * zero line, which would read as a measured silence rather than an absent measurement.
 */
sealed interface PpgPreviewResult {
    data class Ready(val preview: PpgPreview) : PpgPreviewResult
    object Empty : PpgPreviewResult
    data class Invalid(val reason: String) : PpgPreviewResult
}

/**
 * `GET /api/me/predictions/{predictionId}/ppg-preview`.
 *
 * Two contract violations are rejected rather than repaired. More than [MAX_POINTS] points means the
 * response is not the downsampled preview this screen is specified against, and truncating it here
 * would silently move the window's end. Samples that run backwards are rejected instead of sorted,
 * because a reordered signal is a different signal and sorting would hide the fault.
 */
object PpgPreviewParser {

    const val MAX_POINTS: Int = 512

    const val REASON_TOO_MANY_POINTS: String = "too_many_points"
    const val REASON_OUT_OF_ORDER: String = "out_of_order"
    const val REASON_MALFORMED: String = "malformed"

    fun parse(body: String): PpgPreviewResult {
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return PpgPreviewResult.Invalid(REASON_MALFORMED)
        val predictionId = stringOrNull(root, "predictionId")
            ?: return PpgPreviewResult.Invalid(REASON_MALFORMED)
        val array = root.optJSONArray("samples") ?: return PpgPreviewResult.Empty
        if (array.length() == 0) return PpgPreviewResult.Empty
        if (array.length() > MAX_POINTS) {
            return PpgPreviewResult.Invalid(REASON_TOO_MANY_POINTS)
        }

        val samples = ArrayList<PpgSample>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
                ?: return PpgPreviewResult.Invalid(REASON_MALFORMED)
            val atMs = epochMillisOrNull(item.optString("at"))
                ?: return PpgPreviewResult.Invalid(REASON_MALFORMED)
            val value = floatOrNull(item, "value")
                ?: return PpgPreviewResult.Invalid(REASON_MALFORMED)
            if (!value.isFinite()) return PpgPreviewResult.Invalid(REASON_MALFORMED)
            // Equal timestamps are tolerated; a step backwards is not.
            if (samples.isNotEmpty() && atMs < samples.last().atMs) {
                return PpgPreviewResult.Invalid(REASON_OUT_OF_ORDER)
            }
            samples.add(PpgSample(atMs = atMs, value = value))
        }
        if (samples.isEmpty()) return PpgPreviewResult.Empty

        return PpgPreviewResult.Ready(
            PpgPreview(
                predictionId = predictionId,
                windowStartedAtMs = epochMillisOrNull(root.optString("windowStartedAt")),
                windowEndedAtMs = epochMillisOrNull(root.optString("windowEndedAt")),
                samplingHz = floatOrNull(root, "samplingHz"),
                samples = samples,
            ),
        )
    }
}

private fun stringOrNull(node: JSONObject, key: String): String? =
    if (node.isNull(key)) null else node.optString(key).takeIf { it.isNotBlank() }

private fun floatOrNull(node: JSONObject, key: String): Float? {
    if (!node.has(key) || node.isNull(key)) return null
    val value = node.optDouble(key)
    return if (value.isNaN()) null else value.toFloat()
}

private fun epochMillisOrNull(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return try {
        OffsetDateTime.parse(value).toInstant().toEpochMilli()
    } catch (error: DateTimeParseException) {
        null
    }
}

internal fun hourOf(localStart: String?): Int? {
    if (localStart.isNullOrBlank()) return null
    return try {
        OffsetDateTime.parse(localStart).hour
    } catch (error: DateTimeParseException) {
        null
    }
}

/**
 * NT-08 · 대시보드 data access.
 *
 * Every call goes through [AuthenticatedApiClient], so no screen ever sees a 401. The recent-hour
 * series is fetched from its own endpoint on purpose: truncating the 24-hour series would change
 * both the bucket width and the meaning of the end point.
 */
class DashboardRepository(
    private val client: AuthenticatedApiClient,
    private val endpoints: ApiEndpoints,
) {

    fun recentHourSeries(): CravingSeries {
        val response = client.execute(
            ApiRequest("GET", endpoints.cravingProbabilitySeries(RANGE_RECENT_HOUR)),
        )
        if (!response.isSuccessful) throw ApiHttpException(response.statusCode, response.body)
        return CravingProbabilitySeriesParser.parse(response.body)
    }

    fun cravingDashboard(
        timezone: String,
        eventRange: String,
        auqRange: String,
    ): CravingDashboard {
        require(timezone.isNotBlank()) { "a valid IANA timezone is required" }
        val response = client.execute(
            ApiRequest("GET", endpoints.cravingDashboard(timezone, eventRange, auqRange)),
        )
        if (!response.isSuccessful) throw ApiHttpException(response.statusCode, response.body)
        return CravingDashboardParser.parse(response.body)
    }

    fun cravingCalendar(timezone: String, view: String, anchor: String): CravingCalendar {
        require(timezone.isNotBlank()) { "a valid IANA timezone is required" }
        require(
            view == CALENDAR_VIEW_DAY ||
                view == CALENDAR_VIEW_WEEK ||
                view == CALENDAR_VIEW_MONTH,
        ) {
            "view must be day, week, or month"
        }
        val response = client.execute(
            ApiRequest("GET", endpoints.cravingCalendar(timezone, view, anchor)),
        )
        if (!response.isSuccessful) throw ApiHttpException(response.statusCode, response.body)
        return CravingCalendarParser.parse(response.body)
    }

    /**
     * The preview is served `Cache-Control: no-store` over a temporarily decrypted window, so the
     * result is handed straight to the caller's screen state and is never cached or persisted. A 404
     * is the documented "nothing to preview" answer and is reported as [PpgPreviewResult.Empty]
     * rather than as a failure.
     */
    fun ppgPreview(predictionId: String): PpgPreviewResult {
        val response = client.execute(
            ApiRequest(
                "GET",
                endpoints.ppgPreview(predictionId),
                headers = mapOf("Cache-Control" to "no-store"),
            ),
        )
        if (response.statusCode == HTTP_NOT_FOUND) return PpgPreviewResult.Empty
        if (!response.isSuccessful) throw ApiHttpException(response.statusCode, response.body)
        return PpgPreviewParser.parse(response.body)
    }

    companion object {
        private const val HTTP_NOT_FOUND = 404

        const val RANGE_RECENT_HOUR: String = "1h"
        const val EVENT_RANGE_7D: String = "7d"
        const val EVENT_RANGE_30D: String = "30d"
        const val AUQ_RANGE_TODAY: String = "today"
        const val AUQ_RANGE_7D: String = "7d"
        const val AUQ_RANGE_30D: String = "30d"
        const val CALENDAR_VIEW_DAY: String = "day"
        const val CALENDAR_VIEW_WEEK: String = "week"
        const val CALENDAR_VIEW_MONTH: String = "month"
    }
}
