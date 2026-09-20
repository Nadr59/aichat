package com.example.aichat

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.aichat.data.local.ChatDatabase
import com.example.aichat.repository.WebPlatformRepository
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

class AichatApp : Application() {

    @Volatile var geckoRuntime:    GeckoRuntime?  = null
        private set

    @Volatile var aiChatExtension: WebExtension?  = null
        private set

    // ✅ Port من background.js — مستوى الـ Runtime
    @Volatile private var bgPort: WebExtension.Port? = null

    var onAiResponseCaptured:  ((domain: String, text: String) -> Unit)? = null
    var onManualCaptureResult: ((success: Boolean, text: String, debug: JSONObject?) -> Unit)? = null

    lateinit var webPlatformRepository: WebPlatformRepository
        private set

    // ── PortDelegate — background.js ──────────────────────────────────

    private val portDelegate = object : WebExtension.PortDelegate {
        override fun onPortMessage(message: Any, port: WebExtension.Port) {
            val json = parseMessage(message) ?: return
            val type = json.optString("type")
            Log.d("AichatApp", "📩 Port msg: $type")

            when (type) {
                "BG_READY"       -> Log.d("AichatApp", "✅ Background ready!")
                "AI_RESPONSE"    -> handleAutoResponse(json)
                "CAPTURE_RESULT" -> handleCaptureResult(json)
                else             -> Log.d("AichatApp", "⏭ Unknown: $type")
            }
        }

        override fun onDisconnect(port: WebExtension.Port) {
            Log.w("AichatApp", "⚠️ BG Port disconnected")
            if (bgPort === port) bgPort = null
        }
    }

    // ── MessageDelegate — على مستوى الـ Extension (background) ───────

    private val messageDelegate = object : WebExtension.MessageDelegate {
    override fun onMessage(
        nativeApp: String,
        message:   Any,
        sender:    WebExtension.MessageSender
    ): GeckoResult<Any>? {
        val json = parseMessage(message) ?: return null
        when (json.optString("type")) {
            "AI_RESPONSE"    -> handleAutoResponse(json)
            "CAPTURE_RESULT" -> handleCaptureResult(json)
        }
        return null
    }
    }

    // ── onCreate ──────────────────────────────────────────────────────

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

    // ── GeckoRuntime ──────────────────────────────────────────────────

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
        val port = bgPort
        if (port == null) {
            Log.w("AichatApp", "⚠️ No background port")
            Handler(Looper.getMainLooper()).post {
                onManualCaptureResult?.invoke(false, "", null)
            }
            return
        }
        try {
            // ✅ أرسل لـ background.js — هو يوصّل لـ content.js
            port.postMessage(JSONObject().put("type", "CAPTURE"))
            Log.d("AichatApp", "📤 CAPTURE → background.js")
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
                        // ✅ delegate على مستوى Extension — للـ background
                        ext.setMessageDelegate(messageDelegate, "browser")
                        Log.d("AichatApp", "✅ Extension ready: ${ext.id}")
                    }
                },
                { e -> Log.e("AichatApp", "❌ Extension: ${e?.message}") }
            )
    }

    // ── معالجة الرسائل ────────────────────────────────────────────────

    private fun handleAutoResponse(json: JSONObject) {
        val text   = json.optString("text")
        val domain = json.optString("domain", "unknown")
        if (text.length < 80) return
        Log.d("AichatApp", "📨 Auto from $domain: ${text.take(60)}…")
        onAiResponseCaptured?.invoke(domain, text)
    }

    private fun handleCaptureResult(json: JSONObject) {
        val success = json.optBoolean("success", false)
        val text    = json.optString("text")
        val debug   = json.optJSONObject("debug")

        Log.d("AichatApp",
            if (success) "🧠 OK: ${text.take(60)}…"
            else         "⚠️ Failed — debug: $debug"
        )

        Handler(Looper.getMainLooper()).post {
            onManualCaptureResult?.invoke(success, text, debug)
        }
    }

    private fun parseMessage(message: Any): JSONObject? = try {
        when (message) {
            is JSONObject -> message
            is Map<*, *>  -> JSONObject(message as Map<*, *>)
            else          -> null
        }
    } catch (e: Exception) { null }
}
