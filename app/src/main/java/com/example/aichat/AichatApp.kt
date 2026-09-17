package com.example.aichat

import android.app.Application
import android.util.Log
import com.example.aichat.data.local.ChatDatabase
import com.example.aichat.repository.WebPlatformRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

class AichatApp : Application() {

    // Gecko nullable — يُنشأ بشكل غير متزامن
    @Volatile
    var geckoRuntime: GeckoRuntime? = null
        private set

    lateinit var webPlatformRepository: WebPlatformRepository
        private set

    // Scope للعمليات الخلفية
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()

        // ── Database أولاً (سريع) ─────────────────────────────────────────
        try {
            val db = ChatDatabase.getDatabase(this)
            webPlatformRepository = WebPlatformRepository(db.webPlatformDao())
            Log.d("AichatApp", "✅ Database ready")
        } catch (e: Exception) {
            Log.e("AichatApp", "❌ Database failed: ${e.message}", e)
            throw e
        }

        // ── GeckoRuntime على Main بعد تهيئة الـ UI ────────────────────────
        // GeckoRuntime يجب أن يُنشأ على Main Thread لكن بعد أن يصبح جاهزاً
        appScope.launch {
            try {
                if (geckoRuntime == null) {
                    geckoRuntime = GeckoRuntime.create(
                        this@AichatApp,
                        GeckoRuntimeSettings.Builder()
                            .javaScriptEnabled(true)
                            .consoleOutput(false)
                            .build()
                    )
                    Log.d("AichatApp", "✅ GeckoRuntime ready")
                }
            } catch (e: Exception) {
                Log.e("AichatApp", "❌ GeckoRuntime failed: ${e.message}", e)
                geckoRuntime = null
            }
        }
    }
}
