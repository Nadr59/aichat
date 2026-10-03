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
        private const val CONTEXT_TTL_MS = 120_000L
        private const val REDELIVER_AFTER_MS = 20_000L
    }

    @Volatile
    var geckoRuntime: GeckoRuntime? = null
        private set

    @Volatile
    var aiChatExtension: WebExtension? = null
        private set

    @Volatile
    private var aiCapturePort: WebExtension.Port? = null

    @Volatile
    private var captureFlag = false

    private val ctxLock = Any()

    private var contextPending = ""
    private var lastContextId = 0L
    private var contextSetAt = 0L
    private var contextTarget = ""
    private var contextDeliveredAt = 0L

    var onAiResponseCaptured: ((domain: String, text: String) -> Unit)? = null

    var onManualCaptureResult:
        ((success: Boolean, text: String, debug: JSONObject?) -> Unit)? = null

    var onContextWritten:
        ((success: Boolean, stage: String, detail: String) -> Unit)? = null

    lateinit var webPlatformRepository: WebPlatformRepository
        private set

    val debugLog: SnapshotStateList<String> = mutableStateListOf()

    private val mainHandler by lazy {
        Handler(Looper.getMainLooper())
    }

    fun logDebug(
        msg: String,
        toast: Boolean = false
    ) {
        Log.d(TAG, msg)

        mainHandler.post {
            val time = SimpleDateFormat(
                "HH:mm:ss",
                Locale.US
            ).format(Date())

            debugLog.add(
                0,
                "$time  $msg"
            )

            if (debugLog.size > 150) {
                debugLog.removeAt(
                    debugLog.lastIndex
                )
            }

            if (toast) {
                Toast.makeText(
                    applicationContext,
                    msg,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    fun clearDebugLog() {
        debugLog.clear()
    }

    fun showToast(msg: String) {
        mainHandler.post {
            Toast.makeText(
                applicationContext,
                msg,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun triggerCapture() {
        captureFlag = true

        logDebug(
            "📌 captureFlag = true (manual capture)"
        )
    }

    fun setContextPending(
        text: String,
        targetDomain: String = ""
    ) {
        if (text.isBlank()) {
            logDebug(
                "⚠️ setContextPending: Empty text, ignoring"
            )
            return
        }

        val id = System.currentTimeMillis()

        synchronized(ctxLock) {
            contextPending = text
            lastContextId = id
            contextSetAt = id
            contextTarget =
                targetDomain
                    .trim()
                    .lowercase(Locale.ROOT)
            contextDeliveredAt = 0L
        }

        logDebug(
            "📤 Context pending set: ${text.length} chars, id=$id" +
                if (targetDomain.isNotBlank()) {
                    ", target=$targetDomain"
                } else {
                    ""
                },
            toast = true
        )
    }

    fun sendContextToPage(
        memories: List<MemoryItem>,
        customInstruction: String = "",
        isSystemPromptEnabled: Boolean = true,
        targetDomain: String = ""
    ) {
        val memoryContext =
            MemoryContextBuilder().build(memories)

        if (memoryContext.isBlank()) {
            logDebug(
                "⚠️ sendContextToPage: No memories",
                toast = true
            )
            return
        }

        val contextText = buildString {

            appendLine(
                "السياق من محادثاتي السابقة:"
            )

            appendLine()

            appendLine(memoryContext)

            appendLine()

            appendLine("───────────")

            appendLine()

            if (customInstruction.isNotBlank()) {

                appendLine(
                    "تعليمات مخصصة:"
                )

                appendLine(
                    customInstruction
                )

                appendLine()
            }

            appendLine("السؤال:")
        }

        setContextPending(
            contextText,
            targetDomain
        )
    }

    private data class ContextDelivery(
        val text: String,
        val id: Long
    )

    private fun domainMatches(
        domain: String,
        target: String
    ): Boolean {

        if (target.isBlank()) {
            return true
        }

        val d =
            domain.lowercase(Locale.ROOT)

        return d == target ||
            d.endsWith(".$target")
    }

    private fun takeContextFor(
        domain: String
    ): ContextDelivery? {

        val now =
            System.currentTimeMillis()

        var expired = false

        var result: ContextDelivery? = null

        synchronized(ctxLock) {

            if (contextPending.isBlank()) {
                return null
            }

            if (
                now - contextSetAt >
                CONTEXT_TTL_MS
            ) {

                contextPending = ""
                lastContextId = 0L
                expired = true

            } else if (
                domainMatches(
                    domain,
                    contextTarget
                ) &&
                (
                    contextDeliveredAt == 0L ||
                    now - contextDeliveredAt >
                    REDELIVER_AFTER_MS
                )
            ) {

                contextDeliveredAt = now

                result =
                    ContextDelivery(
                        contextPending,
                        lastContextId
                    )
            }
        }

        if (expired) {
            logDebug(
                "⌛ Context expired, cleared"
            )
        }

        return result
    }

    private fun clearContextIfMatches(
        id: Long
    ): Boolean {

        synchronized(ctxLock) {

            if (
                id != 0L &&
                id == lastContextId
            ) {

                contextPending = ""
                lastContextId = 0L
                contextDeliveredAt = 0L

                return true
            }
        }

        return false
    }

    private fun reply(
        obj: JSONObject =
            JSONObject().put("ok", true)
    ): GeckoResult<Any>? {

        return GeckoResult.fromValue<Any>(
            obj
        )
    }

    private val messageDelegateAiCapture =
        object : WebExtension.MessageDelegate {

            override fun onConnect(
                port: WebExtension.Port
            ) {

                logDebug(
                    "🔌 REVERSE TEST: Kotlin Port connected"
                )

                aiCapturePort = port

                port.setDelegate(
                    object : WebExtension.PortDelegate {

                        override fun onPortMessage(
    message: Any,
    port: WebExtension.Port
) {
    logDebug(
        "📩 REVERSE TEST: " +
        "Background → Kotlin: $message"
    )
                        }
                        override fun onDisconnect(
                            port: WebExtension.Port
                        ) {

                            logDebug(
                                "⚠️ REVERSE TEST: Port disconnected"
                            )

                            if (aiCapturePort === port) {
                                aiCapturePort = null
                            }
                        }
                    }
                )

                logDebug(
                    "✅ REVERSE TEST: Port delegate attached"
                )

                /*
                 * نؤخر الإرسال قليلًا.
                 *
                 * الهدف:
                 * التأكد من أن Background أصبح جاهزًا
                 * لاستقبال رسائل الـ Port قبل إرسال
                 * الرسالة من Kotlin.
                 */
                Handler(Looper.getMainLooper()).postDelayed({

                    val testMessage =
                        JSONObject()
                            .put(
                                "type",
                                "REVERSE_TEST"
                            )
                            .put(
                                "source",
                                "AichatApp.kt"
                            )
                            .put(
                                "text",
                                "HELLO_FROM_KOTLIN"
                            )
                            .put(
                                "timestamp",
                                System.currentTimeMillis()
                            )

                    try {

                        port.postMessage(testMessage)

                        logDebug(
                            "📤 REVERSE TEST: Kotlin → Background " +
                            "postMessage() SENT AFTER DELAY"
                        )

                    } catch (e: Exception) {

                        logDebug(
                            "❌ REVERSE TEST: Kotlin → Background FAILED: " +
                            (e.message ?: e.toString())
                        )
                    }

                }, 1000)
            }

            override fun onMessage(
                nativeApp: String,
                message: Any,
                sender: WebExtension.MessageSender
            ): GeckoResult<Any>? {

                // بقية onMessage الحالية لديك تبقى كما هي
                val json =
                    parseMessage(message)

                if (json == null) {

                    logDebug(
                        "❌ parseMessage=null (${message.javaClass.simpleName})"
                    )

                    return reply()
                }

                val type =
                    json.optString("type")

                if (type.isBlank()) {

                    logDebug(
                        "❌ empty type"
                    )

                    return reply()
                }

                if (
                    type != "POLL" &&
                    type != "DEBUG_INFO"
                ) {

                    logDebug(
                        "📩 [aicapture] $type"
                    )
                }

                return when (type) {

                    // ====================================================
                    // Native Messaging diagnostic test
                    // ====================================================

                    "AICHAT_NATIVE_TEST" -> {

                        val source =
                            json.optString(
                                "source",
                                "unknown"
                            )

                        val timestamp =
                            json.optString(
                                "timestamp",
                                ""
                            )

                        logDebug(
                            "🧪 AICHAT_NATIVE_TEST RECEIVED from $nativeApp, source=$source, timestamp=$timestamp"
                        )

                        showToast(
                            "✅ Native Messaging وصل إلى Kotlin"
                        )

                        reply(
                            JSONObject()
                                .put(
                                    "ok",
                                    true
                                )
                                .put(
                                    "native",
                                    true
                                )
                                .put(
                                    "received",
                                    true
                                )
                                .put(
                                    "source",
                                    source
                                )
                        )
                    }

                    "DIRECT_TEST_2" -> {
                        val source = json.optString("source", "")
                        val text = json.optString("text", "")
                        val timestamp = json.optString("timestamp", "")

                        logDebug(
                            "🧪 DIRECT_TEST_2 RECEIVED: " +
                                "source=$source, text=$text, timestamp=$timestamp"
                        )

                        reply(
                            JSONObject()
                                .put("ok", true)
                                .put("native", true)
                                .put("test", "DIRECT_TEST_2")
                        )
                    }

                    // ====================================================
                    // Direct test
                    // ====================================================

                    "DIRECT_TEST" -> {

                        val messageText =
                            json.optString(
                                "message",
                                ""
                            )

                        val domain =
                            json.optString(
                                "domain",
                                ""
                            )

                        logDebug(
                            "🧪 DIRECT_TEST RECEIVED: message=$messageText, domain=$domain"
                        )

                        showToast(
                            "🧪 DIRECT_TEST وصل إلى Kotlin"
                        )

                        reply(
                            JSONObject()
                                .put(
                                    "ok",
                                    true
                                )
                                .put(
                                    "received",
                                    true
                                )
                        )
                    }

                    // ====================================================
                    // Ping
                    // ====================================================

                    "PING" -> {

                        logDebug(
                            "🏓 PING received from background"
                        )

                        reply(
                            JSONObject()
                                .put(
                                    "pong",
                                    true
                                )
                        )
                    }

                    // ====================================================
                    // Poll
                    // ====================================================

                    "POLL" -> {

                        val domain =
                            json.optString(
                                "domain"
                            )

                        val visible =
                            json.optBoolean(
                                "visible",
                                true
                            )

                        val resp =
                            JSONObject()

                        var capture =
                            false

                        if (
                            visible &&
                            captureFlag
                        ) {

                            captureFlag =
                                false

                            capture =
                                true

                            logDebug(
                                "📡 Capture triggered for $domain"
                            )
                        }

                        resp.put(
                            "capture",
                            capture
                        )

                        val delivery =
                            takeContextFor(domain)

                        if (delivery != null) {

                            logDebug(
                                "📤 Delivering context: len=${delivery.text.length}, id=${delivery.id}, domain=$domain, visible=$visible"
                            )

                            resp.put(
                                "hasContext",
                                true
                            )

                            resp.put(
                                "context",
                                delivery.text
                            )

                            resp.put(
                                "id",
                                delivery.id
                            )

                        } else {

                            resp.put(
                                "hasContext",
                                false
                            )

                            resp.put(
                                "context",
                                ""
                            )

                            resp.put(
                                "id",
                                0L
                            )
                        }

                        reply(resp)
                    }

                    // ====================================================
                    // Capture result
                    // ====================================================

                    "CAPTURE_RESULT" -> {

                        val success =
                            json.optBoolean(
                                "success",
                                false
                            )

                        val text =
                            json.optString(
                                "text"
                            )

                        val debug =
                            json.optJSONObject(
                                "debug"
                            )

                        logDebug(
                            if (success) {
                                "🧠 Capture OK: ${text.take(60)}…"
                            } else {
                                "⚠️ Capture Failed"
                            }
                        )

                        mainHandler.post {

                            onManualCaptureResult
                                ?.invoke(
                                    success,
                                    text,
                                    debug
                                )
                        }

                        reply()
                    }

                    // ====================================================
                    // AI response
                    // ====================================================

                    "AI_RESPONSE" -> {

                        val text =
                            json.optString(
                                "text"
                            )

                        val domain =
                            json.optString(
                                "domain",
                                "unknown"
                            )

                        if (text.length >= 80) {

                            logDebug(
                                "📨 Auto Response: ${text.take(60)}…"
                            )

                            mainHandler.post {

                                onAiResponseCaptured
                                    ?.invoke(
                                        domain,
                                        text
                                    )
                            }
                        }

                        reply()
                    }

                    // ====================================================
                    // Context written
                    // ====================================================

                    "CONTEXT_WRITTEN" -> {

                        val success =
                            json.optBoolean(
                                "success",
                                false
                            )

                        val contextId =
                            json.optLong(
                                "contextId",
                                0L
                            )

                        val stage =
                            json.optString(
                                "stage",
                                ""
                            )

                        val detail =
                            json.optString(
                                "detail",
                                ""
                            )

                        logDebug(
                            "✏️ CONTEXT_WRITTEN: success=$success, id=$contextId, stage=$stage, detail=$detail"
                        )

                        mainHandler.post {

                            onContextWritten
                                ?.invoke(
                                    success,
                                    stage,
                                    detail
                                )
                        }

                        showToast(
                            when {

                                !success ->
                                    "❌ فشل الإرسال: $stage ($detail)"

                                stage ==
                                    "button_clicked" ->
                                    "✅ تم الإرسال بنجاح"

                                stage ==
                                    "enter_sent" ->
                                    "✅ أُرسل بالضغط على Enter"

                                else ->
                                    "✅ تمت الكتابة"
                            }
                        )

                        reply()
                    }

                    // ====================================================
                    // Context consumed
                    // ====================================================

                    "CONTEXT_CONSUMED" -> {

                        val contextId =
                            json.optLong(
                                "contextId",
                                0L
                            )

                        val cleared =
                            clearContextIfMatches(
                                contextId
                            )

                        logDebug(
                            "🗑️ CONTEXT_CONSUMED: id=$contextId, cleared=$cleared"
                        )

                        reply()
                    }

                    // ====================================================
                    // Debug information
                    // ====================================================

                    "DEBUG_INFO" -> {

                        logDebug(
                            "🔍 JS: " +
                                json.optString(
                                    "info"
                                )
                        )

                        reply()
                    }

                    // ====================================================
                    // Unknown
                    // ====================================================

                    else -> {

                        logDebug(
                            "⚠️ unknown type: $type"
                        )

                        reply()
                    }
                }
            }
        }

    override fun onCreate() {

        super.onCreate()

        try {

            val db =
                ChatDatabase.getDatabase(
                    this
                )

            webPlatformRepository =
                WebPlatformRepository(
                    db.webPlatformDao()
                )

            Log.d(
                TAG,
                "✅ Database ready"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Database failed: ${e.message}",
                e
            )

            showToast(
                "❌ Database error"
            )

            throw e
        }
    }

    @Synchronized
    fun getOrCreateGeckoRuntime(): GeckoRuntime? {

        if (geckoRuntime != null) {
            return geckoRuntime
        }

        return try {

            val settings =
                GeckoRuntimeSettings.Builder()
                    .aboutConfigEnabled(false)
                    .consoleOutput(true)
                    .remoteDebuggingEnabled(true)
                    .build()

            GeckoRuntime.create(
                applicationContext,
                settings
            ).also { rt ->

                geckoRuntime = rt

                loadAiCaptureExtension(
                    rt
                )

                Log.d(
                    TAG,
                    "✅ GeckoRuntime created"
                )
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ GeckoRuntime error: ${e.message}",
                e
            )

            showToast(
                "❌ GeckoRuntime error"
            )

            null
        }
    }

    private fun loadAiCaptureExtension(
        runtime: GeckoRuntime
    ) {

        runtime.webExtensionController
            .ensureBuiltIn(
                "resource://android/assets/aicapture/",
                "aicapture@aichat.example.com"
            )
            .accept(

                { ext ->

                    if (ext != null) {

                        aiChatExtension =
                            ext

                        ext.setMessageDelegate(
                            messageDelegateAiCapture,
                            "browser"
                        )

                        val ver =
                            ext.metaData?.version ?: "?"

                        val baseUrl =
                            ext.metaData?.baseUrl ?: "?"

                        val temporary =
                            ext.metaData?.temporary ?: false

                        logDebug(
                            "🔎 Extension source: $baseUrl"
                        )

                        Log.d(
                            TAG,
                            "✅ Extension loaded: " +
                                "id=${ext.id}, " +
                                "version=$ver, " +
                                "isBuiltIn=${ext.isBuiltIn}, " +
                                "temporary=$temporary, " +
                                "baseUrl=$baseUrl"
                        )

                        logDebug(
                            "✅ Extension: " +
                                "id=${ext.id}, " +
                                "v=$ver, " +
                                "builtIn=${ext.isBuiltIn}, " +
                                "temporary=$temporary"
                        )
                    } else {

                        Log.e(
                            TAG,
                            "❌ Extension = null"
                        )

                        logDebug(
                            "❌ Extension load failed"
                        )
                    }
                },

                { error ->

                    val msg =
                        error?.message
                            ?: "unknown"

                    Log.e(
                        TAG,
                        "❌ Extension error: $msg"
                    )

                    logDebug(
                        "❌ Extension error: $msg"
                    )
                }
            )
    }

    private fun parseMessage(
        message: Any
    ): JSONObject? = try {

        when (message) {

            is JSONObject ->
                message

            is Map<*, *> ->
                JSONObject(
                    message as Map<*, *>
                )

            is String ->
                JSONObject(message)

            else ->
                null
        }

    } catch (e: Exception) {

        Log.e(
            TAG,
            "❌ parseMessage: ${e.message}"
        )

        null
    }
}
