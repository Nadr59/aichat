@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.aichat.ui.screens

import android.content.ActivityNotFoundException
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.example.aichat.data.model.MemoryItem
import com.example.aichat.data.model.WebPlatform
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
import kotlinx.coroutines.launch
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError
import java.util.Locale

/**
 * UI القديم (Tabs + GeckoView + SendBar) + إضافة:
 * ✅ Web Agent (aiSettings.webAgentId) يتم حقنه ضمن السياق عند الإرسال.
 * ✅ زر في الـ TopBar يفتح قائمة الوكلاء للتبديل بينهم.
 * ✅ Workspace Summary (ذاكرة مشتركة لكل منصات الويب) يتم حقنه ضمن السياق عند الإرسال (scopeKey = web:global).
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

    // ✅ هذا هو الـ Flow الصحيح في WebPlatformRepository عندك
    val enabledPlatforms by webPlatformRepository.enabledPlatforms.collectAsStateWithLifecycle(initialValue = emptyList())

    // ✅ قائمة الوكلاء (Agents) للـ Dialog
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

    var webAgentId by rememberSaveable {
        mutableStateOf(aiSettings.webAgentIdOrNull)
    }

    var accuracyEnabled by rememberSaveable {
        mutableStateOf(aiSettings.accuracyPromptEnabled)
    }

    // مزامنة مبدئية (لو تغيّر من شاشة ثانية)
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
    // Session state (مثل السابق)
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

    // ✅ ذاكرة مشتركة لكل منصات الويب:
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
    // Memory search + curator selection (للـ UI فقط)
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
                    if (currentTitle != platform.name && currentTitle.isNotBlank()) {
                        Text(
                            text = currentTitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = "Web Agent: ${webAgentName ?: "بدون وكيل"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            navigationIcon = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IconButton(onClick = handleBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                    IconButton(onClick = onHome) {
                        Icon(Icons.Filled.Home, contentDescription = "الرئيسية")
                    }
                }
            },
            actions = {
                // ✅ زر اختيار الوكيل
                IconButton(onClick = { showWebAgentDialog = true }) {
                    Icon(Icons.Filled.SmartToy, contentDescription = "Web Agent")
                }

                if (chatViewModel != null && platform.memoryEnabled) {
                    IconButton(onClick = saveToMemory, enabled = !isSavingMemory && !isLoading) {
                        Text(if (isSavingMemory) "⏳" else "🧠")
                    }
                    IconButton(onClick = { memoryInjectionEnabled = !memoryInjectionEnabled }) {
                        Text(if (memoryInjectionEnabled) "📄✅" else "📄❌")
                    }
                }

                IconButton(onClick = { if (session.isOpen) session.reload() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "تحديث")
                }
                IconButton(onClick = { openExternal(context, currentUrl) }) {
                    Icon(Icons.Filled.OpenInBrowser, contentDescription = "فتح خارجي")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
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
                        // ✅ مهم للذاكرة العالمية للويب:
                        // نخزن آخر سؤال للمستخدم حتى يتم تحديث Workspace Summary عند التقاط رد المساعد.
                        vm?.onWebUserMessageSent(question)

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
                                        title = "Web (Global)"
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

                Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
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
