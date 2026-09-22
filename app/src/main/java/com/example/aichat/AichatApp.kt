package com.example.aichat

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.example.aichat.data.local.ChatDatabase
import com.example.aichat.data.local.SystemPrompt
import com.example.aichat.data.model.MemoryItem
import com.example.aichat.repository.MemoryContextBuilder
import com.example.aichat.repository.WebPlatformRepository
import com.example.aichat.web.SessionContextBridge
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtension

class AichatApp : Application() {

    @Volatile var geckoRuntime: GeckoRuntime? = null
        private set

    @Volatile var aiChatExtension: WebExtension? = null
        private set

    @Volatile var contextBridgeExtension: WebExtension? = null
        private set

    @Volatile private var captureFlag = false
    @Volatile var contextPendingMessage = ""
    @Volatile private var pendingBridgeDelegate: WebExtension.MessageDelegate? = null

    private var backgroundPort: WebExtension.Port? = null

    var onAiResponseCaptured: ((domain: String, text: String) -> Unit)? = null
    var onManualCaptureResult: ((success: Boolean, text: String, debug: JSONObject?) -> Unit)? = null

    lateinit var webPlatformRepository: WebPlatformRepository
        private set

    // ── Toast ─────────────────────────────────────────────────────────

    fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }

    // ── triggerCapture ────────────────────────────────────────────────

    fun triggerCapture() {
        captureFlag = true
        Log.d("AichatApp", "📌 Capture flag set")
        showToast("🔍 جاري البحث في الصفحة...")
    }

    // ── sendContextToPage ─────────────────────────────────────────────

    fun sendContextToPage(
        memories: List<MemoryItem>,
        customInstruction: String = ""
    ) {
        if (aiChatExtension == null) {
            showToast("❌ Extension غير جاهزة")
            return
        }
        val memoryContext = MemoryContextBuilder().build(memories)
        val systemPrompt = SystemPrompt.build(
            memoryContext     = memoryContext,
            customInstruction = customInstruction,
            includeAccuracy   = true
        )
        contextPendingMessage = systemPrompt
        showToast("📤 حجم السياق: ${systemPrompt.length} حرف")
        Log.d("AichatApp", "📤 contextPendingMessage: ${systemPrompt.length} chars")
    }

    // ── registerBridgeDelegate ────────────────────────────────────────

    fun registerBridgeDelegate(delegate: WebExtension.MessageDelegate) {
        pendingBridgeDelegate = delegate
        val ext = contextBridgeExtension
        if (ext != null) {
            Handler(Looper.getMainLooper()).post {
                try {
                    ext.setMessageDelegate(delegate, SessionContextBridge.NATIVE_APP)
                    Log.d("AichatApp", "✅ BridgeDelegate registered immediately")
                } catch (e: Exception) {
                    Log.e("AichatApp", "❌ registerBridgeDelegate: ${e.message}")
                }
            }
        } else {
            Log.w("AichatApp", "⚠️ contextBridgeExtension null — delegate queued")
        }
    }

    // ── PortDelegate ──────────────────────────────────────────────────

    private val portDelegate = object : WebExtension.PortDelegate {

        override fun onPortMessage(message: Any, port: WebExtension.Port) {
            val json = parseMessage(message) ?: return
            val type = json.optString("type")
            Log.d("AichatApp", "📩 port: $type")

            when (type) {
                "CHECK_CAPTURE" -> {
                    val flag = captureFlag
                    captureFlag = false
                    if (flag) showToast("📡 تم الاتصال — جاري الاستخراج...")
                    try {
                        port.postMessage(
                            JSONObject()
                                .put("type",    "CHECK_CAPTURE_RESULT")
                                .put("capture", flag)
                        )
                    } catch (e: Exception) {
                        Log.e("AichatApp", "❌ CHECK_CAPTURE: ${e.message}")
                    }
                }

                "GET_CONTEXT" -> {
                    val pending           = contextPendingMessage
                    contextPendingMessage = ""
                    Log.d("AichatApp", "📤 GET_CONTEXT has=${pending.isNotBlank()}")
                    showToast("📤 GET_CONTEXT has=${pending.isNotBlank()}")
                    try {
                        port.postMessage(
                            JSONObject()
                                .put("type",       "GET_CONTEXT_RESULT")
                                .put("hasContext", pending.isNotBlank())
                                .put("context",    pending)
                        )
                    } catch (e: Exception) {
                        Log.e("AichatApp", "❌ GET_CONTEXT: ${e.message}")
                    }
                }

                "AI_RESPONSE"    -> handleAutoResponse(json)
                "CAPTURE_RESULT" -> handleCaptureResult(json)

                "CONTEXT_WRITTEN" -> {
                    val len    = json.optInt("len")
                    val domain = json.optString("domain")
                    Log.d("AichatApp", "✅ written: $len @ $domain")
                    showToast("✅ السياق وصل: $len حرف")
                }

                "DEBUG_INFO" -> {
                    showToast("🔍 ${json.optString("info")}")
                }

                "DEBUG_BUTTONS" -> {
                    val buttons = json.optJSONArray("buttons")
                    val domain  = json.optString("domain")
                    val sb = StringBuilder("🔍 أزرار $domain:\n")
                    if (buttons != null) {
                        for (i in 0 until buttons.length()) {
                            val btn   = buttons.optJSONObject(i)
                            val label = btn?.optString("label") ?: ""
                            val t     = btn?.optString("type")  ?: ""
                            if (label.isNotBlank() || t == "submit") {
                                sb.append("• $label type=$t\n")
                            }
                        }
                    }
                    showToast(sb.toString())
                }

                else -> Log.w("AichatApp", "⚠️ unknown: $type")
            }
        }
    }

    // ── MessageDelegate — aicapture ───────────────────────────────────

    private val messageDelegateAiCapture = object : WebExtension.MessageDelegate {

        override fun onConnect(port: WebExtension.Port) {
            backgroundPort = port
            port.setDelegate(portDelegate)
            Log.d("AichatApp", "✅ aicapture port connected")
            showToast("✅ aicapture port connected")
        }

        override fun onMessage(
            nativeApp: String,
            message: Any,
            sender: WebExtension.MessageSender
        ): GeckoResult<Any>? {
            val json = parseMessage(message) ?: return null
            Log.w("AichatApp", "⚠️ onMessage مباشر: ${json.optString("type")}")
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
                loadContextBridgeExtension(rt)
                Log.d("AichatApp", "✅ GeckoRuntime created")
            }
        } catch (e: Exception) {
            Log.e("AichatApp", "❌ GeckoRuntime: ${e.message}")
            showToast("❌ فشل تهيئة GeckoRuntime")
            null
        }
    }

    // ── loadAiCaptureExtension ────────────────────────────────────────

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
                        ext.setMessageDelegate(messageDelegateAiCapture, "browser")
                        Log.d("AichatApp", "✅ aicapture loaded")
                        showToast("✅ aicapture جاهزة")
                    } else {
                        Log.e("AichatApp", "❌ aicapture = null")
                        showToast("❌ aicapture = null")
                    }
                },
                { e ->
                    Log.e("AichatApp", "❌ aicapture: ${e?.message}")
                    showToast("❌ aicapture: ${e?.message}")
                }
            )
    }

    // ── loadContextBridgeExtension ────────────────────────────────────

    private fun loadContextBridgeExtension(runtime: GeckoRuntime) {
        runtime.webExtensionController
            .ensureBuiltIn(
                "resource://android/assets/contextbridge/",
                "session-memory-bridge@example.local"
            )
            .accept(
                { ext ->
                    if (ext != null) {
                        contextBridgeExtension = ext
                        Log.d("AichatApp", "✅ contextbridge loaded")
                        showToast("✅ Bridge جاهز")

                        val delegate = pendingBridgeDelegate
                        if (delegate != null) {
                            Handler(Looper.getMainLooper()).post {
                                try {
                                    ext.setMessageDelegate(
                                        delegate,
                                        SessionContextBridge.NATIVE_APP
                                    )
                                    Log.d("AichatApp", "✅ Pending delegate registered")
                                } catch (e: Exception) {
                                    Log.e("AichatApp", "❌ pending delegate: ${e.message}")
                                }
                            }
                        }
                    } else {
                        Log.e("AichatApp", "❌ contextbridge = null")
                        showToast("❌ Bridge = null")
                    }
                },
                { e ->
                    Log.e("AichatApp", "❌ contextbridge: ${e?.message}")
                    showToast("❌ Bridge: ${e?.message}")
                }
            )
    }

    // ── معالجة الرسائل ────────────────────────────────────────────────

    private fun handleAutoResponse(json: JSONObject) {
        val text   = json.optString("text")
        val domain = json.optString("domain", "unknown")
        if (text.length < 80) return
        Log.d("AichatApp", "📨 Auto: ${text.take(60)}…")
        onAiResponseCaptured?.invoke(domain, text)
    }

    private fun handleCaptureResult(json: JSONObject) {
        val success = json.optBoolean("success", false)
        val text    = json.optString("text")
        val debug   = json.optJSONObject("debug")
        Log.d("AichatApp",
            if (success) "🧠 OK: ${text.take(60)}…"
            else         "⚠️ Failed: $debug"
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
    } catch (e: Exception) {
        Log.e("AichatApp", "❌ parseMessage: ${e.message}")
        null
    }
}
