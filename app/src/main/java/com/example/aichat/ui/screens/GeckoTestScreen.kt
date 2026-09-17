package com.example.aichat.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeckoTestScreen(
    platform: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val platformInfo = webPlatforms[platform] ?: webPlatforms["huggingchat"]!!

    val runtime = remember {
        (context.applicationContext as AichatApp).getOrCreateGeckoRuntime()
    }

    if (runtime == null) {
        GeckoUnavailableDialog(
            platformInfo = platformInfo,
            onOpenBrowser = {
                openExternal(context, platformInfo.url)
                onBack()
            },
            onBack = onBack
        )
        return
    }

    // ---------- الحالة ----------
    var isLoading by remember { mutableStateOf(true) }
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var currentUrl by remember { mutableStateOf(platformInfo.url) }
    var currentTitle by remember { mutableStateOf(platformInfo.title) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var geckoViewRef by remember { mutableStateOf<GeckoView?>(null) }

    // ---------- الجلسة ----------
    val session = remember {
        GeckoSession().also { s ->

            s.progressDelegate = object : GeckoSession.ProgressDelegate {
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

            s.contentDelegate = object : GeckoSession.ContentDelegate {
                override fun onTitleChange(session: GeckoSession, title: String?) {
                    if (!title.isNullOrBlank()) currentTitle = title.take(50)
                }
            }

            s.navigationDelegate = object : GeckoSession.NavigationDelegate {
                // ✅ يحل مشكلة حبس المستخدم في الشاشة
                override fun onCanGoBack(session: GeckoSession, canGoBack_: Boolean) {
                    canGoBack = canGoBack_
                }

                // ✅ الروابط غير http/https تُفتح خارجياً
                override fun onLoadRequest(
                    session: GeckoSession,
                    request: GeckoSession.NavigationDelegate.LoadRequest
                ): GeckoResult<AllowOrDeny>? {
                    val scheme = Uri.parse(request.uri).scheme?.lowercase()
                    return if (scheme == "http" || scheme == "https" ||
                        scheme == "about" || scheme == "blob" || scheme == "data"
                    ) {
                        GeckoResult.allow()
                    } else {
                        openExternal(context, request.uri)
                        GeckoResult.deny()
                    }
                }

                // ✅ معالجة أخطاء التحميل
                override fun onLoadError(
                    session: GeckoSession,
                    uri: String?,
                    error: WebRequestError
                ): GeckoResult<String>? {
                    isLoading = false
                    loadError = when (error.category) {
                        WebRequestError.ERROR_CATEGORY_NETWORK ->
                            "تعذر الاتصال بالإنترنت"
                        WebRequestError.ERROR_CATEGORY_URI ->
                            "الرابط غير صالح"
                        WebRequestError.ERROR_CATEGORY_SECURITY ->
                            "مشكلة في شهادة الأمان"
                        else -> "حدث خطأ أثناء تحميل الصفحة"
                    }
                    return null
                }
            }
        }
    }

    // ✅ رجوع ذكي: داخل الصفحة أولاً ثم الخروج من الشاشة
    val handleBack: () -> Unit = {
        if (canGoBack) session.goBack() else onBack()
    }
    BackHandler(onBack = handleBack)

    // ✅ إيقاف الجلسة في الخلفية لتوفير البطارية
    DisposableEffect(lifecycleOwner, session) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> session.setActive(false)
                Lifecycle.Event.ON_START -> session.setActive(true)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ✅ تنظيف الموارد
    DisposableEffect(session) {
        onDispose {
            geckoViewRef?.releaseSession()
            session.close()
        }
    }

    // ---------- الواجهة ----------
    Column(modifier = Modifier.fillMaxSize()) {

        TopAppBar(
            title = {
                Column {
                    Text(
                        text = platformInfo.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (currentTitle != platformInfo.title && currentTitle.isNotBlank()) {
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
                IconButton(onClick = handleBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع")
                }
            },
            actions = {
                IconButton(onClick = { session.reload() }) {
                    Icon(Icons.Filled.Refresh, "تحديث")
                }
                // ✅ يفتح الصفحة الحالية وليس صفحة البداية
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
                factory = { ctx ->
                    GeckoView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        if (!session.isOpen) {
                            session.open(runtime)
                            session.loadUri(platformInfo.url)
                        }
                        setSession(session)
                    }.also { geckoViewRef = it }
                }
            )

            // ✅ شريط تقدم حقيقي
            if (isLoading) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .align(Alignment.TopCenter)
                )
            }

            // ✅ شاشة خطأ مع إعادة المحاولة
            loadError?.let { message ->
                LoadErrorView(
                    message = message,
                    onRetry = { session.reload() },
                    onOpenBrowser = { openExternal(context, currentUrl) }
                )
            }
        }
    }
}

// ============================================================
// مساعدات
// ============================================================

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
    message: String,
    onRetry: () -> Unit,
    onOpenBrowser: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "⚠️ $message",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("إعادة المحاولة") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenBrowser) { Text("فتح في المتصفح") }
    }
}

@Composable
private fun GeckoUnavailableDialog(
    platformInfo: WebPlatformInfo,
    onOpenBrowser: () -> Unit,
    onBack: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onBack,
        title = { Text("⚠️ GeckoView غير متاح") },
        text = {
            Text(
                "تعذر تهيئة محرك GeckoView على هذا الجهاز.\n\n" +
                    "يمكنك فتح ${platformInfo.title} في المتصفح الخارجي."
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
