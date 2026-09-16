package com.example.aichat.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

// ============================================================
// الشاشة الرئيسية
// ============================================================

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(
    platform: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val platformInfo = webPlatforms[platform] ?: webPlatforms["huggingchat"]!!

    // null = لم يُختر محرك بعد
    // "webview" = Android WebView
    // "gecko" = GeckoView
    // "browser" = متصفح خارجي
    var selectedEngine by remember { mutableStateOf<String?>(null) }

    // ============================================================
    // Dialog اختيار المحرك
    // ============================================================

    if (selectedEngine == null) {
        EngineSelectionDialog(
            platformInfo = platformInfo,
            onSelectWebView = { selectedEngine = "webview" },
            onSelectGecko = { selectedEngine = "gecko" },
            onSelectBrowser = {
                // فتح في المتصفح الخارجي مباشرة
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(platformInfo.url))
                context.startActivity(intent)
                onBack()
            },
            onDismiss = onBack
        )
        return
    }

    // ============================================================
    // GeckoView
    // ============================================================

    if (selectedEngine == "gecko") {
        GeckoTestScreen(
            platform = platform,
            onBack = onBack
        )
        return
    }

    // ============================================================
    // فتح في المتصفح الخارجي (احتياطي)
    // ============================================================

    if (selectedEngine == "browser") {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(platformInfo.url))
        context.startActivity(intent)
        onBack()
        return
    }

    // ============================================================
    // Android WebView
    // ============================================================

    var isLoading by remember { mutableStateOf(true) }
    var progress by remember { mutableIntStateOf(0) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var currentTitle by remember { mutableStateOf(platformInfo.title) }
    var loadError by remember { mutableStateOf(false) }

    // ✅ BackHandler: رجوع في التاريخ أولاً
    BackHandler {
        if (webViewRef?.canGoBack() == true) {
            webViewRef?.goBack()
        } else {
            onBack()
        }
    }

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
                    // ✅ عنوان الصفحة الحالية
                    if (currentTitle.isNotBlank() && currentTitle != platformInfo.title) {
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
                IconButton(onClick = {
                    if (webViewRef?.canGoBack() == true) {
                        webViewRef?.goBack()
                    } else {
                        onBack()
                    }
                }) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "رجوع")
                }
            },
            actions = {
                // ✅ زر للأمام
                if (canGoForward) {
                    IconButton(onClick = { webViewRef?.goForward() }) {
                        Icon(Icons.Filled.ArrowForward, contentDescription = "للأمام")
                    }
                }

                // زر تحديث
                IconButton(onClick = { webViewRef?.reload() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "تحديث")
                }

                // ✅ فتح في المتصفح الخارجي
                IconButton(onClick = {
                    val url = webViewRef?.url ?: platformInfo.url
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    context.startActivity(intent)
                }) {
                    Icon(Icons.Filled.OpenInBrowser, contentDescription = "فتح في المتصفح")
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

                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)

                    WebView(ctx).apply {

                        setLayerType(View.LAYER_TYPE_HARDWARE, null)

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            cacheMode = WebSettings.LOAD_DEFAULT

                            // ✅ UserAgent: Chrome Desktop لتجاوز حجب Mobile
                            userAgentString =
                                "Mozilla/5.0 (Linux; Android 10; Redmi 8 Build/QKQ1.191014.001) " +
                                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                "Chrome/152.0.7977.87 Mobile Safari/537.36"

                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            defaultTextEncodingName = "UTF-8"
                            loadsImagesAutomatically = true
                            javaScriptCanOpenWindowsAutomatically = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

                            @Suppress("DEPRECATION")
                            setRenderPriority(WebSettings.RenderPriority.HIGH)

                            mediaPlaybackRequiresUserGesture = false

                            // ✅ السماح بالوصول للملفات
                            allowFileAccess = true
                            allowContentAccess = true
                        }

                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(
                                view: WebView?,
                                newProgress: Int
                            ) {
                                progress = newProgress
                                isLoading = newProgress < 100
                            }

                            // ✅ عنوان الصفحة
                            override fun onReceivedTitle(
                                view: WebView?,
                                title: String?
                            ) {
                                if (!title.isNullOrBlank()) {
                                    currentTitle = title.take(60)
                                }
                            }
                        }

                        webViewClient = object : WebViewClient() {

                            override fun onPageStarted(
                                view: WebView?,
                                url: String?,
                                favicon: Bitmap?
                            ) {
                                isLoading = true
                                loadError = false
                                canGoBack = view?.canGoBack() ?: false
                                canGoForward = view?.canGoForward() ?: false

                                // ✅ إخفاء علامات WebView عن المواقع
                                view?.evaluateJavascript(
                                    """
                                    (function() {
                                        try {
                                            // إخفاء webdriver
                                            Object.defineProperty(
                                                navigator, 'webdriver',
                                                { get: () => undefined }
                                            );

                                            // محاكاة Chrome حقيقي
                                            window.chrome = {
                                                runtime: {},
                                                loadTimes: function() {},
                                                csi: function() {},
                                                app: {}
                                            };

                                            // إزالة علامة wv من userAgent
                                            Object.defineProperty(
                                                navigator, 'userAgent',
                                                {
                                                    get: () => navigator.userAgent
                                                        .replace('; wv)', ')')
                                                        .replace('wv', '')
                                                }
                                            );

                                            // plugins وهمية
                                            Object.defineProperty(
                                                navigator, 'plugins',
                                                { get: () => [1, 2, 3] }
                                            );

                                            // language
                                            Object.defineProperty(
                                                navigator, 'language',
                                                { get: () => 'ar-SA' }
                                            );

                                        } catch(e) {
                                            console.log('stealth error:', e);
                                        }
                                    })();
                                    """.trimIndent(),
                                    null
                                )
                            }

                            override fun onPageFinished(
                                view: WebView?,
                                url: String?
                            ) {
                                isLoading = false
                                CookieManager.getInstance().flush()
                                canGoBack = view?.canGoBack() ?: false
                                canGoForward = view?.canGoForward() ?: false
                            }

                            // ✅ معالجة أخطاء التحميل
                            @Suppress("DEPRECATION")
                            override fun onReceivedError(
                                view: WebView?,
                                errorCode: Int,
                                description: String?,
                                failingUrl: String?
                            ) {
                                android.util.Log.e(
                                    "WebScreen",
                                    "Error $errorCode: $description | URL: $failingUrl"
                                )
                                // فقط أظهر الخطأ للصفحة الرئيسية
                                if (failingUrl == platformInfo.url) {
                                    loadError = true
                                    isLoading = false
                                }
                            }
                        }
                    }
                },
                update = { view ->
                    webViewRef = view
                    if (view.url == null) {
                        view.loadUrl(platformInfo.url)
                    }
                }
            )

            // شريط التحميل
            if (isLoading) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .align(Alignment.TopCenter)
                )
            }

            // ✅ رسالة الخطأ مع بدائل
            if (loadError) {
                LoadErrorView(
                    platformInfo = platformInfo,
                    onRetryWebView = {
                        loadError = false
                        webViewRef?.reload()
                    },
                    onSwitchToGecko = {
                        selectedEngine = "gecko"
                    },
                    onOpenInBrowser = {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(platformInfo.url)
                        )
                        context.startActivity(intent)
                    }
                )
            }
        }
    }
}

// ============================================================
// Dialog اختيار المحرك - مُحسَّن
// ============================================================

@Composable
private fun EngineSelectionDialog(
    platformInfo: WebPlatformInfo,
    onSelectWebView: () -> Unit,
    onSelectGecko: () -> Unit,
    onSelectBrowser: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "فتح ${platformInfo.title}",
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column {
                Text(
                    text = "اختر طريقة الفتح:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.size(16.dp))

                // GeckoView - الموصى به
                Button(
                    onClick = onSelectGecko,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🦊 GeckoView (موصى به)")
                }

                Text(
                    text = "محرك Firefox - يفتح معظم المواقع",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = 8.dp,
                        bottom = 8.dp
                    )
                )

                // Android WebView
                OutlinedButton(
                    onClick = onSelectWebView,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🌐 Android WebView")
                }

                Text(
                    text = "أخف - قد لا يفتح بعض المواقع",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = 8.dp,
                        bottom = 8.dp
                    )
                )

                // متصفح خارجي
                OutlinedButton(
                    onClick = onSelectBrowser,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text("📱 فتح في المتصفح الخارجي")
                }

                Text(
                    text = "Chrome/Firefox على جهازك",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}

// ============================================================
// واجهة الخطأ مع بدائل
// ============================================================

@Composable
private fun LoadErrorView(
    platformInfo: WebPlatformInfo,
    onRetryWebView: () -> Unit,
    onSwitchToGecko: () -> Unit,
    onOpenInBrowser: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "⚠️ تعذر فتح الصفحة",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(Modifier.size(8.dp))

            Text(
                text = "لم يتمكن Android WebView من فتح ${platformInfo.title}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.size(24.dp))

            Button(
                onClick = onSwitchToGecko,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("🦊 جرب GeckoView")
            }

            Spacer(Modifier.size(8.dp))

            OutlinedButton(
                onClick = onOpenInBrowser,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("📱 فتح في المتصفح الخارجي")
            }

            Spacer(Modifier.size(8.dp))

            TextButton(
                onClick = onRetryWebView,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("🔄 إعادة المحاولة")
            }
        }
    }
}

// ============================================================
// منصات الويب
// ============================================================

data class WebPlatformInfo(
    val title: String,
    val url: String,
    val description: String = ""
)

val webPlatforms = mapOf(

    // ✅ مجانية بدون تسجيل
    "huggingchat" to WebPlatformInfo(
        title = "HuggingChat 🤗",
        url = "https://huggingface.co/chat",
        description = "مجاني - Llama, Mistral, Qwen"
    ),
    "venice" to WebPlatformInfo(
        title = "Venice AI 🔒",
        url = "https://venice.ai/chat",
        description = "خصوصية كاملة - مجاني جزئياً"
    ),
    "you" to WebPlatformInfo(
        title = "You.com 🔍",
        url = "https://you.com",
        description = "بحث + AI مجاناً"
    ),
    "perplexity" to WebPlatformInfo(
        title = "Perplexity 🔵",
        url = "https://www.perplexity.ai",
        description = "بحث ذكي - مجاني"
    ),

    // ✅ تحتاج حساب مجاني
    "gemini_web" to WebPlatformInfo(
        title = "Gemini 🟦",
        url = "https://gemini.google.com",
        description = "Google - حساب مجاني"
    ),
    "copilot" to WebPlatformInfo(
        title = "Copilot 🪟",
        url = "https://copilot.microsoft.com",
        description = "Microsoft - مجاني"
    ),
    "chatgpt" to WebPlatformInfo(
        title = "ChatGPT 🟢",
        url = "https://chatgpt.com",
        description = "OpenAI - حساب مجاني"
    ),
    "claude" to WebPlatformInfo(
        title = "Claude 🟠",
        url = "https://claude.ai",
        description = "Anthropic - حساب مجاني"
    ),
    "grok" to WebPlatformInfo(
        title = "Grok ✖️",
        url = "https://grok.com",
        description = "xAI - مجاني"
    ),
    "mistral_web" to WebPlatformInfo(
        title = "Le Chat 🇫🇷",
        url = "https://chat.mistral.ai",
        description = "Mistral - مجاني"
    )
)
