
package com.example.aichat.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.example.aichat.AichatApp
import com.example.aichat.GeckoSessionManager
import com.example.aichat.data.local.AiSettings
import com.example.aichat.data.model.Message
import com.example.aichat.data.model.AgentProfile
import com.example.aichat.data.model.QueryStyle
import com.example.aichat.data.model.WebPlatform
import com.example.aichat.repository.AgentPromptBuilder
import com.example.aichat.repository.WebSendCoordinator
import com.example.aichat.ui.components.AgentPickerDialog
import com.example.aichat.ui.viewmodel.AgentsViewModel
import com.example.aichat.ui.viewmodel.AgentChainProgress
import com.example.aichat.ui.viewmodel.MAX_AGENT_CHAIN_STEPS
import com.example.aichat.ui.viewmodel.ChainInputMode
import com.example.aichat.ui.viewmodel.ChainStepSpec
import com.example.aichat.ui.viewmodel.MAX_CHAIN_EXTRA_INSTRUCTION_CHARS
import com.example.aichat.ui.viewmodel.ChatViewModel
import com.example.aichat.ui.viewmodel.WebPlatformsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError
import java.io.File
import java.util.Locale
import com.example.aichat.ui.viewmodel.ChainStore
import com.example.aichat.ui.viewmodel.SavedChain
import com.example.aichat.ui.viewmodel.MAX_SAVED_CHAIN_NAME_CHARS
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    agentsViewModel: AgentsViewModel,
    webPlatformsViewModel: WebPlatformsViewModel,
    settings: AiSettings,
    onBack: () -> Unit,
    onOpenAgents: () -> Unit,
    onOpenSettings: () -> Unit
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
    val chainProgress by viewModel.chainProgress.collectAsState()

    // ═══════════════════════════════════════════════════════════════
    // ✅ تعديل جديد (إصلاح استمرارية السياق بين API ومنصات الويب):
    // نقرأ ملخص الـ Workspace الحالي لاستخدامه كسياق يُحقن في صفحات
    // الويب قبل كل سؤال. هذا الملخص مُحدَّث تلقائيًا من ChatViewModel
    // بعد كل تبادل سواء جاء عبر API أو عبر الويب (نفس workspaceId
    // بفضل التوحيد في خطوة 1.أ)، لذلك هو المصدر الصحيح لضمان السياق
    // في الاتجاهات الثلاثة: API→ويب، ويب→ويب (نفس/مختلف المنصة).
    // ═══════════════════════════════════════════════════════════════
    val workspaceSummary by viewModel.workspaceSummary.collectAsState()

    // ✅ إضافة جديدة (قائمة المزودين السريعة):
    // state محلي يعكس المزود النشط الحالي ونموذجه، يتحدّث فورًا
    // عند التبديل من قائمة API — بدل قراءة settings الثابتة وقت التكوين.
    var activeProviderId by remember { mutableStateOf(settings.provider) }
    var activeProviderLabel by remember {
        mutableStateOf(
            "${settings.getProviderLabel(settings.provider)} · ${settings.getActiveModel()}"
        )
    }

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

    // ── Provider selector (1.ب.2 / 1.ب.3 / 1.ب.4) ──
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
    var showChainDialog by remember { mutableStateOf(false) }
    var topMenuExpanded by remember { mutableStateOf(false) }
    var showWebImportDialog by remember { mutableStateOf(false) }

    // ✅ عارض سجلات التشخيص المؤقت
    var showDebugLogs by remember { mutableStateOf(false) }

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

    // ── 1.ب.3: محرك الويب المخفي ──
    val app = remember(context) { context.applicationContext as AichatApp }

    val selectedPlatform = remember(selectedWebPlatformId, enabledWebPlatforms) {
        enabledWebPlatforms.firstOrNull { it.id == selectedWebPlatformId }
    }

    // ── تصنيف نوع المنصة المختارة ──
    val webKind = remember(selectedPlatform?.id, selectedPlatform?.url) {
        selectedPlatform?.let { WebSendCoordinator.classifyHost(hostOf(it.url)) }
            ?: WebSendCoordinator.WebKind.OTHER
    }

    // ChatGPT + Venice: محرك مخفي 1dp، إرسال آلي كامل (ضغط الزر برمجيًا).
    val isHiddenAutoSendPlatform = remember(webKind) {
        webKind == WebSendCoordinator.WebKind.CHATGPT ||
            webKind == WebSendCoordinator.WebKind.VENICE
    }

    // ── 1.ب.4: Arena — محرك مرئي يستبدل منطقة الرسائل، الحقن تلقائي
    // لكن الإرسال الفعلي يدوي من المستخدم داخل صفحة Arena نفسها.
    // content.js (injectAndSend) يتوقف عمدًا عند "arena_text_only" ولا
    // يضغط زر الإرسال لهذه المنصة؛ startArenaSendObserver يرصد ضغط
    // المستخدم اليدوي (Enter/زر/submit) ليبدأ الالتقاط تلقائيًا.
    val isArenaPlatform = remember(webKind) {
        webKind == WebSendCoordinator.WebKind.ARENA
    }

    // أي منصة يمكن فعليًا حقن نص فيها عبر setContextPending (مخفية أو مرئية)
    val isWebSendCapable = isHiddenAutoSendPlatform || isArenaPlatform

    var webPageLoading by remember(selectedWebPlatformId) { mutableStateOf(true) }
    var webLoadError by remember(selectedWebPlatformId) { mutableStateOf<String?>(null) }
    var webSendInFlight by remember(selectedWebPlatformId) { mutableStateOf(false) }
    var webRuntimeMissing by remember(selectedWebPlatformId) { mutableStateOf(false) }

    // ✅ G1: عدّاد انتظار رد الويب — يميّز "تأخر" عن "فشل محتمل"
    var webSendElapsedSec by remember(selectedWebPlatformId) { mutableStateOf(0) }
    var webSendTimedOut by remember(selectedWebPlatformId) { mutableStateOf(false) }

    val sendDisabledExternally = selectedWebPlatformId != null && (
        !isWebSendCapable || webPageLoading || webSendInFlight || webRuntimeMissing
    )

    // ── 1.ج.1: طي/فرد مربع الإدخال لتحرير مساحة للرسائل/محرك Arena المرئي ──
    var inputBarExpanded by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(isArenaPlatform) {
        if (isArenaPlatform) {
            inputBarExpanded = false
        }
    }

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

    // ── تسجيل التقاط ردود منصة الويب الحالية (مخفية أو مرئية) ──
    DisposableEffect(selectedWebPlatformId, isWebSendCapable) {
        val platform = selectedPlatform

        if (platform != null && isWebSendCapable && platform.memoryEnabled) {
            app.onAiResponseCaptured = { source, text ->
                webSendInFlight = false
                webSendTimedOut = false
                viewModel.onWebAiResponse(
                    platformId = source.ifBlank { platform.id },
                    platformName = platform.name,
                    text = text
                )
            }
        }

        onDispose {
            app.onAiResponseCaptured = null
            app.onManualCaptureResult = null
            app.onContextWritten = null
        }
    }

    // ✅ G1: عدّاد الانتظار — يعمل فقط أثناء webSendInFlight.
    // عند انقضاء المهلة: نحرّر الواجهة فقط (webSendInFlight=false)،
    // ولا نلمس طابور الـVM — فأي رد متأخر يُلتقط ويُحفظ كالمعتاد.
    LaunchedEffect(webSendInFlight) {
        if (!webSendInFlight) return@LaunchedEffect

        webSendElapsedSec = 0
        webSendTimedOut = false

        while (webSendInFlight && webSendElapsedSec < WEB_REPLY_TIMEOUT_SEC) {
            delay(1000)
            webSendElapsedSec++
        }

        if (webSendInFlight) {
            webSendInFlight = false
            webSendTimedOut = true
            app.logDebug(
                "⏱️ WEB_REPLY_TIMEOUT platform=${selectedPlatform?.id ?: "?"} بعد ${WEB_REPLY_TIMEOUT_SEC}ث دون التقاط"
            )
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

    // ═══════════════════════════════════════════════════════════════════════
    // ✅ التعديل الوحيد المقصود في هذه الجلسة: sendWebMessage()
    //
    // قبل التعديل: كانت الدالة تُرسل textToSend الخام فقط عبر
    // app.setContextPending(...)، متجاوزةً WebSendCoordinator.buildContextBlock
    // وbuildFinalTextForPlatform الموجودتين أصلاً وجاهزتين.
    //
    // بعد التعديل: نبني contextBlock من workspaceSummary الحالي (نفس القيمة
    // التي يستخدمها sendChatMessage() عبر API)، وندمجه مع السؤال بالصيغة
    // المناسبة لكل منصة (مخفية لـ ChatGPT/Venice، مرئية لـ Arena) عبر
    // buildFinalTextForPlatform. النص المُسجَّل في قاعدة البيانات والعنوان
    // يبقى rawText النظيف (بدون كتلة السياق) حفاظًا على نظافة السجل.
    //
    // لا تغيير على: onWebUserMessageSentAndTrackInViewModelScope، منطق
    // Arena/ChatGPT/Venice الالتقاطي، أو أي شيء آخر في هذا الملف.
    // ═══════════════════════════════════════════════════════════════════════
    fun sendWebMessage() {
        val platform = selectedPlatform ?: return
        if (!isWebSendCapable) return
        if (webPageLoading || webSendInFlight || webRuntimeMissing) return

        val rawText = (
            if (isEnhanced) enhancedInput.text else userInput.text
        ).trim()

        if (rawText.isBlank()) return

        val activeConversationIdAtSend = conversationId
        val agentIdAtSend = currentAgentId

        scope.launch {
            keyboardController?.hide()
            focusManager.clearFocus(force = true)
            delay(80)

            val priorMessages = activeConversationIdAtSend
                ?.let { activeId ->
                    viewModel.messages.value
                        .filter { it.conversationId == activeId }
                        .takeLast(WEB_CONTEXT_RECENT_MESSAGE_COUNT)
                }
                .orEmpty()

            val recentMessagesContext = priorMessages
                .joinToString(separator = "\n\n") { message ->
                    val speaker = when (message.role.lowercase(Locale.ROOT)) {
                        "user" -> "المستخدم"
                        "assistant" -> "المساعد"
                        else -> message.role
                    }

                    val attachmentNote =
                        if (message.imageBase64 != null) {
                            " [مرفق صورة سابقة غير منقول]"
                        } else {
                            ""
                        }

                    "$speaker$attachmentNote:\n${message.content}"
                }
                .let { transcript ->
                    if (transcript.isBlank()) {
                        ""
                    } else {
                        "RECENT_CONVERSATION_MESSAGES:\n$transcript"
                    }
                }

            webSendInFlight = true
            webSendTimedOut = false
            webSendElapsedSec = 0
            userInput = TextFieldValue("")
            viewModel.clearEnhancedQuery()

            val tracked = viewModel.onWebUserMessageSentAndTrackInViewModelScope(
                userText = rawText,
                platformId = platform.id,
                platformName = platform.name
            ).await()

            if (!tracked) {
                webSendInFlight = false
                return@launch
            }

            val agentPromptForWeb = agents
                .firstOrNull { it.id == agentIdAtSend }
                ?.let { AgentPromptBuilder.build(it) }
                .orEmpty()

            // بناء كتلة السياق من ملخص الـ Workspace الحالي (نفس المصدر
            // المستخدم من جانب API)، وهذا يحل مشكلة فشل انتقال السياق في
            // الاتجاهات: API→ويب، ويب→ويب (نفس/مختلف المنصة).
            val contextBlock = WebSendCoordinator.buildContextBlock(
                agentPrompt = agentPromptForWeb,
                workspaceSummary = workspaceSummary,
                extraNotes = recentMessagesContext
            )

            val finalText = WebSendCoordinator.buildFinalTextForPlatform(
                kind = webKind,
                contextBlock = contextBlock,
                question = rawText
            )

            app.setContextPending(
                text = finalText,
                targetDomain = hostOf(platform.url),
                inputSelector = platform.inputSelector,
                sendSelector = platform.sendSelector,
                sendWithEnter = platform.sendWithEnter
            )
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

                        EngineSelector(
                            settings = settings,
                            activeProviderId = activeProviderId,
                            activeProviderLabel = activeProviderLabel,
                            enabledWebPlatforms = enabledWebPlatforms,
                            selectedWebPlatformId = selectedWebPlatformId,
                            selectedWebPlatformName = selectedWebPlatformName,
                            onProviderChange = { newProviderId, newLabel ->
                                activeProviderId = newProviderId
                                activeProviderLabel = newLabel
                                selectedWebPlatformId = null
                                Toast.makeText(
                                    context,
                                    "تم التبديل إلى $newLabel",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            onSelectWebPlatform = { id ->
                                selectedWebPlatformId = id
                            },
                            onOpenSettings = onOpenSettings
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
                    // ✅ قائمة خيارات موحّدة (⋮) — تجمع كل ما كان أيقونات مبعثرة.
                    Box {
                        IconButton(onClick = { topMenuExpanded = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = "خيارات المحادثة"
                            )
                        }

                        DropdownMenu(
                            expanded = topMenuExpanded,
                            onDismissRequest = { topMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Filled.Memory,
                                            contentDescription = null,
                                            tint = if (memoryAccessEnabled) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            if (memoryAccessEnabled) {
                                                "الذاكرة: مفعّلة"
                                            } else {
                                                "الذاكرة: معطّلة"
                                            }
                                        )
                                    }
                                },
                                onClick = {
                                    viewModel.toggleMemoryAccess(!memoryAccessEnabled)
                                    topMenuExpanded = false
                                }
                            )

                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Filled.SmartToy,
                                            contentDescription = null
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text("اختيار Agent")
                                    }
                                },
                                onClick = {
                                    showAgentDialog = true
                                    topMenuExpanded = false
                                }
                            )

                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Filled.Link,
                                            contentDescription = null
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Column {
                                            Text("سلسلة وكلاء")
                                            if (selectedWebPlatformId != null) {
                                                Text(
                                                    "السلسلة تعمل مع API فقط الآن",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                },
                                onClick = {
                                    showChainDialog = true
                                    topMenuExpanded = false
                                },
                                enabled = selectedWebPlatformId == null && !isLoading
                            )

                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Filled.AccountTree,
                                            contentDescription = null
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text("شجرة المحادثة")
                                    }
                                },
                                onClick = {
                                    showTreeSheet = true
                                    topMenuExpanded = false
                                },
                                enabled = conversationId != null
                            )

                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Filled.Settings,
                                            contentDescription = null
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text("الإعدادات")
                                    }
                                },
                                onClick = {
                                    onOpenSettings()
                                    topMenuExpanded = false
                                }
                            )

                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Filled.BugReport,
                                            contentDescription = null
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text("سجلات التشخيص")
                                    }
                                },
                                onClick = {
                                    showDebugLogs = true
                                    topMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding() // ← 1.ج.1: يدفع المحتوى فوق الكيبورد بدل أن يُغطّى به
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                if (selectedWebPlatformId != null) {
                    val statusLabel = when {
                        !isWebSendCapable -> "هذه المنصة قيد التطوير — قريبًا"
                        webRuntimeMissing -> "⚠️ تعذر تهيئة محرك الويب"
                        webLoadError != null -> "⚠️ ${webLoadError}"
                        webPageLoading -> "جاري تجهيز الصفحة..."
                        webSendTimedOut ->
                            "⚠️ لم يصل رد خلال ${WEB_REPLY_TIMEOUT_SEC}ث — أعد الإرسال، وأي رد متأخر سيُحفظ تلقائيًا"
                        webSendInFlight && webSendElapsedSec >= WEB_REPLY_WARN_AFTER_SEC ->
                            "الرد متأخر (${webSendElapsedSec}ث) — لا يزال الانتظار جاريًا"
                        webSendInFlight -> "بانتظار الرد... (${webSendElapsedSec}ث)"
                        else -> "جاهز"
                    }

                    val statusIsAlert = webSendTimedOut ||
                        (webSendInFlight && webSendElapsedSec >= WEB_REPLY_WARN_AFTER_SEC)

                    Text(
                        text = "${selectedPlatform?.iconEmoji ?: "🌐"} ${selectedPlatform?.name.orEmpty()} — $statusLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (statusIsAlert) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    )

                    if (isHiddenAutoSendPlatform && selectedPlatform != null) {
                        HiddenWebEngineHost(
                            platform = selectedPlatform,
                            app = app,
                            onLoadingChange = { webPageLoading = it },
                            onError = { webLoadError = it },
                            onRuntimeMissing = { webRuntimeMissing = true }
                        )
                    }
                }

                if (isArenaPlatform && selectedPlatform != null) {
                    // ── 1.ب.4: استبدال منطقة الرسائل بمحرك ويب مرئي ──
                    // المستخدم يكتب في InputBar كالمعتاد؛ النص يُحقن تلقائيًا
                    // في صفحة Arena الظاهرة هنا، ويضغط المستخدم الإرسال يدويًا
                    // داخل الصفحة نفسها. بمجرد التقاط الرد تلقائيًا، يمكن
                    // التبديل لـ API لرؤية الفقاعات المحدثة.
                    VisibleWebEngineHost(
                        platform = selectedPlatform,
                        app = app,
                        modifier = Modifier.weight(1f),
                        onLoadingChange = { webPageLoading = it },
                        onError = { webLoadError = it },
                        onRuntimeMissing = { webRuntimeMissing = true }
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
                                    val progress = chainProgress
                                    if (progress != null) {
                                        AgentChainProgressBubble(
                                            progress = progress,
                                            onCancel = {
                                                viewModel.cancelAgentChain()
                                                Toast.makeText(
                                                    context,
                                                    "جارٍ إيقاف السلسلة…",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        )
                                    } else {
                                        ThinkingBubble()
                                    }
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
                sendDisabledExternally = sendDisabledExternally,
                expanded = inputBarExpanded,
                onToggleExpanded = { inputBarExpanded = !inputBarExpanded },
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
                onSend = {
                    if (selectedWebPlatformId != null) sendWebMessage() else send()
                },
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

    // Agent chain dialog
    if (showChainDialog) {
        val chainInputText = (
            if (isEnhanced) enhancedInput.text else userInput.text
        ).trim()

        AgentChainDialog(
            agents = agents,
            inputText = chainInputText,
            onDismiss = { showChainDialog = false },
            onRun = { steps ->
                if (selectedImage != null) {
                    Toast.makeText(
                        context,
                        "السلسلة لا تدعم الصور الآن. أزل الصورة المحددة أولًا.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    showChainDialog = false
                    keyboardController?.hide()
                    focusManager.clearFocus(force = true)
                    viewModel.runAgentChainSteps(chainInputText, steps)
                    userInput = TextFieldValue("")
                    viewModel.clearEnhancedQuery()
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

    // ✅ عارض سجلات التشخيص المؤقت
    if (showDebugLogs) {
        AlertDialog(
            onDismissRequest = { showDebugLogs = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.BugReport,
                        contentDescription = null
                    )

                    Spacer(Modifier.width(8.dp))

                    Text(
                        text = "سجلات التشخيص",
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(
                        onClick = { showDebugLogs = false }
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "إغلاق"
                        )
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val logs = app.debugLog

                    if (logs.isEmpty()) {
                        Text(
                            "لا توجد سجلات حتى الآن.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        SelectionContainer {
                            Text(
                                text = logs.joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(
                            Context.CLIPBOARD_SERVICE
                        ) as ClipboardManager

                        val text = app.debugLog.joinToString("\n")

                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("AiChat Debug Logs", text)
                        )

                        Toast.makeText(
                            context,
                            if (text.isBlank())
                                "لا توجد سجلات لنسخها"
                            else
                                "تم نسخ السجلات",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                ) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("نسخ الكل")
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            app.debugLog.clear()
                        }
                    ) {
                        Icon(
                            Icons.Filled.DeleteOutline,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("مسح")
                    }

                    TextButton(
                        onClick = { showDebugLogs = false }
                    ) {
                        Text("إغلاق")
                    }
                }
            }
        )
    }
}

/* ----------------------------- محرك الويب (مشترك: مخفي/مرئي) ----------------------------- */

@Composable
private fun WebEngineHostCore(
    platform: WebPlatform,
    app: AichatApp,
    boxModifier: Modifier,
    onLoadingChange: (Boolean) -> Unit,
    onError: (String?) -> Unit,
    onRuntimeMissing: () -> Unit
) {
    val runtime = remember { app.getOrCreateGeckoRuntime() }

    if (runtime == null) {
        LaunchedEffect(Unit) { onRuntimeMissing() }
        return
    }

    val sessionKey = platform.id
    val session = remember(sessionKey) { GeckoSessionManager.getOrCreate(sessionKey) }

    DisposableEffect(session, sessionKey) {
        onLoadingChange(!session.isOpen)

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                onLoadingChange(true)
                onError(null)
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {}

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                onLoadingChange(false)
            }
        }

        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                val uri = Uri.parse(request.uri)
                val scheme = uri.scheme?.lowercase(Locale.ROOT)

                if (scheme == "https" || scheme == "http") {
                    if ((uri.fragment ?: "").startsWith("aichat-capture-")) {
                        return GeckoResult.deny()
                    }
                }

                return if (scheme in listOf("http", "https", "about", "blob", "data")) {
                    GeckoResult.allow()
                } else {
                    GeckoResult.deny()
                }
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError
            ): GeckoResult<String>? {
                onLoadingChange(false)
                onError("تعذر تحميل صفحة المنصة")
                return null
            }
        }

        onDispose {
            runCatching { session.setActive(false) }
            runCatching { session.progressDelegate = null }
            runCatching { session.navigationDelegate = null }
        }
    }

    Box(modifier = boxModifier) {
        key(sessionKey) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    GeckoView(ctx).apply {
                        setSession(session)

                        if (!session.isOpen) {
                            session.open(runtime)
                            session.setActive(true)
                            session.loadUri(platform.url)
                        } else {
                            session.setActive(true)
                        }
                    }
                },
                update = { view ->
                    runCatching { view.setSession(session) }
                    runCatching { if (session.isOpen) session.setActive(true) }
                }
            )
        }
    }
}

/** ChatGPT / Venice — محرك مخفي 1dp×1dp (بلا تغيير سلوكي عن 1.ب.3) */
@Composable
private fun HiddenWebEngineHost(
    platform: WebPlatform,
    app: AichatApp,
    onLoadingChange: (Boolean) -> Unit,
    onError: (String?) -> Unit,
    onRuntimeMissing: () -> Unit
) {
    WebEngineHostCore(
        platform = platform,
        app = app,
        boxModifier = Modifier.size(1.dp),
        onLoadingChange = onLoadingChange,
        onError = onError,
        onRuntimeMissing = onRuntimeMissing
    )
}

/** Arena (1.ب.4) — محرك مرئي يملأ المساحة المخصصة له بدلاً من منطقة الرسائل */
@Composable
private fun VisibleWebEngineHost(
    platform: WebPlatform,
    app: AichatApp,
    modifier: Modifier,
    onLoadingChange: (Boolean) -> Unit,
    onError: (String?) -> Unit,
    onRuntimeMissing: () -> Unit
) {
    WebEngineHostCore(
        platform = platform,
        app = app,
        boxModifier = modifier.fillMaxWidth(),
        onLoadingChange = onLoadingChange,
        onError = onError,
        onRuntimeMissing = onRuntimeMissing
    )
}

private fun hostOf(url: String): String =
    runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")

// ✅ G1: عتبات انتظار رد الويب (بالثواني)
private const val WEB_REPLY_WARN_AFTER_SEC = 60
private const val WEB_REPLY_TIMEOUT_SEC = 120
private const val WEB_CONTEXT_RECENT_MESSAGE_COUNT = 4

/* ----------------------------- مُحدِّد المحرّك الموحّد (API + منصات الويب) ----------------------------- */

/**
 * مُحدِّد واحد في الشريط العلوي يعرض المحرّك الحالي:
 * - "⚡ المزود · النموذج" عندما يكون API هو النشط.
 * - "🌐 اسم المنصة" عندما تكون منصة ويب هي النشطة.
 *
 * الضغط عليه يفتح قائمة موحّدة بقسمين:
 *   1) مزودو API المُهيّأون (مفاتيحهم محفوظة).
 *   2) منصات الويب المتاحة (المفعّلة والقابلة للحقن فقط).
 * وسطر أخير للانتقال إلى الإعدادات.
 *
 * يحل محل شريط الـChips المستطيل القديم بالكامل.
 */
@Composable
private fun EngineSelector(
    settings: AiSettings,
    activeProviderId: String,
    activeProviderLabel: String,
    enabledWebPlatforms: List<WebPlatform>,
    selectedWebPlatformId: String?,
    selectedWebPlatformName: String?,
    onProviderChange: (String, String) -> Unit,
    onSelectWebPlatform: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val configuredProviders = remember { settings.getConfiguredApiProviders() }
    var menuExpanded by remember { mutableStateOf(false) }

    // منصات الويب القابلة للاستخدام فعليًا فقط (نفس معيار الشريط القديم).
    val usableWebPlatforms = remember(enabledWebPlatforms) {
        enabledWebPlatforms.filter { platform ->
            val kind = WebSendCoordinator.classifyHost(hostOf(platform.url))
            kind == WebSendCoordinator.WebKind.CHATGPT ||
                kind == WebSendCoordinator.WebKind.VENICE ||
                kind == WebSendCoordinator.WebKind.ARENA
        }
    }

    val currentLabel = if (selectedWebPlatformId != null) {
        val platform = enabledWebPlatforms.firstOrNull { it.id == selectedWebPlatformId }
        "${platform?.iconEmoji ?: "🌐"} ${selectedWebPlatformName ?: "منصة ويب"}"
    } else {
        "⚡ $activeProviderLabel"
    }

    Box {
        Row(
            modifier = Modifier.clickable { menuExpanded = true },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = currentLabel.take(34),
                style = MaterialTheme.typography.labelSmall,
                color = if (selectedWebPlatformId != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = "تبديل المزود أو المنصة",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            // ── القسم الأول: مزودو API ──
            if (configuredProviders.isNotEmpty()) {
                Text(
                    "مزودو API",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )

                configuredProviders.forEach { info ->
                    val isActive =
                        selectedWebPlatformId == null && info.provider == activeProviderId

                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isActive) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                } else {
                                    Spacer(Modifier.width(22.dp))
                                }

                                Column {
                                    Text(
                                        info.label,
                                        fontWeight = if (isActive) {
                                            FontWeight.SemiBold
                                        } else {
                                            FontWeight.Normal
                                        }
                                    )
                                    Text(
                                        info.model.take(30),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        onClick = {
                            menuExpanded = false
                            if (isActive) return@DropdownMenuItem

                            val model = settings.activateApiProvider(info.provider)
                            onProviderChange(info.provider, "${info.label} · $model")
                        }
                    )
                }
            }

            // ── القسم الثاني: منصات الويب ──
            if (usableWebPlatforms.isNotEmpty()) {
                if (configuredProviders.isNotEmpty()) {
                    HorizontalDivider()
                }

                Text(
                    "منصات الويب",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )

                usableWebPlatforms.forEach { platform ->
                    val isActive = selectedWebPlatformId == platform.id

                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isActive) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                } else {
                                    Spacer(Modifier.width(22.dp))
                                }

                                Text("${platform.iconEmoji} ${platform.name}")
                            }
                        },
                        onClick = {
                            menuExpanded = false
                            if (isActive) return@DropdownMenuItem
                            onSelectWebPlatform(platform.id)
                        }
                    )
                }
            }

            if (configuredProviders.isEmpty() && usableWebPlatforms.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("لا يوجد مزودون مهيّأون بعد") },
                    onClick = { },
                    enabled = false
                )
            }

            HorizontalDivider()

            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("إدارة المزودين")
                    }
                },
                onClick = {
                    menuExpanded = false
                    onOpenSettings()
                }
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

    // ✅ قائمة الإجراءات — تظهر فقط عند long-press (بدل الأيقونات الدائمة).
    var actionsMenuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = align
    ) {
        Box {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = bg,
                tonalElevation = 1.dp,
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            // نسخ فوري (كما كان) + إظهار قائمة الإجراءات.
                            onCopy()
                            actionsMenuExpanded = true
                        }
                    )
            ) {
                Text(
                    message.content,
                    color = fg,
                    modifier = Modifier.padding(12.dp)
                )
            }

            DropdownMenu(
                expanded = actionsMenuExpanded,
                onDismissRequest = { actionsMenuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Memory,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("حفظ كذاكرة")
                        }
                    },
                    onClick = {
                        actionsMenuExpanded = false
                        onSaveMemory()
                    }
                )

                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.AccountTree,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("تفريع من هنا")
                        }
                    },
                    onClick = {
                        actionsMenuExpanded = false
                        onBranch()
                    },
                    enabled = branchEnabled
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
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
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
    // ✅ طي الأدوات: قائمة مرفقات + قائمة صياغة + صف فرعي للتحسين النشط.
    var attachMenu by remember { mutableStateOf(false) }
    var styleMenu by remember { mutableStateOf(false) }
    val displayedText = value.text

    Column(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // مقبض الطي/الفرد محفوظ كما كان (لكنه الآن يتحكم فقط بارتفاع حقل النص).
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            IconButton(
                onClick = onToggleExpanded,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = if (expanded) {
                        Icons.Filled.KeyboardArrowDown
                    } else {
                        Icons.Filled.KeyboardArrowUp
                    },
                    contentDescription = if (expanded) {
                        "طي مربع الإدخال"
                    } else {
                        "إظهار خيارات الإدخال"
                    },
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

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

        // ── صف الإدخال الرئيسي: [+ مرفقات] [حقل النص] [🎛️ صياغة] [➤] ──
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // قائمة المرفقات — تجمع: معرض، كاميرا، ملف، صفحة ويب.
            Box {
                IconButton(
                    onClick = { attachMenu = true },
                    enabled = !isLoading
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "مرفقات وأدوات"
                    )
                }

                DropdownMenu(
                    expanded = attachMenu,
                    onDismissRequest = { attachMenu = false }
                ) {
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("صورة من المعرض")
                            }
                        },
                        onClick = {
                            attachMenu = false
                            onOpenGallery()
                        }
                    )

                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("التقاط صورة")
                            }
                        },
                        onClick = {
                            attachMenu = false
                            onOpenCamera()
                        }
                    )

                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("ملف")
                            }
                        },
                        onClick = {
                            attachMenu = false
                            onOpenFile()
                        }
                    )

                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Language, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("استيراد صفحة ويب")
                            }
                        },
                        onClick = {
                            attachMenu = false
                            onOpenWebImport()
                        }
                    )
                }
            }

            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                minLines = if (expanded) 2 else 1,
                maxLines = if (expanded) 6 else 2,
                label = {
                    Text(
                        if (isEnhanced) "النص المُحسَّن" else "اكتب رسالتك"
                    )
                },
                supportingText = if (expanded) {
                    {
                        val bits = buildList {
                            if (hasIdentity) add("Identity")
                            if (isEnhanced) add("Enhanced")
                        }

                        if (bits.isNotEmpty()) {
                            Text(bits.joinToString(" • "))
                        }
                    }
                } else null,
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

            // قائمة الصياغة — تحسين السؤال + تحسين النمط.
            Box {
                IconButton(
                    onClick = { styleMenu = true },
                    enabled = !isLoading
                ) {
                    Icon(
                        Icons.Filled.Tune,
                        contentDescription = "أدوات الصياغة"
                    )
                }

                DropdownMenu(
                    expanded = styleMenu,
                    onDismissRequest = { styleMenu = false }
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(if (isEnhancing) "جارٍ التحسين…" else "✨ تحسين السؤال")
                        },
                        onClick = {
                            styleMenu = false
                            onEnhance()
                        },
                        enabled = !isLoading &&
                            !isEnhancing &&
                            displayedText.isNotBlank()
                    )

                    HorizontalDivider()

                    Text(
                        "تحسين النمط",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )

                    enumValues<QueryStyle>().forEach { style ->
                        DropdownMenuItem(
                            text = {
                                Text(style.name)
                            },
                            onClick = {
                                styleMenu = false
                                onRefineStyle(style)
                            },
                            enabled = !isLoading && displayedText.isNotBlank()
                        )
                    }
                }
            }

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

        // ── صف فرعي يظهر فقط عند وجود نص مُحسَّن نشط ──
        if (isEnhanced) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onClearEnhanced,
                    enabled = !isLoading
                ) {
                    Text("إلغاء التحسين")
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

/* ----------------------------- Agent Chain UI ----------------------------- */

@Composable
private fun AgentChainDialog(
    agents: List<AgentProfile>,
    inputText: String,
    onDismiss: () -> Unit,
    onRun: (List<ChainStepSpec>) -> Unit
) {
    val chain = remember { mutableStateListOf<ChainStepSpec>() }
    val nameById = remember(agents) { agents.associate { it.id to it.name } }
    val isFull = chain.size >= MAX_AGENT_CHAIN_STEPS
    val hasMissingAgent = chain.any { it.agentId !in nameById }
    val canRun = chain.isNotEmpty() && inputText.isNotBlank() && !hasMissingAgent

    // السلاسل المحفوظة (ملف JSON في مجلد التطبيق).
    val dialogContext = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ChainStore(dialogContext) }
    var saved by remember { mutableStateOf<List<SavedChain>>(emptyList()) }
    var saveName by remember { mutableStateOf("") }
    val nameExists = saved.any { it.name.equals(saveName.trim(), ignoreCase = true) }

    LaunchedEffect(Unit) {
        saved = runCatching { store.loadAll() }.getOrDefault(emptyList())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("سلسلة وكلاء") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (inputText.isBlank()) {
                            "⚠️ اكتب نصك في خانة الإدخال أولًا"
                        } else {
                            inputText.take(120) + if (inputText.length > 120) "…" else ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (inputText.isBlank()) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(10.dp)
                    )
                }

                Text(
                    "السلسلة (${chain.size}/$MAX_AGENT_CHAIN_STEPS)",
                    fontWeight = FontWeight.SemiBold
                )

                if (chain.isEmpty()) {
                    Text(
                        "اختر وكيلًا من القائمة أدناه، أو حمّل سلسلة محفوظة.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    chain.forEachIndexed { index, step ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val missing = step.agentId !in nameById
                                    Text(
                                        "${index + 1}. ${nameById[step.agentId] ?: "(وكيل محذوف)"}",
                                        modifier = Modifier.weight(1f),
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (missing) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        maxLines = 1
                                    )

                                    IconButton(
                                        onClick = { chain.add(index - 1, chain.removeAt(index)) },
                                        enabled = index > 0,
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.ArrowUpward,
                                            contentDescription = "أعلى",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    IconButton(
                                        onClick = { chain.add(index + 1, chain.removeAt(index)) },
                                        enabled = index < chain.lastIndex,
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.ArrowDownward,
                                            contentDescription = "أسفل",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    IconButton(
                                        onClick = { chain.removeAt(index) },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = "حذف",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                if (index == 0) {
                                    Text(
                                        "الخطوة الأولى تستلم نصك الأصلي دائمًا.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    Text(
                                        "ما تستلمه هذه الخطوة:",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    ChainInputMode.values().forEach { mode ->
                                        val label = when (mode) {
                                            ChainInputMode.PREVIOUS_ONLY -> "ناتج السابقة فقط"
                                            ChainInputMode.ORIGINAL_AND_PREVIOUS -> "الأصل + ناتج السابقة"
                                            ChainInputMode.ORIGINAL_ONLY -> "النص الأصلي فقط"
                                        }
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    chain[index] = chain[index].copy(inputMode = mode)
                                                },
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            RadioButton(
                                                selected = step.inputMode == mode,
                                                onClick = null
                                            )
                                            Text(
                                                label,
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.padding(start = 8.dp)
                                            )
                                        }
                                    }
                                    if (step.inputMode == ChainInputMode.ORIGINAL_ONLY) {
                                        Text(
                                            "تنبيه: هذه الخطوة تتجاهل ناتج السابقة.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }

                                OutlinedTextField(
                                    value = step.extraInstruction,
                                    onValueChange = {
                                        chain[index] = chain[index].copy(
                                            extraInstruction = it.take(MAX_CHAIN_EXTRA_INSTRUCTION_CHARS)
                                        )
                                    },
                                    label = { Text("تعليمة إضافية (اختياري)") },
                                    supportingText = {
                                        Text("${step.extraInstruction.length}/$MAX_CHAIN_EXTRA_INSTRUCTION_CHARS")
                                    },
                                    maxLines = 3,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    if (hasMissingAgent) {
                        Text(
                            "⚠️ في السلسلة وكيل محذوف. احذف خطوته ثم أضف بديلًا ليُفعَّل التشغيل.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    // حفظ السلسلة الحالية باسم
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = saveName,
                            onValueChange = { saveName = it.take(MAX_SAVED_CHAIN_NAME_CHARS) },
                            label = { Text("اسم السلسلة للحفظ") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            enabled = saveName.isNotBlank(),
                            onClick = {
                                val toSave = chain.toList()
                                val nameToSave = saveName
                                scope.launch {
                                    try {
                                        saved = store.save(nameToSave, toSave)
                                        Toast.makeText(dialogContext, "حُفظت السلسلة.", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(
                                            dialogContext,
                                            e.message ?: "تعذر حفظ السلسلة.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        ) {
                            Text(if (nameExists) "استبدال" else "حفظ")
                        }
                    }
                }

                HorizontalDivider()

                Text(
                    "السلاسل المحفوظة (${saved.size})",
                    fontWeight = FontWeight.SemiBold
                )

                if (saved.isEmpty()) {
                    Text(
                        "لا توجد سلاسل محفوظة بعد.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    saved.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    chain.clear()
                                    chain.addAll(item.steps)
                                    saveName = item.name
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${item.name}  •  ${item.steps.size} خطوات",
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                maxLines = 1
                            )
                            IconButton(
                                onClick = {
                                    val idToDelete = item.id
                                    scope.launch {
                                        try {
                                            saved = store.delete(idToDelete)
                                        } catch (e: Exception) {
                                            Toast.makeText(
                                                dialogContext,
                                                e.message ?: "تعذر حذف السلسلة.",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "حذف السلسلة المحفوظة",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                    Text(
                        "لمسة على السلسلة تحمّلها وتستبدل السلسلة الحالية في الحوار.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                HorizontalDivider()

                Text(
                    if (isFull) "الوكلاء المتاحون (اكتمل الحد الأقصى)" else "الوكلاء المتاحون",
                    fontWeight = FontWeight.SemiBold
                )

                if (agents.isEmpty()) {
                    Text(
                        "لا يوجد وكلاء مستوردون. استورد وكيلًا من «إدارة/استيراد Agents».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    agents.forEach { agent ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isFull) {
                                    chain.add(ChainStepSpec(agentId = agent.id))
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (isFull) {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                            Text(
                                agent.name,
                                maxLines = 1,
                                color = if (isFull) {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onRun(chain.toList()) },
                enabled = canRun
            ) {
                Text("تشغيل")
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
private fun AgentChainProgressBubble(
    progress: AgentChainProgress,
    onCancel: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp
            )

            Text(
                "الخطوة ${progress.step} من ${progress.total} — ${progress.agentName}…",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )

            TextButton(onClick = onCancel) {
                Text("إلغاء")
            }
        }
    }
}
