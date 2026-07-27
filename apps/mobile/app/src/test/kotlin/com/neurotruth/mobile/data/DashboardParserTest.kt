package com.neurotruth.mobile.data

import com.neurotruth.mobile.core.Auq
import com.neurotruth.mobile.core.CravingStage
import com.neurotruth.mobile.core.net.ApiEndpoints
import com.neurotruth.mobile.core.net.ApiRequest
import com.neurotruth.mobile.core.net.ApiResponse
import com.neurotruth.mobile.core.net.ApiTransport
import com.neurotruth.mobile.core.net.AuthenticatedApiClient
import com.neurotruth.mobile.core.net.InMemoryRefreshTokenStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NT-08 response parsing.
 *
 * The three things that must never regress: a gap in the recent-hour series stays a gap, raw
 * `stageCounts` remain exact without an empty hour becoming a zero measurement, and a day with no
 * prediction stays distinguishable from a day whose predictions raised no alert.
 */
class DashboardParserTest {

    // -----------------------------------------------------------------------------------------
    // 1 · recent-hour series
    // -----------------------------------------------------------------------------------------

    @Test
    fun `contiguous buckets form a single segment`() {
        val series = CravingProbabilitySeriesParser.parse(
            seriesJson(
                point("2026-07-21T10:00:00+00:00", 0.10),
                point("2026-07-21T10:00:10+00:00", 0.20),
                point("2026-07-21T10:00:20+00:00", 0.30),
            ),
        )
        val segments = series.segments()
        assertEquals(1, segments.size)
        assertEquals(3, segments.first().size)
    }

    @Test
    fun `a missing bucket breaks the line into two segments`() {
        val series = CravingProbabilitySeriesParser.parse(
            seriesJson(
                point("2026-07-21T10:00:00+00:00", 0.10),
                point("2026-07-21T10:00:10+00:00", 0.20),
                // 10:00:20 has no samples and the server omits it entirely.
                point("2026-07-21T10:00:30+00:00", 0.90),
                point("2026-07-21T10:00:40+00:00", 0.80),
            ),
        )
        val segments = series.segments()
        assertEquals(2, segments.size)
        assertEquals(2, segments[0].size)
        assertEquals(2, segments[1].size)
        // No interpolated point was invented across the gap.
        assertEquals(4, series.points.size)
    }

    @Test
    fun `an isolated sample is its own segment and is never joined to a neighbour`() {
        val series = CravingProbabilitySeriesParser.parse(
            seriesJson(
                point("2026-07-21T10:00:00+00:00", 0.40),
                point("2026-07-21T10:30:00+00:00", 0.60),
            ),
        )
        val segments = series.segments()
        assertEquals(2, segments.size)
        assertTrue(segments.all { it.size == 1 })
    }

    @Test
    fun `the latest value is the final plotted point`() {
        val series = CravingProbabilitySeriesParser.parse(
            seriesJson(
                point("2026-07-21T10:00:20+00:00", 0.30),
                point("2026-07-21T10:00:00+00:00", 0.10),
                point("2026-07-21T10:00:10+00:00", 0.20),
            ),
        )
        val latest = series.latest
        assertNotNull(latest)
        assertEquals(0.30f, latest!!.probability, 1e-4f)
        assertEquals(series.points.last().atMs, latest.atMs)
        assertEquals(series.segments().last().last().atMs, latest.atMs)
    }

    @Test
    fun `an empty series reports no data rather than a zero point`() {
        val series = CravingProbabilitySeriesParser.parse(
            """{"range":"1h","from":"2026-07-21T09:00:00+00:00","to":"2026-07-21T10:00:00+00:00","bucketSeconds":10,"points":[]}""",
        )
        assertFalse(series.hasData)
        assertNull(series.latest)
        assertTrue(series.segments().isEmpty())
    }

    @Test
    fun `the series is capped at 360 ten-second points`() {
        val points = (0 until 400).joinToString(",") { index ->
            val minute = index / 6
            val second = (index % 6) * 10
            """{"at":"2026-07-21T%02d:%02d:%02d+00:00","averageCravingProbability":0.5,"sampleCount":1}"""
                .format(9 + minute / 60, minute % 60, second)
        }
        val series = CravingProbabilitySeriesParser.parse(
            """{"range":"1h","from":"2026-07-21T09:00:00+00:00","to":"2026-07-21T16:00:00+00:00","bucketSeconds":10,"points":[$points]}""",
        )
        assertEquals(CravingProbabilitySeriesParser.MAX_POINTS_1H, series.points.size)
    }

    // -----------------------------------------------------------------------------------------
    // 2 · raw stageCounts
    // -----------------------------------------------------------------------------------------

    @Test
    fun `stage counts remain exact for count-scaled stacking`() {
        val dashboard = CravingDashboardParser.parse(
            dashboardJson(
                hourly = listOf(
                    hourJson(9, sampleCount = 25, low = 5, observe = 7, caution = 9, high = 4),
                ),
            ),
        )
        val bucket = dashboard.hourly.single()
        assertEquals(9, bucket.hour)
        assertTrue(bucket.hasData)
        assertEquals(25, bucket.countedSamples)
        assertEquals(5, bucket.stageCounts[CravingStage.SAFE])
        assertEquals(7, bucket.stageCounts[CravingStage.OBSERVE])
        assertEquals(9, bucket.stageCounts[CravingStage.CAUTION])
        assertEquals(4, bucket.stageCounts[CravingStage.SEVERE])
    }

    @Test
    fun `an hour with no samples is no data rather than zero percent`() {
        val dashboard = CravingDashboardParser.parse(
            dashboardJson(
                hourly = listOf(
                    hourJson(0, sampleCount = 0, low = 0, observe = 0, caution = 0, high = 0),
                ),
            ),
        )
        val bucket = dashboard.hourly.single()
        assertFalse(bucket.hasData)
        assertEquals(0, bucket.countedSamples)
        assertFalse(dashboard.hasHourlyData)
    }

    @Test
    fun `stage counts are read by their wire keys`() {
        val dashboard = CravingDashboardParser.parse(
            dashboardJson(
                hourly = listOf(
                    hourJson(13, sampleCount = 4, low = 0, observe = 0, caution = 0, high = 4),
                ),
            ),
        )
        val bucket = dashboard.hourly.single()
        assertEquals(4, bucket.stageCounts[CravingStage.SEVERE])
        assertEquals("high", CravingStage.SEVERE.stageCountsKey)
    }

    @Test
    fun `all 24 hours survive parsing`() {
        val hours = (0 until 24).map { hour ->
            hourJson(hour, sampleCount = if (hour == 12) 2 else 0, low = if (hour == 12) 2 else 0)
        }
        val dashboard = CravingDashboardParser.parse(dashboardJson(hourly = hours))
        assertEquals(24, dashboard.hourly.size)
        assertEquals(1, dashboard.hourly.count { it.hasData })
        assertTrue(dashboard.hasHourlyData)
    }

    // -----------------------------------------------------------------------------------------
    // 3 · events — no data versus a valid zero
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a day without predictions is no data and never a zero`() {
        val dashboard = CravingDashboardParser.parse(
            dashboardJson(
                events = listOf(eventJson("2026-07-20", hasPrediction = false, recommend = 0, required = 0)),
            ),
        )
        val bucket = dashboard.events.single()
        assertTrue(bucket.isNoData)
        assertFalse(bucket.isValidZero)
    }

    @Test
    fun `a day with predictions and no alert is a valid zero`() {
        val dashboard = CravingDashboardParser.parse(
            dashboardJson(
                events = listOf(eventJson("2026-07-20", hasPrediction = true, recommend = 0, required = 0)),
            ),
        )
        val bucket = dashboard.events.single()
        assertFalse(bucket.isNoData)
        assertTrue(bucket.isValidZero)
        assertEquals(0, bucket.totalCount)
    }

    @Test
    fun `a day with alerts is neither no data nor a valid zero`() {
        val dashboard = CravingDashboardParser.parse(
            dashboardJson(
                events = listOf(eventJson("2026-07-21", hasPrediction = true, recommend = 2, required = 1)),
            ),
        )
        val bucket = dashboard.events.single()
        assertFalse(bucket.isNoData)
        assertFalse(bucket.isValidZero)
        assertEquals(3, bucket.totalCount)
        assertEquals(2, bucket.recommendCount)
        assertEquals(1, bucket.requiredCount)
    }

    @Test
    fun `a missing hasPredictionData flag is read as no data`() {
        val dashboard = CravingDashboardParser.parse(
            """{"dailyEvents":{"range":"7d","buckets":[{"localDate":"2026-07-21","recommendCount":0,"requiredCount":0,"totalCount":0}]}}""",
        )
        val bucket = dashboard.events.single()
        assertTrue(bucket.isNoData)
        assertFalse(bucket.isValidZero)
    }

    // -----------------------------------------------------------------------------------------
    // 4 · AUQ scale
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the raw 0 to 48 average wins over the normalized field`() {
        val dashboard = CravingDashboardParser.parse(
            """{"auq":{"range":"7d","bucketUnit":"day","buckets":[{"localDate":"2026-07-21","averageNormalizedScore":0.25,"averageScore":21.0,"sampleCount":3}]}}""",
        )
        val bucket = dashboard.auq.single()
        assertEquals(21f, bucket.averageScore!!, 1e-4f)
        assertFalse(bucket.usedNormalizedFallback)
        assertTrue(bucket.hasData)
    }

    @Test
    fun `a missing raw average falls back to the normalized score times 48`() {
        val dashboard = CravingDashboardParser.parse(
            """{"auq":{"range":"7d","bucketUnit":"day","buckets":[{"localDate":"2026-07-21","averageNormalizedScore":0.5,"sampleCount":2}]}}""",
        )
        val bucket = dashboard.auq.single()
        assertEquals(Auq.SCALE_MAX / 2f, bucket.averageScore!!, 1e-4f)
        assertTrue(bucket.usedNormalizedFallback)
    }

    @Test
    fun `an hour with no responses has no average`() {
        val dashboard = CravingDashboardParser.parse(
            """{"auq":{"range":"today","bucketUnit":"hour","buckets":[{"localStart":"2026-07-21T03:00:00+09:00","averageNormalizedScore":null,"averageScore":null,"sampleCount":0}]}}""",
        )
        val bucket = dashboard.auq.single()
        assertNull(bucket.averageScore)
        assertFalse(bucket.hasData)
        assertEquals(3, bucket.hour)
        assertFalse(dashboard.hasAuqData)
    }

    // -----------------------------------------------------------------------------------------
    // 5 · PPG preview
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a chronological preview within the cap is ready`() {
        val result = PpgPreviewParser.parse(ppgJson(count = 512))
        assertTrue(result is PpgPreviewResult.Ready)
        val preview = (result as PpgPreviewResult.Ready).preview
        assertEquals(PpgPreviewParser.MAX_POINTS, preview.samples.size)
        assertEquals("11111111-1111-1111-1111-111111111111", preview.predictionId)
        assertTrue(preview.samples.zipWithNext().all { (a, b) -> a.atMs <= b.atMs })
    }

    @Test
    fun `more than 512 points is rejected rather than truncated`() {
        val result = PpgPreviewParser.parse(ppgJson(count = 513))
        assertTrue(result is PpgPreviewResult.Invalid)
        assertEquals(
            PpgPreviewParser.REASON_TOO_MANY_POINTS,
            (result as PpgPreviewResult.Invalid).reason,
        )
    }

    @Test
    fun `a non-chronological series is rejected rather than sorted`() {
        val result = PpgPreviewParser.parse(
            """{"predictionId":"11111111-1111-1111-1111-111111111111","samples":[""" +
                """{"at":"2026-07-21T10:00:00.000+00:00","value":1.0},""" +
                """{"at":"2026-07-21T10:00:00.080+00:00","value":2.0},""" +
                """{"at":"2026-07-21T10:00:00.040+00:00","value":3.0}]}""",
        )
        assertTrue(result is PpgPreviewResult.Invalid)
        assertEquals(
            PpgPreviewParser.REASON_OUT_OF_ORDER,
            (result as PpgPreviewResult.Invalid).reason,
        )
    }

    @Test
    fun `an empty preview is no data rather than an error or a zero line`() {
        val empty = PpgPreviewParser.parse(
            """{"predictionId":"11111111-1111-1111-1111-111111111111","samples":[]}""",
        )
        assertTrue(empty is PpgPreviewResult.Empty)

        val missing = PpgPreviewParser.parse(
            """{"predictionId":"11111111-1111-1111-1111-111111111111"}""",
        )
        assertTrue(missing is PpgPreviewResult.Empty)
    }

    @Test
    fun `a malformed sample is rejected`() {
        val result = PpgPreviewParser.parse(
            """{"predictionId":"11111111-1111-1111-1111-111111111111","samples":[{"at":"nope","value":1.0}]}""",
        )
        assertTrue(result is PpgPreviewResult.Invalid)
        assertEquals(
            PpgPreviewParser.REASON_MALFORMED,
            (result as PpgPreviewResult.Invalid).reason,
        )
    }

    @Test
    fun `series points expose a prediction id only when the server sends one`() {
        val without = CravingProbabilitySeriesParser.parse(
            seriesJson(point("2026-07-21T10:00:00+00:00", 0.4)),
        )
        assertNull(without.points.single().predictionId)
        assertFalse(without.hasSelectablePoints)

        val with = CravingProbabilitySeriesParser.parse(
            """{"range":"1h","from":"2026-07-21T09:00:00+00:00","to":"2026-07-21T10:00:00+00:00","bucketSeconds":10,"points":[{"at":"2026-07-21T10:00:00+00:00","averageCravingProbability":0.4,"sampleCount":1,"predictionId":"11111111-1111-1111-1111-111111111111"}]}""",
        )
        assertTrue(with.hasSelectablePoints)
        assertEquals(1, with.selectablePoints.size)
    }

    @Test
    fun `recent points map to the four display stages without interpolation`() {
        val series = CravingProbabilitySeriesParser.parse(
            seriesJson(
                point("2026-07-21T10:00:00+00:00", 0.10),
                point("2026-07-21T10:00:10+00:00", 0.25),
                point("2026-07-21T10:00:20+00:00", 0.50),
                point("2026-07-21T10:00:30+00:00", 0.75),
            ),
        )
        assertEquals(
            listOf(
                CravingStage.SAFE,
                CravingStage.OBSERVE,
                CravingStage.CAUTION,
                CravingStage.SEVERE,
            ),
            series.points.map { it.stage },
        )
    }

    @Test
    fun `day calendar parses synchronized stage event and AUQ buckets`() {
        val calendar = CravingCalendarParser.parse(
            """
            {
              "timezone":"Asia/Seoul","view":"day","anchor":"2026-07-25","bucketUnit":"hour",
              "period":{"localStart":"2026-07-25T00:00:00+09:00","localEnd":"2026-07-26T00:00:00+09:00"},
              "buckets":[
                {"localStart":"2026-07-25T10:00:00+09:00","hasPredictionData":true,
                 "sampleCount":6,"stageCounts":{"low":1,"observe":2,"caution":1,"high":2},
                 "eventCount":1,"auqAverageScore":24.5,"auqResponseCount":2},
                {"localStart":"2026-07-25T11:00:00+09:00","hasPredictionData":false,
                 "sampleCount":0,"stageCounts":{"low":0,"observe":0,"caution":0,"high":0},
                 "eventCount":0,"auqAverageScore":null,"auqResponseCount":0}
              ]
            }
            """.trimIndent(),
        )
        assertEquals("hour", calendar.bucketUnit)
        assertEquals("10시", calendar.buckets.first().label)
        assertEquals(2, calendar.buckets.first().stageCounts[CravingStage.SEVERE])
        assertEquals(1, calendar.buckets.first().eventCount)
        assertEquals(24.5f, calendar.buckets.first().auqAverageScore!!, 1e-4f)
        assertTrue(calendar.buckets.first().hasStageData)
        assertTrue(calendar.buckets.first().hasAuqData)
        assertFalse(calendar.buckets.last().hasPredictionData)
    }

    @Test
    fun `month calendar preserves a measured zero event and an unmeasured day`() {
        val calendar = CravingCalendarParser.parse(
            """
            {
              "timezone":"Asia/Seoul","view":"month","anchor":"2026-07-25","bucketUnit":"day",
              "period":{"localStart":"2026-07-01T00:00:00+09:00","localEnd":"2026-08-01T00:00:00+09:00"},
              "buckets":[
                {"localDate":"2026-07-01","hasPredictionData":true,"sampleCount":2,
                 "stageCounts":{"low":2,"observe":0,"caution":0,"high":0},"eventCount":0,
                 "auqAverageScore":null,"auqResponseCount":0},
                {"localDate":"2026-07-02","hasPredictionData":false,"sampleCount":0,
                 "stageCounts":{"low":0,"observe":0,"caution":0,"high":0},"eventCount":0,
                 "auqAverageScore":null,"auqResponseCount":0}
              ]
            }
            """.trimIndent(),
        )
        assertEquals("7/01", calendar.buckets.first().label)
        assertTrue(calendar.buckets.first().hasPredictionData)
        assertEquals(0, calendar.buckets.first().eventCount)
        assertFalse(calendar.buckets.last().hasPredictionData)
    }

    @Test
    fun `week calendar parses seven synchronized daily buckets across a month boundary`() {
        val calendar = CravingCalendarParser.parse(weeklyCalendarJson())

        assertEquals(DashboardRepository.CALENDAR_VIEW_WEEK, calendar.view)
        assertEquals("day", calendar.bucketUnit)
        assertEquals(7, calendar.buckets.size)
        assertEquals("6/29", calendar.buckets.first().label)
        assertEquals("7/05", calendar.buckets.last().label)
        assertTrue(calendar.buckets.first().hasStageData)
        assertTrue(calendar.buckets.last().hasAuqData)
    }

    @Test
    fun `calendar client accepts week and requests the weekly endpoint`() {
        val endpoints = ApiEndpoints("https://neurotruth.example")
        val requests = mutableListOf<ApiRequest>()
        val transport = ApiTransport { request ->
            requests.add(request)
            if (request.url == endpoints.refresh) {
                ApiResponse(
                    200,
                    """{"user":{"id":"u-1","email":"patient@example.com","role":"patient","status":"active"},"accessToken":"access-1","refreshToken":"refresh-1","expiresIn":900}""",
                )
            } else {
                ApiResponse(200, weeklyCalendarJson())
            }
        }
        val client = AuthenticatedApiClient(
            endpoints,
            transport,
            InMemoryRefreshTokenStore("refresh-0"),
        ).apply { recoverSession() }

        val calendar = DashboardRepository(client, endpoints).cravingCalendar(
            timezone = "Asia/Seoul",
            view = DashboardRepository.CALENDAR_VIEW_WEEK,
            anchor = "2026-07-01",
        )

        assertEquals(7, calendar.buckets.size)
        assertTrue(requests.any { it.url.contains("view=week") && it.url.contains("anchor=2026-07-01") })
    }

    // -----------------------------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------------------------

    private fun weeklyCalendarJson(): String {
        val buckets = (0 until 7).joinToString(",") { index ->
            val date = if (index < 2) "2026-06-${29 + index}" else "2026-07-%02d".format(index - 1)
            """{"localDate":"$date","hasPredictionData":true,"sampleCount":1,"stageCounts":{"low":1,"observe":0,"caution":0,"high":0},"eventCount":0,"auqAverageScore":${if (index == 6) "12.0" else "null"},"auqResponseCount":${if (index == 6) 1 else 0}}"""
        }
        return """
            {
              "timezone":"Asia/Seoul","view":"week","anchor":"2026-07-01","bucketUnit":"day",
              "period":{"localStart":"2026-06-29T00:00:00+09:00","localEnd":"2026-07-06T00:00:00+09:00"},
              "buckets":[$buckets]
            }
        """.trimIndent()
    }

    private fun ppgJson(count: Int): String {
        val samples = (0 until count).joinToString(",") { index ->
            val millis = index * 40L
            """{"at":"2026-07-21T10:00:%02d.%03d+00:00","value":%.3f}"""
                .format(millis / 1000, millis % 1000, 0.5 + index % 7 * 0.1)
        }
        return """{"predictionId":"11111111-1111-1111-1111-111111111111",""" +
            """"windowStartedAt":"2026-07-21T10:00:00+00:00",""" +
            """"windowEndedAt":"2026-07-21T10:00:20+00:00",""" +
            """"samplingHz":25.0,"samples":[$samples]}"""
    }

    private fun seriesJson(vararg points: String): String =
        """{"range":"1h","from":"2026-07-21T09:00:00+00:00","to":"2026-07-21T10:00:00+00:00","bucketSeconds":10,"points":[${points.joinToString(",")}]}"""

    private fun point(at: String, probability: Double, sampleCount: Int = 1): String =
        """{"at":"$at","averageCravingProbability":$probability,"sampleCount":$sampleCount}"""

    private fun hourJson(
        hour: Int,
        sampleCount: Int,
        low: Int = 0,
        observe: Int = 0,
        caution: Int = 0,
        high: Int = 0,
    ): String =
        """{"localStart":"2026-07-21T%02d:00:00+09:00","averageProbability":null,"minimumProbability":null,"maximumProbability":null,"sampleCount":$sampleCount,"stageCounts":{"low":$low,"observe":$observe,"caution":$caution,"high":$high}}"""
            .format(hour)

    private fun eventJson(
        localDate: String,
        hasPrediction: Boolean,
        recommend: Int,
        required: Int,
    ): String =
        """{"localDate":"$localDate","hasPredictionData":$hasPrediction,"recommendCount":$recommend,"requiredCount":$required,"totalCount":${recommend + required}}"""

    private fun dashboardJson(
        hourly: List<String> = emptyList(),
        events: List<String> = emptyList(),
        auq: List<String> = emptyList(),
    ): String =
        """{"timezone":"Asia/Seoul","generatedAt":"2026-07-21T10:00:00+00:00",""" +
            """"currentCraving":{"probability":null,"at":null},""" +
            """"hourlyCraving":{"buckets":[${hourly.joinToString(",")}]},""" +
            """"dailyEvents":{"range":"7d","buckets":[${events.joinToString(",")}]},""" +
            """"auq":{"range":"today","bucketUnit":"hour","buckets":[${auq.joinToString(",")}]}}"""
}
