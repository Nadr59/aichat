@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.aichat.ui.screens

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aichat.AichatApp
import com.example.aichat.GeckoSessionManager
import com.example.aichat.data.local.AiSettings
import com.example.aichat.data.local.SystemPrompt
import com.example.aichat.data.model.AgentProfile
import com.example.aichat.data.model.Conversation
import com.example.aichat.data.model.MemoryItem
import com.example.aichat.data.model.Message
import com.example.aichat.data.model.WebPlatform
import com.example.aichat.data.model.Workspace
import com.example.aichat.repository.AgentPromptBuilder
import com.example.aichat.repository.AgentRepository
import com.example.aichat.repository.ConversationRepository
import com.example.aichat.repository.MemoryContextBuilder
import com.example.aichat.repository.MemoryCuratorService
import com.example.aichat.repository.MemoryWindowBuilder
import com.example.aichat.repository.WebPlatformRepository
import com.example.aichat.ui.components.AgentPickerDialog
import com.example.aichat.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError
import java.util.Locale

/**
 * GeckoTestScreen (Tabs + GeckoView + SendBar) +:
 * ✅ Web Agent injection
 * ✅ Workspace Summary injection (scopeKey = web:global)
 * ✅ زر "الشجرة" + BottomSheet لشجرة web:global
 *
 * ملاحظة:
 * - BottomSheet الآن لا يعتمد على reflection لمعرفة "محادثات Workspace".
 * - يستخدم مباشرة:
 *   - repo.observeAllWorkspaces() لإيجاد Workspace web:global
 *   - repo.observeConversationsByWorkspace(wsId) لعرض الشجرة
 * - التنقل بين المحادثات واختيارها يتم الآن عبر واجهة عامة في ChatViewModel
 *   (currentWebConversationId / openWebConversation / clearWebConversationSelection)
 *   بدل الكتابة المباشرة إلى MutableStateFlow عبر Reflection.
 */
@Composable
fun GeckoTestScreen(
    webPlatformRepository: WebPlatformRepository,
    aiSettings: AiSettings,
    agentRepository: AgentRepository,
    chatViewModel: ChatViewModel? = null,
    initialPlatformId: String? = null,
    onBack: () -> Unit = {},
    onHome: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val app = context.applicationContext as AichatApp
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val scope = rememberCoroutineScope()

    // محاولة الحصول على ConversationRepository (لا نغيّر signature الشاشة).
    // إن كان موجودًا داخل ChatViewModel (حتى لو private) سنستخرجه بالـ reflection.
    val conversationRepository: ConversationRepository? = remember(chatViewModel) {
        resolveConversationRepositoryFromViewModel(chatViewModel)
    }

    // ✅ مهم: ضمان وجود المنصات الافتراضية
    LaunchedEffect(webPlatformRepository) {
        runCatching { webPlatformRepository.seedDefaults() }
    }

    val enabledPlatforms by webPlatformRepository.enabledPlatforms.collectAsStateWithLifecycle(initialValue = emptyList())
    val agents by agentRepository.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())

    var activePlatformId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(enabledPlatforms, initialPlatformId) {
        if (enabledPlatforms.isEmpty()) return@LaunchedEffect

        val wanted = initialPlatformId?.takeIf { it.isNotBlank() }
        activePlatformId = when {
            activePlatformId != null && enabledPlatforms.any { it.id == activePlatformId } -> activePlatformId
            wanted != null && enabledPlatforms.any { it.id == wanted } -> wanted
            else -> enabledPlatforms.first().id
        }
    }

    val platform: WebPlatform? = remember(enabledPlatforms, activePlatformId) {
        activePlatformId?.let { id -> enabledPlatforms.firstOrNull { it.id == id } }
            ?: enabledPlatforms.firstOrNull()
    }

    if (platform == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (enabledPlatforms.isEmpty()) Text("لا توجد منصات مفعلة بعد.")
            else CircularProgressIndicator()
        }
        return
    }

    val runtime = remember { app.getOrCreateGeckoRuntime() }
    if (runtime == null) {
        GeckoUnavailableDialog(
            platformTitle = platform.name,
            platformUrl = platform.url,
            onOpenBrowser = {
                openExternal(context, platform.url)
                onBack()
            },
            onBack = onBack
        )
        return
    }

    // =========================
    // Web Agent (Picker + injection)
    // =========================
    var showWebAgentDialog by rememberSaveable { mutableStateOf(false) }
    var webAgentId by rememberSaveable { mutableStateOf(aiSettings.webAgentIdOrNull) }
    var accuracyEnabled by rememberSaveable { mutableStateOf(aiSettings.accuracyPromptEnabled) }

    // ✅ شجرة الويب
    var showWebTreeSheet by rememberSaveable { mutableStateOf(false) }

    // ✅ قائمة المزيد + نافذة السجلات
    var showMoreMenu by remember { mutableStateOf(false) }
    var showDebugLogs by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        webAgentId = aiSettings.webAgentIdOrNull
        accuracyEnabled = aiSettings.accuracyPromptEnabled
    }

    val webAgentName = remember(webAgentId, agents) {
        webAgentId?.let { id -> agents.firstOrNull { it.id == id }?.name }
    }

    val webAgent: AgentProfile? by produceState<AgentProfile?>(initialValue = null, webAgentId) {
        value = webAgentId?.let { id -> runCatching { agentRepository.getById(id) }.getOrNull() }
    }

    val webAgentPrompt = remember(webAgent) {
        webAgent?.let { AgentPromptBuilder.build(it) }.orEmpty()
    }

    // =========================
    // Session state
    // =========================
    val sessionKey = platform.id
    val session = remember(sessionKey) { GeckoSessionManager.getOrCreate(sessionKey) }

    var isLoading by remember(sessionKey) { mutableStateOf(!session.isOpen) }
    var progress by remember(sessionKey) { mutableIntStateOf(0) }
    var canGoBack by remember(sessionKey) { mutableStateOf(false) }
    var currentUrl by remember(sessionKey) { mutableStateOf(platform.url) }
    var currentTitle by remember(sessionKey) { mutableStateOf(platform.name) }
    var loadError by remember(sessionKey) { mutableStateOf<String?>(null) }

    // capture
    var isSavingMemory by remember(sessionKey) { mutableStateOf(false) }
    var timeoutRunnable by remember(sessionKey) { mutableStateOf<Runnable?>(null) }

    // memory injection toggle
    var memoryInjectionEnabled by remember(sessionKey) { mutableStateOf(true) }

    // send bar state
    var query by remember(sessionKey) { mutableStateOf("") }
    var enhancedQuery by remember(sessionKey) { mutableStateOf<String?>(null) }
    var isEnhancing by remember(sessionKey) { mutableStateOf(false) }
    var includeMemory by remember(sessionKey) { mutableStateOf(false) }
    var isSending by remember(sessionKey) { mutableStateOf(false) }

    var searchResults by remember(sessionKey) { mutableStateOf(emptyList<MemoryItem>()) }
    var selectedIds by remember(sessionKey) { mutableStateOf(emptyList<Long>()) }
    var curatorReasoning by remember(sessionKey) { mutableStateOf("") }
    var curatedForText by remember(sessionKey) { mutableStateOf("") }

    val displayedText = enhancedQuery ?: query

    val memoryCuratorService = remember {
        MemoryCuratorService(settings = aiSettings, fallbackBuilder = MemoryContextBuilder())
    }
    val memoryWindowBuilder = remember { MemoryWindowBuilder() }

    fun hostOrEmptyFrom(urlStr: String): String =
        runCatching { Uri.parse(urlStr).host.orEmpty() }.getOrDefault("")

    fun targetDomainStable(): String =
        if (platform.url.isNotBlank()) hostOrEmptyFrom(platform.url) else hostOrEmptyFrom(currentUrl)

    fun webKind(): WebKind = classifyHost(targetDomainStable())

    // ✅ ذاكرة وشجرة مشتركة لكل منصات الويب:
    fun webWorkspaceScopeKeyGlobal(): String = "web:global"

    // ============================================================
    // Gecko delegates
    // ============================================================

    DisposableEffect(session, sessionKey) {
        session.progressDelegate =
            object : GeckoSession.ProgressDelegate {
                override fun onPageStart(session: GeckoSession, url: String) {
                    isLoading = true
                    progress = 0
                    loadError = null
                    currentUrl = url
                }

                override fun onProgressChange(session: GeckoSession, progress_: Int) {
                    progress = progress_
                }

                override fun onPageStop(session: GeckoSession, success: Boolean) {
                    isLoading = false
                }
            }

        session.contentDelegate =
            object : GeckoSession.ContentDelegate {
                override fun onTitleChange(session: GeckoSession, title: String?) {
                    if (!title.isNullOrBlank()) currentTitle = title.take(60)
                }
            }

        session.navigationDelegate =
            object : GeckoSession.NavigationDelegate {
                override fun onCanGoBack(session: GeckoSession, canGoBack_: Boolean) {
                    canGoBack = canGoBack_
                }

                override fun onLoadRequest(
                    session: GeckoSession,
                    request: GeckoSession.NavigationDelegate.LoadRequest
                ): GeckoResult<AllowOrDeny>? {
                    val uri = Uri.parse(request.uri)
                    val scheme = uri.scheme?.lowercase()

                    if (scheme == "https" || scheme == "http") {
                        if ((uri.fragment ?: "").startsWith("aichat-capture-")) {
                            return GeckoResult.deny()
                        }
                    }

                    return if (scheme in listOf("http", "https", "about", "blob", "data")) {
                        GeckoResult.allow()
                    } else {
                        openExternal(context, request.uri)
                        GeckoResult.deny()
                    }
                }

                override fun onLoadError(
                    session: GeckoSession,
                    uri: String?,
                    error: WebRequestError
                ): GeckoResult<String>? {
                    isLoading = false
                    loadError =
                        when (error.category) {
                            WebRequestError.ERROR_CATEGORY_NETWORK -> "تعذر الاتصال بالإنترنت"
                            WebRequestError.ERROR_CATEGORY_URI -> "الرابط غير صالح"
                            WebRequestError.ERROR_CATEGORY_SECURITY -> "مشكلة في شهادة الأمان"
                            else -> "حدث خطأ أثناء تحميل الصفحة"
                        }
                    return null
                }
            }

        onDispose {
            runCatching { session.setActive(false) }
            runCatching { session.progressDelegate = null }
            runCatching { session.contentDelegate = null }
            runCatching { session.navigationDelegate = null }
        }
    }

    // ============================================================
    // Extension callbacks
    // ============================================================

    DisposableEffect(platform.id) {
        val p = platform
        val vm = chatViewModel

        app.onContextWritten = { _, _, _ -> isSending = false }

        if (vm != null && p.memoryEnabled) {
            app.onAiResponseCaptured = { _, text ->
                vm.onWebAiResponse(p.id, p.name, text)
            }
            app.onManualCaptureResult = { success, text, debug ->
                timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                timeoutRunnable = null
                isSavingMemory = false

                if (success && text.isNotBlank()) {
                    vm.onWebAiResponse(p.id, p.name, text, forceSaveToMemory = true)
                    Toast.makeText(context, "✅ تم الحفظ\n${text.take(50)}", Toast.LENGTH_LONG).show()
                } else {
                    val info =
                        debug?.let {
                            "assistant=${it.optInt("assistant")} bodyLen=${it.optInt("bodyLen")}"
                        } ?: "no response"
                    Toast.makeText(context, "⚠️ فشل الاستخراج\n$info", Toast.LENGTH_LONG).show()
                }
            }
        }

        onDispose {
            timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            timeoutRunnable = null
            app.onAiResponseCaptured = null
            app.onManualCaptureResult = null
            app.onContextWritten = null
        }
    }

    // ============================================================
    // Memory search + curator selection (UI only)
    // ============================================================

    LaunchedEffect(includeMemory, displayedText, memoryInjectionEnabled, platform.id) {
        val memoryAllowed = memoryInjectionEnabled && platform.memoryEnabled && chatViewModel != null

        if (!memoryAllowed) {
            includeMemory = false
            searchResults = emptyList()
            selectedIds = emptyList()
            curatorReasoning = ""
            curatedForText = ""
            return@LaunchedEffect
        }

        val q = displayedText.trim()
        if (!includeMemory || q.length <= 2) {
            searchResults = emptyList()
            selectedIds = emptyList()
            curatorReasoning = ""
            curatedForText = ""
            return@LaunchedEffect
        }

        delay(350)

        try {
            val candidates = chatViewModel!!.searchSharedMemories(q)
            searchResults = candidates

            if (candidates.isEmpty()) {
                selectedIds = emptyList()
                curatorReasoning = ""
                curatedForText = q
            } else {
                val curatorResult = memoryCuratorService.curateSelection(q, candidates)
                selectedIds = curatorResult.selectedIds
                curatorReasoning = curatorResult.reasoning
                curatedForText = q
            }
        } catch (_: Exception) {
            searchResults = emptyList()
            selectedIds = emptyList()
            curatorReasoning = ""
            curatedForText = ""
        }
    }

    // ============================================================
    // Capture action
    // ============================================================

    val saveToMemory: () -> Unit = save@{
        if (isSavingMemory || isLoading) return@save
        if (!platform.memoryEnabled || chatViewModel == null) return@save

        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        isSavingMemory = true

        Toast.makeText(context, "🧠 جاري البحث في الصفحة...", Toast.LENGTH_SHORT).show()
        app.triggerCapture()

        val r = Runnable {
            if (isSavingMemory) {
                isSavingMemory = false
                Toast.makeText(context, "❌ انتهت المهلة", Toast.LENGTH_LONG).show()
            }
        }
        timeoutRunnable = r
        mainHandler.postDelayed(r, 5000L)
    }

    // ============================================================
    // Back handling
    // ============================================================

    val handleBack: () -> Unit = {
        if (canGoBack) runCatching { session.goBack() }.onFailure { onBack() } else onBack()
    }
    BackHandler { handleBack() }

    DisposableEffect(lifecycleOwner, session) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> runCatching { session.setActive(false) }
                    Lifecycle.Event.ON_START -> runCatching { session.setActive(true) }
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ============================================================
    // UI
    // ============================================================

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = platform.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        text = "الوكيل: ${webAgentName ?: "بدون وكيل"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onHome) {
                    Icon(
                        Icons.Filled.Home,
                        contentDescription = "العودة إلى الشاشة الرئيسية"
                    )
                }
            },
            actions = {
                // الوكيل
                IconButton(onClick = { showWebAgentDialog = true }) {
                    Icon(
                        Icons.Filled.SmartToy,
                        contentDescription = "اختيار الوكيل"
                    )
                }

                // الحفظ
                if (chatViewModel != null && platform.memoryEnabled) {
                    IconButton(
                        onClick = saveToMemory,
                        enabled = !isSavingMemory && !isLoading
                    ) {
                        Text(if (isSavingMemory) "⏳" else "🧠")
                    }
                }

                // الفروع
                if (chatViewModel != null && conversationRepository != null) {
                    IconButton(onClick = { showWebTreeSheet = true }) {
                        Icon(
                            Icons.Filled.AccountTree,
                            contentDescription = "شجرة المحادثات"
                        )
                    }
                }

                // بقية الأدوات في قائمة واحدة
                Box {
                    IconButton(onClick = { showMoreMenu = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "المزيد"
                        )
                    }

                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("عرض السجلات") },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.BugReport,
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                showMoreMenu = false
                                showDebugLogs = true
                            }
                        )

                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (memoryInjectionEnabled)
                                        "إيقاف حقن الذاكرة"
                                    else
                                        "تفعيل حقن الذاكرة"
                                )
                            },
                            onClick = {
                                memoryInjectionEnabled = !memoryInjectionEnabled
                                showMoreMenu = false
                            },
                            enabled = chatViewModel != null && platform.memoryEnabled
                        )

                        DropdownMenuItem(
                            text = { Text("الرجوع داخل الصفحة") },
                            leadingIcon = {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                showMoreMenu = false
                                if (canGoBack) {
                                    runCatching { session.goBack() }
                                } else {
                                    onBack()
                                }
                            }
                        )

                        DropdownMenuItem(
                            text = { Text("تحديث الصفحة") },
                            leadingIcon = {
                                Icon(Icons.Filled.Refresh, contentDescription = null)
                            },
                            onClick = {
                                showMoreMenu = false
                                if (session.isOpen) session.reload()
                            }
                        )

                        DropdownMenuItem(
                            text = { Text("فتح في المتصفح الخارجي") },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.OpenInBrowser,
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                showMoreMenu = false
                                openExternal(context, currentUrl)
                            }
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )

        if (enabledPlatforms.isNotEmpty()) {
            val selectedIndex =
                enabledPlatforms.indexOfFirst { it.id == platform.id }.let { if (it >= 0) it else 0 }

            ScrollableTabRow(selectedTabIndex = selectedIndex, edgePadding = 8.dp) {
                enabledPlatforms.forEach { tabPlatform ->
                    val isSelected = tabPlatform.id == platform.id
                    Tab(
                        selected = isSelected,
                        onClick = { if (!isSelected) activePlatformId = tabPlatform.id },
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (tabPlatform.iconEmoji.isNotBlank()) Text(tabPlatform.iconEmoji)
                                Text(tabPlatform.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    )
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            key(sessionKey) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        GeckoView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )

                            setSession(session)

                            if (!session.isOpen) {
                                session.open(runtime)
                                session.setActive(true)
                                isLoading = true
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

            if (isLoading) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .align(Alignment.TopCenter)
                )
            }

            loadError?.let { message ->
                LoadErrorView(
                    message = message,
                    onRetry = {
                        loadError = null
                        if (session.isOpen) session.reload()
                    },
                    onOpenBrowser = { openExternal(context, currentUrl) }
                )
            }
        }

        WebSendBar(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding(),
            text = displayedText,
            onTextChange = { if (enhancedQuery != null) enhancedQuery = it else query = it },
            isEnhanced = enhancedQuery != null,
            isEnhancing = isEnhancing,
            isSending = isSending,
            memoryEnabled = memoryInjectionEnabled && platform.memoryEnabled && chatViewModel != null,
            includeMemory = includeMemory,
            curatorReasoning = curatorReasoning,
            onToggleMemory = { includeMemory = !includeMemory },
            onEnhance = {
                val t = displayedText.trim()
                if (t.isBlank()) return@WebSendBar

                isEnhancing = true
                scope.launch {
                    try {
                        enhancedQuery = memoryCuratorService.enhanceQuery(
                            userQuery = t,
                            mediatorIdentityText = aiSettings.mediatorIdentityText
                        )
                    } finally {
                        isEnhancing = false
                    }
                }
            },
            onClearEnhanced = { enhancedQuery = null },
            onSend = {
                val vm = chatViewModel
                val question = displayedText.trim()
                if (question.isBlank()) return@WebSendBar
                if (isSending) return@WebSendBar

                if (isLoading) {
                    Toast.makeText(context, "⏳ انتظر حتى تكتمل الصفحة ثم أرسل", Toast.LENGTH_SHORT).show()
                    return@WebSendBar
                }

                isSending = true

                scope.launch {
                    try {
                        // ✅ تسجيل رسالة المستخدم داخل VM قبل الالتقاط/الرد
                        runCatching { vm?.onWebUserMessageSent(question) }

                        val memoryAllowed = memoryInjectionEnabled && includeMemory && platform.memoryEnabled && vm != null

                        val memoryXml: String =
                            if (!memoryAllowed) {
                                ""
                            } else {
                                if (curatedForText == question && selectedIds.isNotEmpty() && searchResults.isNotEmpty()) {
                                    memoryWindowBuilder.build(selectedIds = selectedIds, candidates = searchResults)
                                } else {
                                    val candidates = vm!!.searchSharedMemories(question)
                                    if (candidates.isEmpty()) "" else {
                                        val curator = memoryCuratorService.curateSelection(question, candidates)
                                        if (curator.selectedIds.isEmpty()) "" else {
                                            memoryWindowBuilder.build(
                                                selectedIds = curator.selectedIds,
                                                candidates = candidates
                                            )
                                        }
                                    }
                                }
                            }

                        // ✅ Workspace Summary injection (مشترك لكل الويب)
                        val workspaceSummary: String = conversationRepository
                            ?.let { repo ->
                                runCatching {
                                    val ws = repo.getOrCreateWorkspace(
                                        scopeKey = webWorkspaceScopeKeyGlobal(),
                                        title = "WEB • Global"
                                    )
                                    ws.summary.trim()
                                }.getOrNull()
                            }
                            .orEmpty()

                        val extraNotes = aiSettings.customSystemInstruction.trim()
                        val includeAccuracy = accuracyEnabled
                        val kind = webKind()

                        val contextBlock = buildString {
                            if (webAgentPrompt.isNotBlank()) {
                                appendLine("AGENT_PERSONA:")
                                appendLine(webAgentPrompt.trim())
                                appendLine()
                            }

                            if (workspaceSummary.isNotBlank()) {
                                appendLine("WORKSPACE_SUMMARY:")
                                appendLine(workspaceSummary)
                                appendLine()
                            }

                            if (includeAccuracy) {
                                appendLine("ACCURACY_POLICY:")
                                appendLine(SystemPrompt.DEFAULT_ACCURACY_PROMPT.trim())
                                appendLine()
                            }
                            if (extraNotes.isNotBlank()) {
                                appendLine("EXTRA_NOTES:")
                                appendLine(extraNotes)
                                appendLine()
                            }
                            if (memoryXml.isNotBlank()) {
                                appendLine("SHARED_MEMORIES_XML:")
                                appendLine(memoryXml)
                                appendLine()
                            }
                        }.trim()

                        val finalText = buildFinalTextForPlatform(kind = kind, contextBlock = contextBlock, question = question)

                        val sendWithEnterFinal = if (kind == WebKind.ARENA) false else platform.sendWithEnter
                        val sendSelectorFinal = if (kind == WebKind.ARENA) "" else platform.sendSelector

                        app.setContextPending(
                            text = finalText,
                            targetDomain = targetDomainStable(),
                            inputSelector = platform.inputSelector,
                            sendSelector = sendSelectorFinal,
                            sendWithEnter = sendWithEnterFinal
                        )

                        query = ""
                        enhancedQuery = null
                    } catch (e: Exception) {
                        isSending = false
                        Toast.makeText(context, "⚠️ فشل تجهيز الإرسال: ${e.message ?: "خطأ"}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    // =========================
    // ✅ Web Conversation Tree (BottomSheet)
    // =========================
    if (showWebTreeSheet && chatViewModel != null && conversationRepository != null) {
        WebGlobalTreeBottomSheet(
            repo = conversationRepository,
            vm = chatViewModel,
            platformName = platform.name,
            webAgentId = webAgentId,
            onDismiss = { showWebTreeSheet = false }
        )
    }

    // =========================
    // ✅ Web Agent picker dialog
    // =========================
    if (showWebAgentDialog) {
        AgentPickerDialog(
            title = "Web Agent",
            agents = agents,
            selectedId = webAgentId,
            onDismiss = { showWebAgentDialog = false },
            onSelect = { id ->
                webAgentId = id
                aiSettings.webAgentId = id?.trim().orEmpty()
                showWebAgentDialog = false
            },
            extraTopContent = {
                Column {
                    TextButton(
                        onClick = {
                            webAgentId = null
                            aiSettings.webAgentId = ""
                            showWebAgentDialog = false
                        }
                    ) { Text("بدون وكيل") }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = accuracyEnabled,
                            onCheckedChange = { v ->
                                accuracyEnabled = v
                                aiSettings.accuracyPromptEnabled = v
                            }
                        )
                        Text("تفعيل وثيقة الدقة")
                    }
                }
            }
        )
    }

    // =========================
    // ✅ نافذة السجلات
    // =========================
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

                    Spacer(Modifier.size(8.dp))

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
                    Spacer(Modifier.size(4.dp))
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
                        Spacer(Modifier.size(4.dp))
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

/* ============================ Web Tree BottomSheet ============================ */

@Composable
private fun WebGlobalTreeBottomSheet(
    repo: ConversationRepository,
    vm: ChatViewModel,
    platformName: String,
    webAgentId: String?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Ensure workspace exists
    LaunchedEffect(repo) {
        runCatching { repo.getOrCreateWorkspace(scopeKey = "web:global", title = "WEB • Global") }
    }

    val wsFlow = remember(repo) {
        repo.observeAllWorkspaces().map { all ->
            all.firstOrNull { it.scopeKey == "web:global" }
        }
    }
    val ws: Workspace? by wsFlow.collectAsStateWithLifecycle(initialValue = null)

    val currentId by vm.currentWebConversationId.collectAsStateWithLifecycle(
        initialValue = null
    )

    fun openConversation(id: Long) {
        vm.openWebConversation(id)
    }

    val convsFlow = remember(repo, ws?.id) {
        ws?.id?.let { repo.observeConversationsByWorkspace(it) }
            ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }
    val convsRaw: List<Conversation> by convsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val convs: List<Conversation> = remember(convsRaw) { convsRaw.sortedByDescending { it.updatedAt } }
    val byId: Map<Long, Conversation> = remember(convs) { convs.associateBy { it.id } }

    fun pathToRoot(id: Long): List<Conversation> {
        val out = ArrayList<Conversation>(8)
        var cur: Conversation? = byId[id]
        val seen = HashSet<Long>()
        while (cur != null && seen.add(cur.id)) {
            out.add(cur)
            val p = cur.parentConversationId ?: break
            cur = byId[p]
        }
        return out.asReversed()
    }

    val currentConv: Conversation? = remember(currentId, byId) { currentId?.let { byId[it] } }
    val breadcrumb: List<Conversation> = remember(currentId, byId) { currentId?.let { pathToRoot(it) }.orEmpty() }
    val parentConv: Conversation? = remember(currentConv, byId) { currentConv?.parentConversationId?.let { byId[it] } }

    val children: List<Conversation> = remember(currentId, convs) {
        if (currentId == null) emptyList()
        else convs.filter { it.parentConversationId == currentId }.sortedByDescending { it.updatedAt }
    }
    val siblings: List<Conversation> = remember(currentConv, convs) {
        val pid = currentConv?.parentConversationId ?: return@remember emptyList()
        convs.filter { it.parentConversationId == pid && it.id != currentConv.id }.sortedByDescending { it.updatedAt }
    }
    val roots: List<Conversation> = remember(convs) {
        convs.filter { it.parentConversationId == null }.sortedByDescending { it.updatedAt }
    }

    val currentMessagesFlow = remember(repo, currentId) {
        currentId?.let { repo.getMessages(it) }
            ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }

    val currentMessages by currentMessagesFlow.collectAsStateWithLifecycle(
        initialValue = emptyList()
    )

    var showResetConfirm by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "🌳 شجرة الويب (مشتركة)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Workspace: web:global",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (ws == null) {
                Text("⚠️ تعذر تحميل Workspace.")
            } else {
                if (ws!!.summary.isNotBlank()) {
                    Text(
                        text = "WORKSPACE_SUMMARY (مختصر):\n${ws!!.summary.take(220)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(
                        text = "WORKSPACE_SUMMARY فارغ حاليًا.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "المحادثة الحالية:",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = currentConv?.title?.takeIf { it.isNotBlank() } ?: "لا توجد محادثة محددة بعد",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "ID: ${currentId?.toString() ?: "—"}  •  Platform: $platformName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val workspaceId = ws?.id
                                if (workspaceId == null) {
                                    Toast.makeText(context, "Workspace غير متاح", Toast.LENGTH_SHORT).show()
                                    return@OutlinedButton
                                }
                                scope.launch {
                                    runCatching {
                                        val title = "Web: $platformName".take(60)
                                        val newId = repo.insertConversation(
                                            Conversation(
                                                title = title,
                                                memoryAccessEnabled = true,
                                                agentId = webAgentId,
                                                workspaceId = workspaceId,
                                                parentConversationId = null
                                            )
                                        )
                                        repo.updateTitleAndTimestamp(
                                            id = newId,
                                            title = title,
                                            updatedAt = System.currentTimeMillis()
                                        )
                                        openConversation(newId)
                                    }.onFailure { e ->
                                        Toast.makeText(context, "⚠️ فشل إنشاء محادثة: ${e.message ?: "خطأ"}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            enabled = ws != null
                        ) { Text("➕ محادثة جديدة") }

                        OutlinedButton(
                            onClick = {
                                val workspaceId = ws?.id
                                val pid = currentId
                                if (workspaceId == null || pid == null) {
                                    Toast.makeText(context, "اختر محادثة أولًا", Toast.LENGTH_SHORT).show()
                                    return@OutlinedButton
                                }
                                scope.launch {
                                    runCatching {
                                        val base = (currentConv?.title ?: "Web: $platformName").trim().take(45)
                                        val branchTitle = ("فرع: $base").take(60)
                                        val newId = repo.insertConversation(
                                            Conversation(
                                                title = branchTitle,
                                                memoryAccessEnabled = true,
                                                agentId = webAgentId,
                                                workspaceId = workspaceId,
                                                parentConversationId = pid
                                            )
                                        )
                                        repo.updateTitleAndTimestamp(
                                            id = newId,
                                            title = branchTitle,
                                            updatedAt = System.currentTimeMillis()
                                        )
                                        openConversation(newId)
                                    }.onFailure { e ->
                                        Toast.makeText(context, "⚠️ فشل إنشاء فرع: ${e.message ?: "خطأ"}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            enabled = ws != null && currentId != null
                        ) { Text("🪵 تفريع") }

                        OutlinedButton(
                            onClick = { vm.clearWebConversationSelection() }
                        ) {
                            Text("⛔ إلغاء التحديد")
                        }
                    }
                }
            }

            if (currentId != null) {
                Text(
                    text = "رسائل المحادثة — اختر نقطة التفريع",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )

                if (currentMessages.isEmpty()) {
                    Text(
                        text = "لا توجد رسائل محفوظة في هذه المحادثة.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    currentMessages.forEach { message ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = if (message.role == "user") "أنت" else "المساعد",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )

                                Text(
                                    text = message.content.take(500),
                                    style = MaterialTheme.typography.bodyMedium
                                )

                                OutlinedButton(
                                    onClick = {
                                        vm.branchFromWebMessage(message.id)
                                        onDismiss()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("إنشاء فرع من هذه الرسالة")
                                }
                            }
                        }
                    }
                }
            }

            if (currentId != null) {
                Text("المسار (Breadcrumb):", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                if (breadcrumb.isEmpty()) {
                    Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        breadcrumb.forEach { c ->
                            OutlinedButton(onClick = { openConversation(c.id) }) {
                                Text(c.title.takeIf { it.isNotBlank() }?.take(18) ?: "ID:${c.id}")
                            }
                        }
                    }
                }
            }

            parentConv?.let { p ->
                Text("الأب:", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                ConversationRowLite(
                    title = p.title.takeIf { it.isNotBlank() } ?: "ID:${p.id}",
                    subtitle = "ID: ${p.id}",
                    onClick = { openConversation(p.id) }
                )
            }

            if (siblings.isNotEmpty()) {
                Text("الإخوة:", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                siblings.take(12).forEach { c ->
                    ConversationRowLite(
                        title = c.title.takeIf { it.isNotBlank() } ?: "ID:${c.id}",
                        subtitle = "ID: ${c.id}",
                        onClick = { openConversation(c.id) }
                    )
                }
                if (siblings.size > 12) Text("… +${siblings.size - 12}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (children.isNotEmpty()) {
                Text("الأبناء:", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                children.take(12).forEach { c ->
                    ConversationRowLite(
                        title = c.title.takeIf { it.isNotBlank() } ?: "ID:${c.id}",
                        subtitle = "ID: ${c.id}",
                        onClick = { openConversation(c.id) }
                    )
                }
                if (children.size > 12) Text("… +${children.size - 12}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (roots.isNotEmpty()) {
                Text("الجذور (Top-level):", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                roots.take(10).forEach { c ->
                    ConversationRowLite(
                        title = c.title.takeIf { it.isNotBlank() } ?: "ID:${c.id}",
                        subtitle = "ID: ${c.id}",
                        onClick = { openConversation(c.id) }
                    )
                }
                if (roots.size > 10) Text("… +${roots.size - 10}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Danger zone",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Reset workspace بالكامل (يحذف كل محادثات ورسائل web:global + يصفر summary).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = { showResetConfirm = true },
                        enabled = ws != null
                    ) { Text("🧨 Reset web:global") }
                }
            }

            Spacer(Modifier.height(10.dp))
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("تأكيد Reset") },
            text = { Text("سيتم حذف كل المحادثات والرسائل ضمن web:global وتصفير Workspace Summary. هل أنت متأكد؟") },
            confirmButton = {
                Button(
                    onClick = {
                        val wsId = ws?.id
                        if (wsId == null) {
                            Toast.makeText(context, "Workspace غير متاح", Toast.LENGTH_SHORT).show()
                            showResetConfirm = false
                            return@Button
                        }
                        scope.launch {
                            runCatching {
                                repo.resetWorkspaceFully(wsId)
                                vm.clearWebConversationSelection()
                                Toast.makeText(context, "✅ تم Reset", Toast.LENGTH_SHORT).show()
                                showResetConfirm = false
                                onDismiss()
                            }.onFailure { e ->
                                Toast.makeText(context, "⚠️ فشل Reset: ${e.message ?: "خطأ"}", Toast.LENGTH_LONG).show()
                                showResetConfirm = false
                            }
                        }
                    }
                ) { Text("نعم") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showResetConfirm = false }) { Text("إلغاء") }
            }
        )
    }
}

@Composable
private fun ConversationRowLite(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/* ============================ Send bar (كما كان) ============================ */

@Composable
private fun WebSendBar(
    modifier: Modifier = Modifier,
    text: String,
    onTextChange: (String) -> Unit,
    isEnhanced: Boolean,
    isEnhancing: Boolean,
    isSending: Boolean,
    memoryEnabled: Boolean,
    includeMemory: Boolean,
    curatorReasoning: String,
    onToggleMemory: () -> Unit,
    onEnhance: () -> Unit,
    onClearEnhanced: () -> Unit,
    onSend: () -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (includeMemory && curatorReasoning.isNotBlank()) {
                Text(
                    text = "🧠 اختيار الوسيط: $curatorReasoning",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (memoryEnabled) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable(enabled = !isSending && !isEnhancing, onClick = onToggleMemory)
                            .padding(horizontal = 6.dp, vertical = 6.dp)
                    ) {
                        Checkbox(
                            checked = includeMemory,
                            onCheckedChange = { onToggleMemory() },
                            enabled = !isSending && !isEnhancing
                        )
                        Text("ذاكرة", style = MaterialTheme.typography.labelSmall)
                    }
                }

                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    minLines = 1,
                    maxLines = 5,
                    enabled = !isSending,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    label = { Text(if (isEnhanced) "مُحسّن ✨" else "اكتب رسالتك") }
                )

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        onClick = if (isEnhanced) onClearEnhanced else onEnhance,
                        enabled = text.isNotBlank() && !isSending && !isEnhancing
                    ) {
                        if (!isEnhanced && isEnhancing) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text(if (isEnhanced) "↩️" else "⚡")
                        }
                    }

                    IconButton(
                        onClick = onSend,
                        enabled = text.isNotBlank() && !isSending && !isEnhancing
                    ) {
                        Text(if (isSending) "⏳" else "📤")
                    }
                }
            }
        }
    }
}

/* ============================ Context formatting ============================ */

private enum class WebKind { CHATGPT, VENICE, ARENA, OTHER }

private const val AICHAT_CTX_BEGIN = "<<<AICHAT_HIDDEN_CONTEXT_BEGIN>>>"
private const val AICHAT_CTX_END = "<<<AICHAT_HIDDEN_CONTEXT_END>>>"

private fun classifyHost(host: String): WebKind {
    val h = host.lowercase(Locale.ROOT)
    return when {
        h.contains("chatgpt.com") || h.contains("openai.com") -> WebKind.CHATGPT
        h.contains("venice.ai") -> WebKind.VENICE
        h.contains("lmsys") || h.contains("arena") -> WebKind.ARENA
        else -> WebKind.OTHER
    }
}

private fun buildFinalTextForPlatform(kind: WebKind, contextBlock: String, question: String): String {
    val ctx = contextBlock.trim()
    if (ctx.isBlank()) return question

    return when (kind) {
        WebKind.ARENA -> {
            "### Context (visible)\n$ctx\n### End Context\n\n$question"
        }
        WebKind.CHATGPT, WebKind.VENICE -> {
            "$AICHAT_CTX_BEGIN\n$ctx\n$AICHAT_CTX_END\n\n$question"
        }
        WebKind.OTHER -> {
            "$ctx\n\n$question"
        }
    }
}

/* ============================ Helpers / dialogs ============================ */

private fun openExternal(context: Context, url: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "لا يوجد تطبيق لفتح هذا الرابط", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun LoadErrorView(
    message: String,
    onRetry: () -> Unit,
    onOpenBrowser: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("⚠️", style = MaterialTheme.typography.displaySmall)
            Text(text = message, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Button(onClick = onRetry) { Text("إعادة المحاولة") }
            OutlinedButton(onClick = onOpenBrowser) { Text("فتح في المتصفح") }
        }
    }
}

@Composable
private fun GeckoUnavailableDialog(
    platformTitle: String,
    platformUrl: String,
    onOpenBrowser: () -> Unit,
    onBack: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onBack,
        title = { Text("⚠️ GeckoView غير متاح") },
        text = {
            Text(
                "تعذر تهيئة محرك GeckoView.\n\n" +
                    "يمكنك فتح $platformTitle في المتصفح الخارجي.\n\n" +
                    platformUrl
            )
        },
        confirmButton = { Button(onClick = onOpenBrowser) { Text("📱 فتح في المتصفح") } },
        dismissButton = { OutlinedButton(onClick = onBack) { Text("رجوع") } }
    )
}

/**
 * يحاول استخراج ConversationRepository من ChatViewModel بدون تغيير الـ API لهذه الشاشة.
 * يعتمد على وجود field داخل ChatViewModel نوعه ConversationRepository (حتى لو private).
 */
private fun resolveConversationRepositoryFromViewModel(vm: ChatViewModel?): ConversationRepository? {
    if (vm == null) return null
    return runCatching {
        val wanted = ConversationRepository::class.java
        var c: Class<*>? = vm.javaClass
        while (c != null) {
            val f = c.declaredFields.firstOrNull { wanted.isAssignableFrom(it.type) }
            if (f != null) {
                f.isAccessible = true
                return@runCatching (f.get(vm) as? ConversationRepository)
            }
            c = c.superclass
        }
        null
    }.getOrNull()
}
