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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
    val platformInfo = webPlatforms[platform] ?: webPlatforms["huggingchat"]!!

    // ✅ Runtime من التطبيق - لا يُعاد إنشاؤه
    val runtime = remember {
        (context.applicationContext as AichatApp).geckoRuntime
    }

    var isLoading by remember { mutableStateOf(true) }
    var currentTitle by remember { mutableStateOf(platformInfo.title) }
    var geckoViewRef by remember { mutableStateOf<GeckoView?>(null) }

    val session = remember {
        GeckoSession().also { session ->

            // ✅ مراقبة التحميل
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

            // ✅ مراقبة العنوان
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

    // ✅ BackHandler: رجوع في التاريخ أولاً
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
                    Icon(Icons.Filled.ArrowBack, contentDescription = "رجوع")
                }
            },
            actions = {
                // زر تحديث
                IconButton(onClick = { session.reload() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "تحديث")
                }

                // ✅ فتح في المتصفح الخارجي
                IconButton(onClick = {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(platformInfo.url)
                    )
                    context.startActivity(intent)
                }) {
                    Icon(
                        Icons.Filled.OpenInBrowser,
                        contentDescription = "فتح في المتصفح"
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )

        Box(modifier = Modifier.fillMaxSize()) {

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    GeckoView(viewContext).apply {

                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        // ✅ فتح الجلسة وتحميل الصفحة
                        if (!session.isOpen) {
                            session.open(runtime)
                            session.loadUri(platformInfo.url)
                        }

                        setSession(session)

                    }.also { geckoViewRef = it }
                }
            )

            // ✅ شريط التحميل
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

    // ✅ تنظيف عند مغادرة الشاشة
    DisposableEffect(Unit) {
        onDispose {
            geckoViewRef?.releaseSession()
            session.close()
        }
    }
}
