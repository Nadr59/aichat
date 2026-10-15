package com.example.aichat.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.aichat.data.local.AiSettings
import com.example.aichat.data.model.Message
import com.example.aichat.data.model.QueryStyle
import com.example.aichat.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.delay
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel, settings: AiSettings, onBack: () -> Unit) {
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val selectedImage by viewModel.selectedImageBase64.collectAsState()
    val customRequestCount by viewModel.customRequestCount.collectAsState()
    val memoryAccessEnabled by viewModel.memoryAccessEnabled.collectAsState()
    val enhancedQuery by viewModel.enhancedQuery.collectAsState()
    val isEnhancing by viewModel.isEnhancing.collectAsState()
    val conversationId by viewModel.currentConversationId.collectAsState()

    var userInput by remember { mutableStateOf("") }
    val displayedText = enhancedQuery ?: userInput
    val isEnhanced = enhancedQuery != null
    val hasIdentity = settings.mediatorIdentityText.isNotBlank()

    val listState = rememberLazyListState()
    val context = LocalContext.current

    var cameraImageUri by remember { mutableStateOf<Uri?>(null) }
    var memoryMessage by remember { mutableStateOf<Message?>(null) }
    var showWebImportDialog by remember { mutableStateOf(false) }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        it?.let(viewModel::selectImage)
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) cameraImageUri?.let(viewModel::selectImage)
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        it?.let(viewModel::processAndSaveFile)
    }

    fun openCamera() = runCatching {
        val file = File.createTempFile("camera_photo_", ".jpg", context.cacheDir)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        cameraImageUri = uri
        cameraLauncher.launch(uri)
    }.getOrNull()

    fun copyToClipboard(text: String) {
        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("message", text))
    }

    fun send() {
        if (displayedText.isBlank()) return
        viewModel.sendChatMessage(displayedText)
        userInput = ""
        viewModel.clearEnhancedQuery()
    }

    LaunchedEffect(messages.size, isLoading) {
        val total = messages.size + if (isLoading) 1 else 0
        if (total > 0) listState.animateScrollToItem(total - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("المحادثة", fontWeight = FontWeight.SemiBold)
                        Text(
                            settings.getActiveModel().take(35),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (settings.provider.trim().lowercase() == "custom") {
                            Text(
                                "الطلبات: $customRequestCount",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleMemoryAccess(!memoryAccessEnabled) }) {
                        Icon(
                            Icons.Filled.Memory,
                            contentDescription = null,
                            tint = if (memoryAccessEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            if (messages.isEmpty()) {
                EmptyChatView(Modifier.weight(1f))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(12.dp, 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(messages, key = { it.id }) { msg ->
                        MessageBubble(
                            message = msg,
                            onCopy = ::copyToClipboard,
                            onSaveMemory = { memoryMessage = it }
                        )
                    }
                    if (isLoading) item("thinking") { ThinkingBubble() }
                }
            }

            error?.let {
                ErrorMessage(
                    error = it,
                    onDismiss = viewModel::clearError,
                    onCopy = { copyToClipboard(it) }
                )
            }

            InputBar(
                isLoading = isLoading,
                hasSelectedImage = selectedImage != null,
                userInput = userInput,
                displayedText = displayedText,
                isEnhanced = isEnhanced,
                isEnhancing = isEnhancing,
                hasIdentity = hasIdentity,
                onUserInputChange = { userInput = it },
                onOpenGallery = { galleryLauncher.launch("image/*") },
                onOpenCamera = ::openCamera,
                onOpenFile = { fileLauncher.launch("*/*") },
                onOpenWebImport = { showWebImportDialog = true },
                onRemoveImage = viewModel::clearSelectedImage,
                onSend = ::send,
                onEnhance = { viewModel.enhanceUserQuery(userInput) },
                onClearEnhanced = viewModel::clearEnhancedQuery,
                onUpdateEnhancedQuery = viewModel::updateEnhancedQuery,
                onRefineStyle = { viewModel.refineQueryStyle(userInput, it) }
            )
        }
    }

    memoryMessage?.let { msg ->
        SaveMemoryDialog(
            message = msg,
            conversationId = conversationId,
            onDismiss = { memoryMessage = null },
            onSave = { category, isShared ->
                viewModel.addMemory(msg.content, category, isShared, msg.id)
                memoryMessage = null
            }
        )
    }

    if (showWebImportDialog) {
        WebPageImportDialog(
            onDismiss = { showWebImportDialog = false },
            onConfirm = {
                viewModel.saveWebPage(it)
                showWebImportDialog = false
            }
        )
    }
}

@Composable
private fun WebPageImportDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    val isValid by remember(url) {
        mutableStateOf(url.isBlank() || url.startsWith("http://") || url.startsWith("https://"))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("استيراد صفحة ويب") },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("https://example.com") },
                leadingIcon = { Icon(Icons.Filled.Language, null) },
                singleLine = true,
                isError = !isValid,
                supportingText = { if (!isValid) Text("يجب أن يبدأ الرابط بـ http:// أو https://") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                enabled = url.trim().isNotBlank() && isValid,
                onClick = { onConfirm(url.trim()) }
            ) { Text("استيراد") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

@Composable
private fun EmptyChatView(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                modifier = Modifier.size(64.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Chat, null, modifier = Modifier.size(30.dp))
            } }
            Spacer(Modifier.height(12.dp))
            Text("كيف يمكنني مساعدتك؟", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("اكتب رسالتك لبدء المحادثة", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SaveMemoryDialog(
    message: Message,
    conversationId: Long?,
    onDismiss: () -> Unit,
    onSave: (String, Boolean) -> Unit
) {
    var category by remember { mutableStateOf("OTHER") }
    var isShared by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Memory, null) },
        title = { Text("حفظ في الذاكرة") },
        text = {
            Column {
                Text(message.content, maxLines = 8)
                Spacer(Modifier.height(10.dp))

                Text("التصنيف", fontWeight = FontWeight.SemiBold)
                MemoryOption("معرفة", category == "KNOWLEDGE") { category = "KNOWLEDGE" }
                MemoryOption("مشروع", category == "PROJECT") { category = "PROJECT" }
                MemoryOption("تفضيل", category == "PREFERENCE") { category = "PREFERENCE" }
                MemoryOption("أخرى", category == "OTHER") { category = "OTHER" }

                Spacer(Modifier.height(8.dp))
                Text("النطاق", fontWeight = FontWeight.SemiBold)
                MemoryOption("مشترك", isShared) { isShared = true }
                MemoryOption("هذه المحادثة فقط", !isShared) { isShared = false }

                if (conversationId == null) {
                    Text(
                        "سيتم ربط الذاكرة بالمحادثة الحالية عند توفرها.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(category, isShared) }) { Text("حفظ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

@Composable
private fun MemoryOption(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text)
    }
}

@Composable
private fun InputBar(
    isLoading: Boolean,
    hasSelectedImage: Boolean,
    userInput: String,
    displayedText: String,
    isEnhanced: Boolean,
    isEnhancing: Boolean,
    hasIdentity: Boolean,
    onUserInputChange: (String) -> Unit,
    onOpenGallery: () -> Unit,
    onOpenCamera: () -> Unit,
    onOpenFile: () -> Unit,
    onOpenWebImport: () -> Unit,
    onRemoveImage: () -> Unit,
    onSend: () -> Unit,
    onEnhance: () -> Unit,
    onClearEnhanced: () -> Unit,
    onUpdateEnhancedQuery: (String) -> Unit,
    onRefineStyle: (QueryStyle) -> Unit
) {
    var showAttach by remember { mutableStateOf(false) }
    var showStyle by remember { mutableStateOf(false) }

    Surface(
        Modifier.fillMaxWidth().imePadding().navigationBarsPadding(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(Modifier.padding(10.dp, 8.dp)) {

            if (hasSelectedImage) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Image, null)
                    Spacer(Modifier.width(8.dp))
                    Text("صورة جاهزة للإرسال", modifier = Modifier.weight(1f))
                    IconButton(onClick = onRemoveImage) { Icon(Icons.Filled.Close, "إزالة") }
                }
            }

            Row(verticalAlignment = Alignment.Bottom) {
                Box {
                    IconButton(onClick = { showAttach = true }, enabled = !isLoading) {
                        Icon(Icons.Filled.AttachFile, contentDescription = "إرفاق")
                    }
                    DropdownMenu(showAttach, onDismissRequest = { showAttach = false }) {
                        DropdownMenuItem(text = { Text("الكاميرا") }, onClick = { showAttach = false; onOpenCamera() })
                        DropdownMenuItem(text = { Text("المعرض") }, onClick = { showAttach = false; onOpenGallery() })
                        DropdownMenuItem(text = { Text("ملف (TXT/PDF)") }, onClick = { showAttach = false; onOpenFile() })
                        DropdownMenuItem(text = { Text("صفحة ويب") }, onClick = { showAttach = false; onOpenWebImport() })
                    }
                }

                OutlinedTextField(
                    value = displayedText,
                    onValueChange = { if (isEnhanced) onUpdateEnhancedQuery(it) else onUserInputChange(it) },
                    enabled = !isLoading,
                    modifier = Modifier.weight(1f),
                    minLines = 1,
                    maxLines = 6,
                    label = { Text(if (isEnhanced) "السؤال المُحسّن" else "اكتب رسالة...") },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {

                            if (!isEnhanced && userInput.isNotBlank() && !isLoading) {
                                Box {
                                    IconButton(onClick = { showStyle = true }, enabled = !isEnhancing) {
                                        if (isEnhancing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                        else Icon(Icons.Filled.Palette, contentDescription = "تنقيح")
                                    }
                                    DropdownMenu(showStyle, onDismissRequest = { showStyle = false }) {
                                        QueryStyle.entries.forEach { style ->
                                            DropdownMenuItem(
                                                text = { Text("${style.emoji} ${style.displayName}") },
                                                onClick = { showStyle = false; onRefineStyle(style) }
                                            )
                                        }
                                    }
                                }
                            }

                            if (hasIdentity && !isEnhanced && userInput.isNotBlank() && !isLoading) {
                                IconButton(onClick = onEnhance, enabled = !isEnhancing) {
                                    if (isEnhancing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    else Icon(Icons.Filled.AutoAwesome, contentDescription = "توسيع")
                                }
                            }

                            if (isEnhanced) {
                                IconButton(onClick = onClearEnhanced, enabled = !isLoading) {
                                    Icon(Icons.Filled.Undo, contentDescription = "رجوع")
                                }
                            }

                            IconButton(
                                onClick = onSend,
                                enabled = displayedText.isNotBlank() && !isLoading && !isEnhancing
                            ) { Icon(Icons.Filled.Send, contentDescription = "إرسال") }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ThinkingBubble() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("جاري التفكير...", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: Message,
    onCopy: (String) -> Unit,
    onSaveMemory: (Message) -> Unit
) {
    val isUser = message.role == "user"
    var showActions by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1200)
            copied = false
        }
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        Surface(
            modifier = Modifier.widthIn(max = if (isUser) 320.dp else 360.dp)
                .combinedClickable(onClick = {}, onLongClick = { showActions = true }),
            shape = RoundedCornerShape(14.dp),
            color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    message.content,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (copied) Text("تم النسخ", style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    if (showActions) {
        MessageActionsDialog(
            onDismiss = { showActions = false },
            onCopy = { onCopy(message.content); copied = true; showActions = false },
            onSaveMemory = { showActions = false; onSaveMemory(message) }
        )
    }
}

@Composable
private fun MessageActionsDialog(
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onSaveMemory: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("خيارات الرسالة") },
        text = {
            Column {
                TextButton(onClick = onCopy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.ContentCopy, null)
                    Spacer(Modifier.width(8.dp))
                    Text("نسخ")
                }
                TextButton(onClick = onSaveMemory, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Memory, null)
                    Spacer(Modifier.width(8.dp))
                    Text("حفظ في الذاكرة")
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ErrorMessage(error: String, onDismiss: () -> Unit, onCopy: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)
            .combinedClickable(onClick = {}, onLongClick = onCopy),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(error, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "إغلاق", tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}
