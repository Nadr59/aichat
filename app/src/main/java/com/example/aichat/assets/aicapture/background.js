"use strict";

/* ─────────────────────────────────────────────────────────────
   ⚠️ في البنية الجديدة، background.js ليس جزءاً من مسار الرسائل.
   content.js يتحدث مع Kotlin مباشرة عبر sendNativeMessage
   (بفضل صلاحية nativeMessagingFromContent).

هذا الملف يبقى فقط لسببين:
   1. رسالة BG_READY للتشخيص عند الإقلاع
   2. relay احتياطي إن رجعت لنسخة content.js قديمة
   ───────────────────────────────────────────────────────────── */

var NATIVE_APP = "browser";

// ── إشعار جاهزية عند الإقلاع ──────────────────────────────────
// يجب أن ترى في logcat: "⚠️ unknown type: BG_READY"
// هذا دليل أن قناة background → Kotlin تعمل بالاتجاهين.
try {
    browser.runtime.sendNativeMessage(NATIVE_APP, {
        type: "BG_READY",
        info: "background started"
    });
} catch (e) {}

// ── Relay احتياطي ─────────────────────────────────────────────
browser.runtime.onMessage.addListener(function (message, sender) {
    if (!message || !message.type) return;

// رسائل باتجاه واحد — مررها ولا تنتظر رداً
    if (message.type === "AI_RESPONSE" || message.type === "CAPTURE_RESULT") {
        browser.runtime.sendNativeMessage(NATIVE_APP, {
            type:    message.type,
            text:     message.text    || "",
            success:  message.success || false,
            domain:   message.domain  || "",
            debug:    message.debug   || null
        });
        return;   // ⚠️ بلا return Promise = لا ننتظر رداً
    }

// رسالة تحتاج رداً — القاعدة الحاسمة:
    // أعد Promise نفسه (لا تستدعِ sendNativeMessage كـ statement)
    if (message.type === "CHECK_CAPTURE") {
        return browser.runtime.sendNativeMessage(NATIVE_APP, {
            type:   "CHECK_CAPTURE",
            domain: message.domain || ""
        }).then(function (response) {
            if (response && typeof response === "object") {
                return response;
            }
            return { capture: false, bgRelay: true };
        }).catch(function (e) {
            console.error("[AiChat] BG relay CHECK_CAPTURE failed:", e);
            return { capture: false, bgRelay: true, error: String(e) };
        });
    }
});
