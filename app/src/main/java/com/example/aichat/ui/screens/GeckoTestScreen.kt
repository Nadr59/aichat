package com.example.aichat.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.aichat.AichatApp
import com.example.aichat.data.model.WebPlatform
import com.example.aichat.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequestError

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
    val scope          = rememberCoroutineScope()

    val app = context.applicationContext as AichatApp

    val runtime: GeckoRuntime? = remember {
        app.getOrCreateGeckoRuntime()
    }

    if (runtime == null) {
        GeckoUnavailableDialog(
            platformTitle = title,
            platformUrl   = url,
            onOpenBrowser = { openExternal(context, url); onBack() },
            onBack        = onBack
        )
        return
    }

    // ── الحالة ────────────────────────────────────────────────────────────
    var isLoading      by remember { mutableStateOf(true) }
    var progress       by remember { mutableIntStateOf(0) }
    var canGoBack      by remember { mutableStateOf(false) }
    var currentUrl     by remember { mutableStateOf(url) }
    var currentTitle   by remember { mutableStateOf(title) }
    var loadError      by remember { mutableStateOf<String?>(null) }
    var geckoViewRef   by remember { mutableStateOf<GeckoView?>(null) }
    var isSavingMemory by remember { mutableStateOf(false) }

    // ── Session ───────────────────────────────────────────────────────────
    val session = remember { GeckoSession() }

    // ── ربط Delegates ─────────────────────────────────────────────────────
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
                val scheme = Uri.parse(request.uri).scheme?.lowercase()
                return if (scheme in listOf("http", "https", "about", "blob", "data")) {
                    GeckoResult.allow()
                } else {
                    openExternal(context, request.uri)
                    GeckoResult.deny()
                }
            }
            override fun onLoadError(
                session: GeckoSession,
                uri:     String?,
                error:   WebRequestError
            ): GeckoResult<String>? {
                isLoading = false
                loadError = when (error.category) {
                    WebRequestError.ERROR_CATEGORY_NETWORK  -> "تعذر الاتصال بالإنترنت"
                    WebRequestError.ERROR_CATEGORY_URI      -> "الرابط غير صالح"
                    WebRequestError.ERROR_CATEGORY_SECURITY -> "مشكلة في شهادة الأمان"
                    else -> "حدث خطأ أثناء تحميل الصفحة"
                }
                return null
            }
        }
        Unit
    }

    // ── ربط callbacks الذاكرة ────────────────────────────────────────────
    DisposableEffect(platform?.id) {
        val p  = platform ?: run {
            return@DisposableEffect onDispose {}
        }
        val vm = chatViewModel ?: run {
            return@DisposableEffect onDispose {}
        }

        if (!p.memoryEnabled) return@DisposableEffect onDispose {}

        // callback التلقائي
        app.onAiResponseCaptured = { domain, text ->
            vm.onWebAiResponse(
                platformId   = p.id,
                platformName = p.name,
                text         = text
            )
            Log.d("GeckoTestScreen", "🧠 Auto: ${text.take(60)}")
        }

        // ✅ callback اليدوي — يستقبل نتيجة CAPTURE_NOW
        app.onManualCaptureResult = { success, text ->
            isSavingMemory = false
            if (success && text.isNotBlank()) {
                vm.onWebAiResponse(
                    platformId   = p.id,
                    platformName = p.name,
                    text         = text
                )
                Toast.makeText(context, "✅ تم حفظ الرد في الذاكرة", Toast.LENGTH_SHORT).show()
                Log.d("GeckoTestScreen", "🧠 Manual: ${text.take(60)}")
            } else {
                Toast.makeText(context, "⚠️ لم يُعثر على رد AI في الصفحة", Toast.LENGTH_SHORT).show()
            }
        }

        onDispose {
            app.onAiResponseCaptured  = null
            app.onManualCaptureResult = null
        }
    }

    // ── ربط MessageDelegate بالـ Session ─────────────────────────────────
    // ✅ هذا هو المفتاح — نربط الـ delegate بالـ session مباشرة
    DisposableEffect(session, platform?.id) {
        val p = platform

        if (p != null && chatViewModel != null && p.memoryEnabled) {
            session.setMessageDelegate(
                object : WebExtension.MessageDelegate {
                    override fun onMessage(
                        nativeApp: String,
                        message:   Any?,
                        sender:    WebExtension.MessageSender
                    ): GeckoResult<Any>? {
                        val map     = message as? Map<*, *> ?: return null
                        val type    = map["type"]    as? String ?: return null
                        val text    = map["text"]    as? String ?: ""
                        val success = map["success"] as? Boolean ?: false

                        when (type) {
                            "AI_RESPONSE" -> {
                                if (text.isNotBlank()) {
                                    app.onAiResponseCaptured?.invoke(
                                        map["domain"] as? String ?: "",
                                        text
                                    )
                                }
                            }
                            "CAPTURE_RESULT" -> {
                                app.onManualCaptureResult?.invoke(success, text)
                            }
                        }
                        return null
                    }
                },
                "browser" // nativeApp — يطابق browser.runtime في content.js
            )
        }

        onDispose {
            try { session.setMessageDelegate(null, "browser") } catch (_: Exception) {}
        }
    }

    // ── دالة الحفظ اليدوي — ترسل CAPTURE_NOW لـ content.js ───────────────
    val saveToMemory: () -> Unit = save@{
        if (isSavingMemory || isLoading) return@save
        if (platform == null || chatViewModel == null) return@save
        if (!platform.memoryEnabled) return@save

        isSavingMemory = true

        // ✅ نرسل رسالة للـ extension عبر loadUri بـ javascript: scheme
        // هذا يعمل في GeckoView 130
        session.loadUri(
            buildCaptureScript(),
            null,
            GeckoSession.LOAD_FLAGS_BYPASS_HISTORY
        )
    }

    // ── رجوع ذكي ─────────────────────────────────────────────────────────
    val handleBack: () -> Unit = {
        if (canGoBack) session.goBack() else onBack()
    }
    BackHandler(onBack = handleBack)

    // ── دورة حياة ────────────────────────────────────────────────────────
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP  -> session.setActive(false)
                Lifecycle.Event.ON_START -> session.setActive(true)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ── تنظيف ─────────────────────────────────────────────────────────────
    DisposableEffect(Unit) {
        onDispose {
            try {
                geckoViewRef?.releaseSession()
                session.close()
            } catch (e: Exception) {
                Log.w("GeckoTestScreen", "Cleanup: ${e.message}")
            }
        }
    }

    // ── الواجهة ───────────────────────────────────────────────────────────
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
                // ✅ زر الذاكرة — يعمل الآن
                if (platform != null && platform.memoryEnabled && chatViewModel != null) {
                    IconButton(
                        onClick = saveToMemory,
                        enabled = !isSavingMemory && !isLoading
                    ) {
                        Text(
                            text  = if (isSavingMemory) "⏳" else "🧠",
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

// ── Script يُرسل CAPTURE_NOW عبر postMessage ─────────────────────────────────
// GeckoView 130: الطريقة الموثوقة هي javascript: URI
private fun buildCaptureScript(): String = """
    javascript:(function(){
        window.dispatchEvent(
            new CustomEvent('AiChatCapture', { detail: { type: 'CAPTURE_NOW' } })
        );
    })()
""".trimIndent()


// ── Helpers ───────────────────────────────────────────────────────────────────

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
            Text("تعذر تهيئة محرك GeckoView.\n\nيمكنك فتح $platformTitle في المتصفح الخارجي.")
        },
        confirmButton   = { Button(onClick = onOpenBrowser) { Text("📱 فتح في المتصفح") } },
        dismissButton   = { OutlinedButton(onClick = onBack) { Text("رجوع") } }
    )
}
