package com.example.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhonelinkSetup
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhonelinkSetup
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.core.AurixLanguage
import com.example.ui.screens.AssistantScreen
import com.example.ui.screens.AutomationScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.AurixMidnight
import com.example.ui.theme.AurixNeonCyan
import com.example.ui.theme.AurixObsidian
import com.example.ui.theme.AurixSurface
import com.example.ui.theme.AurixTextPrimary
import com.example.ui.theme.AurixTextSecondary

const val ROUTE_ASSISTANT = "assistant"
const val ROUTE_AUTOMATION = "automation"
const val ROUTE_HISTORY = "history"
const val ROUTE_SETTINGS = "settings"

private data class NavTab(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

private val aurixTabs = listOf(
    NavTab(ROUTE_ASSISTANT, "AURIX", Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome),
    NavTab(ROUTE_AUTOMATION, "Control", Icons.Filled.PhonelinkSetup, Icons.Outlined.PhonelinkSetup),
    NavTab(ROUTE_HISTORY, "History", Icons.Filled.History, Icons.Outlined.History),
    NavTab(ROUTE_SETTINGS, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
)

@Composable
fun AurixApp(
    viewModel: AurixViewModel,
    onRequestMicPermissionAndStartVoice: () -> Unit,
    onRequestMicPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val activeConversationId by viewModel.activeConversationId.collectAsStateWithLifecycle()
    val memoryFacts by viewModel.memoryFacts.collectAsStateWithLifecycle()
    val totalMessageCount by viewModel.totalMessageCount.collectAsStateWithLifecycle()

    val voiceState by viewModel.voiceState.collectAsStateWithLifecycle()
    val isMuted by viewModel.isMuted.collectAsStateWithLifecycle()
    val micAmplitude by viewModel.micAmplitude.collectAsStateWithLifecycle()
    val aiAmplitude by viewModel.aiAmplitude.collectAsStateWithLifecycle()
    val liveUserTranscript by viewModel.liveUserTranscript.collectAsStateWithLifecycle()
    val liveAiTranscript by viewModel.liveAiTranscript.collectAsStateWithLifecycle()
    val textChatState by viewModel.textChatState.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()
    val appCheckStatus by viewModel.appCheckStatus.collectAsStateWithLifecycle()
    val pendingConfirmation by viewModel.pendingConfirmation.collectAsStateWithLifecycle()
    val lastSnapshot by viewModel.lastScreenSnapshot.collectAsStateWithLifecycle()

    fun navigateTo(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 600.dp

        Scaffold(
            containerColor = AurixObsidian,
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                if (!useNavRail) {
                    NavigationBar(
                        containerColor = AurixMidnight,
                        contentColor = AurixTextPrimary
                    ) {
                        aurixTabs.forEach { tab ->
                            val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTo(tab.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = AurixNeonCyan,
                                    selectedTextColor = AurixNeonCyan,
                                    indicatorColor = AurixSurface,
                                    unselectedIconColor = AurixTextSecondary,
                                    unselectedTextColor = AurixTextSecondary
                                ),
                                modifier = Modifier.testTag("nav_${tab.route}")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavRail) {
                    NavigationRail(
                        containerColor = AurixMidnight,
                        contentColor = AurixTextPrimary
                    ) {
                        aurixTabs.forEach { tab ->
                            val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTo(tab.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = AurixNeonCyan,
                                    selectedTextColor = AurixNeonCyan,
                                    indicatorColor = AurixSurface,
                                    unselectedIconColor = AurixTextSecondary,
                                    unselectedTextColor = AurixTextSecondary
                                ),
                                modifier = Modifier.testTag("rail_${tab.route}")
                            )
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = ROUTE_ASSISTANT,
                    modifier = Modifier.weight(1f)
                ) {
                    composable(ROUTE_ASSISTANT) {
                        AssistantScreen(
                            settings = settings,
                            permissions = permissions,
                            messages = messages,
                            voiceState = voiceState,
                            isMuted = isMuted,
                            micAmplitude = micAmplitude,
                            aiAmplitude = aiAmplitude,
                            liveUserTranscript = liveUserTranscript,
                            liveAiTranscript = liveAiTranscript,
                            textChatState = textChatState,
                            statusBanner = statusBanner,
                            pendingConfirmation = pendingConfirmation,
                            onRequestMicAndStartVoice = onRequestMicPermissionAndStartVoice,
                            onStopVoice = { viewModel.stopVoiceConversation() },
                            onToggleMute = { viewModel.toggleMute() },
                            onSendText = { viewModel.sendTextMessage(it) },
                            onRetryLastMessage = { viewModel.retryLastMessage() },
                            onDismissChatError = { viewModel.clearTextChatError() },
                            onNewChat = { viewModel.startNewConversation() },
                            onCycleLanguage = {
                                val next = when (settings.language) {
                                    AurixLanguage.BENGALI -> AurixLanguage.ENGLISH
                                    AurixLanguage.ENGLISH -> AurixLanguage.MULTILINGUAL
                                    AurixLanguage.MULTILINGUAL -> AurixLanguage.BENGALI
                                }
                                viewModel.updateLanguage(next)
                            },
                            onDismissBanner = { viewModel.clearStatusBanner() },
                            onOpenFloatingWindow = { viewModel.launchCompactOverlayNow() },
                            onEditPendingText = { viewModel.updatePendingProposedText(it) },
                            onApprovePending = { viewModel.approvePendingConfirmation() },
                            onCancelPending = { viewModel.cancelPendingConfirmation() }
                        )
                    }

                    composable(ROUTE_AUTOMATION) {
                        AutomationScreen(
                            permissions = permissions,
                            lastSnapshot = lastSnapshot,
                            onRequestMic = onRequestMicPermission,
                            onRequestNotifications = onRequestNotificationPermission,
                            onOpenOverlaySettings = { viewModel.openOverlayPermissionSettings() },
                            onOpenAccessibilitySettings = { viewModel.openAccessibilityPermissionSettings() },
                            onLaunchApp = { viewModel.launchAppShortcut(it) },
                            onOpenSetting = { viewModel.openPhoneSettingsShortcut(it) },
                            onReadScreenNow = { viewModel.captureScreenNow() },
                            onRunAssistantCommand = { cmd ->
                                navigateTo(ROUTE_ASSISTANT)
                                viewModel.sendTextMessage(cmd)
                            }
                        )
                    }

                    composable(ROUTE_HISTORY) {
                        HistoryScreen(
                            conversations = conversations,
                            activeConversationId = activeConversationId,
                            memoryFacts = memoryFacts,
                            totalMessageCount = totalMessageCount,
                            onSelectConversation = { id ->
                                viewModel.selectConversation(id)
                                navigateTo(ROUTE_ASSISTANT)
                            },
                            onDeleteConversation = { viewModel.deleteConversation(it) },
                            onClearAllHistory = { viewModel.clearAllHistory() },
                            onAddMemoryFact = { viewModel.addMemoryFact(it) },
                            onDeleteMemoryFact = { viewModel.deleteMemoryFact(it) },
                            onClearAllMemories = { viewModel.clearAllMemories() }
                        )
                    }

                    composable(ROUTE_SETTINGS) {
                        SettingsScreen(
                            settings = settings,
                            permissions = permissions,
                            appCheckStatus = appCheckStatus,
                            appCheckDiagnostic = viewModel.getAppCheckDiagnostic(),
                            totalMessageCount = totalMessageCount,
                            memoryCount = memoryFacts.size,
                            onSelectLanguage = { viewModel.updateLanguage(it) },
                            onSelectVoice = { viewModel.updateVoice(it) },
                            onVoiceSpeedChange = { viewModel.updateVoiceSpeed(it) },
                            onVolumeChange = { viewModel.updateVolume(it) },
                            onToggleFloatingBubble = { viewModel.setFloatingBubbleEnabled(it) },
                            onToggleBackgroundService = { viewModel.setBackgroundServiceEnabled(it) },
                            onToggleMemory = { viewModel.setMemoryEnabled(it) },
                            onOpenOverlayPermission = { viewModel.openOverlayPermissionSettings() },
                            onOpenAccessibilitySettings = { viewModel.openAccessibilityPermissionSettings() },
                            onLaunchCompactOverlay = { viewModel.launchCompactOverlayNow() },
                            onOpenHistoryTab = { navigateTo(ROUTE_HISTORY) },
                            onClearHistory = { viewModel.clearAllHistory() },
                            onOpenFacebook = { viewModel.openFacebookOwnerProfile() }
                        )
                    }
                }
            }
        }
    }
}
