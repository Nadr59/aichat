package com.example.aichat.ui.screens

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

@Composable
fun GeckoTestScreen(
    platform: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    val platformInfo =
        webPlatforms[platform] ?: webPlatforms["huggingchat"]!!

    /*
     * GeckoRuntime لا يتم إغلاقه عند مغادرة الشاشة.
     *
     * السبب:
     * GeckoRuntime مورد ثقيل ويُفضّل أن يعيش على مستوى
     * التطبيق بدل إنشائه وإغلاقه مع كل فتح وإغلاق للشاشة.
     */
    val runtime = remember {
        GeckoRuntime.create(context.applicationContext)
    }

    val session = remember {
        GeckoSession()
    }

    BackHandler {
        onBack()
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),

        factory = { viewContext ->

            GeckoView(viewContext).apply {

                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                setSession(session)

                if (!session.isOpen) {
                    session.open(runtime)
                    session.loadUri(platformInfo.url)
                }
            }
        }
    )

    DisposableEffect(Unit) {

        onDispose {

            /*
             * إغلاق الجلسة فقط.
             * لا نغلق GeckoRuntime هنا.
             */
            session.close()
        }
    }
}
