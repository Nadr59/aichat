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

    // ✅ Callback — يُستدعى عند التقاط رد AI من منصة ويب
    var onAiResponseCaptured: ((domain: String, text: String) -> Unit)? = null

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
                loadAiCaptureExtension(runtime)
            }
        } catch (e: Exception) {
            Log.e("AichatApp", "❌ GeckoRuntime failed: ${e.message}", e)
            null
        }
    }

    // ── تحميل WebExtension ────────────────────────────────────────────────

    private fun loadAiCaptureExtension(runtime: GeckoRuntime) {
        runtime.webExtensionController
            .ensureBuiltIn(
                "resource://android/assets/aicapture/",
                "aicapture@aichat.example.com"
            )
            .accept(
                { extension ->
                    Log.d("AichatApp", "✅ Extension loaded: ${extension?.id}")
                    if (extension != null) setupMessageDelegate(extension)
                },
                { e ->
                    Log.e("AichatApp", "❌ Extension load failed: ${e?.message}")
                }
            )
    }

    private fun setupMessageDelegate(extension: WebExtension) {
        extension.setMessageDelegate(
            object : WebExtension.MessageDelegate {

                override fun onConnect(port: WebExtension.Port) {
                    Log.d("AichatApp", "✅ Port connected")
                    port.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(
                            message: Any,
                            port:    WebExtension.Port
                        ) {
                            handleMessage(message)
                        }
                        override fun onDisconnect(port: WebExtension.Port) {
                            Log.d("AichatApp", "Port disconnected")
                        }
                    })
                }

                override fun onMessage(
                    nativeApp: String,
                    message:   Any,
                    sender:    WebExtension.MessageSender
                ): org.mozilla.geckoview.GeckoResult<Any>? {
                    handleMessage(message)
                    return null
                }
            },
            "browser"
        )
        Log.d("AichatApp", "✅ MessageDelegate set")
    }

    private fun handleMessage(message: Any) {
        try {
            @Suppress("UNCHECKED_CAST")
            val map    = message as? Map<String, Any> ?: return
            val type   = map["type"]   as? String ?: return
            if (type != "AI_RESPONSE") return

            val text   = map["text"]   as? String ?: return
            val domain = map["domain"] as? String ?: "unknown"

            if (text.length < 80) return

            Log.d("AichatApp", "📨 AI from $domain: ${text.take(60)}...")
            onAiResponseCaptured?.invoke(domain, text)

        } catch (e: Exception) {
            Log.e("AichatApp", "❌ handleMessage: ${e.message}")
        }
    }
}
