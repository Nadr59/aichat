package com.example.aichat

import android.app.Application
import android.util.Log
import com.example.aichat.data.local.ChatDatabase
import com.example.aichat.repository.WebPlatformRepository
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

class AichatApp : Application() {

    // ✅ Lazy — لا يُنشأ في onCreate
    @Volatile
    var geckoRuntime: GeckoRuntime? = null
        private set

    lateinit var webPlatformRepository: WebPlatformRepository
        private set

    override fun onCreate() {
        super.onCreate()

        // Database فقط — سريع وآمن
        try {
            val db = ChatDatabase.getDatabase(this)
            webPlatformRepository = WebPlatformRepository(db.webPlatformDao())
            Log.d("AichatApp", "✅ Database ready")
        } catch (e: Exception) {
            Log.e("AichatApp", "❌ Database failed: ${e.message}", e)
            throw e
        }

        Log.d("AichatApp", "✅ App started — Gecko will init on demand")
    }

    // ✅ ينشئ GeckoRuntime عند الحاجة فقط (Lazy)
    @Synchronized
    fun getOrCreateGeckoRuntime(): GeckoRuntime? {
        if (geckoRuntime != null) return geckoRuntime

        return try {
            val settings = GeckoRuntimeSettings.Builder()
                .aboutConfigEnabled(false)
                .build()

            GeckoRuntime.create(applicationContext, settings).also {
                geckoRuntime = it
                Log.d("AichatApp", "✅ GeckoRuntime created on demand")
            }
        } catch (e: Exception) {
            Log.e("AichatApp", "❌ GeckoRuntime failed: ${e.message}", e)
            null
        }
    }
}
