package com.neurotruth.mobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.neurotruth.mobile.data.AlertSessionEntry
import com.neurotruth.mobile.service.CravingAlertNotifier
import com.neurotruth.mobile.ui.NeuroTruthNavHost
import com.neurotruth.mobile.ui.NeuroTruthRoutes
import com.neurotruth.mobile.ui.theme.NeuroTruthTheme

/**
 * The single activity hosting the Compose tree.
 *
 * The start destination is decided once, on cold start: the product notice wins over everything
 * else, because a freshly installed user — or one whose notice version was bumped — must not reach
 * NT-02 without seeing NT-01.
 */
class MainActivity : ComponentActivity() {

    private var pendingRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = NeuroTruthApp.from(this)

        val noticeRequired = app.noticePolicy.requiresAcknowledgement()
        val authenticated = app.apiClient.hasSession()
        val talkNow = armAlertEntry(intent) && authenticated && !noticeRequired

        val start = when {
            noticeRequired -> NeuroTruthRoutes.NOTICE
            !authenticated -> NeuroTruthRoutes.AUTH
            talkNow -> NeuroTruthRoutes.CHAT
            else -> NeuroTruthRoutes.HOME
        }

        setContent {
            val navController = rememberNavController()

            LaunchedEffect(pendingRoute) {
                pendingRoute?.let { route ->
                    navController.navigate(route) { launchSingleTop = true }
                    pendingRoute = null
                }
            }

            NeuroTruthTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NeuroTruthNavHost(
                        startDestination = start,
                        navController = navController,
                    )
                }
            }
        }
    }

    /**
     * The activity is `singleTask`, so a notification tap on an already-running app arrives here
     * rather than through [onCreate].
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val app = NeuroTruthApp.from(this)
        // Same gate as onCreate: an alert tap must not jump into chat past an unshown product notice
        // or before authentication.
        if (armAlertEntry(intent) &&
            app.apiClient.hasSession() &&
            !app.noticePolicy.requiresAcknowledgement()
        ) {
            pendingRoute = NeuroTruthRoutes.CHAT
        }
    }

    /**
     * NT-05 `지금 대화하기`. Arming makes the resulting session an `alert_checkin` carrying its
     * `triggerAlertId`, instead of a plain manual check-in.
     *
     * `나중에` never reaches this activity — the dismiss receiver handles it and no session is
     * created at all.
     */
    private fun armAlertEntry(intent: Intent?): Boolean {
        if (intent?.action != CravingAlertNotifier.ACTION_TALK_NOW) return false
        val alertId = intent.getStringExtra(CravingAlertNotifier.EXTRA_ALERT_ID)
        if (alertId.isNullOrBlank()) return false
        AlertSessionEntry.arm(alertId)
        return true
    }
}
