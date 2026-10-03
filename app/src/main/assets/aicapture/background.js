"use strict";  
var NATIVE_APP = "browser";  
var nativePort = null;  
  
function logLocal(message) {  
    var ts = new Date().toLocaleTimeString("en-US", { hour12: false });  
    console.log("[Background] " + ts + "  " + message);  
}  
  
function connectToNative() {  
    try {  
        nativePort = browser.runtime.connectNative(NATIVE_APP);  
  
        nativePort.onMessage.addListener(function (message) {  
            logLocal("🚨 RECEIVED FROM KOTLIN: " + JSON.stringify(message));  
  
            browser.tabs.query({ active: true, currentWindow: true })  
            .then(function (tabs) {  
                logLocal("🔎 Active tabs count: " + tabs.length);  
                if (!tabs || tabs.length === 0) throw new Error("NO_ACTIVE_TAB");  
                var tabId = tabs[0].id;  
                logLocal("📤 Sending to content, tab=" + tabId);  
                  
                // الإصلاح: أرسل نفس الاسم الذي ينتظره content.js  
                return browser.tabs.sendMessage(tabId, {  
                    type: "REVERSE_TEST",  
                    text: "HELLO_FROM_KOTLIN",  
                    original: message  
                });  
            })  
            .then(function (response) {  
                logLocal("✅ Content replied: " + JSON.stringify(response));  
            })  
            .catch(function (error) {  
                logLocal("❌ Background → Content FAILED: " + (error && error.message ? error.message : String(error)));  
            });  
        });  
  
        nativePort.onDisconnect.addListener(function () {  
            logLocal("⚠️ Native Port disconnected - Reconnecting in 1s");  
            nativePort = null;  
            setTimeout(connectToNative, 1000);  
        });  
  
        nativePort.postMessage({  
            type: "REVERSE_TEST_READY",  
            source: "background.js",  
            timestamp: new Date().toISOString()  
        });  
        logLocal("📤 Background → Kotlin: REVERSE_TEST_READY");  
  
    } catch (error) {  
        logLocal("❌ connectNative failed: " + String(error));  
    }  
}  
  
browser.runtime.onMessage.addListener(function (message, sender) {  
    if (!message || !message.type) return Promise.resolve({ ok: false });  
      
    // هذا يحافظ على المسار الأمامي Content -> Kotlin  
    if (nativePort && message.type !== "PING") {  
        logLocal("↗️ Forwarding Content -> Kotlin: " + JSON.stringify(message));  
        try { nativePort.postMessage(message); } catch(e){}  
    }  
  
    if (message.type === "PING") {  
        return browser.runtime.sendNativeMessage(NATIVE_APP, { type: "PING" })  
        .then(function (response) { return { ok: true, pong: true, native: response }; });  
    }  
    return Promise.resolve({ ok: true });  
});  
  
logLocal("✅ Background script ready");  
connectToNative();  
