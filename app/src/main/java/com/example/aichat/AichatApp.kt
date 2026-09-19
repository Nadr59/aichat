package com.example.aichat

import android.app.Application
import android.util.Log
import com.example.aichat.data.local.ChatDatabase
import com.example.aichat.repository.WebPlatformRepository
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtension

class AichatApp : Application() {

    @Volatile
    var geckoRuntime: GeckoRuntime? = null
        private set

    @Volatile
    var aiChatExtension: WebExtension? = null
        private set

    // ✅ Port المفتوح — نرسل عليه CAPTURE_NOW
    @Volatile
    private var activePort: WebExtension.Port? = null

    // ── Callbacks ─────────────────────────────────────────────────────
    var onAiResponseCaptured:  ((domain: String, text: String) -> Unit)? = null
    var onManualCaptureResult: ((success: Boolean, text: String) -> Unit)? = null

    lateinit var webPlatformRepository: WebPlatformRepository
        private set

    // ── onCreate ──────────────────────────────────────────────────────

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
    }

    // ── GeckoRuntime ──────────────────────────────────────────────────

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

    // ── ✅ الدالة التي يستدعيها زر 🧠 ────────────────────────────────
    fun requestManualCapture() {
        val port = activePort
        if (port == null) {
            Log.w("AichatApp", "⚠️ No active port — cannot send CAPTURE_NOW")
            // أبلغ الـ Screen بالفشل مباشرة
            onManualCaptureResult?.invoke(false, "")
            return
        }
        try {
            port.postMessage(JSONObject().put("type", "CAPTURE_NOW"))
            Log.d("AichatApp", "📤 Sent CAPTURE_NOW via port")
        } catch (e: Exception) {
            Log.e("AichatApp", "❌ postMessage failed: ${e.message}")
            onManualCaptureResult?.invoke(false, "")
        }
    }

    // ── Extension ─────────────────────────────────────────────────────

    private fun loadAiCaptureExtension(runtime: GeckoRuntime) {
        runtime.webExtensionController
            .ensureBuiltIn(
                "resource://android/assets/aicapture/",
                "aicapture@aichat.example.com"
            )
            .accept(
                { extension ->
                    if (extension != null) {
                        aiChatExtension = extension
                        setupMessageDelegate(extension)
                        Log.d("AichatApp", "✅ Extension loaded: ${extension.id}")
                    }
                },
                { e -> Log.e("AichatApp", "❌ Extension failed: ${e?.message}") }
            )
    }

    private fun setupMessageDelegate(extension: WebExtension) {
        extension.setMessageDelegate(
            object : WebExtension.MessageDelegate {

                // ✅ Port — يُفتح من content.js عبر connectNative
                override fun onConnect(port: WebExtension.Port) {
                    Log.d("AichatApp", "✅ Port connected")
                    activePort = port

                    port.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(message: Any, port: WebExtension.Port) {
                            handleMessage(message)
                        }
                        override fun onDisconnect(port: WebExtension.Port) {
                            Log.d("AichatApp", "⚠️ Port disconnected")
                            activePort = null
                        }
                    })
                }

                // runtime.sendMessage — للرسائل التلقائية
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

    // ── handleMessage ─────────────────────────────────────────────────

    internal fun handleMessage(message: Any) {
        try {
            val json = when (message) {
                is JSONObject -> message
                is Map<*, *>  -> JSONObject(message as Map<*, *>)
                else          -> {
                    Log.w("AichatApp", "Unknown type: ${message::class.simpleName}")
                    return
                }
            }

            val type = json.optString("type").ifBlank { return }

            when (type) {

                "AI_RESPONSE" -> {
                    val text   = json.optString("text")
                    val domain = json.optString("domain", "unknown")
                    if (text.length < 80) return
                    Log.d("AichatApp", "📨 Auto from $domain: ${text.take(60)}…")
                    onAiResponseCaptured?.invoke(domain, text)
                }

                "CAPTURE_RESULT" -> {
                    val success = json.optBoolean("success", false)
                    val text    = json.optString("text")
                    val domain  = json.optString("domain", "unknown")
                    Log.d("AichatApp",
                        if (success) "🧠 Manual from $domain: ${text.take(60)}…"
                        else         "⚠️ Manual failed from $domain"
                    )
                    onManualCaptureResult?.invoke(success, text)
                }

                else -> Log.d("AichatApp", "⏭ Unknown: $type")
            }

        } catch (e: Exception) {
            Log.e("AichatApp", "❌ handleMessage: ${e.message}")
        }
    }
}
