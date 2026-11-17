package com.example.ui.components

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.automation.PendingActionConfirmation
import com.example.core.AurixIdentity
import com.example.core.AurixLanguage
import com.example.data.local.MessageEntity
import com.example.ui.theme.AurixAmber
import com.example.ui.theme.AurixElectricBlue
import com.example.ui.theme.AurixEmerald
import com.example.ui.theme.AurixGlass
import com.example.ui.theme.AurixGlassBorder
import com.example.ui.theme.AurixMidnight
import com.example.ui.theme.AurixNeonCyan
import com.example.ui.theme.AurixNeonPurple
import com.example.ui.theme.AurixSurface
import com.example.ui.theme.AurixSurfaceElevated
import com.example.ui.theme.AurixTextMuted
import com.example.ui.theme.AurixTextPrimary
import com.example.ui.theme.AurixTextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormatter = SimpleDateFormat("hh:mm a", Locale.getDefault())

@Composable
fun ChatMessageBubble(
    message: MessageEntity,
    modifier: Modifier = Modifier
) {
    val isUser = message.role == "user"
    val isAction = message.role == "system_action"

    if (isAction) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(AurixEmerald.copy(alpha = 0.12f))
                    .border(1.dp, AurixEmerald.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Terminal,
                    contentDescription = "System action",
                    tint = AurixEmerald,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AurixTextPrimary,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
        }
        return
    }

    val alignment = if (isUser) Alignment.End else Alignment.Start
    val bubbleBrush = if (isUser) {
        Brush.linearGradient(
            colors = listOf(
                AurixElectricBlue.copy(alpha = 0.34f),
                AurixNeonPurple.copy(alpha = 0.28f)
            )
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                AurixSurfaceElevated.copy(alpha = 0.92f),
                AurixSurface.copy(alpha = 0.92f)
            )
        )
    }
    val borderColor = if (isUser) {
        AurixNeonCyan.copy(alpha = 0.50f)
    } else {
        AurixNeonPurple.copy(alpha = 0.42f)
    }
    val shape = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomStart = if (isUser) 20.dp else 6.dp,
        bottomEnd = if (isUser) 6.dp else 20.dp
    )

    Column(
        horizontalAlignment = alignment,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(shape)
                .background(bubbleBrush)
                .border(1.dp, borderColor, shape)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (isUser) "You" else AurixIdentity.APP_NAME,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isUser) AurixNeonCyan else Color(0xFFDAB6FF),
                        fontWeight = FontWeight.Bold
                    )
                    Icon(
                        imageVector = if (message.inputMode == "VOICE") Icons.Filled.GraphicEq else Icons.Filled.Keyboard,
                        contentDescription = message.inputMode,
                        tint = AurixTextMuted,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = timeFormatter.format(Date(message.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = AurixTextMuted
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = AurixTextPrimary
                )
            }
        }
    }
}

@Composable
fun LiveTranscriptBanner(
    userTranscript: String,
    aiTranscript: String,
    modifier: Modifier = Modifier
) {
    if (userTranscript.isBlank() && aiTranscript.isBlank()) return

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AurixGlass)
            .border(1.dp, AurixGlassBorder, RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        if (userTranscript.isNotBlank()) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = "YOU (Live): ",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurixNeonCyan
                )
                Text(
                    text = userTranscript,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AurixTextPrimary
                )
            }
        }
        if (aiTranscript.isNotBlank()) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = "AURIX: ",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurixNeonPurple
                )
                Text(
                    text = aiTranscript,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AurixTextPrimary
                )
            }
        }
    }
}

@Composable
fun PendingActionConfirmationCard(
    pending: PendingActionConfirmation,
    onEditProposedText: (String) -> Unit,
    onApprove: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(AurixMidnight.copy(alpha = 0.96f))
            .border(
                width = 1.5.dp,
                brush = Brush.linearGradient(listOf(AurixAmber, AurixNeonPurple)),
                shape = RoundedCornerShape(20.dp)
            )
            .padding(14.dp)
            .testTag("pending_confirmation_card")
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Security,
                contentDescription = "Confirmation required",
                tint = AurixAmber,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = pending.actionTitle,
                style = MaterialTheme.typography.titleMedium,
                color = AurixAmber,
                fontWeight = FontWeight.Bold
            )
        }

        if (pending.postSummary.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Post Context: ${pending.postSummary}",
                style = MaterialTheme.typography.bodyMedium,
                color = AurixTextSecondary
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        OutlinedTextField(
            value = pending.proposedText,
            onValueChange = onEditProposedText,
            label = { Text("Proposed Text (${pending.appName})") },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AurixNeonCyan,
                unfocusedBorderColor = AurixGlassBorder,
                focusedTextColor = AurixTextPrimary,
                unfocusedTextColor = AurixTextPrimary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("proposed_comment_input")
        )

        Spacer(modifier = Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("cancel_action_button")
            ) {
                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Cancel")
            }

            Button(
                onClick = onApprove,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AurixEmerald,
                    contentColor = Color.Black
                ),
                modifier = Modifier
                    .weight(1.3f)
                    .height(48.dp)
                    .testTag("approve_action_button")
            ) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Copy & Open ${pending.appName}")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AurixEmptyHero(
    language: AurixLanguage,
    onPromptClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val suggestions = if (language == AurixLanguage.ENGLISH) {
        listOf(
            "Who is your owner?",
            "What is RIAD's Facebook?",
            "Open YouTube",
            "Draft a nice Bengali Facebook comment for me",
            "বাংলায় কথা বলো"
        )
    } else {
        listOf(
            "কেমন আছো AURIX?",
            "তোমার মালিক কে?",
            "RIAD-এর Facebook লিংক দাও",
            "Facebook-এ দেওয়ার জন্য একটা সুন্দর বাংলা কমেন্ট লিখে দাও।",
            "Speak English"
        )
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = if (language == AurixLanguage.ENGLISH) {
                "Tap the glowing AURIX orb to start real-time voice, or type below."
            } else {
                "লাইভ ভয়েসে কথা বলতে AURIX অরবে ট্যাপ করুন, অথবা নিচে লিখে জিজ্ঞাসা করুন।"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = AurixTextSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(14.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            suggestions.forEach { prompt ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(AurixSurface.copy(alpha = 0.85f))
                        .border(1.dp, AurixGlassBorder, RoundedCornerShape(16.dp))
                        .clickable { onPromptClick(prompt) }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = AurixNeonCyan,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = prompt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AurixTextPrimary
                    )
                }
            }
        }
    }
}
