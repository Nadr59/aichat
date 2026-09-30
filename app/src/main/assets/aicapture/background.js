"use strict";

var NATIVE_APP = "browser";

// ══════════════════════════════════════════════════════════════════
// Helper: تسجيل محلي للتشخيص
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
// Helper: إرسال رسالة آمن إلى Kotlin
// ══════════════════════════════════════════════════════════════════

function sendToNative(payload) {
    return new Promise(function (resolve, reject) {
        try {
            browser.runtime.sendNativeMessage(NATIVE_APP, payload, function (response) {
                if (browser.runtime.lastError) {
                    logLocal('❌ Native error: ' + browser.runtime.lastError.message);
                    reject(new Error(browser.runtime.lastError.message));
                } else {
                    resolve(response);
                }
            });
        } catch (e) {
            logLocal('❌ sendToNative exception: ' + e.message);
            reject(e);
        }
    });
}

// ══════════════════════════════════════════════════════════════════
// Main Message Listener
// ══════════════════════════════════════════════════════════════════

browser.runtime.onMessage.addListener(function (message, sender, sendResponse) {
    if (!message || !message.type) {
        logLocal('⚠️ Empty message received');
        sendResponse({ error: 'No message type' });
        return false;
    }

    var type = message.type;
    
    // تجاهل تسجيل GET_CONTEXT — كثير جداً
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
        logLocal('📸 CAPTURE_RESULT: success=' + message.success + 
            ', text=' + (message.text ? message.text.substring(0, 50) + '...' : 'empty'));
        
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
    // GET_CONTEXT — محسّن مع معالجة أخطاء شاملة
    // ────────────────────────────────────────────────────────────────
    if (type === "GET_CONTEXT") {
        var requestId = message.requestId || 0;
        
        try {
            sendToNative({
                type:   "GET_CONTEXT",
                domain: message.domain || ""
            }).then(function (response) {
                try {
                    // تأكد أن response هو object وليس string
                    var data = response;
                    if (typeof response === 'string') {
                        logLocal('⚠️ GET_CONTEXT (id=' + requestId + '): response is string, parsing...');
                        try {
                            data = JSON.parse(response);
                        } catch (parseErr) {
                            logLocal('❌ GET_CONTEXT (id=' + requestId + '): JSON.parse failed: ' + parseErr.message + ', raw=' + response);
                            sendResponse({ 
                                hasContext: false, 
                                context: "", 
                                requestId: requestId,
                                error: "JSON parse error: " + parseErr.message
                            });
                            return;
                        }
                    }
                    
                    // تحقق من صحة data
                    if (!data) {
                        logLocal('❌ GET_CONTEXT (id=' + requestId + '): data is null/undefined after parsing');
                        sendResponse({ 
                            hasContext: false, 
                            context: "", 
                            requestId: requestId,
                            error: "data is null"
                        });
                        return;
                    }
                    
                    if (data.hasContext) {
                        logLocal('✅ GET_CONTEXT (id=' + requestId + '): has=true, len=' + 
                            (data.context ? data.context.length : 0) +
                            ', contextId=' + (data.id || '?'));
                    }
                    
                    // أضف requestId إلى الرد
                    if (!data.hasOwnProperty('requestId')) {
                        data.requestId = requestId;
                    }
                    
                    // تأكد من وجود الحقول المطلوبة
                    if (!data.hasOwnProperty('hasContext')) {
                        data.hasContext = false;
                    }
                    if (!data.hasOwnProperty('context')) {
                        data.context = "";
                    }
                    
                    logLocal('✅ GET_CONTEXT (id=' + requestId + '): sending valid response');
                    sendResponse(data);
                    
                } catch (responseErr) {
                    logLocal('❌ GET_CONTEXT (id=' + requestId + '): response handler error: ' + responseErr.message);
                    sendResponse({ 
                        hasContext: false, 
                        context: "", 
                        requestId: requestId,
                        error: "response handler: " + responseErr.message
                    });
                }
            }).catch(function (nativeErr) {
                logLocal('❌ GET_CONTEXT (id=' + requestId + '): sendToNative error: ' + nativeErr.message);
                sendResponse({ 
                    hasContext: false, 
                    context: "", 
                    requestId: requestId,
                    error: "native: " + nativeErr.message
                });
            });
        } catch (mainErr) {
            logLocal('❌ GET_CONTEXT (id=' + requestId + '): main error: ' + mainErr.message);
            sendResponse({ 
                hasContext: false, 
                context: "", 
                requestId: requestId,
                error: "main: " + mainErr.message
            });
        }
        
        return true; // 🔴 حاسم جداً
    }

    // ────────────────────────────────────────────────────────────────
    // CONTEXT_WRITTEN
    // ────────────────────────────────────────────────────────────────
    if (type === "CONTEXT_WRITTEN") {
        logLocal('✏️ CONTEXT_WRITTEN: success=' + message.success + 
            ', contextId=' + (message.contextId || '?') +
            ', stage=' + (message.stage || '?'));
        
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
    // Fallback: أي نوع غير معروف
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
// Event: تحديث الامتداد
// ══════════════════════════════════════════════════════════════════

browser.runtime.onInstalled.addListener(function (details) {
    if (details.reason === "install") {
        logLocal('🎉 Extension installed successfully');
    } else if (details.reason === "update") {
        logLocal('🔄 Extension updated to current version');
    }
});

// ══════════════════════════════════════════════════════════════════
// رسالة البداية
// ══════════════════════════════════════════════════════════════════

logLocal('✅ Background script loaded successfully');
