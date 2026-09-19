package com.example.aichat

import android.app.Application
import android.util.Log
import com.example.aichat.data.local.ChatDatabase
import com.example.aichat.repository.WebPlatformRepository
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtension

class AichatApp : Application() {

    @Volatile
    var geckoRuntime: GeckoRuntime? = null
        private set

    // ✅ Callback يُستدعى عند وصول رد AI من أي منصة
    var onAiResponseCaptured: ((platformId: String, text: String) -> Unit)? = null

    lateinit var webPlatformRepository: WebPlatformRepository
        private set

    override fun onCreate() {
        super.onCreate()

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

    @Synchronized
    fun getOrCreateGeckoRuntime(): GeckoRuntime? {
        if (geckoRuntime != null) return geckoRuntime

        return try {
            val settings = GeckoRuntimeSettings.Builder()
                .aboutConfigEnabled(false)
                .build()

            GeckoRuntime.create(applicationContext, settings).also { runtime ->
                geckoRuntime = runtime
                Log.d("AichatApp", "✅ GeckoRuntime created")

                // ✅ تحميل WebExtension بعد إنشاء Runtime
                loadAiCaptureExtension(runtime)
            }
        } catch (e: Exception) {
            Log.e("AichatApp", "❌ GeckoRuntime failed: ${e.message}", e)
            null
        }
    }

    // ── تحميل Extension والاستماع لرسائلها ───────────────────────────

    private fun loadAiCaptureExtension(runtime: GeckoRuntime) {
        runtime.webExtensionController
            .ensureBuiltIn(
                "resource://android/assets/aicapture/",
                "aicapture@aichat.example.com"
            )
            .accept(
                { extension ->
                    Log.d("AichatApp", "✅ Extension loaded: ${extension?.id}")
                    setupMessageDelegate(runtime, extension)
                },
                { e ->
                    Log.e("AichatApp", "❌ Extension load failed: ${e?.message}")
                }
            )
    }

    private fun setupMessageDelegate(
        runtime:   GeckoRuntime,
        extension: WebExtension?
    ) {
        if (extension == null) return

        runtime.webExtensionController.setMessageDelegate(
            extension,
            object : WebExtension.MessageDelegate {
                override fun onMessage(
                    nativeApp: String,
                    message:   Any?,
                    sender:    WebExtension.MessageSender
                ): org.mozilla.geckoview.GeckoResult<Any?>? {

                    try {
                        val map = message as? Map<*, *> ?: return null
                        val type = map["type"] as? String ?: return null
                        if (type != "AI_RESPONSE") return null

                        val text   = map["text"]   as? String ?: return null
                        val domain = map["domain"] as? String ?: "unknown"

                        if (text.length < 80) return null

                        Log.d(
                            "AichatApp",
                            "📨 AI captured from $domain: ${text.take(60)}..."
                        )

                        // ✅ إشعار ChatViewModel عبر callback
                        onAiResponseCaptured?.invoke(domain, text)

                    } catch (e: Exception) {
                        Log.e("AichatApp", "❌ Message parse: ${e.message}")
                    }

                    return null
                }
            },
            "browser"   // nativeApp name
        )

        Log.d("AichatApp", "✅ Message delegate ready")
    }
}
