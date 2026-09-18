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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
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
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeckoTestScreen(
    url: String,
    title: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as AichatApp

    // ── انتظر حتى يصبح GeckoRuntime جاهزاً ──────────────────────────────
    var runtime by remember { mutableStateOf<GeckoRuntime?>(app.geckoRuntime) }
    var isWaiting by remember { mutableStateOf(app.geckoRuntime == null) }

    LaunchedEffect(Unit) {
        if (app.geckoRuntime == null) {
            var attempts = 0
            while (app.geckoRuntime == null && attempts < 20) {
                kotlinx.coroutines.delay(500)
                attempts++
            }
        }
        runtime = app.geckoRuntime
        isWaiting = false
    }

    // ── شاشة انتظار ───────────────────────────────────────────────────────
    if (isWaiting) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator()
                Text(
                    text = "جاري تهيئة المتصفح...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    // ── Runtime غير متاح نهائياً ──────────────────────────────────────────
    if (runtime == null) {
        GeckoUnavailableDialog(
            platformTitle = title,
            platformUrl   = url,
            onOpenBrowser = {
                openExternal(context, url)
                onBack()
            },
            onBack = onBack
        )
        return
    }

    // ── الحالة ────────────────────────────────────────────────────────────
    val lifecycleOwner = LocalLifecycleOwner.current

    var isLoading    by remember { mutableStateOf(true) }
    var progress     by remember { mutableIntStateOf(0) }
    var canGoBack    by remember { mutableStateOf(false) }
    var currentUrl   by remember { mutableStateOf(url) }
    var currentTitle by remember { mutableStateOf(title) }
    var loadError    by remember { mutableStateOf<String?>(null) }
    var geckoViewRef by remember { mutableStateOf<GeckoView?>(null) }

    // ── الجلسة ────────────────────────────────────────────────────────────
    val session = remember {
        GeckoSession().also { s ->

            s.progressDelegate = object : GeckoSession.ProgressDelegate {
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

            s.contentDelegate = object : GeckoSession.ContentDelegate {
                override fun onTitleChange(session: GeckoSession, title: String?) {
                    if (!title.isNullOrBlank()) currentTitle = title.take(50)
                }
            }

            s.navigationDelegate = object : GeckoSession.NavigationDelegate {
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
                    uri: String?,
                    error: WebRequestError
                ): GeckoResult<String>? {
                    isLoading = false
                    loadError = when (error.category) {
                        WebRequestError.ERROR_CATEGORY_NETWORK  ->
                            "تعذر الاتصال بالإنترنت"
                        WebRequestError.ERROR_CATEGORY_URI      ->
                            "الرابط غير صالح"
                        WebRequestError.ERROR_CATEGORY_SECURITY ->
                            "مشكلة في شهادة الأمان"
                        else ->
                            "حدث خطأ أثناء تحميل الصفحة"
                    }
                    return null
                }
            }
        }
    }

    // ── رجوع ذكي ─────────────────────────────────────────────────────────
    val handleBack: () -> Unit = {
        if (canGoBack) session.goBack() else onBack()
    }
    BackHandler(onBack = handleBack)

    // ── دورة حياة الجلسة ─────────────────────────────────────────────────
    DisposableEffect(lifecycleOwner, session) {
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

    // ── تنظيف الموارد ─────────────────────────────────────────────────────
    DisposableEffect(session) {
        onDispose {
            try {
                geckoViewRef?.releaseSession()
                session.close()
            } catch (e: Exception) {
                android.util.Log.w("GeckoTestScreen", "Cleanup error: ${e.message}")
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
                    Icon(
                        imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "رجوع"
                    )
                }
            },
            actions = {
                IconButton(onClick = { session.reload() }) {
                    Icon(
                        imageVector        = Icons.Filled.Refresh,
                        contentDescription = "تحديث"
                    )
                }
                IconButton(onClick = { openExternal(context, currentUrl) }) {
                    Icon(
                        imageVector        = Icons.Filled.OpenInBrowser,
                        contentDescription = "فتح في المتصفح"
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )

        Box(modifier = Modifier.fillMaxSize()) {

            // ── GeckoView ─────────────────────────────────────────────────
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory  = { ctx ->
                    GeckoView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        // runtime مضمون غير null هنا (تحقق سابق)
                        if (!session.isOpen) {
                            session.open(runtime!!)
                            session.loadUri(url)
                        }
                        setSession(session)
                    }.also { geckoViewRef = it }
                }
            )

            // ── شريط التقدم ───────────────────────────────────────────────
            if (isLoading) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .align(Alignment.TopCenter)
                )
            }

            // ── شاشة الخطأ ────────────────────────────────────────────────
            loadError?.let { message ->
                LoadErrorView(
                    message       = message,
                    onRetry       = {
                        loadError = null
                        session.reload()
                    },
                    onOpenBrowser = { openExternal(context, currentUrl) }
                )
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun openExternal(context: Context, url: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(
            context,
            "لا يوجد تطبيق لفتح هذا الرابط",
            Toast.LENGTH_SHORT
        ).show()
    }
}

// ── Composables مساعدة ────────────────────────────────────────────────────────

@Composable
private fun LoadErrorView(
    message:       String,
    onRetry:       () -> Unit,
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
            Text(
                text      = "⚠️",
                style     = MaterialTheme.typography.displaySmall
            )
            Text(
                text      = message,
                style     = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                color     = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Button(onClick = onRetry) {
                Text("إعادة المحاولة")
            }
            OutlinedButton(onClick = onOpenBrowser) {
                Text("فتح في المتصفح الخارجي")
            }
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
        title = {
            Text("⚠️ GeckoView غير متاح")
        },
        text = {
            Text(
                "تعذر تهيئة محرك GeckoView على هذا الجهاز.\n\n" +
                "يمكنك فتح $platformTitle في المتصفح الخارجي.\n\n" +
                platformUrl
            )
        },
        confirmButton = {
            Button(onClick = onOpenBrowser) {
                Text("📱 فتح في المتصفح")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onBack) {
                Text("رجوع")
            }
        }
    )
}
