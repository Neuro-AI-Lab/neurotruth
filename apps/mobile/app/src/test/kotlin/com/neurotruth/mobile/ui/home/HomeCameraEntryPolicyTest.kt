package com.neurotruth.mobile.ui.home

import com.neurotruth.mobile.core.CameraActionPolicy
import com.neurotruth.mobile.core.CameraAvailability
import com.neurotruth.mobile.core.ConsentGates
import com.neurotruth.mobile.core.ConsentSelection
import com.neurotruth.mobile.core.WatchConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCameraEntryPolicyTest {

    private val fullConsent = ConsentGates(
        ConsentSelection(
            tos = true,
            privacy = true,
            sensitive = true,
            biosignal = true,
            aiAnalysis = true,
            notification = true,
            reportGeneration = true,
            cameraRppg = true,
            faceVideoRetention = true,
        ),
    )

    @Test
    fun `an available action is enabled with no notice`() {
        val entry = HomeCameraEntryPolicy.resolve(CameraAvailability.Available)
        assertTrue(entry.enabled)
        assertNull(entry.notice)
    }

    /** The whole point of the exception: nothing else in the app can ask for the permission. */
    @Test
    fun `a missing OS permission is navigable rather than blocking`() {
        val availability = CameraActionPolicy.resolve(
            watchState = WatchConnectionState.DISCONNECTED,
            gates = fullConsent,
            cameraPermissionGranted = false,
            rppgServiceReady = true,
        )
        assertEquals(
            CameraAvailability.Blocked(CameraActionPolicy.REASON_PERMISSION),
            availability,
        )

        val entry = HomeCameraEntryPolicy.resolve(availability)
        assertTrue(entry.enabled)
        assertEquals(HomeCameraEntryPolicy.PERMISSION_NOTICE, entry.notice)
    }

    @Test
    fun `a connected watch still blocks the camera action`() {
        val entry = HomeCameraEntryPolicy.resolve(
            CameraActionPolicy.resolve(
                watchState = WatchConnectionState.CONNECTED,
                gates = fullConsent,
                cameraPermissionGranted = true,
                rppgServiceReady = true,
            ),
        )
        assertFalse(entry.enabled)
        assertEquals(CameraActionPolicy.REASON_WATCH_CONNECTED, entry.notice)
    }

    @Test
    fun `checking and error watch states never become navigable`() {
        listOf(WatchConnectionState.CHECKING, WatchConnectionState.ERROR).forEach { watchState ->
            val entry = HomeCameraEntryPolicy.resolve(
                CameraActionPolicy.resolve(
                    watchState = watchState,
                    gates = fullConsent,
                    cameraPermissionGranted = true,
                    rppgServiceReady = true,
                ),
            )
            assertFalse(watchState.name, entry.enabled)
            assertEquals(CameraActionPolicy.REASON_WATCH_UNKNOWN, entry.notice)
        }
    }

    @Test
    fun `missing consent still blocks even with the permission granted`() {
        val entry = HomeCameraEntryPolicy.resolve(
            CameraActionPolicy.resolve(
                watchState = WatchConnectionState.DISCONNECTED,
                gates = ConsentGates(null),
                cameraPermissionGranted = true,
                rppgServiceReady = true,
            ),
        )
        assertFalse(entry.enabled)
        assertEquals(CameraActionPolicy.REASON_CONSENT, entry.notice)
    }

    /** Consent outranks the permission in `:core`, so the button must stay blocked here. */
    @Test
    fun `missing consent outranks a missing permission`() {
        val entry = HomeCameraEntryPolicy.resolve(
            CameraActionPolicy.resolve(
                watchState = WatchConnectionState.DISCONNECTED,
                gates = ConsentGates(null),
                cameraPermissionGranted = false,
                rppgServiceReady = true,
            ),
        )
        assertFalse(entry.enabled)
        assertEquals(CameraActionPolicy.REASON_CONSENT, entry.notice)
    }

    @Test
    fun `an unavailable service still blocks the camera action`() {
        val entry = HomeCameraEntryPolicy.resolve(
            CameraActionPolicy.resolve(
                watchState = WatchConnectionState.DISCONNECTED,
                gates = fullConsent,
                cameraPermissionGranted = true,
                rppgServiceReady = false,
            ),
        )
        assertFalse(entry.enabled)
        assertEquals(CameraActionPolicy.REASON_SERVICE, entry.notice)
    }
}
