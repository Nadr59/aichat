package com.example.aichat

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.example.aichat.data.local.ChatDatabase
import com.example.aichat.data.model.MemoryItem
import com.example.aichat.repository.MemoryContextBuilder
import com.example.aichat.repository.WebPlatformRepository
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtension
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AichatApp : Application() {

    companion object {
        private const val TAG = "AichatApp"

        // صلاحية النص المُرسل للصفحة (حتى لو تأخر تحميل الصفحة/المنصة)
        private const val CONTEXT_TTL_MS = 120_000L

        // مهلة انتظار نتيجة الالتقاط اليدوي
        private const val MANUAL_CAPTURE_TTL_MS = 15_000L

        // اسم الإضافة (assets/aicapture/)
        private const val EXT_BASE_URL = "resource://android/assets/aicapture/"
        private const val EXT_ID = "aicapture@aichat.example.com"
        private const val EXT_NAMESPACE = "browser"
    }

    // =========================
    // Gecko runtime + Extension
    // =========================

    @Volatile
    var geckoRuntime: GeckoRuntime? = null
        private set

    @Volatile
    var aiChatExtension: WebExtension? = null
        private set

    @Volatile
    private var port: WebExtension.Port? = null

    // =========================
    // Web platform repo (اختياري/مفيد)
    // =========================

    lateinit var webPlatformRepository: WebPlatformRepository
        private set

    // =========================
    // Debug log
    // =========================

    val debugLog: SnapshotStateList<String> = mutableStateListOf()

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun logDebug(msg: String, toast: Boolean = false) {
        Log.d(TAG, msg)
        mainHandler.post {
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            debugLog.add(0, "$time  $msg")
            if (debugLog.size > 150) debugLog.removeAt(debugLog.lastIndex)
            if (toast) {
                Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun clearDebugLog() {
        debugLog.clear()
    }

    fun showToast(msg: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }

    // =========================
    // Public callbacks (يستخدمها UI)
    // =========================

    /**
     * ردود المساعد الملتقطة تلقائياً من منصات الويب
     */
    var onAiResponseCaptured: ((domain: String, text: String) -> Unit)? = null

    /**
     * نتيجة الالتقاط اليدوي (زر 🧠)
     */
    var onManualCaptureResult: ((success: Boolean, text: String, debug: JSONObject?) -> Unit)? = null

    /**
     * نتيجة الكتابة/الإرسال داخل المنصة (من الإضافة)
     */
    var onContextWritten: ((success: Boolean, stage: String, detail: String) -> Unit)? = null

    // =========================
    // Manual capture state
    // =========================

    @Volatile
    private var captureFlag = false

    @Volatile
    private var manualCaptureStartedAt = 0L

    fun triggerCapture() {
        captureFlag = true
        manualCaptureStartedAt = System.currentTimeMillis()
        logDebug("📌 MANUAL_CAPTURE start")

        val p = port
        if (p == null) {
            logDebug("❌ MANUAL_CAPTURE: port unavailable")
            captureFlag = false
            manualCaptureStartedAt = 0L
            mainHandler.post {
                onManualCaptureResult?.invoke(false, "", JSONObject().put("reason", "port_unavailable"))
            }
            return
        }

        runCatching {
            val msg = JSONObject()
                .put("type", "MANUAL_CAPTURE")
                .put("timestamp", System.currentTimeMillis())
            p.postMessage(msg)
            logDebug("📤 MANUAL_CAPTURE sent to extension")
        }.onFailure { e ->
            captureFlag = false
            manualCaptureStartedAt = 0L
            logDebug("❌ MANUAL_CAPTURE failed: ${e.message ?: e}")
            mainHandler.post {
                onManualCaptureResult?.invoke(
                    false,
                    "",
                    JSONObject()
                        .put("reason", "port_send_failed")
                        .put("error", e.message ?: e.toString())
                )
            }
        }
    }

    // =========================
    // Context-to-page (Auto Send)
    // =========================

    private val ctxLock = Any()

    // النص المراد إرساله للمنصة
    private var contextPending: String = ""
    private var lastContextId: Long = 0L
    private var contextSetAt: Long = 0L

    // الهدف (اختياري): host domain مثل chatgpt.com
    private var contextTargetDomain: String = ""

    // selectors للإرسال التلقائي
    private var pendingInputSelector: String = ""
    private var pendingSendSelector: String = ""
    private var pendingSendWithEnter: Boolean = true

    /**
     * ✅ هذا هو المسار المعتمد للإرسال الآن (بدون evaluateJS).
     * GeckoTestScreen يستدعيه عند الضغط على "إرسال للمنصة".
     */
    fun setContextPending(
        text: String,
        targetDomain: String = "",
        inputSelector: String = "",
        sendSelector: String = "",
        sendWithEnter: Boolean = true
    ) {
        val t = text.trim()
        if (t.isBlank()) {
            logDebug("⚠️ setContextPending: empty text")
            return
        }

        val id = System.currentTimeMillis()

        synchronized(ctxLock) {
            contextPending = t
            lastContextId = id
            contextSetAt = id
            contextTargetDomain = targetDomain.trim().lowercase(Locale.ROOT)

            pendingInputSelector = inputSelector
            pendingSendSelector = sendSelector
            pendingSendWithEnter = sendWithEnter
        }

        logDebug(
            "📤 Context pending: len=${t.length}, id=$id" +
                (if (contextTargetDomain.isNotBlank()) ", target=$contextTargetDomain" else ""),
            toast = true
        )

        sendPendingContextToPort()
    }

    /**
     * إرسال سياق ذاكرة للمنصة (قديم/اختياري).
     * يبني النص ثم يمرره لـ setContextPending.
     */
    fun sendContextToPage(
        memories: List<MemoryItem>,
        customInstruction: String = "",
        isSystemPromptEnabled: Boolean = true,
        targetDomain: String = "",
        inputSelector: String = "",
        sendSelector: String = "",
        sendWithEnter: Boolean = true
    ) {
        if (!isSystemPromptEnabled) {
            // المستخدم عطّل ذاكرة الويب
            return
        }

        val memoryContext = MemoryContextBuilder().build(memories)
        if (memoryContext.isBlank()) {
            logDebug("⚠️ sendContextToPage: no memories", toast = true)
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

        setContextPending(
            text = contextText,
            targetDomain = targetDomain,
            inputSelector = inputSelector,
            sendSelector = sendSelector,
            sendWithEnter = sendWithEnter
        )
    }

    private fun sendPendingContextToPort(forced: WebExtension.Port? = null) {
        val p = forced ?: port
        if (p == null) {
            logDebug("⚠️ CONTEXT_TO_PAGE: port unavailable")
            return
        }

        val snapshot: JSONObject = synchronized(ctxLock) {
            if (contextPending.isBlank()) {
                logDebug("⚠️ CONTEXT_TO_PAGE: no pending context")
                return
            }

            val age = System.currentTimeMillis() - contextSetAt
            if (age > CONTEXT_TTL_MS) {
                contextPending = ""
                lastContextId = 0L
                contextSetAt = 0L
                logDebug("⌛ CONTEXT_TO_PAGE: expired")
                return
            }

            JSONObject()
                .put("type", "CONTEXT_TO_PAGE")
                .put("text", contextPending)
                .put("contextId", lastContextId)
                .put("domain", contextTargetDomain)
                .put("inputSelector", pendingInputSelector)
                .put("sendSelector", pendingSendSelector)
                .put("sendWithEnter", pendingSendWithEnter)
                .put("timestamp", System.currentTimeMillis())
        }

        runCatching {
            p.postMessage(snapshot)
            logDebug(
                "📤 CONTEXT_TO_PAGE sent: len=${snapshot.optString("text").length}, id=${snapshot.optLong("contextId")}" +
                    (snapshot.optString("domain").takeIf { it.isNotBlank() }?.let { ", target=$it" } ?: "")
            )
        }.onFailure { e ->
            logDebug("❌ CONTEXT_TO_PAGE failed: ${e.message ?: e}")
        }
    }

    private fun clearContextIfMatches(id: Long): Boolean {
        synchronized(ctxLock) {
            if (id != 0L && id == lastContextId) {
                contextPending = ""
                lastContextId = 0L
                contextSetAt = 0L
                return true
            }
        }
        return false
    }

    // =========================
    // Port message handling
    // =========================

    private fun handleManualAssistantResponseIfWaiting(source: String, text: String): Boolean {
        if (!captureFlag) return false

        val age = System.currentTimeMillis() - manualCaptureStartedAt
        if (age > MANUAL_CAPTURE_TTL_MS) {
            logDebug("⌛ Manual capture response too late: ${age}ms")
            captureFlag = false
            manualCaptureStartedAt = 0L
            return false
        }

        captureFlag = false
        manualCaptureStartedAt = 0L
        logDebug("🧠 Manual capture fulfilled via ASSISTANT_RESPONSE")

        mainHandler.post {
            onManualCaptureResult?.invoke(
                true,
                text,
                JSONObject()
                    .put("assistant", 1)
                    .put("bodyLen", text.length)
                    .put("source", source)
            )
        }
        return true
    }

    private fun handleIncoming(messageAny: Any) {
        val json = parseMessage(messageAny)
        if (json == null) {
            logDebug("❌ PORT parseMessage=null")
            return
        }

        val type = json.optString("type")
        if (type.isBlank()) {
            logDebug("❌ PORT message has empty type")
            return
        }

        when (type) {
            "ASSISTANT_RESPONSE" -> {
                val text = json.optString("text", "")
                val source = json.optString("source", "web")
                if (text.isBlank()) return

                logDebug("📨 ASSISTANT_RESPONSE: source=$source len=${text.length}")

                if (handleManualAssistantResponseIfWaiting(source, text)) return

                mainHandler.post { onAiResponseCaptured?.invoke(source, text) }
            }

            "CAPTURE_RESULT" -> {
                val success = json.optBoolean("success", false)
                val text = json.optString("text", "")
                val debug = json.optJSONObject("debug")

                captureFlag = false
                manualCaptureStartedAt = 0L

                logDebug(if (success) "🧠 CAPTURE_RESULT OK: ${text.take(60)}…" else "⚠️ CAPTURE_RESULT failed")

                mainHandler.post { onManualCaptureResult?.invoke(success, text, debug) }
            }

            "CONTEXT_WRITTEN" -> {
                val success = json.optBoolean("success", false)
                val contextId = json.optLong("contextId", 0L)
                val stage = json.optString("stage", "")
                val detail = json.optString("detail", "")

                logDebug("✏️ CONTEXT_WRITTEN success=$success id=$contextId stage=$stage detail=$detail")

                mainHandler.post { onContextWritten?.invoke(success, stage, detail) }

                // Toast خفيف مفيد للمستخدم
                showToast(
                    when {
                        !success -> "❌ فشل الإرسال: $stage ($detail)"
                        stage == "button_clicked" -> "✅ تم الإرسال"
                        stage == "enter_sent" -> "✅ أُرسل بالضغط على Enter"
                        else -> "✅ تمّت الكتابة"
                    }
                )
            }

            "CONTEXT_CONSUMED" -> {
                val contextId = json.optLong("contextId", 0L)
                val cleared = clearContextIfMatches(contextId)
                logDebug("🗑️ CONTEXT_CONSUMED id=$contextId cleared=$cleared")
            }

            "DEBUG_INFO" -> {
                logDebug("🔍 JS: ${json.optString("info")}")
            }

            else -> {
                logDebug("ℹ️ PORT msg: $type")
            }
        }
    }

    private val messageDelegate = object : WebExtension.MessageDelegate {

        override fun onConnect(port: WebExtension.Port) {
            logDebug("🔌 Native Port connected")
            this@AichatApp.port = port

            port.setDelegate(object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, port: WebExtension.Port) {
                    handleIncoming(message)
                }

                override fun onDisconnect(port: WebExtension.Port) {
                    logDebug("⚠️ Native Port disconnected")
                    if (this@AichatApp.port === port) this@AichatApp.port = null
                }
            })

            // إذا كان هناك نص pending قبل اتصال المنفذ (تحميل بطيء للمنصة)
            mainHandler.postDelayed({ sendPendingContextToPort(port) }, 500)
        }

        /**
         * بعض رسائل الاختبار/الـ background قد تصل هنا.
         * نرد دائماً ok حتى لا تتعطل الإضافة.
         */
        override fun onMessage(
            nativeApp: String,
            message: Any,
            sender: WebExtension.MessageSender
        ): GeckoResult<Any>? {
            // تعامل معها مثل رسائل المنفذ إن كانت بنفس الصيغة
            handleIncoming(message)
            return GeckoResult.fromValue(JSONObject().put("ok", true) as Any)
        }
    }

    // =========================
    // Lifecycle
    // =========================

    override fun onCreate() {
        super.onCreate()

        // DB + repo
        val db = ChatDatabase.getDatabase(this)
        webPlatformRepository = WebPlatformRepository(db.webPlatformDao())
        Log.d(TAG, "✅ Database ready")
    }

    @Synchronized
    fun getOrCreateGeckoRuntime(): GeckoRuntime? {
        geckoRuntime?.let { return it }

        return runCatching {
            val settings = GeckoRuntimeSettings.Builder()
                .aboutConfigEnabled(false)
                .consoleOutput(true)
                .remoteDebuggingEnabled(true)
                .build()

            GeckoRuntime.create(applicationContext, settings).also { rt ->
                geckoRuntime = rt
                loadExtension(rt)
                Log.d(TAG, "✅ GeckoRuntime created")
            }
        }.onFailure { e ->
            Log.e(TAG, "❌ GeckoRuntime error: ${e.message}", e)
            showToast("❌ GeckoRuntime error")
        }.getOrNull()
    }

    private fun loadExtension(runtime: GeckoRuntime) {
        runtime.webExtensionController
            .ensureBuiltIn(EXT_BASE_URL, EXT_ID)
            .accept(
                { ext ->
                    if (ext == null) {
                        Log.e(TAG, "❌ Extension is null")
                        logDebug("❌ Extension load failed")
                        return@accept
                    }

                    aiChatExtension = ext
                    ext.setMessageDelegate(messageDelegate, EXT_NAMESPACE)

                    val ver = ext.metaData?.version ?: "?"
                    val baseUrl = ext.metaData?.baseUrl ?: "?"
                    val temporary = ext.metaData?.temporary ?: false

                    Log.d(TAG, "✅ Extension loaded: id=${ext.id} v=$ver baseUrl=$baseUrl temporary=$temporary")
                    logDebug("✅ Extension loaded: v=$ver baseUrl=$baseUrl")
                },
                { error ->
                    val msg = error?.message ?: "unknown"
                    Log.e(TAG, "❌ Extension error: $msg")
                    logDebug("❌ Extension error: $msg")
                }
            )
    }

    // =========================
    // Helpers
    // =========================

    private fun parseMessage(message: Any): JSONObject? = try {
        when (message) {
            is JSONObject -> message
            is Map<*, *> -> JSONObject(message as Map<*, *>)
            is String -> JSONObject(message)
            else -> null
        }
    } catch (e: Exception) {
        Log.e(TAG, "❌ parseMessage: ${e.message}")
        null
    }
}
