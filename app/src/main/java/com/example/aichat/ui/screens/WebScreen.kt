package com.example.aichat.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.aichat.AichatApp
import com.example.aichat.data.model.WebEngine
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(
    url:           String,
    title:         String     = "متصفح",
    initialEngine: WebEngine  = WebEngine.GECKO,
    onNavigateUp:  () -> Unit = {}
) {
    val context       = LocalContext.current
    var currentEngine by remember { mutableStateOf(initialEngine) }
    var isLoading     by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "رجوع")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        currentEngine = when (currentEngine) {
                            WebEngine.GECKO    -> WebEngine.WEBVIEW
                            WebEngine.WEBVIEW  -> WebEngine.GECKO
                            WebEngine.EXTERNAL -> WebEngine.GECKO
                        }
                    }) {
                        Text(
                            when (currentEngine) {
                                WebEngine.GECKO    -> "Gecko 🦊"
                                WebEngine.WEBVIEW  -> "WebView 🌐"
                                WebEngine.EXTERNAL -> "خارجي 🔗"
                            }
                        )
                    }
                    IconButton(onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        )
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "فتح في متصفح خارجي")
                    }
                }
            )
        }
    ) { padding ->

        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            when (currentEngine) {

                WebEngine.GECKO -> {
                    GeckoWebView(
                        url       = url,
                        onLoading = { isLoading = it }
                    )
                }

                WebEngine.WEBVIEW -> {
                    AndroidWebView(
                        url       = url,
                        onLoading = { isLoading = it }
                    )
                }

                WebEngine.EXTERNAL -> {
                    LaunchedEffect(Unit) {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        )
                        onNavigateUp()
                    }
                }
            }

            if (isLoading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
}

// ── GeckoWebView ──────────────────────────────────────────────────────────────

@Composable
private fun GeckoWebView(
    url:       String,
    onLoading: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val app     = context.applicationContext as AichatApp

    // ── انتظر حتى يصبح GeckoRuntime جاهزاً ──────────────────────────────
    var runtime   by remember { mutableStateOf<GeckoRuntime?>(app.geckoRuntime) }
    var isWaiting by remember { mutableStateOf(app.geckoRuntime == null) }

    LaunchedEffect(Unit) {
        if (app.geckoRuntime == null) {
            var attempts = 0
            while (app.geckoRuntime == null && attempts < 20) {
                kotlinx.coroutines.delay(500)
                attempts++
            }
        }
        runtime   = app.geckoRuntime
        isWaiting = false
    }

    // ── انتظار تهيئة Runtime ──────────────────────────────────────────────
    if (isWaiting) {
        Box(
            modifier         = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator()
                Text(
                    text  = "جاري تهيئة المتصفح...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    // ── Runtime غير متاح → fallback إلى WebView ──────────────────────────
    if (runtime == null) {
        AndroidWebView(url = url, onLoading = onLoading)
        return
    }

    // ── GeckoView (runtime مضمون غير null هنا) ───────────────────────────
    val safeRuntime = runtime!!

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory  = { ctx ->
            GeckoView(ctx).apply {
                val session = GeckoSession()

                session.progressDelegate = object : GeckoSession.ProgressDelegate {
                    override fun onPageStart(session: GeckoSession, url: String) {
                        onLoading(true)
                    }
                    override fun onPageStop(session: GeckoSession, success: Boolean) {
                        onLoading(false)
                    }
                }

                session.open(safeRuntime)
                setSession(session)
                session.loadUri(url)
            }
        }
    )
}

// ── AndroidWebView ────────────────────────────────────────────────────────────

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun AndroidWebView(
    url:       String,
    onLoading: (Boolean) -> Unit
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory  = { ctx ->
            WebView(ctx).apply {
                settings.apply {
                    javaScriptEnabled   = true
                    domStorageEnabled   = true
                    userAgentString     =
                        "Mozilla/5.0 (Linux; Android 10; Redmi 8) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/152.0.0.0 Mobile Safari/537.36"
                    mixedContentMode    = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(
                        view:    WebView?,
                        url:     String?,
                        favicon: android.graphics.Bitmap?
                    ) {
                        onLoading(true)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        evaluateJavascript(
                            "Object.defineProperty(navigator,'webdriver',{get:()=>undefined});",
                            null
                        )
                        onLoading(false)
                    }
                }

                webChromeClient = WebChromeClient()
                loadUrl(url)
            }
        }
    )
}
