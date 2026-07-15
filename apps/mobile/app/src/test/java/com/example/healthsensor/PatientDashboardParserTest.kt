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
        assertTrue(parsed.predictions.single().ppgPreviewAvailable)
        assertEquals(24f, parsed.assessments.single().rawScore)
        assertEquals("pending", parsed.reports.single().status)
    }

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
