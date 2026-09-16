package com.example.aichat

import android.app.Application
import android.util.Log
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

class AichatApp : Application() {

    var geckoRuntime: GeckoRuntime? = null
        private set

    override fun onCreate() {
        super.onCreate()
        // ✅ لا نهيئ GeckoRuntime هنا
        // سيتم عند الحاجة فقط (Lazy)
        Log.d("AichatApp", "✅ App started")
    }

    // ✅ تهيئة عند الطلب فقط - Lazy
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
            Log.e("AichatApp", "❌ GeckoRuntime failed: ${e.message}")
            null
        }
    }
}
