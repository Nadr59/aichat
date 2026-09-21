package com.example.aichat.web

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import java.util.UUID

class SessionContextBridge(
    private val session:   GeckoSession,
    private val extension: WebExtension
) {
    companion object {
        const val NATIVE_APP = "memory_context"
        private const val TAG = "ContextBridge"

        private val ALLOWED_HOSTS = setOf(
            "chatgpt.com", "chat.openai.com",
            "claude.ai",
            "gemini.google.com",
            "www.perplexity.ai", "perplexity.ai",
            "copilot.microsoft.com",
            "grok.com",
            "chat.mistral.ai",
            "you.com", "www.you.com"
        )
    }

    data class DeliveryResult(
        val requestId: String,
        val stage:     String,   // filled / clicked / write_failed / send_failed
        val detail:    String = ""
    )

    // ── الحالة ───────────────────────────────────────────────────────

    private var port:    WebExtension.Port?          = null
    private var ready  = CompletableDeferred<Unit>()
    private var pending: Pair<String, CompletableDeferred<JSONObject>>? = null
    private var closed = false

    // ── PortDelegate ─────────────────────────────────────────────────

    private val portDelegate = object : WebExtension.PortDelegate {

        override fun onPortMessage(message: Any, source: WebExtension.Port) {
            if (source !== port || message !is JSONObject) return

            when (message.optString("type")) {
                "READY" -> {
                    Log.d(TAG, "✅ Page ready: ${message.optString("url")}")
                    ready.complete(Unit)
                }
                "RESULT" -> {
                    val p = pending ?: return
                    if (message.optString("requestId") == p.first) {
                        p.second.complete(message)
                        pending = null
                    }
                }
            }
        }
    }

    // ── MessageDelegate ───────────────────────────────────────────────

    private val messageDelegate = object : WebExtension.MessageDelegate {

        override fun onConnect(newPort: WebExtension.Port) {
            val senderUrl  = newPort.sender?.url ?: ""
            val host       = runCatching {
                java.net.URI(senderUrl).host ?: ""
            }.getOrDefault("")

            if (closed || newPort.name != NATIVE_APP
                || newPort.sender?.session !== session
                || host !in ALLOWED_HOSTS) {
                newPort.disconnect()
                return
            }

            // إبطال Port قديم
            port?.disconnect()
            port  = newPort
            ready = CompletableDeferred()
            newPort.setDelegate(portDelegate)

            // مصافحة
            newPort.postMessage(JSONObject().put("type", "HELLO"))
            Log.d(TAG, "✅ Port connected from $host")
        }
    }

    // ── init ──────────────────────────────────────────────────────────

    init {
        Handler(Looper.getMainLooper()).post {
            session.webExtensionController
                .setMessageDelegate(extension, messageDelegate, NATIVE_APP)
        }
    }

    // ── deliver ───────────────────────────────────────────────────────

    suspend fun deliver(
        systemDocument: String,
        memoryContext:  String,
        submit:         Boolean = false,
        timeoutMs:      Long    = 10_000L
    ): DeliveryResult = withContext(Dispatchers.Main.immediate) {

        check(!closed)         { "الجسر مغلق" }
        check(pending == null) { "توجد عملية قيد التنفيذ" }

        // انتظر جاهزية الصفحة
        withTimeout(timeoutMs) { ready.await() }

        val requestId = UUID.randomUUID().toString()
        val deferred  = CompletableDeferred<JSONObject>()
        pending = Pair(requestId, deferred)

        // أرسل للصفحة
        port?.postMessage(
            JSONObject()
                .put("type",           "DELIVER")
                .put("requestId",      requestId)
                .put("systemDocument", systemDocument)
                .put("memoryContext",  memoryContext)
                .put("submit",         submit)
        ) ?: run {
            pending = null
            return@withContext DeliveryResult(requestId, "no_port")
        }

        // انتظر النتيجة
        val result = withTimeout(timeoutMs) { deferred.await() }

        DeliveryResult(
            requestId = requestId,
            stage     = result.optString("stage"),
            detail    = result.optString("detail")
        )
    }

    // ── close ─────────────────────────────────────────────────────────

    fun close() {
        closed = true
        port?.disconnect()
        port = null
        pending?.second?.cancel()
        pending = null
    }
}
