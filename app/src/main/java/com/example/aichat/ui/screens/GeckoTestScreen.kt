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
import androidx.compose.foundation.layout.*
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.aichat.AichatApp
import com.example.aichat.data.local.SystemPrompt
import com.example.aichat.data.model.WebPlatform
import com.example.aichat.repository.MemoryContextBuilder
import com.example.aichat.ui.viewmodel.ChatViewModel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    var isLoading       by remember { mutableStateOf(true) }
    var progress        by remember { mutableIntStateOf(0) }
    var canGoBack       by remember { mutableStateOf(false) }
    var currentUrl      by remember { mutableStateOf(url) }
    var currentTitle    by remember { mutableStateOf(title) }
    var loadError       by remember { mutableStateOf<String?>(null) }
    var geckoViewRef    by remember { mutableStateOf<GeckoView?>(null) }
    var isSavingMemory  by remember { mutableStateOf(false) }
    var isSendingCtx    by remember { mutableStateOf(false) }
    var timeoutRunnable by remember { mutableStateOf<Runnable?>(null) }



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
                return if (scheme in listOf("http","https","about","blob","data")) {
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
    

                
// زر 📤 البسيط
val sendContext: () -> Unit = ctx@{
    if (isLoading) return@ctx
    if (platform == null || !platform.memoryEnabled) return@ctx
    if (chatViewModel == null) return@ctx

    Toast.makeText(context, "📤 جاري تحضير السياق...", Toast.LENGTH_SHORT).show()

    CoroutineScope(Dispatchers.IO).launch {
        try {
            val memories = chatViewModel.getSharedMemories()
            mainHandler.post {
                app.sendContextToPage(memories = memories)
            }
        } catch (e: Exception) {
            mainHandler.post {
                Toast.makeText(context, "❌ ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
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
        modifier         = Modifier.fillMaxSize().padding(24.dp),
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
        title = { Text("⚠️ GeckoView غير متاح") },
        text  = {
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
