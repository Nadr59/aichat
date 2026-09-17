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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.aichat.data.model.WebPlatform
import com.example.aichat.ui.viewmodel.WebPlatformsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    onOpenChat:            (String) -> Unit,
    onNewChat:             () -> Unit,
    onOpenSettings:        () -> Unit,
    onOpenMemory:          () -> Unit,
    webPlatformsViewModel: WebPlatformsViewModel,
    onOpenPlatform:        (WebPlatform) -> Unit,
    onManagePlatforms:     () -> Unit
) {
    // ← منصات مفعّلة فقط من الـ ViewModel (Flow → StateFlow)
    val enabledPlatforms by webPlatformsViewModel.platforms.collectAsState()
    val visiblePlatforms = enabledPlatforms.filter { it.isEnabled }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AiChat") },
                actions = {
                    IconButton(onClick = onOpenMemory) {
                        Icon(Icons.Default.Psychology, contentDescription = "الذاكرة")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "الإعدادات")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewChat) {
                Icon(Icons.Default.Add, contentDescription = "محادثة جديدة")
            }
        }
    ) { padding ->

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {

            // ── شريط منصات الويب ─────────────────────────────────────────────
            if (visiblePlatforms.isNotEmpty()) {
                Row(
                    modifier              = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "منصات الويب",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = onManagePlatforms) {
                        Text("إدارة", style = MaterialTheme.typography.labelMedium)
                    }
                }

                LazyRow(
                    contentPadding        = PaddingValues(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(visiblePlatforms, key = { it.id }) { platform ->
                        WebPlatformChip(
                            platform = platform,
                            onClick  = { onOpenPlatform(platform) }
                        )
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 4.dp))
            }

            // ── قائمة المحادثات ───────────────────────────────────────────────
            // (ضع هنا منطق عرض المحادثات الموجود لديك)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "لا توجد محادثات بعد\nاضغط + لبدء محادثة جديدة",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun WebPlatformChip(
    platform: WebPlatform,
    onClick:  () -> Unit
) {
    SuggestionChip(
        onClick = onClick,
        label   = {
            Text(
                "${platform.iconEmoji} ${platform.name}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    )
}
