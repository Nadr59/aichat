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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.aichat.data.local.AiSettings
import com.example.aichat.data.model.Message
import com.example.aichat.data.model.QueryStyle
import com.example.aichat.data.model.WebPlatform
import com.example.aichat.ui.components.AgentPickerDialog
import com.example.aichat.ui.viewmodel.AgentsViewModel
import com.example.aichat.ui.viewmodel.ChatViewModel
import com.example.aichat.ui.viewmodel.WebPlatformsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    agentsViewModel: AgentsViewModel,
    webPlatformsViewModel: WebPlatformsViewModel,
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

    // Tree data
    val parentConversationId by viewModel.currentParentConversationId.collectAsState()
    val allConversations by viewModel.conversations.collectAsState()
    val currentWorkspaceId by viewModel.currentWorkspaceId.collectAsState()

    val agents by agentsViewModel.agents.collectAsState()
    val currentAgentName = remember(currentAgentId, agents) {
        currentAgentId?.let { id ->
            agents.firstOrNull { it.id == id }?.name
        }
    }

    // ── Provider selector (1.ب.2): API أو منصة ويب (placeholder فقط) ──
    val webPlatforms by webPlatformsViewModel.platforms.collectAsState()
    val enabledWebPlatforms = remember(webPlatforms) {
        webPlatforms.filter { it.isEnabled }
    }

    var selectedWebPlatformId by rememberSaveable { mutableStateOf<String?>(null) }

    val selectedWebPlatformName = remember(selectedWebPlatformId, enabledWebPlatforms) {
        selectedWebPlatformId?.let { id ->
            enabledWebPlatforms.firstOrNull { it.id == id }?.name
        }
    }

    var showAgentDialog by remember { mutableStateOf(false) }
    var showWebImportDialog by remember { mutableStateOf(false) }

    // BottomSheet Tree
    var showTreeSheet by remember { mutableStateOf(false) }
    val treeSheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    // Confirm create branch
    var showBranchConfirm by remember { mutableStateOf(false) }

    // Confirm reset workspace
    var showResetWorkspaceConfirm by remember { mutableStateOf(false) }

    // Children of current conversation
    val childrenFlow = remember(conversationId) {
        conversationId?.let {
            viewModel.observeChildConversations(it)
        } ?: flowOf(emptyList())
    }
    val childConversations by childrenFlow.collectAsState(
        initial = emptyList()
    )

    // Siblings: children of parent
    val siblingsFlow = remember(parentConversationId) {
        parentConversationId?.let {
            viewModel.observeChildConversations(it)
        } ?: flowOf(emptyList())
    }
    val siblingsAll by siblingsFlow.collectAsState(
        initial = emptyList()
    )

    val siblings = remember(siblingsAll, conversationId) {
        val cid = conversationId ?: return@remember emptyList()
        siblingsAll.filterNot { it.id == cid }
    }

    // Current conversation object
    val currentConversation = remember(
        conversationId,
        allConversations
    ) {
        val cid = conversationId ?: return@remember null
        allConversations.firstOrNull { it.id == cid }
    }

    val branchContextMessageCount =
        currentConversation?.branchContextMessageCount ?: 0

    val visibleMessages = remember(
        messages,
        branchContextMessageCount
    ) {
        messages.drop(
            branchContextMessageCount.coerceIn(0, messages.size)
        )
    }

    val branchSourceTitle = remember(
        parentConversationId,
        allConversations
    ) {
        parentConversationId?.let { parentId ->
            allConversations
                .firstOrNull { it.id == parentId }
                ?.title
                ?.ifBlank { "محادثة سابقة" }
        }
    }

    // Breadcrumb: root -> ... -> current
    val breadcrumb = remember(
        conversationId,
        allConversations,
        currentWorkspaceId,
        currentConversation?.workspaceId
    ) {
        val cid = conversationId ?: return@remember emptyList()
        val map = allConversations.associateBy { it.id }
        val wsId = currentConversation?.workspaceId ?: currentWorkspaceId

        val path = mutableListOf<com.example.aichat.data.model.Conversation>()
        var cur = map[cid]
        var guard = 0

        while (cur != null && guard < 60) {
            if (
                wsId != null &&
                cur.workspaceId != null &&
                cur.workspaceId != wsId
            ) {
                break
            }

            path.add(cur)

            val pid = cur.parentConversationId ?: break
            cur = map[pid]
            guard++
        }

        path.reversed()
    }

    val rootInBreadcrumbId = breadcrumb.firstOrNull()?.id

    var userInput by rememberSaveable(
        stateSaver = TextFieldValue.Saver
    ) {
        mutableStateOf(TextFieldValue(""))
    }

    var enhancedInput by rememberSaveable(
        stateSaver = TextFieldValue.Saver
    ) {
        mutableStateOf(TextFieldValue(""))
    }

    val isEnhanced = enhancedQuery != null
    val hasIdentity = settings.mediatorIdentityText.isNotBlank()
    val displayedValue = if (isEnhanced) enhancedInput else userInput

    val listState = rememberLazyListState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var cameraImageUri by remember { mutableStateOf<Uri?>(null) }
    var memoryMessage by remember { mutableStateOf<Message?>(null) }

    fun clearDraftInput() {
        userInput = TextFieldValue("")
    }

    fun openConversationFromTree(targetId: Long) {
        keyboardController?.hide()
        focusManager.clearFocus(force = true)
        clearDraftInput()
        viewModel.openConversation(targetId)
        showTreeSheet = false
        showBranchConfirm = false
        showResetWorkspaceConfirm = false
    }

    LaunchedEffect(enhancedQuery) {
        enhancedInput = if (enhancedQuery != null) {
            TextFieldValue(enhancedQuery!!)
        } else {
            TextFieldValue("")
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) {
        it?.let(viewModel::selectImage)
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        if (ok) {
            cameraImageUri?.let(viewModel::selectImage)
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) {
        it?.let(viewModel::processAndSaveFile)
    }

    fun openCamera() = runCatching {
        val file = File.createTempFile(
            "camera_photo_",
            ".jpg",
            context.cacheDir
        )

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )

        cameraImageUri = uri
        cameraLauncher.launch(uri)
    }.getOrNull()

    fun copyToClipboard(text: String) {
        val cb = context.getSystemService(
            Context.CLIPBOARD_SERVICE
        ) as ClipboardManager

        cb.setPrimaryClip(
            ClipData.newPlainText("message", text)
        )
    }

    fun send() {
        if (isLoading) return

        scope.launch {
            keyboardController?.hide()
            focusManager.clearFocus(force = true)
            delay(80)

            val textToSend = (
                if (isEnhanced) enhancedInput.text else userInput.text
            ).trim()

            if (textToSend.isBlank()) return@launch

            viewModel.sendChatMessage(textToSend)
            userInput = TextFieldValue("")
            viewModel.clearEnhancedQuery()
        }
    }

    LaunchedEffect(visibleMessages.size, isLoading) {
        val total = visibleMessages.size + if (isLoading) 1 else 0

        if (total > 0) {
            runCatching {
                listState.animateScrollToItem(total - 1)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "المحادثة",
                            fontWeight = FontWeight.SemiBold
                        )

                        Text(
                            settings.getActiveModel().take(40),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        currentAgentName?.let {
                            Text(
                                "Agent: $it",
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
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = "رجوع"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showAgentDialog = true }
                    ) {
                        Icon(
                            Icons.Filled.SmartToy,
                            contentDescription = "Agent"
                        )
                    }

                    IconButton(
                        onClick = { showTreeSheet = true },
                        enabled = conversationId != null
                    ) {
                        Icon(
                            Icons.Filled.AccountTree,
                            contentDescription = "شجرة المحادثة"
                        )
                    }

                    IconButton(
                        onClick = {
                            viewModel.toggleMemoryAccess(
                                !memoryAccessEnabled
                            )
                        }
                    ) {
                        Icon(
                            Icons.Filled.Memory,
                            contentDescription = "Memory access",
                            tint = if (memoryAccessEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                                    .copy(alpha = .35f)
                            }
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ProviderSelectorRow(
                enabledWebPlatforms = enabledWebPlatforms,
                selectedWebPlatformId = selectedWebPlatformId,
                onSelectApi = { selectedWebPlatformId = null },
                onSelectWebPlatform = { id -> selectedWebPlatformId = id }
            )

            Column(
                modifier = Modifier.weight(1f)
            ) {
                if (selectedWebPlatformId != null) {
                    WebPlaceholderView(
                        platformName = selectedWebPlatformName ?: "منصة ويب",
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    if (branchContextMessageCount > 0) {
                        BranchContextCard(
                            sourceTitle = branchSourceTitle ?: "المحادثة الأصلية",
                            contextCount = branchContextMessageCount
                        )
                    }

                    if (visibleMessages.isEmpty() && !isLoading) {
                        EmptyChatView(
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(
                                12.dp,
                                16.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(
                                visibleMessages,
                                key = { it.id }
                            ) { msg ->
                                MessageBubble(
                                    message = msg,
                                    onCopy = {
                                        copyToClipboard(msg.content)
                                    },
                                    onSaveMemory = {
                                        memoryMessage = msg
                                    },
                                    onBranch = {
                                        viewModel.branchFromMessage(msg.id)
                                    },
                                    branchEnabled = !isLoading
                                )
                            }

                            if (isLoading) {
                                item("thinking") {
                                    ThinkingBubble()
                                }
                            }
                        }
                    }
                }
            }

            error?.let { err ->
                ErrorMessage(
                    error = err,
                    onDismiss = viewModel::clearError,
                    onCopy = {
                        copyToClipboard(err)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = 12.dp,
                            vertical = 6.dp
                        )
                )
            }

            InputBar(
                isLoading = isLoading,
                hasSelectedImage = selectedImage != null,
                value = displayedValue,
                isEnhanced = isEnhanced,
                isEnhancing = isEnhancing,
                hasIdentity = hasIdentity,
                sendDisabledExternally = selectedWebPlatformId != null,
                onValueChange = { tfv ->
                    if (isEnhanced) {
                        enhancedInput = tfv
                    } else {
                        userInput = tfv
                    }
                },
                onOpenGallery = {
                    galleryLauncher.launch("image/*")
                },
                onOpenCamera = {
                    openCamera()
                },
                onOpenFile = {
                    fileLauncher.launch("*/*")
                },
                onOpenWebImport = {
                    showWebImportDialog = true
                },
                onRemoveImage = viewModel::clearSelectedImage,
                onSend = ::send,
                onEnhance = {
                    viewModel.enhanceUserQuery(userInput.text)
                },
                onClearEnhanced = viewModel::clearEnhancedQuery,
                onRefineStyle = { style ->
                    viewModel.refineQueryStyle(userInput.text, style)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    // BottomSheet Tree UI + Reset Workspace
    if (showTreeSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                showTreeSheet = false
                showBranchConfirm = false
                showResetWorkspaceConfirm = false
            },
            sheetState = treeSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "شجرة المحادثة",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(Modifier.weight(1f))

                    Text(
                        "id=${conversationId ?: "-"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Breadcrumb
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "المسار (Breadcrumb)",
                            fontWeight = FontWeight.SemiBold
                        )

                        if (breadcrumb.isEmpty()) {
                            Text(
                                "غير متوفر حالياً.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(
                                        rememberScrollState()
                                    ),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                breadcrumb.forEachIndexed { idx, conv ->
                                    val isCurrent = conv.id == conversationId

                                    AssistChip(
                                        onClick = {
                                            openConversationFromTree(conv.id)
                                        },
                                        label = {
                                            Text(
                                                (conv.title.ifBlank {
                                                    "بدون عنوان"
                                                }).take(18) +
                                                    if (conv.title.length > 18) "…" else ""
                                            )
                                        },
                                        leadingIcon = {
                                            if (isCurrent) {
                                                Icon(
                                                    Icons.Filled.Check,
                                                    contentDescription = null
                                                )
                                            } else {
                                                Icon(
                                                    Icons.Filled.ChevronRight,
                                                    contentDescription = null
                                                )
                                            }
                                        },
                                        colors = if (isCurrent) {
                                            AssistChipDefaults.assistChipColors(
                                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                                labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        } else {
                                            AssistChipDefaults.assistChipColors()
                                        }
                                    )

                                    if (idx != breadcrumb.lastIndex) {
                                        Text(
                                            "›",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    enabled = rootInBreadcrumbId != null &&
                                        rootInBreadcrumbId != conversationId,
                                    onClick = {
                                        rootInBreadcrumbId?.let {
                                            openConversationFromTree(it)
                                        }
                                    }
                                ) {
                                    Text("فتح الجذر")
                                }
                            }
                        }
                    }
                }

                // Parent
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "الأب",
                            fontWeight = FontWeight.SemiBold
                        )

                        if (parentConversationId == null) {
                            Text(
                                "هذه محادثة جذر (لا يوجد أب).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Column(
                                    Modifier.weight(1f)
                                ) {
                                    val pTitle = allConversations
                                        .firstOrNull {
                                            it.id == parentConversationId
                                        }?.title

                                    Text(
                                        (pTitle
                                            ?: "Conversation #$parentConversationId").take(80)
                                    )

                                    Text(
                                        "id = $parentConversationId",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                TextButton(
                                    onClick = {
                                        parentConversationId?.let {
                                            openConversationFromTree(it)
                                        }
                                    }
                                ) {
                                    Text("فتح الأب")
                                }
                            }
                        }
                    }
                }

                // Siblings
                if (parentConversationId != null) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "إخوة (فروع تحت نفس الأب)",
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )

                                Text(
                                    "${siblings.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (siblings.isEmpty()) {
                                Text(
                                    "لا يوجد إخوة.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 220.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    items(
                                        siblings,
                                        key = { it.id }
                                    ) { conv ->
                                        ConversationNavRow(
                                            title = conv.title,
                                            id = conv.id,
                                            onClick = {
                                                openConversationFromTree(conv.id)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Children
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "الأبناء (فروع من الحالية)",
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )

                            Text(
                                "${childConversations.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (childConversations.isEmpty()) {
                            Text(
                                "لا توجد فروع من هذه المحادثة بعد.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 260.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(
                                    childConversations,
                                    key = { it.id }
                                ) { conv ->
                                    ConversationNavRow(
                                        title = conv.title,
                                        id = conv.id,
                                        onClick = {
                                            openConversationFromTree(conv.id)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Actions row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            showBranchConfirm = true
                        },
                        enabled = conversationId != null && !isLoading
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null
                        )

                        Spacer(Modifier.width(8.dp))
                        Text("إنشاء فرع")
                    }

                    Spacer(Modifier.weight(1f))

                    TextButton(
                        onClick = {
                            showTreeSheet = false
                            showBranchConfirm = false
                            showResetWorkspaceConfirm = false
                        }
                    ) {
                        Text("إغلاق")
                    }
                }

                // Danger zone: Reset workspace
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "إعادة ضبط Workspace",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )

                        Text(
                            "سيتم حذف كل المحادثات والفروع ضمن هذا الـWorkspace + تصفير WORKSPACE_SUMMARY.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )

                        Text(
                            "WorkspaceId: ${
                                currentWorkspaceId
                                    ?: (currentConversation?.workspaceId ?: "-")
                            }",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(
                                alpha = 0.85f
                            )
                        )

                        OutlinedButton(
                            onClick = {
                                showResetWorkspaceConfirm = true
                            },
                            enabled = !isLoading,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            ),
                            border = ButtonDefaults.outlinedButtonBorder.copy(
                                brush = androidx.compose.ui.graphics.SolidColor(
                                    MaterialTheme.colorScheme.onErrorContainer
                                )
                            )
                        ) {
                            Icon(
                                Icons.Filled.DeleteSweep,
                                contentDescription = null
                            )

                            Spacer(Modifier.width(8.dp))
                            Text("Reset workspace بالكامل")
                        }
                    }
                }
            }
        }
    }

    // Confirm create branch
    if (showBranchConfirm) {
        AlertDialog(
            onDismissRequest = {
                showBranchConfirm = false
            },
            title = {
                Text("إنشاء فرع")
            },
            text = {
                Text(
                    "سيتم إنشاء محادثة جديدة كفرع من المحادثة الحالية والانتقال إليها."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBranchConfirm = false
                        showTreeSheet = false
                        clearDraftInput()
                        viewModel.branchFromCurrentConversation()
                    },
                    enabled = conversationId != null && !isLoading
                ) {
                    Text("متابعة")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showBranchConfirm = false
                    }
                ) {
                    Text("إلغاء")
                }
            }
        )
    }

    // Confirm reset workspace
    if (showResetWorkspaceConfirm) {
        AlertDialog(
            onDismissRequest = {
                showResetWorkspaceConfirm = false
            },
            title = {
                Text("Reset workspace بالكامل؟")
            },
            text = {
                Text(
                    "تحذير: سيُحذف كل تاريخ المحادثات والفروع ضمن هذا الـWorkspace وسيتم تصفير ملخص الذاكرة (WORKSPACE_SUMMARY)."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetWorkspaceConfirm = false
                        showTreeSheet = false
                        clearDraftInput()
                        viewModel.resetCurrentWorkspaceFully()
                    },
                    enabled = !isLoading
                ) {
                    Text("نعم، نفّذ")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showResetWorkspaceConfirm = false
                    }
                ) {
                    Text("إلغاء")
                }
            }
        )
    }

    // Agent picker
    if (showAgentDialog) {
        AgentPickerDialog(
            title = "Agent لهذه المحادثة",
            agents = agents,
            selectedId = currentAgentId,
            onDismiss = {
                showAgentDialog = false
            },
            onSelect = { id ->
                viewModel.setConversationAgent(id)
                showAgentDialog = false
            },
            extraTopContent = {
                TextButton(
                    onClick = {
                        showAgentDialog = false
                        onOpenAgents()
                    }
                ) {
                    Text("إدارة/استيراد Agents")
                }
            }
        )
    }

    // Save to memory dialog
    memoryMessage?.let { msg ->
        SaveMemoryDialog(
            message = msg,
            conversationId = conversationId,
            onDismiss = {
                memoryMessage = null
            },
            onSave = { category, isShared ->
                viewModel.addMemory(
                    msg.content,
                    category,
                    isShared,
                    msg.id
                )
                memoryMessage = null
            }
        )
    }

    // Web page import dialog
    if (showWebImportDialog) {
        WebPageImportDialog(
            onDismiss = {
                showWebImportDialog = false
            },
            onConfirm = { url ->
                viewModel.saveWebPage(url)
                showWebImportDialog = false
            }
        )
    }
}

/* ----------------------------- Provider selector (1.ب.2) ----------------------------- */

@Composable
private fun ProviderSelectorRow(
    enabledWebPlatforms: List<WebPlatform>,
    selectedWebPlatformId: String?,
    onSelectApi: () -> Unit,
    onSelectWebPlatform: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = selectedWebPlatformId == null,
            onClick = onSelectApi,
            label = {
                Text("API")
            },
            leadingIcon = {
                Icon(
                    Icons.Filled.SmartToy,
                    contentDescription = null
                )
            }
        )

        enabledWebPlatforms.forEach { platform ->
            FilterChip(
                selected = selectedWebPlatformId == platform.id,
                onClick = {
                    onSelectWebPlatform(platform.id)
                },
                label = {
                    Text("${platform.iconEmoji} ${platform.name}")
                }
            )
        }
    }
}

@Composable
private fun WebPlaceholderView(
    platformName: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "🌐",
                style = MaterialTheme.typography.displaySmall
            )

            Spacer(Modifier.height(8.dp))

            Text(
                "وضع الويب: $platformName",
                fontWeight = FontWeight.SemiBold
            )

            Spacer(Modifier.height(6.dp))

            Text(
                "قريبًا سيتم تنفيذ الإرسال الفعلي هنا",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/* ----------------------------- Tree UI helpers ----------------------------- */

@Composable
private fun ConversationNavRow(
    title: String,
    id: Long,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                Icons.Filled.AccountTree,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )

            Column(
                Modifier.weight(1f)
            ) {
                Text(
                    title.ifBlank { "بدون عنوان" }.take(90)
                )

                Text(
                    "id = $id",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null
            )
        }
    }
}

/* ----------------------------- UI components ----------------------------- */

@Composable
private fun BranchContextCard(
    sourceTitle: String,
    contextCount: Int
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = 12.dp,
                vertical = 10.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                Icons.Filled.AccountTree,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    "متابعة من محادثة سابقة",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )

                Text(
                    sourceTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1
                )

                Text(
                    "$contextCount رسالة محفوظة في السياق",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@Composable
private fun EmptyChatView(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "ابدأ محادثة جديدة",
                fontWeight = FontWeight.SemiBold
            )

            Spacer(Modifier.height(6.dp))

            Text(
                "اكتب سؤالك بالأسفل.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ThinkingBubble() {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp
            )

            Text(
                "يفكّر…",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: Message,
    onCopy: () -> Unit,
    onSaveMemory: () -> Unit,
    onBranch: () -> Unit,
    branchEnabled: Boolean
) {
    val isUser = remember(message) {
        guessIsUser(message)
    }

    val bg = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    val fg = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    val align = if (isUser) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = align
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = bg,
            tonalElevation = 1.dp,
            modifier = Modifier
                .widthIn(max = 520.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        onCopy()
                    }
                )
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                Text(
                    message.content,
                    color = fg
                )

                Spacer(Modifier.height(10.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AssistChip(
                        onClick = onCopy,
                        label = {
                            Text("نسخ")
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.ContentCopy,
                                contentDescription = null
                            )
                        }
                    )

                    AssistChip(
                        onClick = onSaveMemory,
                        label = {
                            Text("حفظ كذاكرة")
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Memory,
                                contentDescription = null
                            )
                        }
                    )
                }

                Spacer(Modifier.height(6.dp))

                AssistChip(
                    onClick = onBranch,
                    enabled = branchEnabled,
                    label = {
                        Text("محادثة جديدة من هنا")
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.AccountTree,
                            contentDescription = null
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun ErrorMessage(
    error: String,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    "خطأ",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )

                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }

            IconButton(onClick = onCopy) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = "Copy",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }

            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun InputBar(
    isLoading: Boolean,
    hasSelectedImage: Boolean,
    value: TextFieldValue,
    isEnhanced: Boolean,
    isEnhancing: Boolean,
    hasIdentity: Boolean,
    sendDisabledExternally: Boolean,
    onValueChange: (TextFieldValue) -> Unit,
    onOpenGallery: () -> Unit,
    onOpenCamera: () -> Unit,
    onOpenFile: () -> Unit,
    onOpenWebImport: () -> Unit,
    onRemoveImage: () -> Unit,
    onSend: () -> Unit,
    onEnhance: () -> Unit,
    onClearEnhanced: () -> Unit,
    onRefineStyle: (QueryStyle) -> Unit,
    modifier: Modifier = Modifier
) {
    var styleMenu by remember { mutableStateOf(false) }
    val displayedText = value.text

    Column(
        modifier = modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (hasSelectedImage) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                AssistChip(
                    onClick = {},
                    label = {
                        Text("📎 صورة مرفقة")
                    }
                )

                TextButton(onClick = onRemoveImage) {
                    Text("إزالة")
                }
            }
        }

        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                minLines = 2,
                maxLines = 6,
                label = {
                    Text(
                        if (isEnhanced) "النص المُحسَّن" else "اكتب رسالتك"
                    )
                },
                supportingText = {
                    val bits = buildList {
                        if (hasIdentity) add("Identity")
                        if (isEnhanced) add("Enhanced")
                    }

                    if (bits.isNotEmpty()) {
                        Text(bits.joinToString(" • "))
                    }
                },
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Send,
                    autoCorrect = false
                ),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (!sendDisabledExternally) {
                            onSend()
                        }
                    }
                )
            )

            IconButton(
                onClick = onSend,
                enabled = !isLoading && displayedText.isNotBlank() && !sendDisabledExternally
            ) {
                Icon(
                    Icons.Filled.Send,
                    contentDescription = "Send"
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onOpenGallery,
                enabled = !isLoading
            ) {
                Icon(
                    Icons.Filled.Image,
                    contentDescription = "Gallery"
                )
            }

            IconButton(
                onClick = onOpenCamera,
                enabled = !isLoading
            ) {
                Icon(
                    Icons.Filled.CameraAlt,
                    contentDescription = "Camera"
                )
            }

            IconButton(
                onClick = onOpenFile,
                enabled = !isLoading
            ) {
                Icon(
                    Icons.Filled.AttachFile,
                    contentDescription = "File"
                )
            }

            IconButton(
                onClick = onOpenWebImport,
                enabled = !isLoading
            ) {
                Icon(
                    Icons.Filled.Language,
                    contentDescription = "Web"
                )
            }

            Spacer(Modifier.weight(1f))

            Box {
                IconButton(
                    onClick = {
                        styleMenu = true
                    },
                    enabled = !isLoading
                ) {
                    Icon(
                        Icons.Filled.Tune,
                        contentDescription = "Refine style"
                    )
                }

                DropdownMenu(
                    expanded = styleMenu,
                    onDismissRequest = {
                        styleMenu = false
                    }
                ) {
                    enumValues<QueryStyle>().forEach { style ->
                        DropdownMenuItem(
                            text = {
                                Text(style.name)
                            },
                            onClick = {
                                styleMenu = false
                                onRefineStyle(style)
                            }
                        )
                    }
                }
            }

            if (isEnhanced) {
                TextButton(
                    onClick = onClearEnhanced,
                    enabled = !isLoading
                ) {
                    Text("إلغاء التحسين")
                }
            } else {
                TextButton(
                    onClick = onEnhance,
                    enabled = !isLoading &&
                        !isEnhancing &&
                        displayedText.isNotBlank()
                ) {
                    Text(
                        if (isEnhancing) "جارٍ التحسين…" else "تحسين السؤال"
                    )
                }
            }
        }
    }
}

@Composable
private fun SaveMemoryDialog(
    message: Message,
    conversationId: Long?,
    onDismiss: () -> Unit,
    onSave: (category: String, isShared: Boolean) -> Unit
) {
    var category by remember {
        mutableStateOf("OTHER")
    }

    var isShared by remember {
        mutableStateOf(true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("حفظ كذاكرة")
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "المحادثة: ${conversationId ?: "-"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(message.content.take(500))

                OutlinedTextField(
                    value = category,
                    onValueChange = {
                        category = it
                    },
                    label = {
                        Text("Category")
                    },
                    singleLine = true
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = isShared,
                        onCheckedChange = {
                            isShared = it
                        }
                    )

                    Spacer(Modifier.width(8.dp))
                    Text("Shared")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        category.trim().ifBlank { "OTHER" },
                        isShared
                    )
                }
            ) {
                Text("حفظ")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}

@Composable
private fun WebPageImportDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var url by remember {
        mutableStateOf("")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("استيراد صفحة ويب")
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "أدخل رابط الصفحة لحفظها/تلخيصها ضمن الذاكرة.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                    },
                    label = {
                        Text("URL")
                    },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(url.trim())
                },
                enabled = url.isNotBlank()
            ) {
                Text("متابعة")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}

/* ----------------------------- Reflection helpers ----------------------------- */

private fun guessIsUser(message: Message): Boolean {
    runCatching {
        val field = message.javaClass.declaredFields
            .firstOrNull { it.name == "isUser" }
            ?: return@runCatching

        field.isAccessible = true
        val value = field.get(message) as? Boolean
            ?: return@runCatching

        return value
    }

    runCatching {
        val field = message.javaClass.declaredFields
            .firstOrNull { it.name == "role" }
            ?: return@runCatching

        field.isAccessible = true

        val role = (field.get(message) as? String)
            ?.lowercase(Locale.ROOT)
            ?: return@runCatching

        return role == "user"
    }

    return false
}
