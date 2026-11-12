@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.aichat.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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

// ألوان دوائر المنصات (خلفية، حرف) — تُختار من اسم المنصة فيثبت لون كل منصة
private val AvatarPalette = listOf(
    Color(0xFFA8F0CF) to Color(0xFF0B3D2A),
    Color(0xFFBFE6FF) to Color(0xFF0A3550),
    Color(0xFFFFE58A) to Color(0xFF5A3A00),
    Color(0xFFFFC9DE) to Color(0xFF5C1230),
    Color(0xFFD9CCFF) to Color(0xFF2A0B73),
    Color(0xFFFFD3B0) to Color(0xFF5A2A00)
)

private fun avatarColors(name: String): Pair<Color, Color> {
    val i = (name.hashCode() and Int.MAX_VALUE) % AvatarPalette.size
    return AvatarPalette[i]
}

@Composable
fun ConversationsScreen(
    viewModel: ChatViewModel,
    settings: AiSettings,
    onBack: () -> Unit, // للتوافق (غير مستخدم حالياً)
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
    val seedReady by webPlatformsViewModel.seedReady.collectAsState()

    val enabledPlatforms = remember(allPlatforms) { allPlatforms.filter { it.isEnabled } }

    var showDeleteDialog by remember { mutableStateOf<Conversation?>(null) }

    val colors = MaterialTheme.colorScheme

    // الخلفية: تدرج هادئ + توهج ناعم في الأعلى (تدرجات عادية، بلا Blur)
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.background, colors.primaryContainer)))
    ) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .size(340.dp)
                .offset(y = (-110).dp)
                .background(
                    Brush.radialGradient(
                        listOf(colors.primary.copy(alpha = 0.20f), Color.Transparent)
                    )
                )
        )

        Scaffold(
            containerColor = Color.Transparent,
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = onNewChat,
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary,
                    shape = CircleShape,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("محادثة جديدة", fontWeight = FontWeight.Bold) }
                )
            }
        ) { padding ->

            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {

                // ── الرأس ──────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier.size(52.dp),
                        shape = RoundedCornerShape(18.dp),
                        color = colors.primary,
                        shadowElevation = 6.dp
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Chat,
                                contentDescription = null,
                                tint = colors.onPrimary
                            )
                        }
                    }

                    Spacer(Modifier.width(12.dp))

                    Column {
                        Text(
                            "AiChat",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = colors.primaryContainer
                        ) {
                            Text(
                                text = settings.getActiveModel().take(30),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onPrimaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    RoundIconButton(Icons.Default.Settings, "الإعدادات", onOpenSettings)
                    Spacer(Modifier.width(8.dp))
                    RoundIconButton(Icons.Default.Memory, "الذاكرة", onOpenMemory)
                }

                // ── منصات الويب ────────────────────────────────
                if (enabledPlatforms.isNotEmpty()) {

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "منصات الويب",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (!seedReady) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }

                        Surface(
                            onClick = onManagePlatforms,
                            shape = CircleShape,
                            color = colors.surface,
                            border = BorderStroke(1.dp, colors.outline.copy(alpha = 0.5f))
                        ) {
                            Text(
                                "إدارة",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = colors.primary,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
                            )
                        }
                    }

                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(enabledPlatforms, key = { it.id }) { platform ->
                            val ok = seedReady && platform.isEnabled
                            PlatformChip(
                                platform = platform,
                                enabled = ok,
                                onClick = { if (ok) onOpenPlatform(platform) }
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                }

                // ── عنوان المحادثات + العدد ────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "المحادثات",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Surface(shape = CircleShape, color = colors.primary) {
                        Box(
                            Modifier.defaultMinSize(minWidth = 24.dp, minHeight = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = conversations.size.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = colors.onPrimary,
                                modifier = Modifier.padding(horizontal = 7.dp)
                            )
                        }
                    }
                }

                // ── قائمة المحادثات ────────────────────────────
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
                                tint = colors.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = "لا توجد محادثات بعد",
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.onSurfaceVariant
                            )
                            Text(
                                text = "اضغط + لبدء محادثة جديدة",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(
                            start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(conversations, key = { it.id }) { conversation ->
                            ConversationItem(
                                conversation = conversation,
                                onOpen = { onOpenChat(conversation.id) },
                                onDelete = { showDeleteDialog = conversation }
                            )
                        }
                    }
                }
            }
        }
    }

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
                TextButton(onClick = { showDeleteDialog = null }) { Text("إلغاء") }
            }
        )
    }
}

@Composable
private fun RoundIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun PlatformChip(
    platform: WebPlatform,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val (bg, fg) = avatarColors(platform.name)
    val letter = platform.name.trim().firstOrNull()?.uppercase() ?: "؟"

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.alpha(if (enabled) 1f else 0.5f),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .height(44.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(bg),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = letter,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = fg
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = platform.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = 140.dp)
                    .padding(end = 6.dp)
            )
        }
    }
}

@Composable
private fun ConversationItem(
    conversation: Conversation,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = MaterialTheme.colorScheme

    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = colors.surface,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.primaryContainer.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Chat,
                    contentDescription = null,
                    tint = colors.primary
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = conversation.title.ifBlank { "محادثة بدون عنوان" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = formatDate(conversation.updatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }

            Spacer(Modifier.width(8.dp))

            Surface(
                onClick = onDelete,
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(14.dp),
                color = colors.surfaceVariant.copy(alpha = 0.6f)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "حذف",
                        tint = colors.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun formatDate(timestamp: Long): String {
    val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    return fmt.format(Date(timestamp))
}
