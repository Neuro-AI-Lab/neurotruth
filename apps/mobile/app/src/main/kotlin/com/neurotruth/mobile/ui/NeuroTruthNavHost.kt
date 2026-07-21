package com.neurotruth.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
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
import com.neurotruth.mobile.ui.chat.ChatScreen
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

    /** NT-01 through NT-03. The bottom bar is hidden until authentication completes. */
    val PRE_AUTH: Set<String> = setOf(NOTICE, AUTH, CONSENT)
}

/**
 * The three bottom tabs of PRD §3.2.
 *
 * There are exactly three. Profile is not a tab — it is reached through 설정 at the top right of
 * Home.
 */
enum class BottomTab(val route: String, val label: String, val icon: ImageVector) {
    HOME(NeuroTruthRoutes.HOME, "홈", Icons.Filled.Home),
    DASHBOARD(NeuroTruthRoutes.DASHBOARD, "대시보드", Icons.Filled.Insights),
    CHAT(NeuroTruthRoutes.CHAT, "챗봇", Icons.AutoMirrored.Filled.Chat),
}

@Composable
fun NeuroTruthNavHost(
    startDestination: String,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute != null && currentRoute !in NeuroTruthRoutes.PRE_AUTH

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
                    onOpenSettings = { /* NT-09 is P1 and is not part of this UI layer. */ },
                    onOpenCameraMeasurement = { /* NT-04R is P1 and is not part of this layer. */ },
                )
            }

            composable(NeuroTruthRoutes.DASHBOARD) {
                DashboardPlaceholder()
            }

            composable(NeuroTruthRoutes.CHAT) {
                ChatScreen(
                    onFinished = { navController.switchTab(NeuroTruthRoutes.HOME) },
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

/**
 * NT-08 is P1 and outside this layer's scope. The tab is real so the bar keeps its three
 * destinations; the screen states plainly that it is not built yet rather than showing zeros.
 */
@Composable
private fun DashboardPlaceholder() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(NeuroTruthSpacing.screenHorizontal),
        verticalArrangement = Arrangement.spacedBy(NeuroTruthSpacing.betweenRows, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "대시보드",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { contentDescription = "대시보드 화면" },
        )
        Text(
            text = "기록 화면은 아직 준비 중이에요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
