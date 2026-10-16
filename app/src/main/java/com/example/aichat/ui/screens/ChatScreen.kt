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
import com.example.aichat.ui.components.AgentPickerDialog
import com.example.aichat.ui.viewmodel.AgentsViewModel
import com.example.aichat.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.delay
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    agentsViewModel: AgentsViewModel,
    settings: AiSettings,
    onBack: () -> Unit,
    onOpenAgents: () -> Unit
) {
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val selectedImage by viewModel.selectedImageBase64.collectAsState()
    val customRequestCount by viewModel.customRequestCount.collectAsState()
    val memoryAccessEnabled by viewModel.memoryAccessEnabled.collectAsState()
    val enhancedQuery by viewModel.enhancedQuery.collectAsState()
    val isEnhancing by viewModel.isEnhancing.collectAsState()
    val conversationId by viewModel.currentConversationId.collectAsState()
    val currentAgentId by viewModel.currentAgentId.collectAsState()

    val agents by agentsViewModel.agents.collectAsState()
    val currentAgentName = remember(currentAgentId, agents) {
        currentAgentId?.let { id -> agents.firstOrNull { it.id == id }?.name }
    }

    var showAgentDialog by remember { mutableStateOf(false) }

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
                        currentAgentName?.let {
                            Text(
                                "🤖 $it",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
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
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "رجوع") }
                },
                actions = {
                    IconButton(onClick = { showAgentDialog = true }) {
                        Icon(Icons.Filled.SmartToy, contentDescription = "Agent")
                    }

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

    if (showAgentDialog) {
        AgentPickerDialog(
            title = "Agent لهذه المحادثة",
            agents = agents,
            selectedId = currentAgentId,
            onDismiss = { showAgentDialog = false },
            onSelect = { id ->
                viewModel.setConversationAgent(id)
                showAgentDialog = false
            },
            extraTopContent = {
                TextButton(onClick = {
                    showAgentDialog = false
                    onOpenAgents()
                }) { Text("إدارة/استيراد Agents") }
            }
        )
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

/* باقي الدوال المساعدة (WebPageImportDialog/EmptyChatView/SaveMemoryDialog/…)
   انسخها من ملفك الحالي كما هي بدون تغيير */
