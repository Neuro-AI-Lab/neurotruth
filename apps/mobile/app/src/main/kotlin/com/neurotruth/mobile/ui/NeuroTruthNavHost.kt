package com.neurotruth.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.neurotruth.mobile.ui.auth.AuthScreen
import com.neurotruth.mobile.ui.auq.AuqScreen
import com.neurotruth.mobile.ui.chat.ChatScreen
import com.neurotruth.mobile.ui.dashboard.DashboardScreen
import com.neurotruth.mobile.ui.rppg.RppgCaptureScreen
import com.neurotruth.mobile.ui.settings.SettingsScreen
import com.neurotruth.mobile.ui.consent.ConsentScreen
import com.neurotruth.mobile.ui.home.HomeScreen
import com.neurotruth.mobile.ui.notice.NoticeScreen
import com.neurotruth.mobile.ui.theme.NeuroTruthSpacing

object NeuroTruthRoutes {
    const val NOTICE = "notice"
    const val AUTH = "auth"
    const val CONSENT = "consent"
    const val HOME = "home"
    const val DASHBOARD = "dashboard"
    const val CHAT = "chat"
    const val AUQ = "auq"
    const val SETTINGS = "settings"
    const val RPPG = "rppg"

    /** NT-01 through NT-03. The bottom bar is hidden until authentication completes. */
    val PRE_AUTH: Set<String> = setOf(NOTICE, AUTH, CONSENT)

    /** The three destinations that own a bottom-bar tab. Sub-screens run full width without it. */
    val TAB_ROUTES: Set<String> = setOf(HOME, DASHBOARD, CHAT)
}

/**
 * The three bottom tabs of PRD §3.2.
 *
 * There are exactly three. Profile is not a tab — it is reached through 설정 at the top right of
 * Home.
 */
enum class BottomTab(val route: String, val label: String, val icon: ImageVector) {
    HOME(NeuroTruthRoutes.HOME, "홈", Icons.Outlined.Home),
    DASHBOARD(NeuroTruthRoutes.DASHBOARD, "대시보드", Icons.Outlined.BarChart),
    CHAT(NeuroTruthRoutes.CHAT, "챗봇", Icons.AutoMirrored.Outlined.Chat),
}

@Composable
fun NeuroTruthNavHost(
    startDestination: String,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // Only the three tab destinations show the bar; settings, AUQ and rPPG are full-width sub-screens.
    val showBottomBar = currentRoute in NeuroTruthRoutes.TAB_ROUTES

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    BottomTab.entries.forEach { tab ->
                        val selected = backStackEntry?.destination?.hierarchy
                            ?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.switchTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                            modifier = Modifier.semantics {
                                contentDescription =
                                    "${tab.label} 탭${if (selected) ", 선택됨" else ""}"
                            },
                        )
                    }
                }
            }
        },
    ) { insets ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier
                .fillMaxSize()
                .padding(insets),
        ) {
            composable(NeuroTruthRoutes.NOTICE) {
                NoticeScreen(
                    onAcknowledged = {
                        navController.navigate(NeuroTruthRoutes.AUTH) {
                            popUpTo(NeuroTruthRoutes.NOTICE) { inclusive = true }
                        }
                    },
                )
            }

            composable(NeuroTruthRoutes.AUTH) {
                AuthScreen(
                    onNavigateToConsent = { navController.navigate(NeuroTruthRoutes.CONSENT) },
                    onNavigateToHome = { navController.enterAuthenticatedArea() },
                )
            }

            composable(NeuroTruthRoutes.CONSENT) {
                ConsentScreen(onSaved = { navController.enterAuthenticatedArea() })
            }

            composable(NeuroTruthRoutes.HOME) {
                HomeScreen(
                    onOpenSettings = { navController.navigate(NeuroTruthRoutes.SETTINGS) },
                    onOpenCameraMeasurement = { navController.navigate(NeuroTruthRoutes.RPPG) },
                )
            }

            composable(NeuroTruthRoutes.DASHBOARD) {
                DashboardScreen()
            }

            composable(NeuroTruthRoutes.CHAT) {
                ChatScreen(
                    onFinished = { navController.switchTab(NeuroTruthRoutes.DASHBOARD) },
                    onRequiresAuq = {
                        navController.navigate(NeuroTruthRoutes.AUQ) { launchSingleTop = true }
                    },
                )
            }

            composable(NeuroTruthRoutes.AUQ) {
                AuqScreen(
                    // Submitting and skipping both land here. Chat re-enters ensureSession, which
                    // returns the same active session, so NT-06 is not offered again.
                    onContinueToChat = {
                        navController.navigate(NeuroTruthRoutes.CHAT) {
                            popUpTo(NeuroTruthRoutes.AUQ) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }

            composable(NeuroTruthRoutes.SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onSignedOut = {
                        navController.navigate(NeuroTruthRoutes.AUTH) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                inclusive = true
                            }
                            launchSingleTop = true
                        }
                    },
                )
            }

            composable(NeuroTruthRoutes.RPPG) {
                RppgCaptureScreen(
                    // A completed job has already been routed once and the session ensured, so this
                    // only moves the user to the conversation.
                    onCompleted = {
                        navController.navigate(NeuroTruthRoutes.CHAT) {
                            popUpTo(NeuroTruthRoutes.RPPG) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onCancelled = { navController.popBackStack() },
                )
            }
        }
    }
}

/** Clears NT-01..NT-03 from the back stack so the back gesture cannot return to signup. */
private fun NavHostController.enterAuthenticatedArea() {
    navigate(NeuroTruthRoutes.HOME) {
        popUpTo(graph.findStartDestination().id) { inclusive = true }
        launchSingleTop = true
    }
}

/** Tab switching preserves each tab's own state, per PRD §3.2. */
private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(NeuroTruthRoutes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
