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

    // ── Callbacks ─────────────────────────────────────────────────────────

    /** التلقائي — يُستدعى عند رصد رد AI_RESPONSE */
    var onAiResponseCaptured: ((domain: String, text: String) -> Unit)? = null

    /** ✅ اليدوي — يُستدعى عند ورود CAPTURE_RESULT */
    var onManualCaptureResult: ((success: Boolean, text: String) -> Unit)? = null

    lateinit var webPlatformRepository: WebPlatformRepository
        private set

    // ── onCreate ──────────────────────────────────────────────────────────

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
        Log.d("AichatApp", "✅ App started")
    }

    // ── GeckoRuntime ──────────────────────────────────────────────────────

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

    // ── Extension ─────────────────────────────────────────────────────────

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
                    Log.e("AichatApp", "❌ Extension failed: ${e?.message}")
                }
            )
    }

    private fun setupMessageDelegate(extension: WebExtension) {
        extension.setMessageDelegate(
            object : WebExtension.MessageDelegate {

                override fun onConnect(port: WebExtension.Port) {
                    Log.d("AichatApp", "✅ Port connected")
                    port.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(message: Any, port: WebExtension.Port) {
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

    // ── ✅ handleMessage — يعالج النوعين ──────────────────────────────────

    private fun handleMessage(message: Any) {
        try {
            @Suppress("UNCHECKED_CAST")
            val map  = message as? Map<String, Any> ?: return
            val type = map["type"] as? String ?: return

            when (type) {

                // ── تلقائي ──────────────────────────────────────────────
                "AI_RESPONSE" -> {
                    val text   = map["text"]   as? String ?: return
                    val domain = map["domain"] as? String ?: "unknown"

                    if (text.length < 80) {
                        Log.d("AichatApp", "⏭ Too short: ${text.length}")
                        return
                    }

                    Log.d("AichatApp", "📨 Auto AI from $domain: ${text.take(60)}…")
                    onAiResponseCaptured?.invoke(domain, text)
                }

                // ── ✅ يدوي ───────────────────────────────────────────────
                "CAPTURE_RESULT" -> {
                    val success = map["success"] as? Boolean ?: false
                    val text    = map["text"]    as? String  ?: ""
                    val domain  = map["domain"]  as? String  ?: "unknown"

                    Log.d("AichatApp",
                        if (success) "🧠 Manual capture from $domain: ${text.take(60)}…"
                        else         "⚠️ Manual capture failed from $domain"
                    )

                    onManualCaptureResult?.invoke(success, text)
                }

                else -> Log.d("AichatApp", "⏭ Unknown message type: $type")
            }

        } catch (e: Exception) {
            Log.e("AichatApp", "❌ handleMessage: ${e.message}")
        }
    }
}
