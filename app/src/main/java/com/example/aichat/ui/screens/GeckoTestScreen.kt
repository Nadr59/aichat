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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.aichat.AichatApp
import com.example.aichat.GeckoSessionManager
import com.example.aichat.data.local.AiSettings
import com.example.aichat.data.local.SystemPrompt
import com.example.aichat.data.model.MemoryItem
import com.example.aichat.data.model.QueryStyle
import com.example.aichat.data.model.WebPlatform
import com.example.aichat.repository.MemoryContextBuilder
import com.example.aichat.repository.MemoryCuratorService
import com.example.aichat.repository.MemoryWindowBuilder
import com.example.aichat.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError

private const val TAG = "GeckoTestScreen"

/**
 * GeckoView artifact عندك لا يوفر evaluateJS كـ API Kotlin،
 * لذلك نستخدم Reflection لتجنب خطأ compile.
 *
 * إذا لم توجد evaluateJS فعلاً وقت التشغيل، سنسجل تحذير فقط.
 * (الحل النهائي وقتها: إرسال عبر WebExtension messaging بدل JS injection.)
 */
private fun evaluateJsCompat(session: GeckoSession, js: String) {
    runCatching {
        val method = session.javaClass.methods.firstOrNull { m ->
            (m.name == "evaluateJS" || m.name == "evaluateJavascript") &&
                m.parameterTypes.size == 1 &&
                m.parameterTypes[0] == String::class.java
        } ?: throw NoSuchMethodException("No evaluateJS(String) / evaluateJavascript(String) in this GeckoView")

        method.invoke(session, js)
    }.onFailure { e ->
        Log.w(TAG, "JS injection failed: ${e.message}", e)
    }
}

/**
 * JS helper: يكتب النص داخل حقل إدخال المنصة ويضغط إرسال (أو Enter).
 * يعتمد على selectors داخل WebPlatform:
 * - inputSelector
 * - sendSelector
 * - sendWithEnter
 */
private fun buildAutoSendJs(
    text: String,
    inputSelector: String,
    sendSelector: String,
    sendWithEnter: Boolean
): String {
    val payload = JSONObject().apply {
        put("text", text)
        put("inputSelector", inputSelector)
        put("sendSelector", sendSelector)
        put("sendWithEnter", sendWithEnter)
    }.toString()

    return """
        (() => {
          const p = $payload;

          const pick = (sel) => {
            if (!sel || !sel.trim()) return null;
            return document.querySelector(sel);
          };

          const input =
            pick(p.inputSelector) ||
            document.querySelector('textarea, [contenteditable="true"]');

          if (!input) return JSON.stringify({ok:false, step:"input_not_found"});

          const setText = (el, value) => {
            if ("value" in el) el.value = value;
            else el.textContent = value;

            el.dispatchEvent(new Event("input", {bubbles:true}));
            el.dispatchEvent(new Event("change", {bubbles:true}));
          };

          input.focus();
          setText(input, p.text);

          let btn =
            pick(p.sendSelector) ||
            document.querySelector('button[type="submit"], button[aria-label*="send" i], button[data-testid*="send" i]');

          if (btn) {
            btn.click();
            return JSON.stringify({ok:true, mode:"click"});
          }

          if (p.sendWithEnter) {
            const ev = {key:"Enter", code:"Enter", keyCode:13, which:13, bubbles:true, cancelable:true};
            input.dispatchEvent(new KeyboardEvent("keydown", ev));
            input.dispatchEvent(new KeyboardEvent("keyup", ev));
            return JSON.stringify({ok:true, mode:"enter"});
          }

          return JSON.stringify({ok:false, step:"send_not_found"});
        })();
    """.trimIndent()
}

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

    var isSavingMemory by remember(sessionKey) { mutableStateOf(false) }
    var timeoutRunnable by remember(sessionKey) { mutableStateOf<Runnable?>(null) }

    /**
     * 📄✅ / 📄❌
     * تمكين/تعطيل "إرسال الذاكرة للويب" فقط (Memory injection).
     * customSystemInstruction تُرسل دائماً.
     */
    var isSystemPromptEnabled by remember(sessionKey) { mutableStateOf(true) }

    var showSendDialog by remember(sessionKey) { mutableStateOf(false) }
    var showDebugLog by remember(sessionKey) { mutableStateOf(false) }

    // ============================================================
    // Session delegates
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
                    if (!title.isNullOrBlank()) currentTitle = title.take(50)
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
    // Extension callbacks (capture -> save to shared memory)
    // ============================================================

    DisposableEffect(platform?.id) {
        val p = platform
        val vm = chatViewModel

        if (p != null && vm != null && p.memoryEnabled) {
            app.onAiResponseCaptured = { _, text ->
                Log.d(TAG, "📨 Callback received: platform=${p.name}, len=${text.length}")
                app.logDebug("📨 GeckoTestScreen received ASSISTANT_RESPONSE: len=${text.length}")

                vm.onWebAiResponse(
                    platformId = p.id,
                    platformName = p.name,
                    text = text
                )
            }

            app.onManualCaptureResult = { success, text, debug ->
                timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                timeoutRunnable = null
                isSavingMemory = false

                if (success && text.isNotBlank()) {
                    vm.onWebAiResponse(
                        platformId = p.id,
                        platformName = p.name,
                        text = text,
                        forceSaveToMemory = true
                    )

                    Toast.makeText(context, "✅ تم الحفظ\n${text.take(50)}", Toast.LENGTH_LONG).show()
                } else {
                    val info =
                        debug?.let {
                            "assistant=${it.optInt("assistant")} " +
                                "articles=${it.optInt("articles")} " +
                                "body=${it.optInt("bodyLen")}"
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
        }
    }

    // ============================================================
    // Actions
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

    val openSendDialog: () -> Unit = {
        if (!isLoading && platform != null && chatViewModel != null) {
            showSendDialog = true
        }
    }

    if (showSendDialog && chatViewModel != null) {
        SendToWebDialog(
            chatViewModel = chatViewModel,
            memoryEnabled = isSystemPromptEnabled,
            onDismiss = { showSendDialog = false },
            onSend = { finalText ->
    val p = platform
    if (p != null) {
        val host = runCatching { Uri.parse(currentUrl).host.orEmpty() }.getOrDefault("")
        app.setContextPending(
            text = finalText,
            targetDomain = host,
            inputSelector = p.inputSelector,
            sendSelector = p.sendSelector,
            sendWithEnter = p.sendWithEnter
        )
        app.logDebug("📤 CONTEXT_TO_PAGE queued | platform=${p.id} host=$host len=${finalText.length}")
    } else {
        Toast.makeText(context, "⚠️ لا توجد منصة محددة", Toast.LENGTH_SHORT).show()
    }
    showSendDialog = false
            }
        )
    }

    if (showDebugLog) {
        DebugLogDialog(app = app, onDismiss = { showDebugLog = false })
    }

    val handleBack: () -> Unit = {
        if (canGoBack) runCatching { session.goBack() }.onFailure { onBack() }
        else onBack()
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

    DisposableEffect(Unit) {
        onDispose {
            timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            timeoutRunnable = null

            try {
                geckoViewRef?.releaseSession()
                geckoViewRef = null
                session.setActive(false)
            } catch (e: Exception) {
                Log.w(TAG, "Cleanup: ${e.message}")
            }
        }
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IconButton(onClick = handleBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع داخل المنصة")
                    }
                    IconButton(onClick = onHome) {
                        Icon(Icons.Filled.Home, contentDescription = "الصفحة الرئيسية")
                    }
                }
            },
            actions = {
                if (platform != null && chatViewModel != null) {

                    if (platform.memoryEnabled) {
                        IconButton(
                            onClick = saveToMemory,
                            enabled = !isSavingMemory && !isLoading
                        ) {
                            Text(if (isSavingMemory) "⏳" else "🧠", style = MaterialTheme.typography.titleMedium)
                        }

                        Spacer(Modifier.width(6.dp))

                        IconButton(onClick = { showDebugLog = true }) {
                            Text("📋", style = MaterialTheme.typography.titleMedium)
                        }

                        Spacer(Modifier.width(6.dp))

                        IconButton(onClick = { isSystemPromptEnabled = !isSystemPromptEnabled }) {
                            Text(
                                text = if (isSystemPromptEnabled) "📄✅" else "📄❌",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }

                        Spacer(Modifier.width(6.dp))
                    }

                    IconButton(onClick = openSendDialog, enabled = !isLoading) {
                        Text("📤", style = MaterialTheme.typography.titleMedium)
                    }

                    Spacer(Modifier.width(10.dp))
                }

                IconButton(onClick = { session.reload() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "تحديث")
                }

                IconButton(onClick = { openExternal(context, currentUrl) }) {
                    Icon(Icons.Filled.OpenInBrowser, contentDescription = "فتح في المتصفح")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
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
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (tabPlatform.iconEmoji.isNotBlank()) {
                                    Text(tabPlatform.iconEmoji, style = MaterialTheme.typography.bodyMedium)
                                }
                                Text(tabPlatform.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    )
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {

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
                Text(
                    "لا توجد رسائل تشخيص بعد.\nجرّب زر 🧠 أو 📤 ثم افتح هذا السجل.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp)
                ) {
                    items(logs) { line ->
                        Text(
                            text = line,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
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

@Composable
private fun SendToWebDialog(
    chatViewModel: ChatViewModel,
    memoryEnabled: Boolean = true,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var enhancedQuery by remember { mutableStateOf<String?>(null) }
    var isEnhancing by remember { mutableStateOf(false) }
    var showStyleMenu by remember { mutableStateOf(false) }

    var includeMemory by remember { mutableStateOf(false) }

    var searchResults by remember { mutableStateOf(emptyList<MemoryItem>()) }
    var selectedIds by remember { mutableStateOf(emptyList<Long>()) }
    var curatorReasoning by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val aiSettings = remember { AiSettings(context) }

    val memoryCuratorService = remember {
        MemoryCuratorService(
            settings = aiSettings,
            fallbackBuilder = MemoryContextBuilder()
        )
    }

    val memoryWindowBuilder = remember { MemoryWindowBuilder() }

    val displayedText = enhancedQuery ?: query

    LaunchedEffect(memoryEnabled) {
        if (!memoryEnabled) {
            includeMemory = false
            searchResults = emptyList()
            selectedIds = emptyList()
            curatorReasoning = ""
        }
    }

    LaunchedEffect(includeMemory, displayedText, memoryEnabled) {
        if (!memoryEnabled) return@LaunchedEffect

        if (includeMemory && displayedText.length > 2) {
            try {
                val candidates = chatViewModel.searchSharedMemories(displayedText)
                searchResults = candidates

                if (candidates.isEmpty()) {
                    selectedIds = emptyList()
                    curatorReasoning = ""
                } else {
                    val curatorResult = memoryCuratorService.curateSelection(
                        userQuery = displayedText,
                        candidates = candidates
                    )
                    selectedIds = curatorResult.selectedIds
                    curatorReasoning = curatorResult.reasoning
                }
            } catch (_: Exception) {
                searchResults = emptyList()
                selectedIds = emptyList()
                curatorReasoning = ""
            }
        } else {
            searchResults = emptyList()
            selectedIds = emptyList()
            curatorReasoning = ""
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
                Text(
                    text = "💬 إرسال سؤال إلى المنصة",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                OutlinedTextField(
                    value = displayedText,
                    onValueChange = { if (enhancedQuery != null) enhancedQuery = it else query = it },
                    label = { Text(if (enhancedQuery != null) "السؤال المُحسّن ✨" else "اكتب سؤالك") },
                    placeholder = { Text("مثال: ما الطقس اليوم؟") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 6,
                    enabled = !isEnhancing
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val textToEnhance = displayedText.trim()
                            if (textToEnhance.isBlank()) return@OutlinedButton

                            isEnhancing = true
                            scope.launch {
                                try {
                                    val enhanced = memoryCuratorService.enhanceQuery(
                                        userQuery = textToEnhance,
                                        mediatorIdentityText = aiSettings.mediatorIdentityText
                                    )
                                    enhancedQuery = enhanced
                                } catch (e: Exception) {
                                    Toast.makeText(context, "❌ فشل التحسين: ${e.message}", Toast.LENGTH_SHORT).show()
                                } finally {
                                    isEnhancing = false
                                }
                            }
                        },
                        enabled = displayedText.isNotBlank() && !isEnhancing,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isEnhancing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text("⚡ توسيع")
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = { showStyleMenu = true },
                            enabled = displayedText.isNotBlank() && !isEnhancing,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("🎨 صياغة")
                        }

                        DropdownMenu(
                            expanded = showStyleMenu,
                            onDismissRequest = { showStyleMenu = false }
                        ) {
                            for (style in QueryStyle.entries) {
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(style.emoji, style = MaterialTheme.typography.titleMedium)
                                            Column {
                                                Text(style.displayName, fontWeight = FontWeight.Bold)
                                                Text(
                                                    text = style.description,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        showStyleMenu = false
                                        val textToRefine = displayedText.trim()
                                        if (textToRefine.isBlank()) return@DropdownMenuItem

                                        isEnhancing = true
                                        scope.launch {
                                            try {
                                                val refined = memoryCuratorService.refineQueryStyle(
                                                    userQuery = textToRefine,
                                                    style = style
                                                )
                                                enhancedQuery = refined
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "❌ فشل التحسين: ${e.message}", Toast.LENGTH_SHORT).show()
                                            } finally {
                                                isEnhancing = false
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }

                    if (enhancedQuery != null) {
                        OutlinedButton(
                            onClick = { enhancedQuery = null },
                            modifier = Modifier.weight(1f)
                        ) { Text("🧹 مسح") }
                    }
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .let { base -> if (memoryEnabled) base.clickable { includeMemory = !includeMemory } else base }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = includeMemory && memoryEnabled,
                        onCheckedChange = { if (memoryEnabled) includeMemory = it },
                        enabled = memoryEnabled
                    )

                    Spacer(Modifier.width(8.dp))

                    Column {
                        Text("🧠 إضافة سياق من الذاكرة", fontWeight = FontWeight.Bold)

                        if (!memoryEnabled) {
                            Text(
                                text = "📄❌ تم تعطيل إرسال الذاكرة — سيتم إرسال السؤال + الملاحظات الإضافية فقط",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (includeMemory && memoryEnabled && searchResults.isNotEmpty()) {
                    Text(
                        text = "ذكريات مرشحة (${searchResults.size})",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    searchResults.take(5).forEach { memory ->
                        val isSelected = memory.id in selectedIds

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedIds =
                                        if (isSelected) selectedIds.filterNot { it == memory.id }
                                        else selectedIds + memory.id
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = isSelected, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = memory.content.take(80) + if (memory.content.length > 80) "..." else "",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    if (curatorReasoning.isNotBlank()) {
                        Text(
                            text = "اختيار الوسيط: $curatorReasoning",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("❌ إلغاء") }

                    Button(
                        onClick = {
                            scope.launch {
                                val extraNotes = aiSettings.customSystemInstruction.trim()
                                val includeAccuracy = aiSettings.accuracyPromptEnabled

                                val memoryXml =
                                    if (memoryEnabled && includeMemory && selectedIds.isNotEmpty()) {
                                        memoryWindowBuilder.build(selectedIds = selectedIds, candidates = searchResults)
                                    } else ""

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
                                    append(displayedText)
                                }

                                onSend(finalText)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = displayedText.isNotBlank() && !isEnhancing
                    ) { Text("📤 إرسال للمنصة") }
                }
            }
        }
    }
}

private fun openExternal(context: Context, url: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: ActivityNotFoundException) {
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

            Text(
                text = message,
                style = MaterialTheme.typography.titleMedium,
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
