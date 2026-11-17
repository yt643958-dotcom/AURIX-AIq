package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.AppCheckStatus
import com.example.core.AurixIdentity
import com.example.core.AurixLanguage
import com.example.core.AurixVoice
import com.example.data.local.AurixSettings
import com.example.ui.PermissionReadiness
import com.example.ui.components.AurixBrandHeader
import com.example.ui.components.AurixLogoMark
import com.example.ui.theme.AurixAmber
import com.example.ui.theme.AurixEmerald
import com.example.ui.theme.AurixGlass
import com.example.ui.theme.AurixGlassBorder
import com.example.ui.theme.AurixMidnight
import com.example.ui.theme.AurixNeonCyan
import com.example.ui.theme.AurixNeonPurple
import com.example.ui.theme.AurixObsidian
import com.example.ui.theme.AurixRoseError
import com.example.ui.theme.AurixSurface
import com.example.ui.theme.AurixTextMuted
import com.example.ui.theme.AurixTextPrimary
import com.example.ui.theme.AurixTextSecondary
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AurixSettings,
    permissions: PermissionReadiness,
    appCheckStatus: AppCheckStatus,
    appCheckDiagnostic: String,
    totalMessageCount: Int,
    memoryCount: Int,
    onSelectLanguage: (AurixLanguage) -> Unit,
    onSelectVoice: (AurixVoice) -> Unit,
    onVoiceSpeedChange: (Float) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onToggleBackgroundService: (Boolean) -> Unit,
    onToggleMemory: (Boolean) -> Unit,
    onOpenHistoryTab: () -> Unit,
    onClearHistory: () -> Unit,
    onOpenFacebook: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(AurixObsidian, AurixMidnight, AurixObsidian)))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AurixBrandHeader(subtitle = "Voice, Language, Background & Privacy Controls")

        // 1. Language Selection
        SettingsCard(icon = Icons.Filled.Language, title = "Language Selection (ভাষা)") {
            Text(
                text = "Bangladeshi Bengali (bn-BD) is the primary default language.",
                style = MaterialTheme.typography.bodyMedium,
                color = AurixTextSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AurixLanguage.entries.forEach { lang ->
                    val selected = settings.language == lang
                    SelectableChip(
                        label = lang.nativeName,
                        selected = selected,
                        onClick = { onSelectLanguage(lang) }
                    )
                }
            }
        }

        // 2. Voice Selection
        SettingsCard(icon = Icons.Filled.RecordVoiceOver, title = "AI Voice Selection") {
            Text(
                text = "Default: Natural Male Voice (${AurixVoice.CHARON.displayName}) for Bangladeshi Bengali & English.",
                style = MaterialTheme.typography.bodyMedium,
                color = AurixTextSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AurixVoice.entries.forEach { voice ->
                    SelectableChip(
                        label = "${voice.displayName} (${voice.gender})",
                        selected = settings.voice == voice,
                        onClick = { onSelectVoice(voice) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Selected: ${settings.voice.displayName} — ${settings.voice.toneDescription}",
                style = MaterialTheme.typography.labelSmall,
                color = AurixNeonCyan
            )
        }

        // 3 & 4. Voice Speed & Volume Settings
        SettingsCard(icon = Icons.Filled.Speed, title = "Speaking Speed & Volume") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Voice Speed",
                    style = MaterialTheme.typography.bodyLarge,
                    color = AurixTextPrimary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${String.format("%.2f", settings.voiceSpeed)}x",
                    style = MaterialTheme.typography.labelMedium,
                    color = AurixNeonCyan
                )
            }
            Slider(
                value = settings.voiceSpeed,
                onValueChange = onVoiceSpeedChange,
                valueRange = 0.75f..1.50f,
                colors = SliderDefaults.colors(
                    thumbColor = AurixNeonCyan,
                    activeTrackColor = AurixNeonCyan
                )
            )

            Spacer(modifier = Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.VolumeUp, contentDescription = null, tint = AurixNeonPurple, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Output Volume",
                    style = MaterialTheme.typography.bodyLarge,
                    color = AurixTextPrimary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${(settings.volume * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = AurixNeonPurple
                )
            }
            Slider(
                value = settings.volume,
                onValueChange = onVolumeChange,
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = AurixNeonPurple,
                    activeTrackColor = AurixNeonPurple
                )
            )
        }

        // 5. Background Assistant Settings
        SettingsCard(icon = Icons.Filled.Layers, title = "Background Assistant") {
            SwitchRow(
                title = "Background Foreground Service",
                subtitle = if (permissions.notificationsGranted) {
                    "Keeps live assistant & persistent notification controls active"
                } else {
                    "Allow notifications in Control tab to show persistent controls"
                },
                checked = settings.backgroundServiceEnabled,
                onCheckedChange = onToggleBackgroundService
            )
        }

        // 7 & 8. Chat History & Memory Settings
        SettingsCard(icon = Icons.Filled.Psychology, title = "Chat History & Memory") {
            SwitchRow(
                title = "Personalization Memory",
                subtitle = "$memoryCount stored context facts • Passwords never stored",
                checked = settings.memoryEnabled,
                onCheckedChange = onToggleMemory
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onOpenHistoryTab,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                ) {
                    Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("History ($totalMessageCount)")
                }
                OutlinedButton(
                    onClick = onClearHistory,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                ) {
                    Text("Clear Chats", color = AurixRoseError)
                }
            }
        }

        // 9. API Connection Status
        SettingsCard(icon = Icons.Filled.CloudDone, title = "Gemini API Connection Status") {
            val badgeColor = when (appCheckStatus) {
                AppCheckStatus.DEBUG_ACTIVE, AppCheckStatus.RECAPTCHA_ACTIVE -> AurixEmerald
                AppCheckStatus.INITIALIZING -> AurixAmber
                AppCheckStatus.ERROR -> AurixRoseError
            }
            InfoLine("Backend", "Firebase AI Logic (App Check Protected)")
            InfoLine("App Check", appCheckDiagnostic, valueColor = badgeColor)
            InfoLine("Live Voice Model", AurixIdentity.LIVE_MODEL_NAME)
            InfoLine(
                "Text Models",
                "${AurixIdentity.TEXT_MODEL_NAME} / ${AurixIdentity.TEXT_FALLBACK_MODEL_NAME}"
            )
        }

        // 10. Privacy Settings
        SettingsCard(icon = Icons.Filled.Lock, title = "Privacy & Security") {
            Text(
                text = "• All conversation history and memory facts stay in local Room SQLite storage.\n" +
                    "• Sensitive credentials (passwords, API keys, OTPs) are never saved to memory.\n" +
                    "• External actions (like posting Facebook comments) always require explicit confirmation.",
                style = MaterialTheme.typography.bodyMedium,
                color = AurixTextSecondary
            )
        }

        // 11. Application Information (AURIX AI + RIAD + Official Facebook)
        SettingsCard(icon = Icons.Filled.Info, title = "Application Information") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AurixLogoMark(size = 54.dp, cornerRadius = 14.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "${AurixIdentity.APP_NAME} v${AurixIdentity.APP_VERSION}",
                        style = MaterialTheme.typography.titleMedium,
                        color = AurixTextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Owner, Creator & Developer: ${AurixIdentity.OWNER_NAME}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AurixNeonCyan
                    )
                    Text(
                        text = AurixIdentity.OFFICIAL_FACEBOOK_URL,
                        style = MaterialTheme.typography.labelSmall,
                        color = AurixTextMuted
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = onOpenFacebook,
                colors = ButtonDefaults.buttonColors(containerColor = AurixNeonCyan, contentColor = Color.Black),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("open_owner_facebook_btn")
            ) {
                Icon(Icons.Filled.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Open RIAD's Official Facebook")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsCard(
    icon: ImageVector,
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(AurixGlass)
            .border(1.dp, AurixGlassBorder, RoundedCornerShape(20.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = AurixNeonCyan, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = AurixTextPrimary,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun SelectableChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) AurixNeonCyan.copy(alpha = 0.22f) else AurixSurface)
            .border(1.dp, if (selected) AurixNeonCyan else AurixGlassBorder, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) AurixNeonCyan else AurixTextPrimary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = AurixTextPrimary)
            Text(text = subtitle, style = MaterialTheme.typography.labelSmall, color = AurixTextSecondary)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = AurixNeonCyan
            )
        )
    }
}

@Composable
private fun InfoLine(
    label: String,
    value: String,
    valueColor: Color = AurixTextPrimary
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = AurixTextSecondary)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = value, style = MaterialTheme.typography.labelMedium, color = valueColor)
    }
}
