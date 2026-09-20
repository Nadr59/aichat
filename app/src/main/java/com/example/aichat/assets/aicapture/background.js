
"use strict";

var NATIVE_APP    = "browser";
var RECONNECT_MS  = 2000;
var TAB_TIMEOUT   = 10000;

var nativePort      = null;
var reconnectTimer  = null;

// ── اتصال بـ Kotlin ───────────────────────────────────────────────

function connectNative() {
    if (reconnectTimer) {
        clearTimeout(reconnectTimer);
        reconnectTimer = null;
    }

    try {
        nativePort = browser.runtime.connectNative(NATIVE_APP);
    } catch (e) {
        console.log("[bg] connectNative failed:", e.message);
        reconnectTimer = setTimeout(connectNative, RECONNECT_MS);
        return;
    }

    nativePort.onMessage.addListener(onNativeMessage);

    nativePort.onDisconnect.addListener(function (p) {
        var err = (p && p.error && p.error.message) || "unknown";
        console.log("[bg] native disconnected:", err);
        nativePort = null;
        reconnectTimer = setTimeout(connectNative, RECONNECT_MS);
    });

    // ✅ أخبر Kotlin أن الجسر جاهز
    safePost({ type: "BG_READY", ts: Date.now() });
    console.log("[bg] native port connected ✅");
}

function safePost(obj) {
    if (!nativePort) {
        console.log("[bg] drop — no port:", obj.type);
        return false;
    }
    try {
        nativePort.postMessage(obj);
        return true;
    } catch (e) {
        console.log("[bg] postMessage failed:", e.message);
        return false;
    }
}

// ── استقبال أوامر من Kotlin ───────────────────────────────────────

function onNativeMessage(msg) {
    if (!msg || !msg.type) return;
    console.log("[bg] from Kotlin:", msg.type);

    if (msg.type === "CAPTURE") {
        sendCaptureToContent(msg.tabId);
    }
}

// ── إرسال CAPTURE لـ content.js ──────────────────────────────────

function sendCaptureToContent(preferredTabId) {
    getTargetTab(preferredTabId).then(function (tab) {
        if (!tab) {
            safePost({ type: "CAPTURE_RESULT", success: false,
                       text: "", error: "no_tab" });
            return;
        }

        var done = false;

        // استقبل الرد من content.js
        function onContentReply(message, sender) {
            if (done) return;
            if (!message || message.type !== "CAPTURE_RESULT") return;
            if (sender.tab && sender.tab.id !== tab.id) return;

            done = true;
            browser.runtime.onMessage.removeListener(onContentReply);

            // أرسل لـ Kotlin
            safePost({
                type:    "CAPTURE_RESULT",
                success: message.success,
                text:    message.text   || "",
                domain:  message.domain || "",
                debug:   message.debug  || null
            });
        }

        browser.runtime.onMessage.addListener(onContentReply);

        // timeout إذا لم يرد content.js
        setTimeout(function () {
            if (done) return;
            done = true;
            browser.runtime.onMessage.removeListener(onContentReply);
            safePost({ type: "CAPTURE_RESULT", success: false,
                       text: "", error: "timeout" });
        }, TAB_TIMEOUT);

        // أرسل الأمر لـ content.js
        browser.tabs.sendMessage(tab.id, { type: "CAPTURE" })
            .catch(function (e) {
                if (done) return;
                done = true;
                browser.runtime.onMessage.removeListener(onContentReply);
                safePost({ type: "CAPTURE_RESULT", success: false,
                           text: "", error: e.message });
            });

    }).catch(function (e) {
        safePost({ type: "CAPTURE_RESULT", success: false,
                   text: "", error: e.message });
    });
}

function getTargetTab(preferredId) {
    if (typeof preferredId === "number") {
        return browser.tabs.get(preferredId).catch(function () {
            return getActiveTab();
        });
    }
    return getActiveTab();
}

function getActiveTab() {
    return browser.tabs.query({ active: true }).then(function (tabs) {
        if (tabs && tabs.length > 0) return tabs[0];
        return browser.tabs.query({}).then(function (all) {
            return (all && all.length > 0) ? all[0] : null;
        });
    });
}

// ── استقبال AI_RESPONSE من content.js ────────────────────────────

browser.runtime.onMessage.addListener(function (message, sender) {
    if (!message || !message.type) return;

    if (message.type === "AI_RESPONSE") {
        safePost({
            type:   "AI_RESPONSE",
            text:   message.text   || "",
            domain: message.domain || "",
            url:    message.url    || ""
        });
    }
});

// ── ابدأ ──────────────────────────────────────────────────────────

connectNative();
