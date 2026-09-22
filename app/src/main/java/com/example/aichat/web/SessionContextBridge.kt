package com.example.aichat.web

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import java.util.UUID

class SessionContextBridge(
    private val runtime:   GeckoRuntime,
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
        val stage: String,
        val detail: String = ""
    )

    // ── الحالة ───────────────────────────────────────────────────────

    private var port: WebExtension.Port? = null
    private var ready = CompletableDeferred<Unit>()
    private var pending: Pair<String, CompletableDeferred<JSONObject>>? = null
    private var closed = false

    // ── PortDelegate ─────────────────────────────────────────────────

    private val portDelegate = object : WebExtension.PortDelegate {

        override fun onPortMessage(message: Any, source: WebExtension.Port) {
            if (source !== port) return
            val json = when (message) {
                is JSONObject -> message
                is Map<*, *>  -> runCatching { JSONObject(message as Map<*, *>) }.getOrNull()
                else          -> null
            } ?: return

            when (json.optString("type")) {
                "READY" -> {
                    Log.d(TAG, "✅ Page ready: ${json.optString("url")}")
                    if (!ready.isCompleted) ready.complete(Unit)
                }
                "RESULT" -> {
                    val p = pending ?: return
                    if (json.optString("requestId") == p.first) {
                        p.second.complete(json)
                        pending = null
                    }
                }
            }
        }

        override fun onDisconnect(port: WebExtension.Port) {
            Log.w(TAG, "Port disconnected")
            this@SessionContextBridge.port = null
        }
    }

    // ── MessageDelegate ───────────────────────────────────────────────

    private val messageDelegate = object : WebExtension.MessageDelegate {

        override fun onConnect(newPort: WebExtension.Port) {
            // التحقق من الاسم
            if (newPort.name != NATIVE_APP) {
                newPort.disconnect()
                return
            }

            // التحقق من الـ host
            val senderUrl = newPort.sender?.url ?: ""
            val host = runCatching {
                java.net.URI(senderUrl).host ?: ""
            }.getOrDefault("")

            if (closed || host !in ALLOWED_HOSTS) {
                Log.w(TAG, "Port rejected: closed=$closed host=$host")
                newPort.disconnect()
                return
            }

            // إبطال Port قديم
            port?.disconnect()
            port  = newPort
            ready = CompletableDeferred()
            newPort.setDelegate(portDelegate)

            // مصافحة
            try {
                newPort.postMessage(JSONObject().put("type", "HELLO"))
                Log.d(TAG, "✅ Port connected from $host")
            } catch (e: Exception) {
                Log.e(TAG, "HELLO failed: ${e.message}")
            }
        }
    }

    // ── init ──────────────────────────────────────────────────────────

    init {
        // يجب تسجيل الـ delegate على الـ runtime (ليس session)
        Handler(Looper.getMainLooper()).post {
            runtime.webExtensionController
                .setMessageDelegate(extension, messageDelegate, NATIVE_APP)
            Log.d(TAG, "✅ MessageDelegate registered")
        }
    }

    // ── deliver ───────────────────────────────────────────────────────

    suspend fun deliver(
        systemDocument: String,
        memoryContext: String,
        submit: Boolean = false,
        timeoutMs: Long = 10_000L
    ): DeliveryResult = withContext(Dispatchers.Main) {

        if (closed) {
            return@withContext DeliveryResult("", "bridge_closed")
        }
        if (pending != null) {
            return@withContext DeliveryResult("", "busy")
        }

        val requestId = UUID.randomUUID().toString()

        // انتظار جاهزية الصفحة
        try {
            withTimeout(timeoutMs) { ready.await() }
        } catch (e: TimeoutCancellationException) {
            return@withContext DeliveryResult(requestId, "page_not_ready")
        }

        val currentPort = port
        if (currentPort == null) {
            return@withContext DeliveryResult(requestId, "no_port")
        }

        // إعداد الانتظار
        val deferred = CompletableDeferred<JSONObject>()
        pending = Pair(requestId, deferred)

        // الإرسال
        try {
            currentPort.postMessage(
                JSONObject()
                    .put("type", "DELIVER")
                    .put("requestId", requestId)
                    .put("systemDocument", systemDocument)
                    .put("memoryContext", memoryContext)
                    .put("submit", submit)
            )
        } catch (e: Exception) {
            pending = null
            Log.e(TAG, "postMessage failed: ${e.message}")
            return@withContext DeliveryResult(requestId, "send_failed", e.message ?: "")
        }

        // انتظار النتيجة
        return@withContext try {
            val result = withTimeout(timeoutMs) { deferred.await() }
            DeliveryResult(
                requestId = requestId,
                stage     = result.optString("stage", "unknown"),
                detail    = result.optString("detail", "")
            )
        } catch (e: TimeoutCancellationException) {
            pending = null
            DeliveryResult(requestId, "timeout")
        } catch (e: CancellationException) {
            pending = null
            DeliveryResult(requestId, "cancelled")
        }
    }

    // ── close ─────────────────────────────────────────────────────────

    fun close() {
        closed = true
        try { port?.disconnect() } catch (_: Exception) {}
        port = null
        pending?.second?.cancel()
        pending = null
        Log.d(TAG, "Bridge closed")
    }
}
