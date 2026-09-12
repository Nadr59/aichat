package com.example.aichat.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(
    platform: String,
    onBack: () -> Unit
) {
    val platformInfo = webPlatforms[platform] ?: webPlatforms["huggingchat"]!!
    var isLoading by remember { mutableStateOf(true) }
    var progress by remember { mutableIntStateOf(0) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {

        TopAppBar(
            title = { Text(platformInfo.title) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "رجوع")
                }
            },
            actions = {
                IconButton(onClick = { webViewRef?.reload() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "تحديث")
                }
            }
        )

        Box(modifier = Modifier.fillMaxSize()) {

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->

                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)

                    WebView(context).apply {

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            cacheMode = WebSettings.LOAD_DEFAULT
                            userAgentString =
                                "Mozilla/5.0 (Linux; Android 10; Redmi 8 Build/QKQ1.191014.001) " +
                                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                "Chrome/124.0.6367.82 Mobile Safari/537.36"
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            defaultTextEncodingName = "UTF-8"
                            loadsImagesAutomatically = true
                            javaScriptCanOpenWindowsAutomatically = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
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
                        }

                        webViewClient = object : WebViewClient() {

                            override fun onPageStarted(
                                view: WebView?,
                                url: String?,
                                favicon: Bitmap?
                            ) {
                                isLoading = true
                            }

                            override fun onPageFinished(
                                view: WebView?,
                                url: String?
                            ) {
                                isLoading = false
                                CookieManager.getInstance().flush()
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

            if (isLoading) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .align(Alignment.TopCenter)
                )
            }
        }
    }
}

data class WebPlatformInfo(
    val title: String,
    val url: String
)

val webPlatforms = mapOf(
    "huggingchat" to WebPlatformInfo(
        title = "HuggingChat 🤗",
        url = "https://huggingface.co/chat"
    ),
    "you" to WebPlatformInfo(
        title = "You.com",
        url = "https://you.com"
    ),
    "poe" to WebPlatformInfo(
        title = "Poe",
        url = "https://poe.com"
    ),
    "gemini_web" to WebPlatformInfo(
        title = "Gemini Web",
        url = "https://gemini.google.com"
    ),
    "copilot" to WebPlatformInfo(
        title = "Copilot",
        url = "https://copilot.microsoft.com"
    )
)
