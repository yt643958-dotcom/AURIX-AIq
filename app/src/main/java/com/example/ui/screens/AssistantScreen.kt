package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.ai.TextChatState
import com.example.ai.VoiceSessionState
import com.example.automation.PendingActionConfirmation
import com.example.core.AurixLanguage
import com.example.data.local.AurixSettings
import com.example.data.local.MessageEntity
import com.example.ui.PermissionReadiness
import com.example.ui.components.AurixBrandHeader
import com.example.ui.components.AurixEmptyHero
import com.example.ui.components.AurixVoiceOrb
import com.example.ui.components.ChatMessageBubble
import com.example.ui.components.LiveTranscriptBanner
import com.example.ui.components.PendingActionConfirmationCard
import com.example.ui.components.VoiceWaveformVisualizer
import com.example.ui.theme.AurixElectricBlue
import com.example.ui.theme.AurixEmerald
import com.example.ui.theme.AurixGlass
import com.example.ui.theme.AurixGlassBorder
import com.example.ui.theme.AurixMidnight
import com.example.ui.theme.AurixNeonCyan
import com.example.ui.theme.AurixNeonPurple
import com.example.ui.theme.AurixObsidian
import com.example.ui.theme.AurixRoseError
import com.example.ui.theme.AurixSurface
import com.example.ui.theme.AurixTextPrimary
import com.example.ui.theme.AurixTextSecondary

@Composable
fun AssistantScreen(
    settings: AurixSettings,
    permissions: PermissionReadiness,
    messages: List<MessageEntity>,
    voiceState: VoiceSessionState,
    isMuted: Boolean,
    micAmplitude: Float,
    aiAmplitude: Float,
    liveUserTranscript: String,
    liveAiTranscript: String,
    textChatState: TextChatState,
    statusBanner: String?,
    pendingConfirmation: PendingActionConfirmation?,
    onRequestMicAndStartVoice: () -> Unit,
    onStopVoice: () -> Unit,
    onToggleMute: () -> Unit,
    onSendText: (String) -> Unit,
    onRetryLastMessage: () -> Unit,
    onDismissChatError: () -> Unit,
    onNewChat: () -> Unit,
    onCycleLanguage: () -> Unit,
    onDismissBanner: () -> Unit,
    onOpenFloatingWindow: () -> Unit,
    onEditPendingText: (String) -> Unit,
    onApprovePending: () -> Unit,
    onCancelPending: () -> Unit,
    modifier: Modifier = Modifier
) {
    var inputText by rememberSaveable { mutableStateOf("") }
    var orbExpanded by rememberSaveable { mutableStateOf(true) }
    val listState = rememberLazyListState()

    val isVoiceActive = voiceState == VoiceSessionState.LISTENING ||
        voiceState == VoiceSessionState.SPEAKING ||
        voiceState == VoiceSessionState.MUTED ||
        voiceState == VoiceSessionState.CONNECTING

    LaunchedEffect(messages.size, textChatState) {
        val extra = if (textChatState !is TextChatState.Idle) 1 else 0
        val targetIndex = (messages.size + extra - 1).coerceAtLeast(0)
        if (messages.isNotEmpty() || extra > 0) {
            listState.animateScrollToItem(targetIndex)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(AurixObsidian, AurixMidnight, AurixObsidian)
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Top HUD Header with exact AURIX AI logo + RIAD badge + Language & New Chat
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                AurixBrandHeader(
                    subtitle = "${settings.language.nativeName} • ${settings.voice.displayName}",
                    modifier = Modifier.weight(1f)
                )

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(AurixSurface)
                        .border(1.dp, AurixGlassBorder, RoundedCornerShape(12.dp))
                        .clickable(onClick = onCycleLanguage)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .testTag("language_pill")
                ) {
                    Text(
                        text = when (settings.language) {
                            AurixLanguage.BENGALI -> "বাংলা"
                            AurixLanguage.ENGLISH -> "EN"
                            AurixLanguage.MULTILINGUAL -> "AUTO"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = AurixNeonCyan
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                IconButton(
                    onClick = onOpenFloatingWindow,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("launch_floating_window_btn")
                ) {
                    Icon(
                        imageVector = Icons.Filled.OpenInNew,
                        contentDescription = "Open floating assistant window",
                        tint = AurixTextSecondary
                    )
                }

                IconButton(
                    onClick = onNewChat,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("new_chat_btn")
                ) {
                    Icon(
                        imageVector = Icons.Filled.AddComment,
                        contentDescription = "New conversation",
                        tint = AurixNeonCyan
                    )
                }
            }

            // Status / Error Banner
            AnimatedVisibility(visible = !statusBanner.isNullOrBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(AurixElectricBlue.copy(alpha = 0.16f))
                        .border(1.dp, AurixNeonCyan.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = statusBanner.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AurixTextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismissBanner, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = AurixTextSecondary)
                    }
                }
            }

            // Collapsible Voice Orb + Waveform Header Card
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(AurixGlass)
                    .border(1.dp, AurixGlassBorder, RoundedCornerShape(24.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val statusColor = when (voiceState) {
                        VoiceSessionState.LISTENING -> AurixNeonCyan
                        VoiceSessionState.SPEAKING -> AurixNeonPurple
                        VoiceSessionState.CONNECTING -> AurixElectricBlue
                        VoiceSessionState.MUTED -> AurixTextSecondary
                        VoiceSessionState.ERROR -> AurixRoseError
                        VoiceSessionState.DISCONNECTED -> AurixEmerald
                    }
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (voiceState) {
                            VoiceSessionState.DISCONNECTED -> if (settings.language == AurixLanguage.ENGLISH) {
                                "Gemini Live Ready • Tap Orb to Speak"
                            } else {
                                "Gemini Live প্রস্তুত • কথা বলতে অরবে ট্যাপ করুন"
                            }
                            VoiceSessionState.CONNECTING -> "Connecting to Gemini Live..."
                            VoiceSessionState.LISTENING -> if (settings.language == AurixLanguage.ENGLISH) {
                                "Listening live..."
                            } else {
                                "শুনছি... (বাংলা বা ইংরেজিতে বলুন)"
                            }
                            VoiceSessionState.SPEAKING -> "AURIX Speaking (Interrupt anytime)"
                            VoiceSessionState.MUTED -> "Mic Muted"
                            VoiceSessionState.ERROR -> "Live Voice Error — Tap to Retry"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { orbExpanded = !orbExpanded },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = if (orbExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = if (orbExpanded) "Collapse orb" else "Expand orb",
                            tint = AurixTextSecondary
                        )
                    }
                }

                AnimatedVisibility(visible = orbExpanded) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AurixVoiceOrb(
                            voiceState = voiceState,
                            micAmplitude = micAmplitude,
                            aiAmplitude = aiAmplitude,
                            orbSize = if (messages.isEmpty()) 172.dp else 128.dp,
                            onClick = {
                                if (isVoiceActive) {
                                    onStopVoice()
                                } else {
                                    onRequestMicAndStartVoice()
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        VoiceWaveformVisualizer(
                            voiceState = voiceState,
                            micAmplitude = micAmplitude,
                            aiAmplitude = aiAmplitude,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                        )
                    }
                }

                if (isVoiceActive) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .height(44.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(AurixSurface)
                                .border(1.dp, AurixGlassBorder, RoundedCornerShape(14.dp))
                                .clickable(onClick = onToggleMute)
                                .padding(horizontal = 14.dp)
                                .testTag("mute_toggle_btn")
                        ) {
                            Icon(
                                imageVector = if (isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                                contentDescription = if (isMuted) "Unmute mic" else "Mute mic",
                                tint = if (isMuted) AurixRoseError else AurixEmerald,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isMuted) "Unmute" else "Mute",
                                style = MaterialTheme.typography.labelLarge,
                                color = AurixTextPrimary
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .height(44.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(AurixRoseError.copy(alpha = 0.22f))
                                .border(1.dp, AurixRoseError.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                                .clickable(onClick = onStopVoice)
                                .padding(horizontal = 14.dp)
                                .testTag("stop_voice_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Stop,
                                contentDescription = "Stop voice conversation",
                                tint = AurixRoseError,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Stop Voice",
                                style = MaterialTheme.typography.labelLarge,
                                color = AurixRoseError
                            )
                        }
                    }
                }
            }

            // Live Speech Transcription Banner
            LiveTranscriptBanner(
                userTranscript = liveUserTranscript,
                aiTranscript = liveAiTranscript,
                modifier = Modifier.padding(top = 8.dp)
            )

            // Confirmation Card for Facebook comments / sensitive actions
            pendingConfirmation?.let { conf ->
                PendingActionConfirmationCard(
                    pending = conf,
                    onEditProposedText = onEditPendingText,
                    onApprove = onApprovePending,
                    onCancel = onCancelPending,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // Conversation Feed
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(vertical = 10.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (messages.isEmpty()) {
                    item {
                        AurixEmptyHero(
                            language = settings.language,
                            onPromptClick = { onSendText(it) }
                        )
                    }
                } else {
                    items(messages, key = { it.id }) { msg ->
                        ChatMessageBubble(message = msg)
                    }
                }

                when (textChatState) {
                    is TextChatState.Sending -> {
                        item(key = "chat_sending_indicator") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                            ) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    color = AurixNeonCyan,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (settings.language == AurixLanguage.ENGLISH) {
                                        "AURIX AI is thinking..."
                                    } else {
                                        "AURIX AI চিন্তা করছে..."
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AurixTextSecondary
                                )
                            }
                        }
                    }

                    is TextChatState.Streaming -> {
                        item(key = "chat_streaming_preview") {
                            ChatMessageBubble(
                                message = MessageEntity(
                                    id = -999L,
                                    conversationId = 0L,
                                    role = "model",
                                    content = textChatState.text,
                                    inputMode = "TEXT",
                                    languageCode = settings.language.code
                                )
                            )
                        }
                    }

                    is TextChatState.Error -> {
                        item(key = "chat_error_card") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(AurixRoseError.copy(alpha = 0.14f))
                                    .border(1.dp, AurixRoseError.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                                    .padding(12.dp)
                                    .testTag("chat_error_card")
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Filled.ErrorOutline,
                                        contentDescription = "Reply error",
                                        tint = AurixRoseError,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (settings.language == AurixLanguage.ENGLISH) {
                                            "AURIX AI could not reply"
                                        } else {
                                            "AURIX AI উত্তর দিতে পারেনি"
                                        },
                                        style = MaterialTheme.typography.labelLarge,
                                        color = AurixRoseError,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = textChatState.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AurixTextPrimary
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = onRetryLastMessage,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = AurixRoseError,
                                            contentColor = Color.White
                                        ),
                                        modifier = Modifier
                                            .height(48.dp)
                                            .testTag("retry_message_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Refresh,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            if (settings.language == AurixLanguage.ENGLISH) "Retry" else "আবার চেষ্টা করুন"
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = onDismissChatError,
                                        modifier = Modifier.height(48.dp)
                                    ) {
                                        Text(
                                            if (settings.language == AurixLanguage.ENGLISH) "Dismiss" else "বাদ দিন",
                                            color = AurixTextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    TextChatState.Idle -> Unit
                }
            }

            // Unified Text + Voice Composer Bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
            ) {
                IconButton(
                    onClick = {
                        if (isVoiceActive) onStopVoice() else onRequestMicAndStartVoice()
                    },
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(
                            if (isVoiceActive) AurixRoseError.copy(alpha = 0.22f)
                            else AurixNeonCyan.copy(alpha = 0.16f)
                        )
                        .border(
                            width = 1.dp,
                            color = if (isVoiceActive) AurixRoseError else AurixNeonCyan,
                            shape = CircleShape
                        )
                        .testTag("mic_button")
                ) {
                    Icon(
                        imageVector = if (isVoiceActive) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = if (isVoiceActive) "Stop voice" else "Start voice",
                        tint = if (isVoiceActive) AurixRoseError else AurixNeonCyan
                    )
                }

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = {
                        Text(
                            text = if (settings.language == AurixLanguage.ENGLISH) {
                                "Message AURIX AI..."
                            } else {
                                "বাংলায় বা ইংরেজিতে লিখুন..."
                            },
                            color = AurixTextSecondary
                        )
                    },
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (inputText.isNotBlank()) {
                                onSendText(inputText)
                                inputText = ""
                            }
                        }
                    ),
                    shape = RoundedCornerShape(22.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AurixNeonCyan,
                        unfocusedBorderColor = AurixGlassBorder,
                        focusedContainerColor = AurixSurface.copy(alpha = 0.85f),
                        unfocusedContainerColor = AurixSurface.copy(alpha = 0.72f),
                        focusedTextColor = AurixTextPrimary,
                        unfocusedTextColor = AurixTextPrimary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chat_input_field")
                )

                IconButton(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            onSendText(inputText)
                            inputText = ""
                        }
                    },
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(listOf(AurixElectricBlue, AurixNeonPurple))
                        )
                        .testTag("send_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send message",
                        tint = Color.White
                    )
                }
            }
        }
    }
}
