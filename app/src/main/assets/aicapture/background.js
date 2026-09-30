"use strict";

var NATIVE_APP = "browser";

// ══════════════════════════════════════════════════════════════════
// Helper: Logging
// ══════════════════════════════════════════════════════════════════

function logLocal(message) {
    var timestamp = new Date().toLocaleTimeString('en-US', {
        hour12: false,
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit'
    });
    console.log('[Background] ' + timestamp + '  ' + message);
}

// ══════════════════════════════════════════════════════════════════
// Helper: Send to Native (Promise-based)
// ══════════════════════════════════════════════════════════════════

function sendToNative(payload) {
    logLocal('🔄 sendToNative START: type=' + payload.type);
    
    return browser.runtime.sendNativeMessage(NATIVE_APP, payload)
        .then(function (response) {
            logLocal('✅ sendToNative SUCCESS: type=' + payload.type);
            return response;
        })
        .catch(function (error) {
            logLocal('❌ sendToNative CATCH: type=' + payload.type + ', error=' + error.message);
            throw error;
        });
}

// ══════════════════════════════════════════════════════════════════
// Message Listener
// ══════════════════════════════════════════════════════════════════

browser.runtime.onMessage.addListener(function (message, sender, sendResponse) {
    if (!message || !message.type) {
        logLocal('⚠️ Empty message received');
        sendResponse({ error: 'No message type' });
        return false;
    }

    var type = message.type;
    
    if (type !== "GET_CONTEXT") {
        logLocal('📨 Received: ' + type);
    }

    // ────────────────────────────────────────────────────────────────
    // AI_RESPONSE
    // ────────────────────────────────────────────────────────────────
    if (type === "AI_RESPONSE") {
        logLocal('📤 AI_RESPONSE: ' + (message.text ? message.text.substring(0, 50) + '...' : 'empty'));
        
        sendToNative({
            type:   "AI_RESPONSE",
            text:   message.text   || "",
            domain: message.domain || ""
        }).catch(function (e) {
            logLocal('❌ AI_RESPONSE failed: ' + e.message);
        });
        
        sendResponse({ ok: true });
        return false;
    }

    // ────────────────────────────────────────────────────────────────
    // CHECK_CAPTURE
    // ────────────────────────────────────────────────────────────────
    if (type === "CHECK_CAPTURE") {
        logLocal('🧠 CHECK_CAPTURE requested');
        
        sendToNative({
            type:   "CHECK_CAPTURE",
            domain: message.domain || ""
        }).then(function (response) {
            logLocal('✅ CHECK_CAPTURE response: capture=' + (response && response.capture));
            sendResponse(response || { capture: false });
        }).catch(function (e) {
            logLocal('❌ CHECK_CAPTURE error: ' + e.message);
            sendResponse({ capture: false });
        });
        
        return true;
    }

    // ────────────────────────────────────────────────────────────────
    // CAPTURE_RESULT
    // ────────────────────────────────────────────────────────────────
    if (type === "CAPTURE_RESULT") {
        logLocal('📸 CAPTURE_RESULT: success=' + message.success);
        
        sendToNative({
            type:    "CAPTURE_RESULT",
            success: message.success || false,
            text:    message.text    || "",
            domain:  message.domain  || "",
            debug:   message.debug   || null
        }).catch(function (e) {
            logLocal('❌ CAPTURE_RESULT failed: ' + e.message);
        });
        
        sendResponse({ ok: true });
        return false;
    }

    // ────────────────────────────────────────────────────────────────
    // GET_CONTEXT — Direct from Kotlin (onMessage)
    // ────────────────────────────────────────────────────────────────
    if (type === "GET_CONTEXT") {
        var requestId = message.requestId || 0;
        
        sendToNative({
            type:   "GET_CONTEXT",
            domain: message.domain || ""
        }).then(function (response) {
            var data = response;
            if (typeof response === 'string') {
                logLocal('⚠️ GET_CONTEXT (id=' + requestId + '): response is string, parsing...');
                try {
                    data = JSON.parse(response);
                } catch (parseErr) {
                    logLocal('❌ GET_CONTEXT (id=' + requestId + '): JSON.parse failed');
                    sendResponse({ 
                        hasContext: false, 
                        context: "", 
                        requestId: requestId
                    });
                    return;
                }
            }
            
            if (!data) {
                logLocal('⚠️ GET_CONTEXT (id=' + requestId + '): data is null');
                sendResponse({ 
                    hasContext: false, 
                    context: "", 
                    requestId: requestId
                });
                return;
            }
            
            if (data.hasContext) {
                logLocal('✅ GET_CONTEXT (id=' + requestId + '): has=true, len=' + 
                    (data.context ? data.context.length : 0));
            }
            
            data.requestId = requestId;
            data.hasContext = data.hasContext || false;
            data.context = data.context || "";
            
            sendResponse(data);
            
        }).catch(function (error) {
            logLocal('❌ GET_CONTEXT (id=' + requestId + '): error=' + error.message);
            sendResponse({ 
                hasContext: false, 
                context: "", 
                requestId: requestId
            });
        });
        
        return true;
    }

    // ────────────────────────────────────────────────────────────────
    // CONTEXT_WRITTEN
    // ────────────────────────────────────────────────────────────────
    if (type === "CONTEXT_WRITTEN") {
        logLocal('✏️ CONTEXT_WRITTEN: success=' + message.success);
        
        sendToNative({
            type:      "CONTEXT_WRITTEN",
            success:   message.success   || false,
            contextId: message.contextId || 0,
            stage:     message.stage     || "",
            detail:    message.detail    || "",
            domain:    message.domain    || ""
        }).catch(function (e) {
            logLocal('❌ CONTEXT_WRITTEN failed: ' + e.message);
        });
        
        sendResponse({ ok: true });
        return false;
    }

    // ────────────────────────────────────────────────────────────────
    // CONTEXT_CONSUMED
    // ────────────────────────────────────────────────────────────────
    if (type === "CONTEXT_CONSUMED") {
        var contextId = message.contextId || 0;
        logLocal('🗑️ CONTEXT_CONSUMED: contextId=' + contextId);
        
        sendToNative({
            type:      "CONTEXT_CONSUMED",
            contextId: contextId
        }).catch(function (e) {
            logLocal('❌ CONTEXT_CONSUMED failed: ' + e.message);
        });
        
        sendResponse({ ok: true });
        return false;
    }

    // ────────────────────────────────────────────────────────────────
    // DEBUG_INFO
    // ────────────────────────────────────────────────────────────────
    if (type === "DEBUG_INFO") {
        logLocal('🔍 DEBUG_INFO: ' + (message.info || ''));
        
        sendToNative({
            type: "DEBUG_INFO",
            info: message.info || ""
        }).catch(function (e) {
            logLocal('❌ DEBUG_INFO failed: ' + e.message);
        });
        
        sendResponse({ ok: true });
        return false;
    }

    // ────────────────────────────────────────────────────────────────
    // Fallback
    // ────────────────────────────────────────────────────────────────
    logLocal('⚠️ UNKNOWN_TYPE: ' + type);
    
    sendToNative({
        type:     "UNKNOWN_TYPE",
        original: type,
        payload:  JSON.stringify(message)
    }).catch(function (e) {
        logLocal('❌ UNKNOWN_TYPE handler failed: ' + e.message);
    });
    
    sendResponse({ error: 'Unknown message type: ' + type });
    return false;
});

// ══════════════════════════════════════════════════════════════════
// Extension Events
// ══════════════════════════════════════════════════════════════════

browser.runtime.onInstalled.addListener(function (details) {
    if (details.reason === "install") {
        logLocal('🎉 Extension installed');
    } else if (details.reason === "update") {
        logLocal('🔄 Extension updated');
    }
});

// ══════════════════════════════════════════════════════════════════
// Startup
// ══════════════════════════════════════════════════════════════════

logLocal('✅ Background script ready');
