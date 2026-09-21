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
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtension

class AichatApp : Application() {

    @Volatile var geckoRuntime:    GeckoRuntime? = null
        private set

    @Volatile var aiChatExtension: WebExtension? = null
        private set

    @Volatile private var captureFlag:       Boolean = false
    @Volatile var contextPendingMessage:     String  = ""

    private var backgroundPort: WebExtension.Port? = null

    var onAiResponseCaptured:  ((domain: String, text: String) -> Unit)? = null
    var onManualCaptureResult: ((success: Boolean, text: String, debug: JSONObject?) -> Unit)? = null

    lateinit var webPlatformRepository: WebPlatformRepository
        private set

    // ── Toast helper ──────────────────────────────────────────────────

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
        memories:          List<MemoryItem>,
        customInstruction: String = ""
    ) {
        if (aiChatExtension == null) {
            showToast("❌ Extension غير جاهزة")
            return
        }

        val memoryContext = MemoryContextBuilder().build(memories)
        val systemPrompt  = SystemPrompt.build(
            memoryContext     = memoryContext,
            customInstruction = customInstruction,
            includeAccuracy   = true
        )

        contextPendingMessage = systemPrompt
        showToast("📤 حجم السياق: ${systemPrompt.length} حرف")
        Log.d("AichatApp", "📤 contextPendingMessage: ${systemPrompt.length} chars")
    }

    // ── PortDelegate — يستقبل من background.js ───────────────────────

    private val portDelegate = object : WebExtension.PortDelegate {

        override fun onPortMessage(message: Any, port: WebExtension.Port) {
            val json = parseMessage(message) ?: return
            val type = json.optString("type")
            Log.d("AichatApp", "📩 port: $type")

            when (type) {

                // ── طلبات تحتاج رد عبر Port ──────────────────────

                "CHECK_CAPTURE" -> {
                    val flag    = captureFlag
                    captureFlag = false
                    if (flag) showToast("📡 تم الاتصال — جاري الاستخراج...")
                    try {
                        port.postMessage(
                            JSONObject()
                                .put("type",    "CHECK_CAPTURE_RESULT")
                                .put("capture", flag)
                        )
                    } catch (e: Exception) {
                        Log.e("AichatApp", "❌ port.postMessage: ${e.message}")
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
                        Log.e("AichatApp", "❌ port.postMessage GET_CONTEXT: ${e.message}")
                    }
                }

                // ── رسائل بدون رد ─────────────────────────────────

                "AI_RESPONSE"    -> handleAutoResponse(json)
                "CAPTURE_RESULT" -> handleCaptureResult(json)

                "CONTEXT_WRITTEN" -> {
                    val len    = json.optInt("len")
                    val domain = json.optString("domain")
                    Log.d("AichatApp", "✅ written: $len @ $domain")
                    showToast("✅ السياق وصل: $len حرف")
                }

                "DEBUG_INFO" -> {
                    val info = json.optString("info")
                    Log.d("AichatApp", "🔍 $info")
                    showToast("🔍 $info")
                }

                "DEBUG_BUTTONS" -> {
                    val buttons = json.optJSONArray("buttons")
                    val domain  = json.optString("domain")
                    val sb      = StringBuilder("🔍 أزرار $domain:\n")
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

                else -> Log.w("AichatApp", "⚠️ unknown port type: $type")
            }
        }
    }

    // ── MessageDelegate ───────────────────────────────────────────────

    private val messageDelegate = object : WebExtension.MessageDelegate {

        override fun onConnect(port: WebExtension.Port) {
            backgroundPort = port
            port.setDelegate(portDelegate)
            Log.d("AichatApp", "✅ background port connected")
            showToast("✅ background port connected")
        }

        override fun onMessage(
            nativeApp: String,
            message:   Any,
            sender:    WebExtension.MessageSender
        ): GeckoResult<Any>? {
            // content.js يرسل مباشرة عبر sendNativeMessage
            // لكن الآن كل شيء عبر Port
            // نتركها فارغة للتوافق
            val json = parseMessage(message) ?: return null
            val type = json.optString("type")
            Log.w("AichatApp", "⚠️ onMessage مباشر (غير متوقع): $type")
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
            showToast("❌ فشل تهيئة GeckoRuntime")
            null
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
                        ext.setMessageDelegate(messageDelegate, "browser")
                        Log.d("AichatApp", "✅ Extension loaded: ${ext.id}")
                        showToast("✅ Extension جاهزة")
                    } else {
                        Log.e("AichatApp", "❌ Extension is null")
                        showToast("❌ Extension = null")
                    }
                },
                { e ->
                    val msg   = e?.message        ?: "null"
                    val cause = e?.cause?.message ?: "no cause"
                    Log.e("AichatApp", "❌ Extension error: $msg | $cause")
                    showToast("❌ $msg | $cause")
                }
            )
    }
    // أضف في AichatApp.kt

@Volatile var contextBridgeExtension: WebExtension? = null
    private set

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
                    Log.d("AichatApp", "✅ ContextBridge loaded")
                    showToast("✅ Bridge جاهز")
                }
            },
            { e ->
                Log.e("AichatApp", "❌ Bridge: ${e?.message}")
                showToast("❌ Bridge: ${e?.message}")
            }
        )
}

// في getOrCreateGeckoRuntime — أضف بعد loadAiCaptureExtension:
loadContextBridgeExtension(rt)

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
