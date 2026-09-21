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

    // ── يُستدعى من زر 🧠 ─────────────────────────────────────────────

    fun triggerCapture() {
        captureFlag = true
        Log.d("AichatApp", "📌 Capture flag set")
        showToast("🔍 جاري البحث في الصفحة...")
    }

    // ── إرسال السياق لـ content.js ───────────────────────────────────

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
        Log.d("AichatApp", "📤 contextPendingMessage set: ${systemPrompt.length} chars")
    }

    // ── MessageDelegate ───────────────────────────────────────────────

    private val messageDelegate = object : WebExtension.MessageDelegate {

        override fun onMessage(
            nativeApp: String,
            message:   Any,
            sender:    WebExtension.MessageSender
        ): GeckoResult<Any>? {

            val json = parseMessage(message) ?: run {
                Log.e("AichatApp", "❌ parseMessage failed — raw: $message")
                return null
            }

            val type = json.optString("type")
            Log.d("AichatApp", "📩 onMessage type=$type")

            return when (type) {

                "CHECK_CAPTURE" -> {
                    val flag    = captureFlag
                    captureFlag = false
                    if (flag) showToast("📡 تم الاتصال — جاري الاستخراج...")
                    GeckoResult.fromValue(
                        JSONObject().put("capture", flag)
                    )
                }

                "GET_CONTEXT" -> {
                    val pending           = contextPendingMessage
                    contextPendingMessage = ""
                    Log.d("AichatApp", "📤 GET_CONTEXT → ${pending.take(80)}")
                    GeckoResult.fromValue(
                        JSONObject()
                            .put("hasContext", pending.isNotBlank())
                            .put("context",    pending)
                    )
                }

                "DEBUG_BUTTONS" -> {
                    val buttons = json.optJSONArray("buttons")
                    val domain  = json.optString("domain")
                    val sb      = StringBuilder("🔍 أزرار $domain:\n")
                    if (buttons != null) {
                        for (i in 0 until buttons.length()) {
                            val btn    = buttons.optJSONObject(i)
                            val label  = btn?.optString("label")  ?: ""
                            val testid = btn?.optString("testid") ?: ""
                            val t      = btn?.optString("type")   ?: ""
                            sb.append("• label=$label testid=$testid type=$t\n")
                        }
                    } else {
                        sb.append("لا يوجد أزرار!")
                    }
                    Log.d("AichatApp", sb.toString())
                    showToast(sb.toString())
                    null
                }

                "AI_RESPONSE" -> {
                    handleAutoResponse(json)
                    null
                }

                "CAPTURE_RESULT" -> {
                    handleCaptureResult(json)
                    null
                }

                else -> {
                    Log.w("AichatApp", "⚠️ unknown type: $type")
                    null
                }
            }
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
                    showToast("❌ خطأ: $msg")
                    showToast("❌ السبب: $cause")
                }
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
    } catch (e: Exception) {
        Log.e("AichatApp", "❌ parseMessage: ${e.message}")
        null
    }
}
