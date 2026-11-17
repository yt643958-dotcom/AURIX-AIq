package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.automation.AutomationResult
import com.example.ui.PermissionReadiness
import com.example.ui.components.AurixBrandHeader
import com.example.ui.theme.AurixAmber
import com.example.ui.theme.AurixElectricBlue
import com.example.ui.theme.AurixEmerald
import com.example.ui.theme.AurixGlass
import com.example.ui.theme.AurixGlassBorder
import com.example.ui.theme.AurixMidnight
import com.example.ui.theme.AurixNeonCyan
import com.example.ui.theme.AurixNeonPurple
import com.example.ui.theme.AurixObsidian
import com.example.ui.theme.AurixSurface
import com.example.ui.theme.AurixTextPrimary
import com.example.ui.theme.AurixTextSecondary

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutomationScreen(
    permissions: PermissionReadiness,
    onRequestMic: () -> Unit,
    onRequestNotifications: () -> Unit,
    onLaunchApp: (String) -> AutomationResult,
    onOpenSetting: (String) -> AutomationResult,
    onRunAssistantCommand: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var feedback by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(AurixObsidian, AurixMidnight, AurixObsidian)))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        AurixBrandHeader(subtitle = "Phone Shortcuts & Social Draft Assistant")

        Spacer(modifier = Modifier.height(14.dp))

        if (!feedback.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(AurixElectricBlue.copy(alpha = 0.16f))
                    .border(1.dp, AurixNeonCyan.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = feedback.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AurixTextPrimary
                )
            }
        }

        // 1. Permissions & Service Authorization Matrix
        SectionHeader(title = "Permissions & System Readiness")
        Spacer(modifier = Modifier.height(8.dp))
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(AurixGlass)
                .border(1.dp, AurixGlassBorder, RoundedCornerShape(20.dp))
                .padding(14.dp)
        ) {
            PermissionStatusRow(
                icon = Icons.Filled.Mic,
                title = "Microphone (Live Voice)",
                granted = permissions.micGranted,
                actionLabel = "Grant",
                onAction = onRequestMic
            )
            PermissionStatusRow(
                icon = Icons.Filled.Notifications,
                title = "Background Notification",
                granted = permissions.notificationsGranted,
                actionLabel = "Allow",
                onAction = onRequestNotifications
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 2. Confirmation-Gated Facebook & Social Draft Assistant
        SectionHeader(title = "Confirmation-Based Social Assistant")
        Spacer(modifier = Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(AurixSurface.copy(alpha = 0.85f))
                .border(1.dp, AurixNeonPurple.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                .padding(14.dp)
        ) {
            Text(
                text = "Facebook Bengali Comment Assistant",
                style = MaterialTheme.typography.titleMedium,
                color = AurixNeonCyan,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "AURIX AI drafts a natural Bangladeshi Bengali comment for you. Review or edit it, tap Approve to copy it to your clipboard and open Facebook safely.",
                style = MaterialTheme.typography.bodyMedium,
                color = AurixTextSecondary
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    onRunAssistantCommand("Facebook-এ একটি সুন্দর বাংলা কমেন্ট লিখে দাও এবং Approve কার্ড দেখাও।")
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = AurixNeonPurple,
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("run_fb_automation_btn")
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Draft Facebook Comment")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 3. Authorized App Launchers & Settings Shortcuts
        SectionHeader(title = "Authorized Apps & Settings")
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            QuickAppChip("Facebook", Icons.Filled.Public) {
                feedback = formatResult(onLaunchApp("facebook"))
            }
            QuickAppChip("WhatsApp", Icons.Filled.Forum) {
                feedback = formatResult(onLaunchApp("whatsapp"))
            }
            QuickAppChip("YouTube", Icons.Filled.PlayArrow) {
                feedback = formatResult(onLaunchApp("youtube"))
            }
            QuickAppChip("Chrome", Icons.Filled.Public) {
                feedback = formatResult(onLaunchApp("chrome"))
            }
            QuickAppChip("Wi-Fi", Icons.Filled.Wifi) {
                feedback = formatResult(onOpenSetting("wifi"))
            }
            QuickAppChip("Bluetooth", Icons.Filled.Bluetooth) {
                feedback = formatResult(onOpenSetting("bluetooth"))
            }
            QuickAppChip("Settings", Icons.Filled.Settings) {
                feedback = formatResult(onOpenSetting("general"))
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = AurixTextPrimary,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun PermissionStatusRow(
    icon: ImageVector,
    title: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = if (granted) AurixEmerald else AurixAmber,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = AurixTextPrimary
            )
            Text(
                text = if (granted) "Authorized" else "Permission needed",
                style = MaterialTheme.typography.labelSmall,
                color = if (granted) AurixEmerald else AurixAmber
            )
        }
        if (granted) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "Granted",
                tint = AurixEmerald,
                modifier = Modifier.size(22.dp)
            )
        } else {
            OutlinedButton(
                onClick = onAction,
                modifier = Modifier.height(42.dp)
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = AurixAmber, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun QuickAppChip(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(AurixSurface)
            .border(1.dp, AurixGlassBorder, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp)
    ) {
        Icon(icon, contentDescription = label, tint = AurixNeonCyan, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = AurixTextPrimary)
    }
}

private fun formatResult(result: AutomationResult): String = when (result) {
    is AutomationResult.Success -> result.message
    is AutomationResult.RequiresPermission -> "${result.permissionName}: ${result.guidance}"
    is AutomationResult.Failed -> result.reason
}
