package com.example.aichat.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.aichat.data.local.AiSettings
import com.example.aichat.data.model.Conversation
import com.example.aichat.data.model.WebPlatform
import com.example.aichat.ui.viewmodel.ChatViewModel
import com.example.aichat.ui.viewmodel.WebPlatformsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ConversationsScreen(
    viewModel: ChatViewModel,
    settings: AiSettings,
    onBack: () -> Unit, // غير مستخدم حالياً، أبقيته للتوافق
    onOpenChat: (Long) -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMemory: () -> Unit,
    webPlatformsViewModel: WebPlatformsViewModel,
    onOpenPlatform: (WebPlatform) -> Unit,
    onManagePlatforms: () -> Unit
) {
    val conversations by viewModel.conversations.collectAsState()
    val allPlatforms by webPlatformsViewModel.platforms.collectAsState()

    // ✅ جديد: منع Race condition قبل اكتمال seed/patch للمنصات
    val seedReady by webPlatformsViewModel.seedReady.collectAsState()

    val enabledPlatforms = remember(allPlatforms) { allPlatforms.filter { it.isEnabled } }

    var showDeleteDialog by remember { mutableStateOf<Conversation?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AiChat", fontWeight = FontWeight.Bold)
                        Text(
                            text = settings.getActiveModel().take(30),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenMemory) {
                        Icon(Icons.Default.Memory, contentDescription = "الذاكرة")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "الإعدادات")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewChat,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("محادثة جديدة") }
            )
        }
    ) { padding ->

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {

            // ── شريط منصات الويب ──────────────────────────────────────────
            if (enabledPlatforms.isNotEmpty()) {

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "منصات الويب",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (!seedReady) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }

                    TextButton(onClick = onManagePlatforms) {
                        Text("إدارة")
                    }
                }

                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(enabledPlatforms, key = { it.id }) { platform ->
                        SuggestionChip(
                            enabled = seedReady && platform.isEnabled,
                            onClick = { if (seedReady && platform.isEnabled) onOpenPlatform(platform) },
                            label = {
                                Text(
                                    "${platform.iconEmoji} ${platform.name}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 4.dp))
            }

            // ── قائمة المحادثات ────────────────────────────────────────────
            if (conversations.isEmpty()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Chat,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "لا توجد محادثات بعد",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "اضغط + لبدء محادثة جديدة",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 88.dp)
                ) {
                    items(conversations, key = { it.id }) { conversation ->
                        ConversationItem(
                            conversation = conversation,
                            onOpen = { onOpenChat(conversation.id) },
                            onDelete = { showDeleteDialog = conversation }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    // ── Dialog حذف المحادثة ────────────────────────────────────────────────
    showDeleteDialog?.let { conv ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("حذف المحادثة") },
            text = { Text("هل تريد حذف \"${conv.title}\"؟ لا يمكن التراجع.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteConversation(conv)
                        showDeleteDialog = null
                    }
                ) {
                    Text("حذف", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("إلغاء")
                }
            }
        )
    }
}

@Composable
private fun ConversationItem(
    conversation: Conversation,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        headlineContent = {
            Text(
                text = conversation.title.ifBlank { "محادثة بدون عنوان" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium
            )
        },
        supportingContent = {
            Text(
                text = formatDate(conversation.updatedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingContent = {
            Icon(
                imageVector = Icons.Default.Chat,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "حذف",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

private fun formatDate(timestamp: Long): String {
    val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    return fmt.format(Date(timestamp))
}
