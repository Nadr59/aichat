package com.example.aichat.ui.screens

import android.content.ActivityNotFoundException
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.aichat.AichatApp
import com.example.aichat.data.local.AiSettings
import com.example.aichat.data.model.MemoryItem
import com.example.aichat.data.model.QueryStyle
import com.example.aichat.data.model.WebPlatform
import com.example.aichat.repository.MemoryContextBuilder
import com.example.aichat.repository.MemoryCuratorService
import com.example.aichat.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import org.mozilla.geckoview.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeckoTestScreen(
    url:           String,
    title:         String,
    onBack:        () -> Unit,
    platform:      WebPlatform?   = null,
    chatViewModel: ChatViewModel? = null
) {
    val context        = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val app            = context.applicationContext as AichatApp
    val mainHandler    = remember { Handler(Looper.getMainLooper()) }
    val runtime        = remember { app.getOrCreateGeckoRuntime() }

    if (runtime == null) {
        GeckoUnavailableDialog(
            platformTitle = title,
            platformUrl   = url,
            onOpenBrowser = { openExternal(context, url); onBack() },
            onBack        = onBack
        )
        return
    }

    // ── الحالة ───────────────────────────────────────────────────────
    var isLoading             by remember { mutableStateOf(true) }
    var progress              by remember { mutableIntStateOf(0) }
    var canGoBack             by remember { mutableStateOf(false) }
    var currentUrl            by remember { mutableStateOf(url) }
    var currentTitle          by remember { mutableStateOf(title) }
    var loadError             by remember { mutableStateOf<String?>(null) }
    var geckoViewRef          by remember { mutableStateOf<GeckoView?>(null) }
    var isSavingMemory        by remember { mutableStateOf(false) }
    var isSendingCtx          by remember { mutableStateOf(false) }
    var timeoutRunnable       by remember { mutableStateOf<Runnable?>(null) }
    var isSystemPromptEnabled by remember { mutableStateOf(true) }
    var showSendDialog        by remember { mutableStateOf(false) }

    // ── Session ──────────────────────────────────────────────────────
    val session = remember { GeckoSession() }

    remember(session) {
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                isLoading  = true
                progress   = 0
                loadError  = null
                currentUrl = url
            }
            override fun onProgressChange(session: GeckoSession, progress_: Int) {
                progress = progress_
            }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                isLoading = false
            }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                if (!title.isNullOrBlank()) currentTitle = title.take(50)
            }
        }
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack_: Boolean) {
                canGoBack = canGoBack_
            }
            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                val uri    = Uri.parse(request.uri)
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
                loadError = when (error.category) {
                    WebRequestError.ERROR_CATEGORY_NETWORK  -> "تعذر الاتصال بالإنترنت"
                    WebRequestError.ERROR_CATEGORY_URI      -> "الرابط غير صالح"
                    WebRequestError.ERROR_CATEGORY_SECURITY -> "مشكلة في شهادة الأمان"
                    else                                    -> "حدث خطأ أثناء تحميل الصفحة"
                }
                return null
            }
        }
        Unit
    }

    // ── المسار 1: callbacks الذاكرة ──────────────────────────────────
    DisposableEffect(platform?.id) {
        val p  = platform
        val vm = chatViewModel

        if (p != null && vm != null && p.memoryEnabled) {
            app.onAiResponseCaptured = { domain, text ->
                vm.onWebAiResponse(
                    platformId   = p.id,
                    platformName = p.name,
                    text         = text
                )
                Log.d("GeckoTestScreen", "🧠 Auto: ${text.take(60)}")
            }
            app.onManualCaptureResult = { success, text, debug ->
                timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                timeoutRunnable = null
                isSavingMemory  = false

                if (success && text.isNotBlank()) {
                    vm.onWebAiResponse(
                        platformId   = p.id,
                        platformName = p.name,
                        text         = text
                    )
                    Toast.makeText(
                        context,
                        "✅ تم الحفظ\n${text.take(50)}",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    val info = debug?.let {
                        "assistant=${it.optInt("assistant")} " +
                        "articles=${it.optInt("articles")} " +
                        "body=${it.optInt("bodyLen")}"
                    } ?: "no response"
                    Toast.makeText(
                        context,
                        "⚠️ فشل الاستخراج\n$info",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        onDispose {
            timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            timeoutRunnable           = null
            app.onAiResponseCaptured  = null
            app.onManualCaptureResult = null
        }
    }

    // ── المسار 1: زر 🧠 ──────────────────────────────────────────────
    val saveToMemory: () -> Unit = save@{
        if (isSavingMemory || isLoading) return@save
        if (platform == null || !platform.memoryEnabled) return@save
        if (chatViewModel == null) return@save

        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        isSavingMemory = true
        Toast.makeText(context, "🧠 جاري البحث في الصفحة...", Toast.LENGTH_SHORT).show()
        app.triggerCapture()

        val r = Runnable {
            if (isSavingMemory) {
                isSavingMemory = false
                Toast.makeText(
                    context,
                    "❌ انتهت المهلة\nExt=${app.aiChatExtension != null}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        timeoutRunnable = r
        mainHandler.postDelayed(r, 5000L)
    }

    // ── المسار 2: زر 📤 ──────────────────────────────────────────────
    val sendContext: () -> Unit = {
        if (!isLoading && platform != null && platform.memoryEnabled && chatViewModel != null) {
            showSendDialog = true
        }
    }

    if (showSendDialog && chatViewModel != null) {
        SendToWebDialog(
            chatViewModel = chatViewModel,
            onDismiss     = { showSendDialog = false },
            onSend        = { finalText ->
                app.setContextPending(finalText)
                showSendDialog = false
                Toast.makeText(
                    context,
                    "📤 جاهز للإرسال (${finalText.length} حرف)",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }

    // ── رجوع ذكي ─────────────────────────────────────────────────────
    val handleBack: () -> Unit = {
        if (canGoBack) session.goBack() else onBack()
    }
    BackHandler(onBack = handleBack)

    // ── دورة الحياة ──────────────────────────────────────────────────
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP  -> session.setActive(false)
                Lifecycle.Event.ON_START -> session.setActive(true)
                else                     -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ── تنظيف ────────────────────────────────────────────────────────
    DisposableEffect(Unit) {
        onDispose {
            timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            try {
                geckoViewRef?.releaseSession()
                session.close()
            } catch (e: Exception) {
                Log.w("GeckoTestScreen", "Cleanup: ${e.message}")
            }
        }
    }

    // ── الواجهة ──────────────────────────────────────────────────────
    Column(modifier = Modifier.fillMaxSize()) {

        TopAppBar(
            title = {
                Column {
                    Text(
                        text       = title,
                        style      = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis
                    )
                    if (currentTitle != title && currentTitle.isNotBlank()) {
                        Text(
                            text     = currentTitle,
                            style    = MaterialTheme.typography.labelSmall,
                            color    = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = handleBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع")
                }
            },
            actions = {
                if (platform != null && platform.memoryEnabled && chatViewModel != null) {

                    // المسار 1 — زر 🧠
                    IconButton(
                        onClick = saveToMemory,
                        enabled = !isSavingMemory && !isLoading
                    ) {
                        Text(
                            text  = if (isSavingMemory) "⏳" else "🧠",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    // في TopAppBar actions، أضف زر تشخيص
IconButton(
    onClick = {
        scope.launch {
            val result = try {
                // إرسال رسالة test مباشرة
                val testMsg = JSONObject().apply {
                    put("type", "DEBUG_INFO")
                    put("info", "Test from Kotlin at ${System.currentTimeMillis()}")
                }
                
                app.aiChatExtension?.let { ext ->
                    // محاولة إرسال
                    Toast.makeText(
                        context,
                        "Extension: ${ext.id} - trying to send test...",
                        Toast.LENGTH_LONG
                    ).show()
                }
                
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    "Error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
) {
    Text("🔍", style = MaterialTheme.typography.titleMedium)
}

                    // زر تفعيل/تعطيل الوثيقة
                    IconButton(
                        onClick = { isSystemPromptEnabled = !isSystemPromptEnabled }
                    ) {
                        Text(
                            text  = if (isSystemPromptEnabled) "📄✅" else "📄❌",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    // المسار 2 — زر 📤
                    IconButton(
                        onClick = sendContext,
                        enabled = !isSendingCtx && !isLoading
                    ) {
                        Text(
                            text  = if (isSendingCtx) "⏳" else "📤",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }

                IconButton(onClick = { session.reload() }) {
                    Icon(Icons.Filled.Refresh, "تحديث")
                }
                IconButton(onClick = { openExternal(context, currentUrl) }) {
                    Icon(Icons.Filled.OpenInBrowser, "فتح في المتصفح")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )

        Box(modifier = Modifier.fillMaxSize()) {

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory  = { ctx ->
                    GeckoView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        if (!session.isOpen) {
                            session.open(runtime)
                            session.loadUri(url)
                        }
                        setSession(session)
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
                    message       = message,
                    onRetry       = { loadError = null; session.reload() },
                    onOpenBrowser = { openExternal(context, currentUrl) }
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// Dialog شامل: سؤال + تحسين + صياغة + سياق
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun SendToWebDialog(
    chatViewModel: ChatViewModel,
    onDismiss:     () -> Unit,
    onSend:        (String) -> Unit
) {
    var query          by remember { mutableStateOf("") }
    var enhancedQuery  by remember { mutableStateOf<String?>(null) }
    var isEnhancing    by remember { mutableStateOf(false) }
    var showStyleMenu  by remember { mutableStateOf(false) }
    var includeContext by remember { mutableStateOf(false) }
    var searchResults  by remember { mutableStateOf(emptyList<MemoryItem>()) }
    var selectedIds    by remember { mutableStateOf(setOf<Long>()) }

    val scope               = rememberCoroutineScope()
    val context             = LocalContext.current
    val aiSettings          = remember { AiSettings(context) }
    val memoryCuratorService = remember {
        MemoryCuratorService(
            settings        = aiSettings,
            fallbackBuilder = MemoryContextBuilder()
        )
    }

    // النص المعروض (محسّن أو أصلي)
    val displayedText = enhancedQuery ?: query

    // بحث تلقائي عند تفعيل السياق
    LaunchedEffect(includeContext, displayedText) {
        if (includeContext && displayedText.length > 2) {
            try {
                searchResults = chatViewModel.searchSharedMemories(displayedText)
                selectedIds   = searchResults.take(3).map { it.id }.toSet()
            } catch (e: Exception) {
                searchResults = emptyList()
                selectedIds   = emptySet()
            }
        } else {
            searchResults = emptyList()
            selectedIds   = emptySet()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 650.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                // ═══════════════════════════════════════
                // العنوان
                // ═══════════════════════════════════════
                Text(
                    text       = "💬 إرسال سؤال إلى المنصة",
                    style      = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                // ═══════════════════════════════════════
                // حقل السؤال
                // ═══════════════════════════════════════
                OutlinedTextField(
                    value         = displayedText,
                    onValueChange = {
                        if (enhancedQuery != null) enhancedQuery = it
                        else query = it
                    },
                    label = {
                        Text(
                            if (enhancedQuery != null) "السؤال المُحسّن ✨"
                            else "اكتب سؤالك"
                        )
                    },
                    placeholder = { Text("مثال: ما الطقس اليوم؟") },
                    modifier    = Modifier.fillMaxWidth(),
                    minLines    = 2,
                    maxLines    = 6,
                    enabled     = !isEnhancing
                )

                // ═══════════════════════════════════════
                // أزرار التحسين
                // ═══════════════════════════════════════
                Row(
                    modifier            = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // ⚡ توسيع الطلب
                    if (aiSettings.mediatorIdentityText.isNotBlank()) {
                        OutlinedButton(
                            onClick = {
                                if (query.isBlank()) return@OutlinedButton
                                isEnhancing = true
                                scope.launch {
                                    try {
                                        val enhanced = memoryCuratorService.enhanceQuery(
                                            userQuery            = query,
                                            mediatorIdentityText = aiSettings.mediatorIdentityText
                                        )
                                        enhancedQuery = enhanced
                                    } catch (e: Exception) {
                                        Toast.makeText(
                                            context,
                                            "❌ فشل التحسين: ${e.message}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } finally {
                                        isEnhancing = false
                                    }
                                }
                            },
                            enabled  = query.isNotBlank() && !isEnhancing,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isEnhancing) {
                                CircularProgressIndicator(
                                    modifier    = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            Text("⚡ توسيع")
                        }
                    }

                    // 🎨 تحسين الصياغة
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick  = { showStyleMenu = true },
                            enabled  = query.isNotBlank() && !isEnhancing,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("🎨 صياغة")
                        }

                        DropdownMenu(
                            expanded         = showStyleMenu,
                            onDismissRequest = { showStyleMenu = false }
                        ) {
                            for (style in QueryStyle.entries) {
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            verticalAlignment     = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text  = style.emoji,
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                            Column {
                                                Text(
                                                    text       = style.displayName,
                                                    style      = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text  = style.description,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        showStyleMenu = false
                                        if (query.isBlank()) return@DropdownMenuItem
                                        isEnhancing = true
                                        scope.launch {
                                            try {
                                                val refined = memoryCuratorService.refineQueryStyle(
                                                    userQuery = query,
                                                    style     = style
                                                )
                                                enhancedQuery = refined
                                            } catch (e: Exception) {
                                                Toast.makeText(
                                                    context,
                                                    "❌ فشل التحسين: ${e.message}",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            } finally {
                                                isEnhancing = false
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }

                    // 🧹 مسح التحسين
                    if (enhancedQuery != null) {
                        OutlinedButton(
                            onClick  = { enhancedQuery = null },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("🧹 مسح")
                        }
                    }
                }

                // ═══════════════════════════════════════
                // خيار السياق
                // ═══════════════════════════════════════
                HorizontalDivider()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { includeContext = !includeContext }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked         = includeContext,
                        onCheckedChange = { includeContext = it }
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text       = "🧠 إضافة سياق من الذاكرة",
                            style      = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (includeContext) {
                            Text(
                                text  = "سيتم البحث عن ذكريات ذات صلة تلقائياً",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // ═══════════════════════════════════════
                // اختيار الذكريات
                // ═══════════════════════════════════════
                if (includeContext && searchResults.isNotEmpty()) {
                    Text(
                        text       = "ذكريات ذات صلة (${searchResults.size}):",
                        style      = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color      = MaterialTheme.colorScheme.primary
                    )

                    searchResults.take(5).forEach { memory ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedIds = if (memory.id in selectedIds) {
                                        selectedIds - memory.id
                                    } else {
                                        selectedIds + memory.id
                                    }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked         = memory.id in selectedIds,
                                onCheckedChange = null
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text  = memory.content.take(80) +
                                        if (memory.content.length > 80) "..." else "",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                } else if (includeContext && displayedText.length > 2) {
                    Text(
                        text     = "⚠️ لم توجد ذكريات ذات صلة بهذا السؤال",
                        style    = MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                // ═══════════════════════════════════════
                // الأزرار النهائية
                // ═══════════════════════════════════════
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick  = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("❌ إلغاء")
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                val finalText = if (includeContext && selectedIds.isNotEmpty()) {
                                    val selected       = searchResults.filter { it.id in selectedIds }
                                    val contextBuilder = MemoryContextBuilder()
                                    val ctx            = contextBuilder.build(selected)

                                    buildString {
                                        appendLine("السياق من محادثاتي السابقة:")
                                        appendLine()
                                        appendLine(ctx)
                                        appendLine()
                                        appendLine("───────────")
                                        appendLine()
                                        appendLine("السؤال:")
                                        append(displayedText)
                                    }
                                } else {
                                    displayedText
                                }

                                onSend(finalText)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled  = displayedText.isNotBlank() && !isEnhancing
                    ) {
                        Text("📤 إرسال للمنصة")
                    }
                }
            }
        }
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

private fun openExternal(context: Context, url: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "لا يوجد تطبيق لفتح هذا الرابط", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun LoadErrorView(
    message:       String,
    onRetry:       () -> Unit,
    onOpenBrowser: () -> Unit
) {
    Box(
        modifier         = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("⚠️", style = MaterialTheme.typography.displaySmall)
            Text(
                text      = message,
                style     = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Button(onClick = onRetry) { Text("إعادة المحاولة") }
            OutlinedButton(onClick = onOpenBrowser) { Text("فتح في المتصفح") }
        }
    }
}

@Composable
private fun GeckoUnavailableDialog(
    platformTitle: String,
    platformUrl:   String,
    onOpenBrowser: () -> Unit,
    onBack:        () -> Unit
) {
    AlertDialog(
        onDismissRequest = onBack,
        title            = { Text("⚠️ GeckoView غير متاح") },
        text             = {
            Text(
                "تعذر تهيئة محرك GeckoView.\n\n" +
                "يمكنك فتح $platformTitle في المتصفح الخارجي."
            )
        },
        confirmButton = {
            Button(onClick = onOpenBrowser) { Text("📱 فتح في المتصفح") }
        },
        dismissButton = {
            OutlinedButton(onClick = onBack) { Text("رجوع") }
        }
    )
}
