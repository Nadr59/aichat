package com.example.aichat

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.example.aichat.data.local.ChatDatabase
import com.example.aichat.repository.MemoryContextBuilder
import com.example.aichat.repository.WebPlatformRepository
import com.example.aichat.data.model.MemoryItem
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtension
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AichatApp : Application() {

    @Volatile var geckoRuntime: GeckoRuntime? = null
        private set

    @Volatile var aiChatExtension: WebExtension? = null
        private set

    @Volatile private var captureFlag    = false
    @Volatile private var contextPending = ""
    @Volatile private var lastContextId  = 0L

    var onAiResponseCaptured:  ((domain: String, text: String) -> Unit)? = null
    var onManualCaptureResult: ((success: Boolean, text: String, debug: JSONObject?) -> Unit)? = null
    var onContextWritten:      ((success: Boolean, stage: String, detail: String) -> Unit)? = null

    lateinit var webPlatformRepository: WebPlatformRepository
        private set

    // ══════════════════════════════════════════════════════════════════
    // Debug Log
    // ══════════════════════════════════════════════════════════════════

    val debugLog: SnapshotStateList<String> = mutableStateListOf()

    fun logDebug(msg: String) {
        Log.d("AichatApp", msg)
        Handler(Looper.getMainLooper()).post {
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            debugLog.add(0, "$time  $msg")
            if (debugLog.size > 150) debugLog.removeAt(debugLog.lastIndex)
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }

    fun clearDebugLog() {
        debugLog.clear()
    }

    fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Public APIs
    // ══════════════════════════════════════════════════════════════════

    fun triggerCapture() {
        captureFlag = true
        logDebug("📌 captureFlag = true (manual capture)")
    }

    fun setContextPending(text: String) {
        if (text.isBlank()) {
            logDebug("⚠️ setContextPending: Empty text, ignoring")
            return
        }

        contextPending = text
        lastContextId = System.currentTimeMillis()
        logDebug("📤 Context pending set: ${text.length} chars, id=$lastContextId")
    }

    fun sendContextToPage(
        memories:              List<MemoryItem>,
        customInstruction:     String  = "",
        isSystemPromptEnabled: Boolean = true
    ) {
        val memoryContext = MemoryContextBuilder().build(memories)

        if (memoryContext.isBlank()) {
            logDebug("⚠️ sendContextToPage: No memories")
            return
        }

        val contextText = buildString {
            appendLine("السياق من محادثاتي السابقة:")
            appendLine()
            appendLine(memoryContext)
            appendLine()
            appendLine("───────────")
            appendLine()
            if (customInstruction.isNotBlank()) {
                appendLine("تعليمات مخصصة:")
                appendLine(customInstruction)
                appendLine()
            }
            appendLine("السؤال:")
        }

        setContextPending(contextText)
    }

    // ══════════════════════════════════════════════════════════════════
    // WebExtension MessageDelegate
    // ══════════════════════════════════════════════════════════════════

    private val messageDelegateAiCapture = object : WebExtension.MessageDelegate {

        override fun onMessage(
            nativeApp: String,
            message:   Any,
            sender:    WebExtension.MessageSender
        ): GeckoResult<Any>? {

            val json = parseMessage(message)
            if (json == null) {
                logDebug("❌ parseMessage=null")
                return GeckoResult.fromValue(null)
            }

            val type = json.optString("type")
            if (type.isBlank()) {
                logDebug("❌ empty type")
                return GeckoResult.fromValue(null)
            }

            if (type != "GET_CONTEXT") {
                logDebug("📩 [aicapture] $type")
            }

            return when (type) {

                // ══════════════════════════════════════════════════════
                // Manual Capture
                // ══════════════════════════════════════════════════════

                "CHECK_CAPTURE" -> handleCheckCapture(json)

                "CAPTURE_RESULT" -> {
                    handleCaptureResult(json)
                    GeckoResult.fromValue(null)
                }

                // ══════════════════════════════════════════════════════
                // Auto Response
                // ══════════════════════════════════════════════════════

                "AI_RESPONSE" -> {
                    handleAutoResponse(json)
                    GeckoResult.fromValue(null)
                }

                // ══════════════════════════════════════════════════════
                // Context Flow
                // ══════════════════════════════════════════════════════

                "GET_CONTEXT" -> handleGetContext()

                "CONTEXT_WRITTEN" -> {
                    handleContextWritten(json)
                    GeckoResult.fromValue(null)
                }

                "CONTEXT_CONSUMED" -> {
                    handleContextConsumed(json)
                    GeckoResult.fromValue(null)
                }

                // ══════════════════════════════════════════════════════
                // Debug
                // ══════════════════════════════════════════════════════

                "DEBUG_INFO" -> {
                    val info = json.optString("info")
                    logDebug("🔍 JS: $info")
                    GeckoResult.fromValue(null)
                }

                else -> {
                    logDebug("⚠️ unknown type: $type")
                    GeckoResult.fromValue(null)
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Message Handlers
    // ══════════════════════════════════════════════════════════════════

    private fun handleCheckCapture(json: JSONObject): GeckoResult<Any> {
        val flag = captureFlag
        captureFlag = false
        
        if (flag) {
            logDebug("📡 Capture triggered")
        }
        
        return GeckoResult.fromValue(
            JSONObject().put("capture", flag)
        )
    }

    private fun handleCaptureResult(json: JSONObject) {
        val success = json.optBoolean("success", false)
        val text    = json.optString("text")
        val debug   = json.optJSONObject("debug")
        
        val msg = if (success) {
            "🧠 Capture OK: ${text.take(60)}…"
        } else {
            "⚠️ Capture Failed"
        }
        logDebug(msg)
        
        Handler(Looper.getMainLooper()).post {
            onManualCaptureResult?.invoke(success, text, debug)
        }
    }

    private fun handleAutoResponse(json: JSONObject) {
        val text   = json.optString("text")
        val domain = json.optString("domain", "unknown")
        
        if (text.length < 80) return
        
        logDebug("📨 Auto Response: ${text.take(60)}…")
        
        Handler(Looper.getMainLooper()).post {
            onAiResponseCaptured?.invoke(domain, text)
        }
    }

    /**
     * GET_CONTEXT — Response directly from onMessage
     * No separate Native Host needed!
     */
    private fun handleGetContext(): GeckoResult<Any> {
        return try {
            val pending = contextPending
            val has = pending.isNotBlank()
            
            logDebug("📤 GET_CONTEXT: has=$has, len=${pending.length}")
            
            val response = JSONObject()
            response.put("hasContext", has)
            response.put("context", if (has) pending else "")
            response.put("id", lastContextId)
            
            logDebug("📤 Sending response: hasContext=$has, len=${response.optString("context").length}")
            
            GeckoResult.fromValue(response)
        } catch (e: Exception) {
            logDebug("❌ GET_CONTEXT error: ${e.message}")
            e.printStackTrace()
            GeckoResult.fromValue(
                JSONObject()
                    .put("hasContext", false)
                    .put("context", "")
            )
        }
    }

    private fun handleContextWritten(json: JSONObject) {
        val success    = json.optBoolean("success", false)
        val contextId  = json.optLong("contextId", 0L)
        val stage      = json.optString("stage", "")
        val detail     = json.optString("detail", "")
        
        logDebug("✏️ CONTEXT_WRITTEN: success=$success, id=$contextId")
        
        Handler(Looper.getMainLooper()).post {
            onContextWritten?.invoke(success, stage, detail)
        }
        
        val userMsg = when {
            !success -> "❌ فشل الإرسال: $detail"
            stage == "button_clicked" -> "✅ تم الإرسال بنجاح"
            else -> "✅ تمت الكتابة"
        }
        showToast(userMsg)
    }

    private fun handleContextConsumed(json: JSONObject) {
        val contextId = json.optLong("contextId", 0L)
        
        logDebug("🗑️ CONTEXT_CONSUMED: contextId=$contextId")
        
        if (contextId == lastContextId && contextId != 0L) {
            contextPending = ""
            lastContextId = 0L
            logDebug("✅ contextPending cleared")
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // onCreate
    // ══════════════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        try {
            val db = ChatDatabase.getDatabase(this)
            webPlatformRepository = WebPlatformRepository(db.webPlatformDao())
            Log.d("AichatApp", "✅ Database ready")
        } catch (e: Exception) {
            Log.e("AichatApp", "❌ Database failed: ${e.message}", e)
            showToast("❌ Database error")
            throw e
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // GeckoRuntime
    // ══════════════════════════════════════════════════════════════════

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
            Log.e("AichatApp", "❌ GeckoRuntime error: ${e.message}", e)
            showToast("❌ GeckoRuntime error")
            null
        }
    }

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
                        Log.d("AichatApp", "✅ Extension loaded: ${ext.id}")
                        logDebug("✅ Extension loaded successfully")
                    } else {
                        Log.e("AichatApp", "❌ Extension = null")
                        logDebug("❌ Extension load failed")
                    }
                },
                { error ->
                    val msg = error?.message ?: "unknown"
                    Log.e("AichatApp", "❌ Extension error: $msg")
                    logDebug("❌ Extension error: $msg")
                }
            )
    }

    // ══════════════════════════════════════════════════════════════════
    // Message Parser
    // ══════════════════════════════════════════════════════════════════

    private fun parseMessage(message: Any): JSONObject? = try {
        when (message) {
            is JSONObject -> message
            is Map<*, *>  -> JSONObject(message as Map<*, *>)
            else -> null
        }
    } catch (e: Exception) {
        Log.e("AichatApp", "❌ parseMessage: ${e.message}")
        null
    }
}
