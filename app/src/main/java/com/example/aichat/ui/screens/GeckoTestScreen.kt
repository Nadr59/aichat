// app/src/main/java/com/example/aichat/ui/screens/GeckoTestScreen.kt
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.aichat.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aichat.AichatApp
import com.example.aichat.data.model.AgentProfile
import com.example.aichat.data.model.WebPlatform
import com.example.aichat.providers.AiSettings
import com.example.aichat.repository.AgentPromptBuilder
import com.example.aichat.repository.AgentRepository
import com.example.aichat.repository.WebPlatformRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import java.util.Locale
import java.util.UUID

/**
 * Gecko web chat screen (ChatGPT/Venice/Arena).
 *
 * متوافق مع AichatApp الحالي:
 * - الإرسال للويب عبر: app.setContextPending(text, targetDomain, ..., sendWithEnter)
 * - لا يوجد "mode" في الرسالة، لذلك نعتمد قواعد:
 *   - Arena: نمنع الإرسال التلقائي (sendWithEnter=false + لا sendSelector).
 *   - ChatGPT/Venice: نسمح بالإرسال التلقائي.
 * - Web Agent مستقل (من AiSettings.webAgentId) يتم دمجه داخل block مخفي لـ ChatGPT/Venice
 *   (ثم content.js يقوم بإخفائه/إزالته بعد الإرسال عبر sanitizer).
 */
@Composable
fun GeckoTestScreen(
    webPlatformRepository: WebPlatformRepository,
    aiSettings: AiSettings,
    agentRepository: AgentRepository,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as AichatApp }
    val scope = rememberCoroutineScope()

    // Ensure runtime exists (and extension loads) using app API.
    val runtime = remember(app) { app.getOrCreateGeckoRuntime() }
    if (runtime == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text("GeckoRuntime غير جاهز.")
        }
        return
    }

    // ------- Platforms (repo compat) -------
    val platformsFlow = remember(webPlatformRepository) { platformsFlowCompat(webPlatformRepository) }
    val platforms by platformsFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    var selectedPlatformId by rememberSaveable { mutableStateOf<Long?>(null) }
    val selectedPlatform: WebPlatform? = remember(platforms, selectedPlatformId) {
        val chosen = selectedPlatformId?.let { id -> platforms.firstOrNull { it.id == id } }
        chosen ?: platforms.firstOrNull()
    }

    LaunchedEffect(platforms) {
        if (selectedPlatformId == null) {
            selectedPlatformId = platforms.firstOrNull()?.id
        } else if (selectedPlatformId != null && platforms.none { it.id == selectedPlatformId }) {
            selectedPlatformId = platforms.firstOrNull()?.id
        }
    }

    // ------- Gecko session management -------
    var sessionKey by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }

    var currentUrl by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }

    val geckoSession = remember(sessionKey) {
        GeckoSession().apply {
            open(runtime)

            progressDelegate = object : GeckoSession.ProgressDelegate {
                override fun onPageStart(session: GeckoSession, url: String) {
                    isLoading = true
                    currentUrl = url
                }

                override fun onPageStop(session: GeckoSession, success: Boolean) {
                    isLoading = false
                }
            }

            navigationDelegate = object : GeckoSession.NavigationDelegate {
                override fun onLocationChange(session: GeckoSession, url: String?) {
                    currentUrl = url
                }

                override fun onCanGoBack(session: GeckoSession, canGoBack_: Boolean) {
                    canGoBack = canGoBack_
                }

                override fun onCanGoForward(session: GeckoSession, canGoForward_: Boolean) {
                    canGoForward = canGoForward_
                }
            }
        }
    }

    DisposableEffect(geckoSession) {
        onDispose { runCatching { geckoSession.close() } }
    }

    // Load URL when platform OR session changes.
    LaunchedEffect(sessionKey, selectedPlatform?.url) {
        val url = selectedPlatform?.url?.trim().orEmpty()
        if (url.isNotEmpty()) geckoSession.loadUri(url)
    }

    // ------- Web Agent (independent) -------
    // نقرأه بـ compat حتى لا نعتمد 100% على شكل AiSettings الحالي أثناء الدمج.
    val webAgentId: String? by produceState<String?>(initialValue = null, aiSettings) {
        value = readWebAgentIdCompat(aiSettings)
    }

    var webAgent by remember { mutableStateOf<AgentProfile?>(null) }
    LaunchedEffect(webAgentId) {
        webAgent = webAgentId?.let { id ->
            runCatching { agentRepository.getById(id) }.getOrNull()
        }
    }

    val webAgentPrompt = remember(webAgent) {
        webAgent?.let { AgentPromptBuilder.build(it) }.orEmpty()
    }

    // ------- UI state -------
    var extraContext by rememberSaveable { mutableStateOf("") }
    var userText by rememberSaveable { mutableStateOf("") }

    // Manual capture result dialog
    var captureDialogOpen by rememberSaveable { mutableStateOf(false) }
    var captureText by rememberSaveable { mutableStateOf("") }
    var captureSuccess by rememberSaveable { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    // Hook callbacks (and restore on dispose)
    DisposableEffect(app) {
        val oldManual = app.onManualCaptureResult
        val oldWritten = app.onContextWritten

        app.onManualCaptureResult = { success, text, _ ->
            captureSuccess = success
            captureText = text
            captureDialogOpen = true
        }

        app.onContextWritten = { success, stage, detail ->
            scope.launch {
                snackbarHostState.showSnackbar(
                    if (success) "✅ $stage" else "❌ $stage ($detail)"
                )
            }
        }

        onDispose {
            app.onManualCaptureResult = oldManual
            app.onContextWritten = oldWritten
        }
    }

    fun stableDomain(): String {
        val fromPlatform = selectedPlatform?.url?.let(::hostFromUrl)
        val fromCurrent = currentUrl?.let(::hostFromUrl)
        return (fromPlatform ?: fromCurrent ?: "").lowercase(Locale.ROOT)
    }

    fun kind(): WebKind {
        val h = stableDomain()
        return classifyHost(h)
    }

    fun buildFinalText(): String {
        val text = userText.trim()
        val ctx = buildContextBlock(kind(), webAgentPrompt, extraContext)
        return if (ctx.isBlank()) text else "$ctx\n\n$text"
    }

    fun sendToWeb() {
        val domain = stableDomain()
        val text = userText.trim()
        if (text.isBlank()) return

        if (domain.isBlank()) {
            scope.launch { snackbarHostState.showSnackbar("تعذر تحديد الدومين الحالي.") }
            return
        }

        val finalText = buildFinalText()
        val k = kind()

        // Arena: ممنوع إرسال تلقائي لتفادي مشاكل الأمان/الاختبارات.
        val sendWithEnter = k != WebKind.ARENA

        app.setContextPending(
            text = finalText,
            targetDomain = domain,
            inputSelector = "",
            sendSelector = "",
            sendWithEnter = sendWithEnter
        )
    }

    // ------- Screen -------
    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    val label = when (kind()) {
                        WebKind.CHATGPT -> "ChatGPT"
                        WebKind.VENICE -> "Venice"
                        WebKind.ARENA -> "Arena"
                        WebKind.OTHER -> "Web"
                    }
                    Text("Gecko • $label")
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // Platform row + nav
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PlatformDropdown(
                    platforms = platforms,
                    selected = selectedPlatform,
                    onSelect = { p ->
                        selectedPlatformId = p.id
                        currentUrl = null
                        isLoading = false
                        canGoBack = false
                        canGoForward = false
                        sessionKey = UUID.randomUUID().toString()
                    },
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = { if (canGoBack) geckoSession.goBack() },
                    enabled = canGoBack
                ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }

                IconButton(
                    onClick = { if (canGoForward) geckoSession.goForward() },
                    enabled = canGoForward
                ) { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward") }

                IconButton(onClick = { geckoSession.reload() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Reload")
                }

                IconButton(
                    onClick = { app.triggerCapture() },
                    enabled = !isLoading
                ) {
                    Icon(Icons.Filled.Psychology, contentDescription = "Manual Capture")
                }
            }

            // GeckoView
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                key(sessionKey) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            GeckoView(ctx).apply { setSession(geckoSession) }
                        },
                        update = { view ->
                            if (view.session !== geckoSession) view.setSession(geckoSession)
                        }
                    )
                }

                if (isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            // Web agent line
            Text(
                text = "Web Agent: " + (webAgent?.name ?: "(بدون)"),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )

            // Extra context (optional)
            OutlinedTextField(
                value = extraContext,
                onValueChange = { extraContext = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                minLines = 2,
                maxLines = 5,
                label = { Text("Context (اختياري)") },
                supportingText = {
                    when (kind()) {
                        WebKind.ARENA -> Text("Arena: سيتم الحقن بدون إرسال تلقائي، والسياق سيكون مرئيًا (بدون sanitizer).")
                        WebKind.CHATGPT, WebKind.VENICE -> Text("ChatGPT/Venice: سيتم حقن السياق بعلامات مخفية ليقوم content.js بإخفائه بعد الإرسال.")
                        WebKind.OTHER -> Text("قد تختلف سلوكيات الحقن حسب المنصة.")
                    }
                }
            )

            // Message + Send
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = userText,
                    onValueChange = { userText = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("رسالتك") },
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (!isLoading) sendToWeb()
                        }
                    )
                )

                Button(
                    onClick = { sendToWeb() },
                    enabled = userText.isNotBlank() && !isLoading
                ) {
                    Text("Send")
                }
            }

            // Debug log (آخر سطور)
            DebugLogPanel(
                lines = app.debugLog,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 0.dp, max = 160.dp)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }

    if (captureDialogOpen) {
        AlertDialog(
            onDismissRequest = { captureDialogOpen = false },
            confirmButton = {
                TextButton(onClick = { captureDialogOpen = false }) { Text("إغلاق") }
            },
            title = { Text(if (captureSuccess) "Manual Capture ✅" else "Manual Capture ❌") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(captureText.ifBlank { "(فارغ)" })
                }
            }
        )
    }
}

/* -------------------------------- UI bits -------------------------------- */

@Composable
private fun PlatformDropdown(
    platforms: List<WebPlatform>,
    selected: WebPlatform?,
    onSelect: (WebPlatform) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selected?.name ?: "اختر منصة",
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            platforms.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.name) },
                    onClick = {
                        expanded = false
                        onSelect(p)
                    }
                )
            }
        }
    }
}

@Composable
private fun DebugLogPanel(
    lines: List<String>,
    modifier: Modifier = Modifier,
) {
    if (lines.isEmpty()) return

    Surface(
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
    ) {
        LazyColumn(
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(lines.take(12)) { line ->
                Text(line, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/* ------------------------------- Context rules ------------------------------- */

private enum class WebKind { CHATGPT, VENICE, ARENA, OTHER }

/**
 * يجب أن تطابق هذه العلامات ما يعتمد عليه content.js لإزالة/إخفاء السياق بعد الإرسال.
 */
private const val AICHAT_CTX_BEGIN = "<<<AICHAT_HIDDEN_CONTEXT_BEGIN>>>"
private const val AICHAT_CTX_END = "<<<AICHAT_HIDDEN_CONTEXT_END>>>"

private fun buildContextBlock(
    kind: WebKind,
    agentPrompt: String,
    extraContext: String,
): String {
    val agent = agentPrompt.trim()
    val extra = extraContext.trim()

    val merged = buildString {
        if (agent.isNotBlank()) {
            appendLine("## Agent Persona")
            appendLine(agent)
        }
        if (extra.isNotBlank()) {
            if (isNotEmpty()) appendLine()
            appendLine("## Context / Memory")
            appendLine(extra)
        }
    }.trim()

    if (merged.isBlank()) return ""

    return when (kind) {
        WebKind.CHATGPT, WebKind.VENICE -> "$AICHAT_CTX_BEGIN\n$merged\n$AICHAT_CTX_END"
        WebKind.ARENA -> "### Context (visible)\n$merged\n### End Context"
        WebKind.OTHER -> merged
    }
}

private fun classifyHost(host: String): WebKind {
    val h = host.lowercase(Locale.ROOT)
    return when {
        h.contains("chatgpt.com") || h.contains("openai.com") -> WebKind.CHATGPT
        h.contains("venice.ai") -> WebKind.VENICE
        h.contains("lmsys") || h.contains("arena") -> WebKind.ARENA
        else -> WebKind.OTHER
    }
}

private fun hostFromUrl(url: String): String? =
    runCatching { Uri.parse(url).host }.getOrNull()

/* -------------------------- Repo/settings compat helpers -------------------------- */

@Suppress("UNCHECKED_CAST")
private fun platformsFlowCompat(repo: WebPlatformRepository): Flow<List<WebPlatform>> {
    // Preferred: property platformsFlow: Flow<List<WebPlatform>>
    runCatching {
        val f = repo.javaClass.methods.firstOrNull { it.name == "getPlatformsFlow" && it.parameterTypes.isEmpty() }
        if (f != null) return f.invoke(repo) as Flow<List<WebPlatform>>
    }

    runCatching {
        val prop = repo.javaClass.methods.firstOrNull { it.name == "getPlatformsFlow" && it.parameterTypes.isEmpty() }
        if (prop != null) return prop.invoke(repo) as Flow<List<WebPlatform>>
    }

    runCatching {
        val m = repo.javaClass.methods.firstOrNull { it.name == "platformsFlow" && it.parameterTypes.isEmpty() }
        if (m != null) return m.invoke(repo) as Flow<List<WebPlatform>>
    }

    runCatching {
        val field = repo.javaClass.declaredFields.firstOrNull { it.name == "platformsFlow" }
        if (field != null) {
            field.isAccessible = true
            return field.get(repo) as Flow<List<WebPlatform>>
        }
    }

    // Worst-case fallback
    return flowOf(emptyList())
}

private fun readWebAgentIdCompat(aiSettings: AiSettings): String? {
    // Try common getters
    val getters = listOf("getWebAgentId", "webAgentId")
    for (g in getters) {
        val v = runCatching {
            val m = aiSettings.javaClass.methods.firstOrNull { it.name == g && it.parameterTypes.isEmpty() }
            m?.invoke(aiSettings) as? String
        }.getOrNull()
        if (!v.isNullOrBlank()) return v
    }

    // Try field
    val v = runCatching {
        val f = aiSettings.javaClass.declaredFields.firstOrNull { it.name == "webAgentId" } ?: return@runCatching null
        f.isAccessible = true
        f.get(aiSettings) as? String
    }.getOrNull()

    return v?.takeIf { it.isNotBlank() }
}
