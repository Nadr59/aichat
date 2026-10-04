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

    var onAiResponseCaptured:
        ((domain: String, text: String) -> Unit)? = null

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

        sendPendingContextToPort()
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

    private fun domainMatches(
        domain: String,
        target: String
    ): Boolean {
        if (target.isBlank()) {
            return true
        }

        val d =
            domain
                .lowercase(Locale.ROOT)
                .trim()

        return d == target ||
            d.endsWith(".$target")
    }

    /**
     * إرسال النص الحالي مباشرة إلى background.js.
     *
     * المسار:
     *
     * AichatApp
     *      ↓
     * WebExtension.Port
     *      ↓
     * background.js
     *      ↓
     * content.js
     */
    private fun sendPendingContextToPort(
        forcedPort: WebExtension.Port? = null
    ) {
        val port =
            forcedPort ?: aiCapturePort

        if (port == null) {
            logDebug(
                "⚠️ CONTEXT_TO_PAGE: Native Port unavailable"
            )
            return
        }

        val snapshot: JSONObject

        synchronized(ctxLock) {

            if (contextPending.isBlank()) {
                logDebug(
                    "⚠️ CONTEXT_TO_PAGE: No pending context"
                )
                return
            }

            if (
                System.currentTimeMillis() - contextSetAt >
                CONTEXT_TTL_MS
            ) {
                contextPending = ""
                lastContextId = 0L

                logDebug(
                    "⌛ CONTEXT_TO_PAGE: Context expired"
                )

                return
            }

            if (
                !domainMatches(
                    contextTarget,
                    contextTarget
                )
            ) {
                return
            }

            snapshot =
                JSONObject()
                    .put(
                        "type",
                        "CONTEXT_TO_PAGE"
                    )
                    .put(
                        "text",
                        contextPending
                    )
                    .put(
                        "contextId",
                        lastContextId
                    )
                    .put(
                        "domain",
                        contextTarget
                    )
                    .put(
                        "timestamp",
                        System.currentTimeMillis()
                    )
        }

        try {

            port.postMessage(
                snapshot
            )

            logDebug(
                "📤 CONTEXT_TO_PAGE SENT: " +
                    "len=${snapshot.optString("text").length}, " +
                    "id=${snapshot.optLong("contextId")}" +
                    if (snapshot.optString("domain").isNotBlank()) {
                        ", target=${snapshot.optString("domain")}"
                    } else {
                        ""
                    }
            )

        } catch (e: Exception) {

            logDebug(
                "❌ CONTEXT_TO_PAGE FAILED: " +
                    (e.message ?: e.toString())
            )
        }
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
                contextSetAt = 0L

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

    /**
     * معالجة رسائل content.js التي تصل عبر Native Port.
     *
     * هذه الرسائل لا تأتي من onMessage().
     * لذلك يجب التعامل معها هنا.
     */
    private fun handlePortMessage(
        message: Any,
        port: WebExtension.Port
    ) {
        val json =
            parseMessage(message)

        if (json == null) {
            logDebug(
                "❌ PORT parseMessage=null"
            )
            return
        }

        val type =
            json.optString("type")

        if (type.isBlank()) {
            logDebug(
                "❌ PORT message has empty type"
            )
            return
        }

        when (type) {
            "ASSISTANT_RESPONSE" -> {

    val text = json.optString("text", "")
    val source = json.optString("source", "web")

    if (text.isBlank()) {
        logDebug("⚠️ ASSISTANT_RESPONSE: empty")
        return
    }

    logDebug(
        "📨 ASSISTANT_RESPONSE received: " +
            "source=$source, len=${text.length}"
    )

    mainHandler.post {
        onAiResponseCaptured?.invoke(
            source,
            text
        )
    }
            }

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
                    "✏️ CONTEXT_WRITTEN: " +
                        "success=$success, " +
                        "id=$contextId, " +
                        "stage=$stage, " +
                        "detail=$detail"
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
                            "enter_sent" ->
                            "✅ أُرسل بالضغط على Enter"

                        stage ==
                            "text_injected" ->
                            "✅ تمت الكتابة في المنصة"

                        else ->
                            "✅ تمت الكتابة"
                    }
                )
            }

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
                    "🗑️ CONTEXT_CONSUMED: " +
                        "id=$contextId, cleared=$cleared"
                )
            }

            "DEBUG_INFO" -> {

                logDebug(
                    "🔍 JS: " +
                        json.optString(
                            "info"
                        )
                )
            }

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
            }

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
            }

            else -> {

                logDebug(
                    "ℹ️ PORT message received: $type"
                )
            }
        }
    }

    private val messageDelegateAiCapture =
        object : WebExtension.MessageDelegate {

            override fun onConnect(
                port: WebExtension.Port
            ) {

                logDebug(
                    "🔌 Native Port connected"
                )

                aiCapturePort = port

                port.setDelegate(
                    object : WebExtension.PortDelegate {

                        override fun onPortMessage(
                            message: Any,
                            port: WebExtension.Port
                        ) {
                            logDebug(
                                "📩 Background → Kotlin: $message"
                            )

                            handlePortMessage(
                                message,
                                port
                            )
                        }

                        override fun onDisconnect(
                            port: WebExtension.Port
                        ) {

                            logDebug(
                                "⚠️ Native Port disconnected"
                            )

                            if (aiCapturePort === port) {
                                aiCapturePort = null
                            }
                        }
                    }
                )

                logDebug(
                    "✅ Port delegate attached"
                )

                /*
                 * إذا كان هناك نص pending قبل اتصال الـPort،
                 * أرسله الآن مباشرة.
                 */
                mainHandler.postDelayed({

                    sendPendingContextToPort(
                        port
                    )

                }, 500)
            }

            override fun onMessage(
                nativeApp: String,
                message: Any,
                sender: WebExtension.MessageSender
            ): GeckoResult<Any>? {

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

                        val source =
                            json.optString(
                                "source",
                                ""
                            )

                        val text =
                            json.optString(
                                "text",
                                ""
                            )

                        val timestamp =
                            json.optString(
                                "timestamp",
                                ""
                            )

                        logDebug(
                            "🧪 DIRECT_TEST_2 RECEIVED: " +
                                "source=$source, text=$text, timestamp=$timestamp"
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
                                    "test",
                                    "DIRECT_TEST_2"
                                )
                        )
                    }

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

                    "POLL" -> {

                        /*
                         * أبقينا هذا المسار للتوافق مع الاختبارات
                         * السابقة، لكن التدفق العملي الجديد لا يعتمد عليه.
                         */

                        val domain =
                            json.optString(
                                "domain"
                            )

                        val visible =
                            json.optBoolean(
                                "visible",
                                true
                            )

                        logDebug(
                            "🔄 POLL received through onMessage: " +
                                "domain=$domain, visible=$visible"
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
                        }

                        resp.put(
                            "capture",
                            capture
                        )

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

                        reply(resp)
                    }

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

                    "DEBUG_INFO" -> {

                        logDebug(
                            "🔍 JS: " +
                                json.optString(
                                    "info"
                                )
                        )

                        reply()
                    }

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
