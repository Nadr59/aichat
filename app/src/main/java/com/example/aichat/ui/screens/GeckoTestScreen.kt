
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Memory
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.example.aichat.data.model.WebPlatform
import com.example.aichat.repository.AgentPromptBuilder
import com.example.aichat.repository.AgentRepository
import com.example.aichat.repository.MemoryContextBuilder
import com.example.aichat.repository.MemoryCuratorService
import com.example.aichat.repository.MemoryWindowBuilder
import com.example.aichat.repository.WebPlatformRepository
import com.example.aichat.repository.WebSendCoordinator
import com.example.aichat.ui.components.AgentPickerDialog
import com.example.aichat.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError

/**
 * GeckoTestScreen (Tabs + GeckoView + SendBar) — شاشة متصفح مزودات الويب.
 *
 * الهدف الاستراتيجي: شاشة محادثة واحدة (ChatScreen) يعمل فيها GeckoView
 * كمحرك خلفي مخفي. هذه الشاشة تبقى أداة اختبار/تشخيص — وستبقى ظاهرة
 * لمنصة Arena تحديدًا لأنها لا تدعم الإرسال التلقائي (لصق يدوي).
 *
 * ✅ خطوة الويب — Workspace لكل محادثة (نموذج موحَّد مع API):
 * - أُلغي "web:global" كوجهة كتابة؛ المحادثة النشطة (أو محادثة جديدة
 *   بـWorkspace فريد) هي هدف أي إرسال، والملخص المُحقَن يُقرأ من
 *   vm.workspaceSummary — نفس المصدر الذي يُحدَّث، فيتطابقان دائمًا.
 * - أُزيل الاعتماد على Reflection لاستخراج ConversationRepository:
 *   الحقن يقرأ StateFlow مباشرة، والشجرة تعمل عبر دوال الـVM العامة.
 *
 * ✅ تصميم الواجهة: رأس بنفسجي فاتح قابل للطي (كبسولة أدوات + أقراص منصات)،
 * ومربع إدخال قابل للطي، لإعطاء أكبر مساحة لصفحة الويب.
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

    // ✅ ملخص Workspace المحادثة النشطة — مصدر الحقن الوحيد (يتطابق مع
    // المصدر الذي يحدّثه onWebAiResponse في الـVM).
    val workspaceSummaryFlow = remember(chatViewModel) {
        chatViewModel?.workspaceSummary ?: MutableStateFlow("")
    }
    val currentWorkspaceSummary by workspaceSummaryFlow.collectAsStateWithLifecycle()

    // عرض رسائل النجاح/الخطأ القادمة من الـVM داخل شاشة الويب.
    val vmErrorFlow = remember(chatViewModel) {
        chatViewModel?.error ?: MutableStateFlow<String?>(null)
    }
    val vmError by vmErrorFlow.collectAsStateWithLifecycle()
    val vmSuccessFlow = remember(chatViewModel) {
        chatViewModel?.successMessage ?: MutableStateFlow<String?>(null)
    }
    val vmSuccess by vmSuccessFlow.collectAsStateWithLifecycle()

    LaunchedEffect(vmError) {
        vmError?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            chatViewModel?.clearError()
        }
    }
    LaunchedEffect(vmSuccess) {
        vmSuccess?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            chatViewModel?.clearSuccessMessage()
        }
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

    // ✅ طلب انتقال معلّق إلى منصة أخرى (بعد تجهيز ملخص الفرع، بانتظار جاهزية المنصة الهدف)
    var pendingWebHandoff by remember {
        mutableStateOf<PendingWebHandoff?>(null)
    }

    // ✅ قائمة المزيد + نافذة السجلات
    var showMoreMenu by remember { mutableStateOf(false) }
    var showDebugLogs by rememberSaveable { mutableStateOf(false) }

    // علبة المواد
    var showMaterials by remember { mutableStateOf(false) }

    // ✅ طي الرأس العلوي (كبسولة الأدوات + أقراص المنصات) لتكبير صفحة الويب
    var showHeader by rememberSaveable { mutableStateOf(true) }

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
    var showSendBar by rememberSaveable(sessionKey) {
        mutableStateOf(true)
    }

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

    fun webKind(): WebSendCoordinator.WebKind = WebSendCoordinator.classifyHost(targetDomainStable())

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
            app.onAiResponseCaptured = { source, text ->
                val responsePlatformId = source.ifBlank { p.id }
                vm.onWebAiResponse(
                    platformId = responsePlatformId,
                    platformName = p.name,
                    text = text
                )
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
    // Web handoff injection (الانتقال إلى منصة أخرى بسياق مُلخَّص)
    // ============================================================

    LaunchedEffect(
        pendingWebHandoff?.id,
        activePlatformId,
        isLoading
    ) {
        val handoff = pendingWebHandoff ?: return@LaunchedEffect

        if (activePlatformId != handoff.targetPlatformId) {
            return@LaunchedEffect
        }

        if (isLoading) {
            return@LaunchedEffect
        }

        val vm = chatViewModel ?: run {
            pendingWebHandoff = null
            return@LaunchedEffect
        }

        // استهلاك الطلب أولًا لمنع تكرار الإرسال عند إعادة التركيب.
        pendingWebHandoff = null
        isSending = true

        try {
            val continuationQuestion =
                "تابع النقاش اعتمادًا على سياق المحادثة السابقة. " +
                "ابدأ من آخر نقطة في الملخص، وأجب عن المسألة غير المحسومة دون إعادة النقاش من البداية."

            val tracked = vm.onWebUserMessageSentAndTrack(
                userText = continuationQuestion,
                platformId = platform.id,
                platformName = platform.name
            )

            if (!tracked) {
                isSending = false
                Toast.makeText(
                    context,
                    "تعذر تسجيل طلب المتابعة في الفرع.",
                    Toast.LENGTH_LONG
                ).show()
                return@LaunchedEffect
            }

            val contextBlock = WebSendCoordinator.buildContextBlock(
                agentPrompt = webAgentPrompt,
                handoffSummary = handoff.summary,
                accuracyPrompt = if (accuracyEnabled) SystemPrompt.DEFAULT_ACCURACY_PROMPT else null,
                extraNotes = aiSettings.customSystemInstruction
            )

            val finalText = WebSendCoordinator.buildFinalTextForPlatform(
                kind = webKind(),
                contextBlock = contextBlock,
                question = continuationQuestion
            )

            val kind = webKind()

            app.setContextPending(
                text = finalText,
                targetDomain = targetDomainStable(),
                inputSelector = platform.inputSelector,
                sendSelector = if (kind == WebSendCoordinator.WebKind.ARENA) "" else platform.sendSelector,
                sendWithEnter = if (kind == WebSendCoordinator.WebKind.ARENA) false else platform.sendWithEnter
            )

            Toast.makeText(
                context,
                "تم تجهيز السياق في ${platform.name}.",
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            isSending = false
            Toast.makeText(
                context,
                "فشل تجهيز متابعة السياق: ${e.message ?: "خطأ"}",
                Toast.LENGTH_LONG
            ).show()
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
    // علبة المواد: معالجة الخلاصات في الخلفية (عبر مزود API)
    // ============================================================

    val digestSender: (suspend (String, String) -> String?)? =
        remember<(suspend (String, String) -> String?)?>(chatViewModel) {
            val vm = chatViewModel
            if (vm == null) {
                null
            } else {
                val s: suspend (String, String) -> String? = { persona, input ->
                    vm.repository.sendMessage(
                        history = emptyList(),
                        userMessage = input,
                        imageBase64 = null,
                        memoryContext = "",
                        hasMemorySource = false,
                        agentPrompt = persona
                    )
                }
                s
            }
        }
    val digestHasWork = app.materialStore.items.any {
        it.status == com.example.aichat.repository.MaterialStatus.PENDING ||
            it.status == com.example.aichat.repository.MaterialStatus.PROCESSING
    }
    LaunchedEffect(digestSender, digestHasWork) {
        val sender = digestSender
        if (digestHasWork && sender != null) {
            com.example.aichat.repository.MaterialDigestRunner.runPending(
                store = app.materialStore,
                sender = sender,
                log = { app.logDebug(it) }
            )
        }
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

    val colors = MaterialTheme.colorScheme
    val webShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        if (showHeader) {
            // ───────── الرأس المفتوح ─────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(colors.primaryContainer, colors.background)
                        )
                    )
                    .statusBarsPadding()
                    .padding(top = 8.dp, bottom = 6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // الرئيسية
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .shadow(3.dp, RoundedCornerShape(16.dp))
                            .clip(RoundedCornerShape(16.dp))
                            .background(colors.surface)
                            .clickable(onClick = onHome),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Home,
                            contentDescription = "العودة إلى الشاشة الرئيسية",
                            tint = colors.primary
                        )
                    }

                    Spacer(Modifier.width(10.dp))

                    // العنوان + الوكيل
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = platform.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "الوكيل: ${webAgentName ?: "بدون وكيل"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    // كبسولة الأدوات
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = colors.surface,
                        shadowElevation = 3.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // الوكيل
                            IconButton(
                                onClick = { showWebAgentDialog = true },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Filled.SmartToy,
                                    contentDescription = "اختيار الوكيل",
                                    tint = colors.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // حفظ آخر رد كذاكرة
                            if (chatViewModel != null && platform.memoryEnabled) {
                                IconButton(
                                    onClick = saveToMemory,
                                    enabled = !isSavingMemory && !isLoading,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    if (isSavingMemory) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Icon(
                                            Icons.Filled.Memory,
                                            contentDescription = "حفظ آخر رد كذاكرة",
                                            tint = if (isLoading) colors.outline else colors.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }

                            // الفروع
                            if (chatViewModel != null) {
                                IconButton(
                                    onClick = { showWebTreeSheet = true },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.AccountTree,
                                        contentDescription = "شجرة المحادثات",
                                        tint = colors.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            // بقية الأدوات في قائمة واحدة
                            Box {
                                IconButton(
                                    onClick = { showMoreMenu = true },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.MoreVert,
                                        contentDescription = "المزيد",
                                        tint = colors.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                DropdownMenu(
                                    expanded = showMoreMenu,
                                    onDismissRequest = { showMoreMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("علبة المواد") },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Filled.Inventory2,
                                                contentDescription = null
                                            )
                                        },
                                        onClick = {
                                            showMoreMenu = false
                                            showMaterials = true
                                        }
                                    )

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

                            // طي الرأس
                            IconButton(
                                onClick = { showHeader = false },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Filled.KeyboardArrowUp,
                                    contentDescription = "طي الشريط العلوي",
                                    tint = colors.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }

                // أقراص المنصات
                if (enabledPlatforms.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        enabledPlatforms.forEach { tabPlatform ->
                            val isSelected = tabPlatform.id == platform.id
                            PlatformPill(
                                id = tabPlatform.id,
                                name = tabPlatform.name,
                                selected = isSelected,
                                onClick = { if (!isSelected) activePlatformId = tabPlatform.id }
                            )
                        }
                    }
                }
            }
        } else {
            // ───────── الرأس المطوي ─────────
            CollapsedTopStrip(
                title = platform.name,
                onExpand = { showHeader = true }
            )
        }

        // ───────── صفحة الويب ─────────
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(webShape)
                .background(colors.surface)
        ) {
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

            // زر عائم لإظهار مربع الإدخال بعد طيه
            if (!showSendBar) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(14.dp)
                        .size(44.dp)
                        .shadow(4.dp, CircleShape)
                        .clip(CircleShape)
                        .background(colors.primary)
                        .clickable { showSendBar = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Keyboard,
                        contentDescription = "إظهار مربع الإدخال",
                        tint = colors.onPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        if (showSendBar) {
            WebSendBar(
                modifier = Modifier.fillMaxWidth(),
                text = displayedText,
                onTextChange = { if (enhancedQuery != null) enhancedQuery = it else query = it },
                isEnhanced = enhancedQuery != null,
                isEnhancing = isEnhancing,
                isSending = isSending,
                memoryEnabled = memoryInjectionEnabled && platform.memoryEnabled && chatViewModel != null,
                includeMemory = includeMemory,
                curatorReasoning = curatorReasoning,
                onToggleMemory = { includeMemory = !includeMemory },
                onCollapse = { showSendBar = false },
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
                            // ✅ تسجيل رسالة المستخدم داخل VM مع تتبّع الطلب المعلّق قبل الإرسال الفعلي
                            val tracked = vm?.onWebUserMessageSentAndTrack(
                                userText = question,
                                platformId = platform.id,
                                platformName = platform.name
                            ) ?: false

                            if (!tracked) {
                                isSending = false
                                Toast.makeText(
                                    context,
                                    "تعذر تسجيل السؤال في محادثة الويب.",
                                    Toast.LENGTH_LONG
                                ).show()
                                return@launch
                            }

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

                            // ✅ ملخص Workspace المحادثة النشطة — يُقرأ من
                            // vm.workspaceSummary (نفس المصدر المُحدَّث)،
                            // بلا Reflection وبلا web:global.
                            val workspaceSummary: String = currentWorkspaceSummary.trim()

                            val extraNotes = aiSettings.customSystemInstruction.trim()
                            val includeAccuracy = accuracyEnabled
                            val kind = webKind()

                            val contextBlock = WebSendCoordinator.buildContextBlock(
                                agentPrompt = webAgentPrompt,
                                workspaceSummary = workspaceSummary,
                                accuracyPrompt = if (includeAccuracy) SystemPrompt.DEFAULT_ACCURACY_PROMPT else null,
                                extraNotes = extraNotes,
                                memoryXml = memoryXml
                            )

                            val finalText = WebSendCoordinator.buildFinalTextForPlatform(
                                kind = kind,
                                contextBlock = contextBlock,
                                question = question
                            )

                            val sendWithEnterFinal = if (kind == WebSendCoordinator.WebKind.ARENA) false else platform.sendWithEnter
                            val sendSelectorFinal = if (kind == WebSendCoordinator.WebKind.ARENA) "" else platform.sendSelector

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
    }

    // =========================
    // ✅ Web Conversation Tree (BottomSheet)
    // =========================
    if (showWebTreeSheet && chatViewModel != null) {
        WebGlobalTreeBottomSheet(
            vm = chatViewModel,
            platformName = platform.name,
            enabledPlatforms = enabledPlatforms,
            onHandoffRequested = { messageId, targetPlatform ->
                scope.launch {
                    try {
                        if (targetPlatform.id == platform.id) {
                            Toast.makeText(
                                context,
                                "اختر منصة أخرى غير المنصة الحالية.",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@launch
                        }

                        Toast.makeText(
                            context,
                            "جاري تجهيز سياق الانتقال...",
                            Toast.LENGTH_SHORT
                        ).show()

                        val summary =
                            chatViewModel.createWebHandoffFromMessage(messageId)

                        if (summary.isBlank()) {
                            throw IllegalStateException("ملخص السياق فارغ.")
                        }

                        pendingWebHandoff = PendingWebHandoff(
                            id = System.currentTimeMillis(),
                            targetPlatformId = targetPlatform.id,
                            summary = summary
                        )

                        activePlatformId = targetPlatform.id
                        showWebTreeSheet = false
                    } catch (e: Exception) {
                        Toast.makeText(
                            context,
                            "تعذر تجهيز الانتقال: ${e.message ?: "خطأ غير معروف"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            },
            currentPlatformId = platform.id,
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
    // علبة المواد
    // =========================
    if (showMaterials) {
        MaterialsDialog(
            store = app.materialStore,
            boxStore = app.boxStore,
            onDismiss = { showMaterials = false },
            onInsertToInput = { materialText ->
                val old = displayedText
                val merged =
                    if (old.isBlank()) materialText else old.trimEnd() + "\n\n" + materialText
                if (enhancedQuery != null) enhancedQuery = merged else query = merged
                showSendBar = true
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

/**
 * طلب انتقال معلّق إلى منصة أخرى بعد إنشاء فرع المتابعة وتجهيز ملخص السياق.
 */
private data class PendingWebHandoff(
    val id: Long,
    val targetPlatformId: String,
    val summary: String
)

/* ============================ Header pieces ============================ */

private val PlatformDotPalette = listOf(
    Color(0xFFB8F0DC),
    Color(0xFFFFC38A),
    Color(0xFFD9D0FF),
    Color(0xFFBFE3FF),
    Color(0xFFFFD3E0)
)

/** شريط رفيع يظهر عند طي الرأس: اسم المنصة وسهم للفتح. */
@Composable
private fun CollapsedTopStrip(
    title: String,
    onExpand: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.primaryContainer)
            .statusBarsPadding()
            .clickable(onClick = onExpand)
            .padding(horizontal = 14.dp)
            .height(30.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = colors.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = "إظهار الشريط العلوي",
            tint = colors.primary,
            modifier = Modifier.size(22.dp)
        )
    }
}

/** قرص منصة: دائرة بحرفها الأول + الاسم. المختار بنفسجي غامق. */
@Composable
private fun PlatformPill(
    id: String,
    name: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    val bg = if (selected) colors.primary else colors.surface
    val fg = if (selected) colors.onPrimary else colors.onSurface
    val dot = PlatformDotPalette[(id.hashCode() and 0x7fffffff) % PlatformDotPalette.size]
    val letter = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"

    Row(
        modifier = Modifier
            .shadow(if (selected) 0.dp else 1.dp, shape)
            .clip(shape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(dot),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = letter,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1B1230)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/* ============================ Web Tree BottomSheet ============================ */

/**
 * شجرة محادثات الويب — تعمل كليًا عبر دوال ChatViewModel العامة بعد
 * إلغاء web:global:
 * - القائمة من vm.webConversations (محادثات Workspaces ذات بادئة "web:"،
 *   وتشمل أرشيف web:global القديم للقراءة).
 * - الملخص المعروض هو ملخص Workspace المحادثة النشطة (vm.workspaceSummary).
 * - الإنشاء/التفريع/التصفير تمر عبر الـVM حصريًا (لا كتابة مباشرة في repo).
 *
 * ملاحظة سقالة: "المسار/Breadcrumb" يتوقف عند أول سلف غير موجود في
 * قائمة الويب (مثلاً أصل API في انتقال عابر للمنصات) — مقبول هنا.
 */
@Composable
private fun WebGlobalTreeBottomSheet(
    vm: ChatViewModel,
    platformName: String,
    enabledPlatforms: List<WebPlatform>,
    onHandoffRequested: (Long, WebPlatform) -> Unit,
    currentPlatformId: String,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val convsRaw: List<Conversation> by vm.webConversations.collectAsStateWithLifecycle()
    val convs: List<Conversation> = remember(convsRaw) { convsRaw.sortedByDescending { it.updatedAt } }
    val byId: Map<Long, Conversation> = remember(convs) { convs.associateBy { it.id } }

    val currentId by vm.currentWebConversationId.collectAsStateWithLifecycle()
    val currentWorkspaceId by vm.currentWorkspaceId.collectAsStateWithLifecycle()
    val wsSummary by vm.workspaceSummary.collectAsStateWithLifecycle()
    val currentMessages by vm.messages.collectAsStateWithLifecycle()

    fun openConversation(id: Long) {
        vm.openWebConversation(id)
    }

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

    var showResetConfirm by rememberSaveable { mutableStateOf(false) }

    // ✅ معرّف الرسالة التي طُلبت المتابعة منها إلى منصة أخرى (لعرض نافذة اختيار المنصة)
    var handoffMessageId by remember {
        mutableStateOf<Long?>(null)
    }

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
                text = "🌳 شجرة $platformName",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Workspace الحالي: ${currentWorkspaceId?.toString() ?: "—"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (wsSummary.isNotBlank()) {
                Text(
                    text = "WORKSPACE_SUMMARY (مختصر):\n${wsSummary.take(220)}",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text(
                    text = "WORKSPACE_SUMMARY فارغ حاليًا.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "المحادثة الحالية:",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = currentConv?.title?.takeIf { it.isNotBlank() }
                            ?: currentId?.let { "ID: $it (محادثة نشطة خارج قائمة الويب)" }
                            ?: "لا توجد محادثة محددة بعد",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "ID: ${currentId?.toString() ?: "—"}  •  Platform: $platformName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { vm.newWebConversation("Web: $platformName") }
                        ) { Text("➕ محادثة جديدة") }

                        OutlinedButton(
                            onClick = { vm.branchFromCurrentWebConversation() },
                            enabled = currentId != null
                        ) { Text("🪵 تفريع") }

                        OutlinedButton(
                            onClick = {
                                vm.clearWebConversationSelection()
                                vm.newConversation()
                            }
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

                                OutlinedButton(
                                    onClick = {
                                        handoffMessageId = message.id
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = enabledPlatforms.size > 1
                                ) {
                                    Text("متابعة من هذه الرسالة في منصة أخرى")
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
                        text = "حذف كل محادثات ورسائل Workspace المحادثة الحالية + تصفير ملخصه.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = { showResetConfirm = true },
                        enabled = currentWorkspaceId != null
                    ) { Text("🧨 Reset Workspace الحالي") }
                }
            }

            Spacer(Modifier.height(10.dp))
        }
    }

    if (handoffMessageId != null) {
        AlertDialog(
            onDismissRequest = {
                handoffMessageId = null
            },
            title = {
                Text("اختر منصة متابعة النقاش")
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "سيُنشأ فرع من الرسالة المختارة، ويُنقل ملخص السياق إلى المنصة الأخرى."
                    )

                    enabledPlatforms
                        .filter { it.id != currentPlatformId }
                        .forEach { target ->
                            OutlinedButton(
                                onClick = {
                                    val selectedMessageId = handoffMessageId
                                    handoffMessageId = null

                                    if (selectedMessageId != null) {
                                        onHandoffRequested(
                                            selectedMessageId,
                                            target
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(target.iconEmoji)
                                    Text(target.name)
                                }
                            }
                        }

                    if (enabledPlatforms.none { it.id != currentPlatformId }) {
                        Text("لا توجد منصة أخرى مفعّلة.")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        handoffMessageId = null
                    }
                ) {
                    Text("إلغاء")
                }
            }
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("تأكيد Reset") },
            text = { Text("سيتم حذف كل المحادثات والرسائل ضمن Workspace المحادثة الحالية وتصفير ملخصه. هل أنت متأكد؟") },
            confirmButton = {
                Button(
                    onClick = {
                        vm.resetWebGlobalWorkspaceFully()
                        showResetConfirm = false
                        onDismiss()
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

/* ============================ Send bar (التصميم الجديد) ============================ */

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
    onCollapse: () -> Unit,
    onEnhance: () -> Unit,
    onClearEnhanced: () -> Unit,
    onSend: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val canSend = text.isNotBlank() && !isSending && !isEnhancing

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = colors.surface,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // شرائح صغيرة: تحسين + ذاكرة + سهم الطي
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val enhanceEnabled = text.isNotBlank() && !isSending && !isEnhancing
                MiniChip(
                    label = when {
                        isEnhanced -> "↩️ تراجع عن التحسين"
                        isEnhancing -> "… جاري التحسين"
                        else -> "⚡ تحسين"
                    },
                    selected = isEnhanced,
                    enabled = if (isEnhanced) !isSending else enhanceEnabled,
                    onClick = if (isEnhanced) onClearEnhanced else onEnhance
                )

                if (memoryEnabled) {
                    MiniChip(
                        label = "🧠 ذاكرة",
                        selected = includeMemory,
                        enabled = !isSending && !isEnhancing,
                        onClick = onToggleMemory
                    )
                }

                Spacer(Modifier.weight(1f))

                IconButton(
                    onClick = onCollapse,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = "طي مربع الإدخال",
                        tint = colors.primary
                    )
                }
            }

            if (includeMemory && curatorReasoning.isNotBlank()) {
                Text(
                    text = "🧠 اختيار الوسيط: $curatorReasoning",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    minLines = 1,
                    maxLines = 5,
                    enabled = !isSending,
                    shape = RoundedCornerShape(24.dp),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    placeholder = { Text(if (isEnhanced) "مُحسّن ✨" else "اكتب رسالتك") }
                )

                Box(
                    modifier = Modifier
                        .padding(bottom = 4.dp)
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (canSend) colors.primary else colors.primaryContainer)
                        .clickable(enabled = canSend, onClick = onSend),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "إرسال",
                            tint = if (canSend) colors.onPrimary else colors.outline,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val bg = if (selected) colors.primaryContainer else colors.background
    val fg = when {
        !enabled -> colors.outline
        selected -> colors.primary
        else -> colors.onSurface
    }

    Box(
        modifier = Modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, if (selected) colors.primary else colors.outline, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = fg,
            maxLines = 1
        )
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
