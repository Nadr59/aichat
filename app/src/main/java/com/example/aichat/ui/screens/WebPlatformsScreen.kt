package com.example.aichat.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.aichat.data.model.WebEngine
import com.example.aichat.data.model.WebPlatform
import com.example.aichat.ui.viewmodel.WebPlatformsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebPlatformsScreen(
    viewModel: WebPlatformsViewModel,
    onOpenPlatform: (WebPlatform) -> Unit,
    onNavigateUp: () -> Unit = {}
) {
    val platforms by viewModel.platforms.collectAsState()
    val message   by viewModel.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var showAddDialog by remember { mutableStateOf(false) }

    // عرض الرسائل عبر Snackbar
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("منصات الويب") },
                navigationIcon = {
                    TextButton(onClick = onNavigateUp) { Text("←") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                icon    = { Icon(Icons.Default.Add, contentDescription = null) },
                text    = { Text("إضافة منصة") }
            )
        }
    ) { padding ->

        if (platforms.isEmpty()) {
            Box(
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier            = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentPadding      = PaddingValues(bottom = 88.dp) // مساحة للـ FAB
            ) {
                items(platforms, key = { it.id }) { platform ->
                    PlatformRow(
                        platform = platform,
                        onOpen   = { if (platform.isEnabled) onOpenPlatform(platform) },
                        onToggle = { viewModel.toggleEnabled(platform) },
                        onDelete = { viewModel.delete(platform) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (showAddDialog) {
        AddPlatformDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, url, account, engine, emoji ->
                viewModel.add(name, url, account, engine, emoji)
                showAddDialog = false
            }
        )
    }
}

// ── PlatformRow ───────────────────────────────────────────────────────────────

@Composable
private fun PlatformRow(
    platform: WebPlatform,
    onOpen:   () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    ListItem(
        modifier = Modifier
            .clickable(onClick = onOpen)
            .then(
                if (!platform.isEnabled)
                    Modifier  // خافت بصرياً عبر alpha
                else Modifier
            ),
        leadingContent = {
            Text(
                text  = platform.iconEmoji,
                style = MaterialTheme.typography.headlineSmall
            )
        },
        headlineContent = {
            Text(
                text  = platform.name,
                color = if (platform.isEnabled)
                    MaterialTheme.colorScheme.onSurface
                else
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
        },
        supportingContent = {
            Text(
                text  = buildString {
                    append(platform.url)
                    if (platform.requiresAccount) append("  •  👤 يتطلب حساب")
                    if (platform.isBuiltIn)       append("  •  افتراضي")
                },
                style   = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color    = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Switch(
                    checked         = platform.isEnabled,
                    onCheckedChange = { onToggle() }
                )
                // زر الحذف فقط للمنصات المخصصة
                if (!platform.isBuiltIn) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector        = Icons.Default.Delete,
                            contentDescription = "حذف ${platform.name}",
                            tint               = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    )
}

// ── AddPlatformDialog ─────────────────────────────────────────────────────────

@Composable
private fun AddPlatformDialog(
    onDismiss: () -> Unit,
    onConfirm: (
        name: String,
        url: String,
        requiresAccount: Boolean,
        engine: WebEngine,
        emoji: String
    ) -> Unit
) {
    var name           by remember { mutableStateOf("") }
    var url            by remember { mutableStateOf("") }
    var emoji          by remember { mutableStateOf("🌐") }
    var requireAccount by remember { mutableStateOf(false) }
    var engine         by remember { mutableStateOf(WebEngine.GECKO) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title   = { Text("إضافة منصة ويب") },
        text    = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {

                OutlinedTextField(
                    value         = name,
                    onValueChange = { name = it },
                    label         = { Text("الاسم") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value         = url,
                    onValueChange = { url = it },
                    label         = { Text("الرابط") },
                    placeholder   = { Text("https://...") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value         = emoji,
                    onValueChange = { emoji = it.take(2) },
                    label         = { Text("رمز (إيموجي)") },
                    singleLine    = true
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked         = requireAccount,
                        onCheckedChange = { requireAccount = it }
                    )
                    Text("يتطلب تسجيل دخول")
                }

                Text(
                    "المحرك المفضل",
                    style = MaterialTheme.typography.labelMedium
                )

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WebEngine.entries.forEach { e ->
                        FilterChip(
                            selected = engine == e,
                            onClick  = { engine = e },
                            label    = { Text(e.name) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && url.isNotBlank(),
                onClick = { onConfirm(name, url, requireAccount, engine, emoji) }
            ) { Text("إضافة") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}
