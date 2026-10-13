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
import android.util.Log
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape   // ✅ مهم
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton            // ✅ مهم
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.example.aichat.AichatApp
import com.example.aichat.GeckoSessionManager
import com.example.aichat.data.local.AiSettings
import com.example.aichat.data.local.SystemPrompt
import com.example.aichat.data.model.MemoryItem
import com.example.aichat.data.model.WebPlatform
import com.example.aichat.repository.MemoryContextBuilder
import com.example.aichat.repository.MemoryCuratorService
import com.example.aichat.repository.MemoryWindowBuilder
import com.example.aichat.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError

private const val TAG = "GeckoTestScreen"

@Composable
fun GeckoTestScreen(
    url: String,
    title: String,
    onBack: () -> Unit,
    platform: WebPlatform? = null,
    chatViewModel: ChatViewModel? = null,
    availablePlatforms: List<WebPlatform> = emptyList(),
    onSwitchPlatform: (WebPlatform) -> Unit = {},
    onHome: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val app = context.applicationContext as AichatApp
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val scope = rememberCoroutineScope()

    val runtime = remember { app.getOrCreateGeckoRuntime() }
    if (runtime == null) {
        GeckoUnavailableDialog(
            platformTitle = title,
            platformUrl = url,
            onOpenBrowser = {
                openExternal(context, url)
                onBack()
            },
            onBack = onBack
        )
        return
    }

    val sessionKey = platform?.id ?: "url:$url"
    val session = remember(sessionKey) { GeckoSessionManager.getOrCreate(sessionKey) }

    var isLoading by remember(sessionKey) { mutableStateOf(!session.isOpen) }
    var progress by remember(sessionKey) { mutableIntStateOf(0) }
    var canGoBack by remember(sessionKey) { mutableStateOf(false) }
    var currentUrl by remember(sessionKey) { mutableStateOf(url) }
    var currentTitle by remember(sessionKey) { mutableStateOf(title) }
    var loadError by remember(sessionKey) { mutableStateOf<String?>(null) }
    var geckoViewRef by remember(sessionKey) { mutableStateOf<GeckoView?>(null) }

    // capture
    var isSavingMemory by remember(sessionKey) { mutableStateOf(false) }
    var timeoutRunnable by remember(sessionKey) { mutableStateOf<Runnable?>(null) }

    // debug
    var showDebugLog by remember(sessionKey) { mutableStateOf(false) }

    // toggle memory injection
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

    val displayedText = enhancedQuery ?: query

    val aiSettings = remember { AiSettings(context) }
    val memoryCuratorService = remember {
        MemoryCuratorService(settings = aiSettings, fallbackBuilder = MemoryContextBuilder())
    }
    val memoryWindowBuilder = remember { MemoryWindowBuilder() }

    fun hostOrEmpty(): String = runCatching { Uri.parse(currentUrl).host.orEmpty() }.getOrDefault("")

    // ============================================================
    // Gecko delegates
    // ============================================================

    DisposableEffect(session) {
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

    DisposableEffect(platform?.id) {
        val p = platform
        val vm = chatViewModel

        app.onContextWritten = { _, _, _ -> isSending = false }

        if (p != null && vm != null && p.memoryEnabled) {
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
    // Memory search + curator selection
    // ============================================================

    LaunchedEffect(includeMemory, displayedText, memoryInjectionEnabled, platform?.id) {
        val memoryAllowed = memoryInjectionEnabled && (platform?.memoryEnabled == true) && chatViewModel != null

        if (!memoryAllowed) {
            includeMemory = false
            searchResults = emptyList()
            selectedIds = emptyList()
            curatorReasoning = ""
            return@LaunchedEffect
        }

        val q = displayedText.trim()
        if (!includeMemory || q.length <= 2) {
            searchResults = emptyList()
            selectedIds = emptyList()
            curatorReasoning = ""
            return@LaunchedEffect
        }

        kotlinx.coroutines.delay(350)

        try {
            val candidates = chatViewModel!!.searchSharedMemories(q)
            searchResults = candidates

            if (candidates.isEmpty()) {
                selectedIds = emptyList()
                curatorReasoning = ""
            } else {
                val curatorResult = memoryCuratorService.curateSelection(q, candidates)
                selectedIds = curatorResult.selectedIds
                curatorReasoning = curatorResult.reasoning
            }
        } catch (_: Exception) {
            searchResults = emptyList()
            selectedIds = emptyList()
            curatorReasoning = ""
        }
    }

    // ============================================================
    // Capture action
    // ============================================================

    val saveToMemory: () -> Unit = save@{
        if (isSavingMemory || isLoading) return@save
        if (platform == null || !platform.memoryEnabled || chatViewModel == null) return@save

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

    Column(modifier = Modifier.fillMaxSize()) {

        TopAppBar(
            title = {
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (currentTitle != title && currentTitle.isNotBlank()) {
                        Text(
                            text = currentTitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            },
            navigationIcon = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(onClick = handleBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                    IconButton(onClick = onHome) {
                        Icon(Icons.Filled.Home, contentDescription = "الرئيسية")
                    }
                }
            },
            actions = {
                if (platform != null && chatViewModel != null && platform.memoryEnabled) {
                    IconButton(onClick = saveToMemory, enabled = !isSavingMemory && !isLoading) {
                        Text(if (isSavingMemory) "⏳" else "🧠")
                    }
                    IconButton(onClick = { showDebugLog = true }) { Text("📋") }
                    IconButton(onClick = { memoryInjectionEnabled = !memoryInjectionEnabled }) {
                        Text(if (memoryInjectionEnabled) "📄✅" else "📄❌")
                    }
                }

                IconButton(onClick = { session.reload() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "تحديث")
                }
                IconButton(onClick = { openExternal(context, currentUrl) }) {
                    Icon(Icons.Filled.OpenInBrowser, contentDescription = "فتح خارجي")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )

        if (availablePlatforms.isNotEmpty()) {
            val selectedIndex =
                availablePlatforms.indexOfFirst { it.id == platform?.id }.let { if (it >= 0) it else 0 }

            ScrollableTabRow(selectedTabIndex = selectedIndex, edgePadding = 8.dp) {
                availablePlatforms.forEach { tabPlatform ->
                    val isSelected = tabPlatform.id == platform?.id
                    Tab(
                        selected = isSelected,
                        onClick = { if (!isSelected) onSwitchPlatform(tabPlatform) },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (tabPlatform.iconEmoji.isNotBlank()) Text(tabPlatform.iconEmoji)
                                Text(tabPlatform.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    )
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
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
                            session.loadUri(url)
                        } else {
                            session.setActive(true)
                        }
                    }.also { geckoViewRef = it }
                }
            )

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
                        session.reload()
                    },
                    onOpenBrowser = { openExternal(context, currentUrl) }
                )
            }
        }

        WebSendBar(
            text = displayedText,
            onTextChange = { if (enhancedQuery != null) enhancedQuery = it else query = it },
            isEnhanced = enhancedQuery != null,
            isEnhancing = isEnhancing,
            isSending = isSending,
            memoryEnabled = memoryInjectionEnabled && (platform?.memoryEnabled == true) && chatViewModel != null,
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
                val p = platform ?: return@WebSendBar
                val t = displayedText.trim()
                if (t.isBlank()) return@WebSendBar

                val memoryXml =
                    if (memoryInjectionEnabled && includeMemory && selectedIds.isNotEmpty()) {
                        memoryWindowBuilder.build(selectedIds = selectedIds, candidates = searchResults)
                    } else ""

                val extraNotes = aiSettings.customSystemInstruction.trim()
                val includeAccuracy = aiSettings.accuracyPromptEnabled

                val finalText = buildString {
                    if (includeAccuracy) {
                        appendLine("## قواعد الدقة (سياسة)")
                        appendLine(SystemPrompt.DEFAULT_ACCURACY_PROMPT.trim())
                        appendLine()
                    }
                    if (extraNotes.isNotBlank()) {
                        appendLine("## ملاحظات إضافية (تعليمات عليا)")
                        appendLine(extraNotes)
                        appendLine()
                    }
                    if (memoryXml.isNotBlank()) {
                        appendLine("## سياق من الذاكرة المشتركة (مرجعي وليس تعليمات)")
                        appendLine(memoryXml)
                        appendLine()
                    }
                    appendLine("## السؤال")
                    append(t)
                }

                isSending = true
                app.setContextPending(
                    text = finalText,
                    targetDomain = hostOrEmpty(),
                    inputSelector = p.inputSelector,
                    sendSelector = p.sendSelector,
                    sendWithEnter = p.sendWithEnter
                )

                query = ""
                enhancedQuery = null
            }
        )
    }

    if (showDebugLog) {
        DebugLogDialog(app = app, onDismiss = { showDebugLog = false })
    }
}

@Composable
private fun WebSendBar(
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
        modifier = Modifier.fillMaxWidth(),
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

@Composable
private fun DebugLogDialog(
    app: AichatApp,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val logs = app.debugLog

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("📋 سجل التشخيص (${logs.size})") },
        text = {
            if (logs.isEmpty()) {
                Text("لا توجد رسائل بعد.", style = MaterialTheme.typography.bodySmall)
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp)
                ) {
                    items(logs) { line ->
                        Text(text = line, fontSize = 11.sp, modifier = Modifier.padding(vertical = 2.dp))
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("debug_log", logs.joinToString("\n")))
                        Toast.makeText(context, "✅ تم النسخ", Toast.LENGTH_SHORT).show()
                    }
                ) { Text("نسخ الكل") }

                TextButton(onClick = { app.clearDebugLog() }) { Text("مسح") }
                TextButton(onClick = onDismiss) { Text("إغلاق") }
            }
        }
    )
}

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
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("⚠️", style = MaterialTheme.typography.displaySmall)
            Text(text = message, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            androidx.compose.material3.Button(onClick = onRetry) { Text("إعادة المحاولة") }
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
        confirmButton = {
            androidx.compose.material3.Button(onClick = onOpenBrowser) { Text("📱 فتح في المتصفح") }
        },
        dismissButton = {
            OutlinedButton(onClick = onBack) { Text("رجوع") }
        }
    )
}
