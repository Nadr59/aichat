package com.example.aichat

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.aichat.data.local.ChatDatabase
import com.example.aichat.repository.WebPlatformRepository
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtension

class AichatApp : Application() {

    @Volatile var geckoRuntime:    GeckoRuntime?  = null
        private set

    @Volatile var aiChatExtension: WebExtension?  = null
        private set

    // ✅ Port يأتي من content.js عبر browser.runtime.connect()
    @Volatile private var activePort: WebExtension.Port? = null

    var onAiResponseCaptured:  ((domain: String, text: String) -> Unit)? = null
    var onManualCaptureResult: ((success: Boolean, text: String, debug: JSONObject?) -> Unit)? = null

    lateinit var webPlatformRepository: WebPlatformRepository
        private set

    override fun onCreate() {
        super.onCreate()
        try {
            val db = ChatDatabase.getDatabase(this)
            webPlatformRepository = WebPlatformRepository(db.webPlatformDao())
            Log.d("AichatApp", "✅ Database ready")
        } catch (e: Exception) {
            Log.e("AichatApp", "❌ Database: ${e.message}", e)
            throw e
        }
    }

    @Synchronized
    fun getOrCreateGeckoRuntime(): GeckoRuntime? {
        if (geckoRuntime != null) return geckoRuntime
        return try {
            val settings = GeckoRuntimeSettings.Builder()
                .aboutConfigEnabled(false)
                .build()
            GeckoRuntime.create(applicationContext, settings).also { rt ->
                geckoRuntime = rt
                loadAiCaptureExtension(rt)
                Log.d("AichatApp", "✅ GeckoRuntime created")
            }
        } catch (e: Exception) {
            Log.e("AichatApp", "❌ GeckoRuntime: ${e.message}")
            null
        }
    }

    // ── زر 🧠 ────────────────────────────────────────────────────────

    fun requestManualCapture() {
        val port = activePort
        if (port == null) {
            Log.w("AichatApp", "⚠️ No active port")
            Handler(Looper.getMainLooper()).post {
                onManualCaptureResult?.invoke(false, "", null)
            }
            return
        }
        try {
            port.postMessage(JSONObject().put("type", "CAPTURE_NOW"))
            Log.d("AichatApp", "📤 CAPTURE_NOW sent")
        } catch (e: Exception) {
            Log.e("AichatApp", "❌ postMessage: ${e.message}")
            Handler(Looper.getMainLooper()).post {
                onManualCaptureResult?.invoke(false, "", null)
            }
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
                { ext ->
                    if (ext != null) {
                        aiChatExtension = ext
                        setupMessageDelegate(ext)
                        Log.d("AichatApp", "✅ Extension: ${ext.id}")
                    }
                },
                { e -> Log.e("AichatApp", "❌ Extension: ${e?.message}") }
            )
    }

    private fun setupMessageDelegate(extension: WebExtension) {
        extension.setMessageDelegate(
            object : WebExtension.MessageDelegate {

                // ✅ content.js اتصل عبر browser.runtime.connect()
                override fun onConnect(port: WebExtension.Port) {
                    Log.d("AichatApp", "✅ Port connected: ${port.name}")

                    // ✅ حفظ الـ Port فوراً
                    activePort = port

                    port.setDelegate(object : WebExtension.PortDelegate {

                        override fun onPortMessage(
                            message: Any,
                            port:    WebExtension.Port
                        ) {
                            val json = parseMessage(message) ?: return
                            val type = json.optString("type")

                            when (type) {
                                "CAPTURE_RESULT" -> handleCaptureResult(json)
                                else             -> handleMessage(json)
                            }
                        }

                        override fun onDisconnect(port: WebExtension.Port) {
                            Log.d("AichatApp", "⚠️ Port disconnected")
                            if (activePort === port) activePort = null
                        }
                    })
                }

                // رسائل sendMessage التلقائية
                override fun onMessage(
                    nativeApp: String,
                    message:   Any,
                    sender:    WebExtension.MessageSender
                ): org.mozilla.geckoview.GeckoResult<Any>? {
                    val json = parseMessage(message)
                    if (json != null) handleMessage(json)
                    return null
                }
            },
            "browser"
        )
        Log.d("AichatApp", "✅ MessageDelegate set")
    }

    // ── معالجة الرسائل ────────────────────────────────────────────────

    private fun handleMessage(json: JSONObject) {
        val type = json.optString("type").ifBlank { return }
        when (type) {
            "AI_RESPONSE" -> {
                val text   = json.optString("text")
                val domain = json.optString("domain", "unknown")
                if (text.length < 80) return
                Log.d("AichatApp", "📨 Auto from $domain: ${text.take(60)}…")
                Handler(Looper.getMainLooper()).post {
                    onAiResponseCaptured?.invoke(domain, text)
                }
            }
            "CAPTURE_RESULT" -> handleCaptureResult(json)
        }
    }

    private fun handleCaptureResult(json: JSONObject) {
        val success = json.optBoolean("success", false)
        val text    = json.optString("text")
        val domain  = json.optString("domain", "unknown")
        val debug   = json.optJSONObject("debug")

        Log.d("AichatApp",
            if (success) "🧠 Manual OK from $domain: ${text.take(60)}…"
            else         "⚠️ Manual failed from $domain — debug: $debug"
        )

        Handler(Looper.getMainLooper()).post {
            onManualCaptureResult?.invoke(success, text, debug)
        }
    }

    private fun parseMessage(message: Any): JSONObject? {
        return try {
            when (message) {
                is JSONObject -> message
                is Map<*, *>  -> JSONObject(message as Map<*, *>)
                else          -> null
            }
        } catch (e: Exception) { null }
    }
}
