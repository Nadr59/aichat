"use strict";

const NATIVE_APP = "browser";

let nativePort = null;
let currentPlatformTabId = null;

function logLocal(message) {
  const ts = new Date().toLocaleTimeString("en-US", { hour12: false });
  console.log("[Background] " + ts + "  " + message);
}

function postToKotlin(obj) {
  if (!nativePort) {
    logLocal("❌ postToKotlin: Native port unavailable");
    return;
  }
  try {
    nativePort.postMessage(obj);
  } catch (e) {
    logLocal("❌ postToKotlin failed: " + (e && e.message ? e.message : String(e)));
  }
}

/**
 * يحاول إيجاد tab نشط:
 * 1) إذا كان لدينا currentPlatformTabId نستخدمه.
 * 2) وإلا نحاول الحصول على active tab الحالية.
 */
function findActivePlatformTab() {
  if (currentPlatformTabId !== null) {
    return browser.tabs
      .get(currentPlatformTabId)
      .then((tab) => {
        if (!tab) throw new Error("NO_PLATFORM_TAB");
        return tab;
      })
      .catch((err) => {
        logLocal("⚠️ Saved platform tab invalid: " + (err && err.message ? err.message : String(err)));
        currentPlatformTabId = null;
        // fallback للـ active tab
        return browser.tabs.query({ active: true, currentWindow: true }).then((tabs) => {
          if (!tabs || tabs.length === 0) throw new Error("NO_ACTIVE_TAB");
          return tabs[0];
        });
      });
  }

  return browser.tabs.query({ active: true, currentWindow: true }).then((tabs) => {
    if (!tabs || tabs.length === 0) throw new Error("NO_ACTIVE_TAB");
    return tabs[0];
  });
}

// ============================================================
// Kotlin → Background → Content
// ============================================================

function sendContextToPlatform(message) {
  return findActivePlatformTab()
    .then((tab) => {
      logLocal("📤 Background → Content: tab=" + tab.id + " url=" + (tab.url || ""));

      // ✅ تمرير selectors للإرسال التلقائي
      return browser.tabs.sendMessage(tab.id, {
        type: "CONTEXT_TO_PAGE",
        text: message.text || "",
        contextId: message.contextId || 0,
        domain: message.domain || "",
        inputSelector: message.inputSelector || "",
        sendSelector: message.sendSelector || "",
        sendWithEnter: message.sendWithEnter !== false, // default true
        timestamp: message.timestamp || Date.now()
      });
    })
    .then((response) => {
      logLocal("✅ Content replied: " + JSON.stringify(response || {}));
      return response;
    });
}

function sendManualCaptureToPlatform() {
  return findActivePlatformTab()
    .then((tab) => {
      logLocal("📤 MANUAL_CAPTURE → Content: tab=" + tab.id + " url=" + (tab.url || ""));
      return browser.tabs.sendMessage(tab.id, {
        type: "MANUAL_CAPTURE",
        timestamp: Date.now()
      });
    })
    .then((response) => {
      logLocal("✅ MANUAL_CAPTURE Content replied: " + JSON.stringify(response || {}));
      return response;
    });
}

// ============================================================
// Native messaging connect
// ============================================================

function connectToNative() {
  try {
    logLocal("🔌 Connecting to Native: " + NATIVE_APP);
    nativePort = browser.runtime.connectNative(NATIVE_APP);
    logLocal("✅ Native Port created");

    nativePort.onMessage.addListener((message) => {
      logLocal("🚨 FROM KOTLIN: " + JSON.stringify(message || {}));

      if (!message || !message.type) return;

      // CONTEXT_TO_PAGE
      if (message.type === "CONTEXT_TO_PAGE") {
        sendContextToPlatform(message).catch((err) => {
          const detail = err && err.message ? err.message : String(err);
          logLocal("❌ CONTEXT_TO_PAGE Background → Content failed: " + detail);

          postToKotlin({
            type: "CONTEXT_WRITTEN",
            success: false,
            contextId: message.contextId || 0,
            stage: "background_to_content_failed",
            detail: detail
          });
        });
        return;
      }

      // MANUAL_CAPTURE
      if (message.type === "MANUAL_CAPTURE") {
        sendManualCaptureToPlatform().catch((err) => {
          const detail = err && err.message ? err.message : String(err);
          logLocal("❌ MANUAL_CAPTURE Background → Content failed: " + detail);

          postToKotlin({
            type: "CAPTURE_RESULT",
            success: false,
            text: "",
            debug: { reason: detail }
          });
        });
        return;
      }
    });

    nativePort.onDisconnect.addListener(() => {
      logLocal("⚠️ Native Port disconnected");
      nativePort = null;

      setTimeout(() => {
        logLocal("🔄 Reconnecting...");
        connectToNative();
      }, 1000);
    });

    // رسالة readiness بسيطة
    postToKotlin({
      type: "EXT_READY",
      source: "background.js",
      timestamp: new Date().toISOString()
    });

  } catch (e) {
    logLocal("❌ connectNative failed: " + (e && e.message ? e.message : String(e)));
  }
}

// ============================================================
// Content → Background
// ============================================================

browser.runtime.onMessage.addListener((message, sender) => {
  if (!message || !message.type) return Promise.resolve({ ok: false });

  // content.js يخبرنا بالتاب النشط
  if (message.type === "CONTENT_READY") {
    if (sender && sender.tab && sender.tab.id !== undefined) {
      currentPlatformTabId = sender.tab.id;
      logLocal(
        "📌 CONTENT_READY saved tab=" +
          currentPlatformTabId +
          " url=" +
          (sender.tab.url || message.url || "")
      );
    } else {
      logLocal("⚠️ CONTENT_READY without sender.tab.id");
    }
    return Promise.resolve({ ok: true });
  }

  // تمرير أي رسالة أخرى من content إلى Kotlin (ASSISTANT_RESPONSE / CONTEXT_WRITTEN / CAPTURE_RESULT / DEBUG_INFO...)
  if (nativePort) {
    logLocal("↗️ Content → Kotlin: " + JSON.stringify(message));
    try {
      nativePort.postMessage(message);
    } catch (e) {
      logLocal("❌ Content → Kotlin failed: " + (e && e.message ? e.message : String(e)));
    }
  } else {
    logLocal("❌ Content → Kotlin failed: Native port unavailable");
  }

  return Promise.resolve({ ok: true });
});

// Startup
logLocal("✅ Background script ready");
connectToNative();
