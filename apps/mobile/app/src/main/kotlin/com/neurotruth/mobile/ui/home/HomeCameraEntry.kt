package com.neurotruth.mobile.ui.home

import com.neurotruth.mobile.core.CameraActionPolicy
import com.neurotruth.mobile.core.CameraAvailability

/** What Home does with the camera action: whether it is tappable, and what it says. */
data class HomeCameraEntry(
    val enabled: Boolean,
    val notice: String?,
)

/**
 * Turns [CameraActionPolicy]'s verdict into the Home button state.
 *
 * Consent, Watch state and service readiness stay exactly as `:core` decided them — those are the
 * genuinely blocking cases and their reason strings are shown verbatim.
 *
 * The OS camera permission is the one exception. NT-03 keeps consent and OS permission separate and
 * has the permission requested when the feature is first opened, and NT-04R's capture screen is the
 * only place in the app that asks for it. Leaving the button disabled on a missing permission would
 * therefore strand a fully consented user with no path to the request dialog, so that single case is
 * navigable: the button opens the capture screen, which asks.
 *
 * The discriminator is [CameraActionPolicy.REASON_PERMISSION] because the reason string is the only
 * thing `:core` exposes about *which* gate failed, and `:core` is not being changed for this.
 */
object HomeCameraEntryPolicy {
    const val PERMISSION_NOTICE: String = "측정을 시작하면 카메라 권한을 요청해요."

    fun resolve(availability: CameraAvailability): HomeCameraEntry = when (availability) {
        is CameraAvailability.Available -> HomeCameraEntry(enabled = true, notice = null)
        is CameraAvailability.Blocked ->
            if (availability.reason == CameraActionPolicy.REASON_PERMISSION) {
                HomeCameraEntry(enabled = true, notice = PERMISSION_NOTICE)
            } else {
                HomeCameraEntry(enabled = false, notice = availability.reason)
            }
    }
}
