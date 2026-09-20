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

    @Volatile private var capturePort: WebExtension.Port? = null

    private val pendingSessions = mutableListOf<GeckoSession>()

    var onAiResponseCaptured:  ((domain: String, text: String) -> Unit)? = null
    var onManualCaptureResult: ((success: Boolean, text: String, debug: JSONObject?) -> Unit)? = null

    lateinit var webPlatformRepository: WebPlatformRepository
        private set

    // ── PortDelegate ──────────────────────────────────────────────────

    private val portDelegate = object : WebExtension.PortDelegate {
        override fun onPortMessage(message: Any, port: WebExtension.Port) {
            val json = parseMessage(message) ?: return
            Log.d("AichatApp", "📩 Port: ${json.optString("type")}")
            when (json.optString("type")) {
                "CAPTURE_RESULT" -> handleCaptureResult(json)
            }
        }
        override fun onDisconnect(port: WebExtension.Port) {
            Log.d("AichatApp", "⚠️ Port disconnected")
            if (capturePort === port) capturePort = null
        }
    }

    // ── MessageDelegate ───────────────────────────────────────────────

    private val messageDelegate = object : WebExtension.MessageDelegate {

        override fun onConnect(port: WebExtension.Port) {
            Log.d("AichatApp", "✅ onConnect — Port ready!")
            capturePort = port
            port.setDelegate(portDelegate)
        }

        override fun onMessage(
            nativeApp: String,
            message:   Any,
            sender:    WebExtension.MessageSender
        ): GeckoResult<Any>? {
            val json = parseMessage(message) ?: return null
            Log.d("AichatApp", "📩 onMessage: ${json.optString("type")}")
            when (json.optString("type")) {
                "AI_RESPONSE" -> handleAutoResponse(json)
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

    // ── ✅ تسجيل delegate — مستقل عن فتح الجلسة ──────────────────────

    fun registerSessionDelegate(session: GeckoSession) {
        val ext = aiChatExtension
        if (ext != null) {
            session.webExtensionController
                .setMessageDelegate(ext, messageDelegate, "browser")
            Log.d("AichatApp", "✅ Delegate registered immediately")
        } else {
            Log.w("AichatApp", "⏳ Extension not ready — queuing session")
            synchronized(pendingSessions) {
                if (!pendingSessions.contains(session)) {
                    pendingSessions.add(session)
                }
            }
        }
    }

    // ── زر 🧠 ────────────────────────────────────────────────────────

    fun requestManualCapture() {
        val port = capturePort
        if (port == null) {
            Log.w("AichatApp", "⚠️ No port available")
            Handler(Looper.getMainLooper()).post {
                onManualCaptureResult?.invoke(false, "", null)
            }
            return
        }
        try {
            port.postMessage(JSONObject().put("type", "CAPTURE"))
            Log.d("AichatApp", "📤 CAPTURE sent")
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
                        Log.d("AichatApp", "✅ Extension: ${ext.id}")

                        // ✅ سجّل كل الـ sessions المنتظرة
                        synchronized(pendingSessions) {
                            pendingSessions.forEach { s ->
                                s.webExtensionController
                                    .setMessageDelegate(
                                        ext, messageDelegate, "browser"
                                    )
                                Log.d("AichatApp", "✅ Queued session registered")
                            }
                            pendingSessions.clear()
                        }
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
