package com.example.aichat.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    viewModel:             ChatViewModel,
    settings:              AiSettings,
    onBack:                () -> Unit,
    onOpenChat:            (Long) -> Unit,      // ← جديد
    onNewChat:             () -> Unit,           // ← جديد
    onOpenSettings:        () -> Unit,           // ← جديد
    onOpenMemory:          () -> Unit,
    webPlatformsViewModel: WebPlatformsViewModel,
    onOpenPlatform:        (WebPlatform) -> Unit,
    onManagePlatforms:     () -> Unit
) {
    val conversations    by viewModel.conversations.collectAsState()
    val allPlatforms     by webPlatformsViewModel.platforms.collectAsState()
    val enabledPlatforms  = allPlatforms.filter { it.isEnabled }

    var showDeleteDialog by remember { mutableStateOf<Conversation?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AiChat", fontWeight = FontWeight.Bold)
                        Text(
                            text  = settings.getActiveModel().take(30),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenMemory) {
                        Icon(Icons.Default.Memory, contentDescription = "الذاكرة")
                    }
                    // ✅ مُصلَح: يستدعي onOpenSettings بدل lambda فارغة
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
                // ✅ مُصلَح: يستدعي onNewChat بدل onBack
                onClick = onNewChat,
                icon    = { Icon(Icons.Default.Add, contentDescription = null) },
                text    = { Text("محادثة جديدة") }
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
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text  = "منصات الويب",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = onManagePlatforms) {
                        Text("إدارة")
                    }
                }

                LazyRow(
                    contentPadding        = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(enabledPlatforms, key = { it.id }) { platform ->
                        SuggestionChip(
                            onClick = { onOpenPlatform(platform) },
                            label   = {
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
                            imageVector        = Icons.Default.Chat,
                            contentDescription = null,
                            modifier           = Modifier.size(56.dp),
                            tint               = MaterialTheme.colorScheme
                                .onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text  = "لا توجد محادثات بعد",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text  = "اضغط + لبدء محادثة جديدة",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                .copy(alpha = 0.6f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier       = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 88.dp)
                ) {
                    items(conversations, key = { it.id }) { conversation ->
                        ConversationItem(
                            conversation = conversation,
                            // ✅ مُصلَح: يستدعي onOpenChat بدل viewModel مباشرة
                            onOpen  = { onOpenChat(conversation.id) },
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
            title   = { Text("حذف المحادثة") },
            text    = { Text("هل تريد حذف \"${conv.title}\"؟ لا يمكن التراجع.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteConversation(conv)
                    showDeleteDialog = null
                }) {
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

// ── ConversationItem ──────────────────────────────────────────────────────────

@Composable
private fun ConversationItem(
    conversation: Conversation,
    onOpen:       () -> Unit,
    onDelete:     () -> Unit
) {
    ListItem(
        modifier        = Modifier.clickable(onClick = onOpen),
        headlineContent = {
            Text(
                text       = conversation.title.ifBlank { "محادثة بدون عنوان" },
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium
            )
        },
        supportingContent = {
            Text(
                text  = formatDate(conversation.updatedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingContent = {
            Icon(
                imageVector        = Icons.Default.Chat,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.primary
            )
        },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector        = Icons.Default.Delete,
                    contentDescription = "حذف",
                    tint               = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

private fun formatDate(timestamp: Long): String {
    val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    return fmt.format(Date(timestamp))
}
