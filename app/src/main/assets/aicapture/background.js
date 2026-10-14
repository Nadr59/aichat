"use strict";

/**
 * AiChat WebExtension — background.js
 * هدف هذا الملف:
 * - استقبال رسائل Kotlin عبر NativeMessaging Port
 * - توجيهها إلى content.js في تبويب المنصة الصحيح
 *
 * إصلاح أساسي:
 * - لا نعتمد على tabs.query({active:true}) لأن GeckoView كثيراً يرجّع NO_ACTIVE_TAB
 * - نعتمد على آخر sender.tab.id من content.js (CONTENT_READY / DEBUG_INFO / أي رسالة)
 * - وإذا لم يكن لدينا tabId بعد، نحاول إيجاد tab بمطابقة domain القادم من Kotlin
 */

const NATIVE_APP = "browser";

let nativePort = null;

/** آخر تبويب معروف للمنصة (من sender.tab.id) */
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

/** احفظ sender.tab.id من أي رسالة قادمة من content.js */
function rememberSenderTab(sender, message) {
  try {
    if (sender && sender.tab && sender.tab.id !== undefined) {
      const newId = sender.tab.id;
      if (currentPlatformTabId !== newId) {
        currentPlatformTabId = newId;
        logLocal(
          "📌 remembered platform tab=" +
            currentPlatformTabId +
            " url=" +
            (sender.tab.url || (message && message.url) || "")
        );
      }
    }
  } catch (e) {
    // ignore
  }
}

/**
 * حل تبويب المنصة المستهدف:
 * 1) إذا لدينا currentPlatformTabId → استخدمه
 * 2) وإلا → tabs.query({}) ثم:
 *    - اختر tab يطابق domain (host) إن وجد
 *    - إن لم يوجد وكان هناك tab واحد → اختره
 *    - وإلا فشل
 */
function resolvePlatformTab(message) {
  const targetDomain = (message && (message.domain || message.targetDomain) ? String(message.domain || message.targetDomain) : "")
    .trim()
    .toLowerCase();

  if (currentPlatformTabId !== null) {
    return browser.tabs
      .get(currentPlatformTabId)
      .then((tab) => {
        if (!tab) throw new Error("NO_PLATFORM_TAB");
        return tab;
      })
      .catch(() => {
        currentPlatformTabId = null;
        throw new Error("NO_PLATFORM_TAB");
      });
  }

  return browser.tabs.query({}).then((tabs) => {
    if (!tabs || tabs.length === 0) throw new Error("NO_PLATFORM_TAB");

    if (targetDomain) {
      // match by url host contains domain
      for (let i = 0; i < tabs.length; i++) {
        const u = (tabs[i].url || "").toLowerCase();
        if (u.includes(targetDomain)) {
          currentPlatformTabId = tabs[i].id;
          logLocal("🎯 resolved tab by domain=" + targetDomain + " -> tab=" + currentPlatformTabId);
          return tabs[i];
        }
      }
    }

    if (tabs.length === 1) {
      currentPlatformTabId = tabs[0].id;
      logLocal("🎯 resolved single tab -> tab=" + currentPlatformTabId);
      return tabs[0];
    }

    throw new Error("NO_PLATFORM_TAB");
  });
}

// ============================================================
// Kotlin → Background → Content
// ============================================================

function sendContextToPlatform(message) {
  return resolvePlatformTab(message)
    .then((tab) => {
      logLocal("📤 Background → Content: tab=" + tab.id + " url=" + (tab.url || ""));

      return browser.tabs.sendMessage(tab.id, {
        type: "CONTEXT_TO_PAGE",
        text: message.text || "",
        contextId: message.contextId || 0,
        domain: message.domain || "",

        // selectors للإرسال التلقائي
        inputSelector: message.inputSelector || "",
        sendSelector: message.sendSelector || "",
        sendWithEnter: message.sendWithEnter !== false,

        timestamp: message.timestamp || Date.now()
      });
    })
    .then((response) => {
      logLocal("✅ Content replied: " + JSON.stringify(response || {}));
      return response;
    });
}

function sendManualCaptureToPlatform(message) {
  return resolvePlatformTab(message || {})
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

      if (message.type === "MANUAL_CAPTURE") {
        sendManualCaptureToPlatform(message).catch((err) => {
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

  // ✅ احفظ tab.id من أي رسالة
  rememberSenderTab(sender, message);

  // content.js يخبرنا بالجاهزية (مفيد لكنه لم يعد شرطاً)
  if (message.type === "CONTENT_READY") {
    return Promise.resolve({ ok: true });
  }

  // تمرير أي رسالة أخرى من content إلى Kotlin
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
