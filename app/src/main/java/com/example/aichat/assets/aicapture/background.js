"use strict";

var NATIVE_APP   = "browser";
var TAB_TIMEOUT  = 10000;

// ══════════════════════════════════════════════════════
// استقبال رسائل content.js
// ══════════════════════════════════════════════════════

browser.runtime.onMessage.addListener(function (message, sender) {
    if (!message || !message.type) return;

    var type = message.type;
    console.log("[bg] from content:", type);

    // ── AI_RESPONSE: أرسل لـ Kotlin بدون رد ──────────────
    if (type === "AI_RESPONSE") {
        browser.runtime.sendNativeMessage(NATIVE_APP, {
            type:   "AI_RESPONSE",
            text:   message.text   || "",
            domain: message.domain || ""
        });
        return; // لا رد لـ content.js
    }

    // ── CHECK_CAPTURE: اسأل Kotlin وأرجع الرد لـ content.js ──
    if (type === "CHECK_CAPTURE") {
        // ✅ أرجع Promise — هذا يجعل content.js يحصل على الرد
        return browser.runtime.sendNativeMessage(NATIVE_APP, {
            type:   "CHECK_CAPTURE",
            domain: message.domain || ""
        }).then(function (response) {
            console.log("[bg] CHECK_CAPTURE response from Kotlin:", 
                        JSON.stringify(response));
            return response; // ✅ يصل لـ content.js .then()
        }).catch(function (e) {
            console.error("[bg] CHECK_CAPTURE error:", e);
            return { capture: false };
        });
    }

    // ── CAPTURE_RESULT: أرسل لـ Kotlin ────────────────────
    if (type === "CAPTURE_RESULT") {
        browser.runtime.sendNativeMessage(NATIVE_APP, {
            type:    "CAPTURE_RESULT",
            success: message.success || false,
            text:    message.text    || "",
            domain:  message.domain  || "",
            debug:   message.debug   || null
        });
        return;
    }
});

console.log("[bg] background ready ✅");
