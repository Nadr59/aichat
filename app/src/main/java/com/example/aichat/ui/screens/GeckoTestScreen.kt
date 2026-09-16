package com.example.aichat.ui.screens

import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import com.example.aichat.AichatApp
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeckoTestScreen(
    platform: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val platformInfo = webPlatforms[platform]
        ?: webPlatforms["huggingchat"]!!

    // ✅ Lazy - ينشئ فقط عند الحاجة
    val runtime = remember {
        (context.applicationContext as AichatApp)
            .getOrCreateGeckoRuntime()
    }

    // ✅ إذا فشل GeckoRuntime - افتح في متصفح خارجي
    if (runtime == null) {
        GeckoUnavailableDialog(
            platformInfo = platformInfo,
            onOpenBrowser = {
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(platformInfo.url)
                )
                context.startActivity(intent)
                onBack()
            },
            onBack = onBack
        )
        return
    }

    var isLoading by remember { mutableStateOf(true) }
    var currentTitle by remember { mutableStateOf(platformInfo.title) }
    var geckoViewRef by remember { mutableStateOf<GeckoView?>(null) }

    val session = remember {
        GeckoSession().also { session ->

            session.progressDelegate =
                object : GeckoSession.ProgressDelegate {
                    override fun onPageStart(
                        session: GeckoSession,
                        url: String
                    ) {
                        isLoading = true
                    }

                    override fun onPageStop(
                        session: GeckoSession,
                        success: Boolean
                    ) {
                        isLoading = false
                    }
                }

            session.contentDelegate =
                object : GeckoSession.ContentDelegate {
                    override fun onTitleChange(
                        session: GeckoSession,
                        title: String?
                    ) {
                        if (!title.isNullOrBlank()) {
                            currentTitle = title.take(50)
                        }
                    }
                }
        }
    }

    BackHandler {
        session.goBack()
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
                    if (currentTitle != platformInfo.title &&
                        currentTitle.isNotBlank()
                    ) {
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
                IconButton(onClick = { session.goBack() }) {
                    Icon(Icons.Filled.ArrowBack, "رجوع")
                }
            },
            actions = {
                IconButton(onClick = { session.reload() }) {
                    Icon(Icons.Filled.Refresh, "تحديث")
                }
                IconButton(onClick = {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(platformInfo.url)
                    )
                    context.startActivity(intent)
                }) {
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

            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .align(Alignment.TopCenter)
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            geckoViewRef?.releaseSession()
            session.close()
        }
    }
}

// ============================================================
// Dialog عند فشل GeckoRuntime
// ============================================================

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
