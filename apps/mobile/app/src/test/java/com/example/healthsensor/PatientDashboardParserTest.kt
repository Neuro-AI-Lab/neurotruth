package com.example.healthsensor

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PatientDashboardParserTest {
    @Test
    fun cravingDashboardParsesFixedHourlyAndDailyBarContracts() {
        val root = JSONObject()
            .put("timezone", "Asia/Seoul")
            .put("generatedAt", "2026-07-16T03:00:00Z")
            .put(
                "currentCraving",
                JSONObject().put("probability", 0.81).put("at", "2026-07-16T02:59:59Z")
            )
            .put(
                "hourlyCraving",
                JSONObject().put("buckets", JSONArray().apply {
                    repeat(24) { hour ->
                        put(
                            JSONObject()
                                .put("localStart", "2026-07-16T${"%02d".format(hour)}:00:00+09:00")
                                .put("averageProbability", if (hour == 0) JSONObject.NULL else 0.5)
                                .put("minimumProbability", if (hour == 0) JSONObject.NULL else 0.2)
                                .put("maximumProbability", if (hour == 0) JSONObject.NULL else 0.8)
                                .put("sampleCount", if (hour == 0) 0 else 60)
                        )
                    }
                })
            )
            .put(
                "dailyEvents",
                JSONObject().put("range", "7d").put("buckets", JSONArray().apply {
                    repeat(7) { day ->
                        put(
                            JSONObject()
                                .put("localDate", "2026-07-${"%02d".format(10 + day)}")
                                .put("hasPredictionData", day != 0)
                                .put("recommendCount", 0)
                                .put("requiredCount", 0)
                                .put("totalCount", 0)
                        )
                    }
                })
            )
            .put(
                "auq",
                JSONObject().put("range", "today").put("bucketUnit", "hour")
                    .put("buckets", JSONArray().apply {
                        repeat(24) { hour ->
                            put(
                                JSONObject()
                                    .put("localStart", "2026-07-16T${"%02d".format(hour)}:00:00+09:00")
                                    .put("averageNormalizedScore", if (hour == 2) 0.625 else JSONObject.NULL)
                                    .put("sampleCount", if (hour == 2) 2 else 0)
                            )
                        }
                    })
            )

        val parsed = PatientDashboardParser.parseCravingDashboard(root.toString())

        assertEquals(24, parsed.hourlyCraving.size)
        assertNull(parsed.hourlyCraving.first().averageProbability)
        assertEquals(0.81f, parsed.currentCraving?.probability)
        assertTrue(parsed.dailyEvents.first().let { !it.hasPredictionData && it.totalCount == 0 })
        assertTrue(parsed.dailyEvents[1].let { it.hasPredictionData && it.totalCount == 0 })
        assertEquals(0.625f, parsed.auq[2].averageNormalizedScore)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cravingDashboardRejectsMissingWallClockHour() {
        PatientDashboardParser.parseCravingDashboard(
            """{
              "timezone":"Asia/Seoul","generatedAt":"2026-07-16T03:00:00Z","currentCraving":null,
              "hourlyCraving":{"buckets":[]},
              "dailyEvents":{"range":"7d","buckets":[]},
              "auq":{"range":"today","bucketUnit":"hour","buckets":[]}
            }"""
        )
    }

    @Test
    fun emptyDashboardIsValidForEverySupportedRange() {
        DashboardRange.values().forEach { range ->
            val parsed = PatientDashboardParser.parse(
                """{"range":"${range.wire}","predictions":[],"assessments":[],"events":[],"latestState":null,"longitudinalState":null,"reports":[]}"""
            )
            assertEquals(range, parsed.range)
            assertTrue(parsed.predictions.isEmpty())
            assertNull(parsed.latestState)
        }
    }

    @Test
    fun populatedDashboardKeepsCategoricalStateAndReportStatusOnly() {
        val parsed = PatientDashboardParser.parse(
            """{
              "range":"7d",
              "predictions":[{"predictionId":"11111111-1111-4111-8111-111111111111","at":"2026-07-15T01:00:00Z","class":"high","probability":0.8,"ppgPreviewAvailable":true}],
              "assessments":[{"assessmentId":"22222222-2222-4222-8222-222222222222","sessionId":"33333333-3333-4333-8333-333333333333","at":"2026-07-15T01:01:00Z","rawScore":24,"scaleMin":8,"scaleMax":56}],
              "events":[{"eventId":"event-1","type":"alert","at":"2026-07-15T01:02:00Z","label":"상태 확인 제안"}],
              "latestState":{"state":"mid","confidence":0.6,"summaryStatus":"ready","summary":"긴장 가능성","createdAt":"2026-07-15T01:03:00Z"},
              "longitudinalState":null,
              "reports":[{"sessionId":"33333333-3333-4333-8333-333333333333","status":"pending","updatedAt":"2026-07-15T01:04:00Z","body":"never expose"}]
            }"""
        )

        assertEquals("high", parsed.predictions.single().stateClass)
        assertNull(parsed.predictions.single().cravingProbability)
        assertTrue(parsed.predictions.single().ppgPreviewAvailable)
        assertEquals(24f, parsed.assessments.single().rawScore)
        assertEquals("pending", parsed.reports.single().status)
    }

    @Test
    fun probabilitySeriesParsesBinaryLikelihoodAndRangeContract() {
        val parsed = PatientDashboardParser.parseProbabilitySeries(
            """{
              "range":"10m",
              "from":"2026-07-15T00:50:00Z",
              "to":"2026-07-15T01:00:00Z",
              "bucketSeconds":1,
              "points":[
                {"at":"2026-07-15T00:59:58Z","averageCravingProbability":0.25,"sampleCount":1},
                {"at":"2026-07-15T00:59:59Z","averageCravingProbability":0.75,"sampleCount":1}
              ]
            }"""
        )

        assertEquals(ProbabilityRange.MINUTES_10, parsed.range)
        assertEquals(2, parsed.points.size)
        assertEquals(0.75f, parsed.points.last().probability)
    }

    @Test
    fun liveAppendDeduplicatesCapsAndLeavesTimestampGaps() {
        val base = CravingProbabilitySeries(
            range = ProbabilityRange.MINUTES_10,
            fromMs = 0L,
            toMs = 600_000L,
            bucketSeconds = 1,
            points = (0 until 600).map { index ->
                CravingProbabilityPoint(index * 1_000L, 0.1f, 1)
            }
        )
        val appended = CravingProbabilitySeriesState.appendLive(
            base,
            prediction(timestampMs = 600_000L, probability = 0.8f, id = "new")
        )
        val deduplicated = CravingProbabilitySeriesState.appendLive(
            appended,
            prediction(timestampMs = 600_000L, probability = 0.8f, id = "new")
        )

        assertEquals(600, deduplicated.points.size)
        assertEquals(0.8f, deduplicated.points.last().probability)

        val timestampDeduplicated = CravingProbabilitySeriesState.appendLive(
            deduplicated.copy(
                points = deduplicated.points.dropLast(1) +
                    CravingProbabilityPoint(600_000L, 0.2f, 1)
            ),
            prediction(timestampMs = 600_000L, probability = 0.8f, id = "server-id")
        )
        assertEquals(600, timestampDeduplicated.points.size)
        assertEquals(0.8f, timestampDeduplicated.points.last().probability)

        val withGap = deduplicated.copy(
            points = listOf(
                CravingProbabilityPoint(1_000L, 0.1f, 1),
                CravingProbabilityPoint(2_000L, 0.2f, 1),
                CravingProbabilityPoint(10_000L, 0.3f, 1)
            )
        )
        assertEquals(listOf(2, 1), CravingProbabilitySeriesState.segments(withGap).map { it.size })
    }

    private fun prediction(timestampMs: Long, probability: Float, id: String) = CravingPrediction(
        cravingClass = if (probability > 0.5f) 1 else 0,
        timestampMs = timestampMs,
        rawBody = "{}",
        confidence = maxOf(probability, 1f - probability),
        cravingProbability = probability,
        classProbabilities = mapOf("low" to 1f - probability, "high" to probability),
        predictionId = id
    )

    @Test
    fun ppgPreviewRequiresChronologicalAtMost512Points() {
        val preview = PatientDashboardParser.parsePpgPreview(
            """{"predictionId":"11111111-1111-4111-8111-111111111111","samplingHz":25.0,"samples":[{"at":"2026-07-15T01:00:00Z","value":0.1},{"at":"2026-07-15T01:00:00.040Z","value":0.2}]}"""
        )
        assertEquals(2, preview.samples.size)
        assertTrue(preview.samples.first().atMs <= preview.samples.last().atMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun ppgPreviewRejectsNonChronologicalSeries() {
        PatientDashboardParser.parsePpgPreview(
            """{"predictionId":"11111111-1111-4111-8111-111111111111","samplingHz":25.0,"samples":[{"at":"2026-07-15T01:00:01Z","value":0.1},{"at":"2026-07-15T01:00:00Z","value":0.2}]}"""
        )
    }
}
