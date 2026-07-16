package com.example.healthsensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PatientDashboardParserTest {
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
