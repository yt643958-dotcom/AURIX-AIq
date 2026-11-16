package com.example.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.ConversationEntity
import com.example.data.local.MemoryFactEntity
import com.example.ui.components.AurixBrandHeader
import com.example.ui.theme.AurixElectricBlue
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFmt = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())

@Composable
fun HistoryScreen(
    conversations: List<ConversationEntity>,
    activeConversationId: Long,
    memoryFacts: List<MemoryFactEntity>,
    totalMessageCount: Int,
    onSelectConversation: (Long) -> Unit,
    onDeleteConversation: (Long) -> Unit,
    onClearAllHistory: () -> Unit,
    onAddMemoryFact: (String) -> Unit,
    onDeleteMemoryFact: (Long) -> Unit,
    onClearAllMemories: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var newMemoryText by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(AurixObsidian, AurixMidnight, AurixObsidian)))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        AurixBrandHeader(subtitle = "Chat History ($totalMessageCount msgs) & Memory")

        Spacer(modifier = Modifier.height(14.dp))

        // Segmented Switch: Chat History vs Memory
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            TabPill(
                label = "Conversations (${conversations.size})",
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                modifier = Modifier
                    .weight(1f)
                    .testTag("tab_conversations")
            )
            TabPill(
                label = "Memory (${memoryFacts.size})",
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                modifier = Modifier
                    .weight(1f)
                    .testTag("tab_memory")
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (selectedTab == 0) {
            if (conversations.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = onClearAllHistory,
                        modifier = Modifier
                            .height(44.dp)
                            .testTag("clear_all_history_btn")
                    ) {
                        Icon(
                            Icons.Filled.DeleteSweep,
                            contentDescription = null,
                            tint = AurixRoseError,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Clear All History", color = AurixRoseError)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                if (conversations.isEmpty()) {
                    item {
                        EmptyInfoCard(
                            icon = Icons.Filled.Forum,
                            message = "No saved conversations yet. Start chatting with AURIX AI in Bengali or English."
                        )
                    }
                } else {
                    items(conversations, key = { it.id }) { conv ->
                        val isActive = conv.id == activeConversationId
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(if (isActive) AurixElectricBlue.copy(alpha = 0.16f) else AurixGlass)
                                .border(
                                    1.dp,
                                    if (isActive) AurixNeonCyan else AurixGlassBorder,
                                    RoundedCornerShape(18.dp)
                                )
                                .clickable { onSelectConversation(conv.id) }
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Forum,
                                contentDescription = null,
                                tint = if (isActive) AurixNeonCyan else AurixNeonPurple,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = conv.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = AurixTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${conv.languageCode} • ${dateFmt.format(Date(conv.updatedAt))}" +
                                        if (isActive) " • Active" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isActive) AurixNeonCyan else AurixTextSecondary
                                )
                            }
                            IconButton(
                                onClick = { onDeleteConversation(conv.id) },
                                modifier = Modifier.size(44.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = "Delete conversation",
                                    tint = AurixRoseError
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // Memory Manager
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = newMemoryText,
                    onValueChange = { newMemoryText = it },
                    placeholder = { Text("Add preference (e.g. Call me Riyad)") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AurixNeonCyan,
                        unfocusedBorderColor = AurixGlassBorder,
                        focusedTextColor = AurixTextPrimary,
                        unfocusedTextColor = AurixTextPrimary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("add_memory_input")
                )
                Button(
                    onClick = {
                        if (newMemoryText.isNotBlank()) {
                            onAddMemoryFact(newMemoryText)
                            newMemoryText = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AurixNeonPurple),
                    modifier = Modifier
                        .height(52.dp)
                        .testTag("add_memory_btn")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Add memory", tint = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Privacy Guard: Passwords, API keys, and OTPs are automatically blocked from memory.",
                style = MaterialTheme.typography.labelSmall,
                color = AurixTextMuted
            )

            if (memoryFacts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = onClearAllMemories,
                        modifier = Modifier.height(42.dp)
                    ) {
                        Text("Clear All Memory", color = AurixRoseError)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                if (memoryFacts.isEmpty()) {
                    item {
                        EmptyInfoCard(
                            icon = Icons.Filled.Psychology,
                            message = "No memory facts saved yet. AURIX AI remembers your preferred language, voice, and approved context."
                        )
                    }
                } else {
                    items(memoryFacts, key = { it.id }) { fact ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(AurixSurface)
                                .border(1.dp, AurixGlassBorder, RoundedCornerShape(16.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Psychology,
                                contentDescription = null,
                                tint = AurixNeonCyan,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = fact.fact,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = AurixTextPrimary
                                )
                                Text(
                                    text = "${fact.category} • ${dateFmt.format(Date(fact.createdAt))}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AurixTextMuted
                                )
                            }
                            IconButton(
                                onClick = { onDeleteMemoryFact(fact.id) },
                                modifier = Modifier.size(44.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = "Delete memory",
                                    tint = AurixRoseError
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) AurixNeonCyan.copy(alpha = 0.2f) else AurixSurface
            )
            .border(
                1.dp,
                if (selected) AurixNeonCyan else AurixGlassBorder,
                RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) AurixNeonCyan else AurixTextSecondary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun EmptyInfoCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    message: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(AurixGlass)
            .border(1.dp, AurixGlassBorder, RoundedCornerShape(20.dp))
            .padding(24.dp)
    ) {
        Icon(icon, contentDescription = null, tint = AurixNeonCyan, modifier = Modifier.size(36.dp))
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = AurixTextSecondary
        )
    }
}
