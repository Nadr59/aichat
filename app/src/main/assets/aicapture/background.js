"use strict";

/**
 * AiChat WebExtension — background.js
 *
 * [v1.0.1-diag] إضافة تشخيصية بحتة فقط (بدون أي تغيير في منطق الاتصال):
 * جميع سجلات logLocal() أصبحت تُوجَّه الآن أيضاً إلى Kotlin عبر نفس قناة
 * DEBUG_INFO التي يستخدمها content.js بنجاح (وتظهر في عارض 🐛 داخل
 * ChatScreen). الهدف: الحصول على دليل فعلي من اختبار Venice القادم حول
 * التسلسل الزمني الكامل لأحداث background.js (استلام من Kotlin، تحديد
 * التبويب، محاولات الإرسال، النتيجة)، بدل رؤية رسالة الفشل النهائية فقط.
 *
 * ملاحظة أمان مهمة: postToKotlin() كانت تستدعي logLocal() داخل مساري
 * الفشل الخاصين بها. لو تُركت كما هي وأصبحت logLocal() تستدعي
 * postToKotlin()، لنتج استدعاء دائري لا نهائي عند غياب nativePort (وهي
 * بالضبط اللحظة التي نريد تشخيصها). لذلك عُدِّلت مساري الفشل في
 * postToKotlin() لاستخدام console.log مباشرة بدل logLocal — هذا التعديل
 * المصاحب ضروري لتفادي عطل جديد، وهو الوحيد غير المتعلق مباشرة بالإضافة
 * التشخيصية نفسها.
 *
 * الهدف الأصلي للملف (دون تغيير):
 * - استقبال رسائل Kotlin عبر NativeMessaging Port
 * - توجيهها إلى content.js في تبويب المنصة الصحيح
 *
 * إصلاحات أساسية (قديمة، دون تغيير):
 * 1) لا نعتمد على tabs.query({active:true}) (مشاكل GeckoView)
 * 2) لا نعتمد على "آخر تبويب" global لأن content.js محقون على <all_urls>
 *    وبالتالي أي صفحة يمكن أن تغيّر currentTab بالخطأ.
 *    الحل: تخزين tabId لكل منصة/دومين (Map domain -> tabId).
 * 3) مطابقة دومين صحيحة عبر hostname (بدون includes)
 * 4) Retry عند خطأ: Receiving end does not exist (سباق تحميل/إعادة تحميل)
 */

const NATIVE_APP = "browser";
const BG_VERSION = "1.0.1-diag";

let nativePort = null;

// domain(base) -> tabId
const tabByBaseDomain = new Map();

// tabId readiness (CONTENT_READY وصل من content.js)
const readyTabs = new Set();

// ============================================================
// Logging
// ============================================================

function logLocal(message) {
  const ts = new Date().toLocaleTimeString("en-US", { hour12: false });
  const line = "[Background] " + ts + "  " + message;

  try {
    console.log(line);
  } catch (_) {}

  // [v1.0.1-diag] توجيه كل سجل محلي أيضاً إلى Kotlin عبر نفس قناة
  // DEBUG_INFO التي يستخدمها content.js فعلياً (مُتحقَّق أنها تصل لعارض
  // 🐛). لا يُستدعى logLocal من داخل postToKotlin (انظر الملاحظة أعلاه)
  // لتفادي استدعاء دائري لا نهائي.
  postToKotlin({
    type: "DEBUG_INFO",
    source: "background.js",
    platform: "background",
    host: "",
    url: "",
    version: BG_VERSION,
    info: String(message || ""),
    timestamp: Date.now()
  });
}

function postToKotlin(obj) {
  if (!nativePort) {
    // [v1.0.1-diag] console.log مباشرة (وليس logLocal) لتفادي الحلقة
    // اللانهائية: logLocal -> postToKotlin -> (فشل) -> logLocal -> ...
    try {
      console.log("[Background] ❌ postToKotlin: Native port unavailable");
    } catch (_) {}
    return;
  }
  try {
    nativePort.postMessage(obj);
  } catch (e) {
    // [v1.0.1-diag] نفس السبب: console.log مباشرة هنا أيضاً.
    try {
      console.log(
        "[Background] ❌ postToKotlin failed: " +
        (e && e.message ? e.message : String(e))
      );
    } catch (_) {}
  }
}

// ============================================================
// Domain / URL helpers
// ============================================================

function normalize(s) {
  return String(s || "").trim().toLowerCase();
}

function safeHost(url) {
  try {
    return new URL(url).hostname.toLowerCase();
  } catch (_) {
    return "";
  }
}

/**
 * حوّل أي host إلى base domain للمنصات المعروفة.
 * يرجع "" إذا الموقع ليس منصة مدعومة (لمنع تلويث tabByBaseDomain).
 */
function toBaseDomainFromHost(host) {
  host = normalize(host);

  // ChatGPT aliases -> treat both as chatgpt.com
  if (host === "chatgpt.com" || host.endsWith(".chatgpt.com")) return "chatgpt.com";
  if (host === "chat.openai.com" || host.endsWith(".chat.openai.com")) return "chatgpt.com";

  // Arena
  if (host === "arena.ai" || host.endsWith(".arena.ai")) return "arena.ai";

  // Venice
  if (host === "venice.ai" || host.endsWith(".venice.ai")) return "venice.ai";

  return "";
}

function toBaseDomainFromUrl(url) {
  return toBaseDomainFromHost(safeHost(url));
}

function buildUrlQueryPatterns(baseDomain) {
  baseDomain = normalize(baseDomain);

  if (baseDomain === "chatgpt.com") {
    // include chat.openai.com too
    return [
      "*://chatgpt.com/*",
      "*://*.chatgpt.com/*",
      "*://chat.openai.com/*",
      "*://*.chat.openai.com/*"
    ];
  }

  return [`*://${baseDomain}/*`, `*://*.${baseDomain}/*`];
}

function purgeTabFromMaps(tabId) {
  readyTabs.delete(tabId);
  for (const [domain, id] of tabByBaseDomain.entries()) {
    if (id === tabId) tabByBaseDomain.delete(domain);
  }
}

// ============================================================
// Remember sender tab (Content -> Background)
// ============================================================

function rememberSenderTab(sender, message) {
  try {
    const tabId = sender?.tab?.id;
    if (tabId === undefined || tabId === null) return;

    // الأفضلية: sender.tab.url ثم message.url (CONTENT_READY يرسل url)
    const url = sender?.tab?.url || message?.url || "";
    const baseDomain = toBaseDomainFromUrl(url);

    // تجاهل أي صفحات ليست منصات (لأن content.js على <all_urls>)
    if (!baseDomain) return;

    const old = tabByBaseDomain.get(baseDomain);
    if (old !== tabId) {
      tabByBaseDomain.set(baseDomain, tabId);
      logLocal(`📌 remembered ${baseDomain} -> tab=${tabId} url=${url}`);
    }
  } catch (_) {
    // ignore
  }
}

// ============================================================
// Resolve target tab
// ============================================================

function resolvePlatformTab(message) {
  const requestedDomain = normalize(message?.domain || message?.targetDomain);

  if (!requestedDomain) {
    return Promise.reject(new Error("NO_TARGET_DOMAIN"));
  }

  // Normalize aliases to base domain when possible
  const baseDomain = toBaseDomainFromHost(requestedDomain) || requestedDomain;

  // 1) Try remembered tab for this domain
  const rememberedTabId = tabByBaseDomain.get(baseDomain);
  if (rememberedTabId != null) {
    return browser.tabs
      .get(rememberedTabId)
      .then((tab) => {
        if (!tab) throw new Error("NO_PLATFORM_TAB");

        const tabBase = toBaseDomainFromUrl(tab.url || "");
        // إذا كانت منصة معروفة: تأكد من التطابق
        // (للدومينات غير المعروفة، tabBase سيكون "" لذا لا نعتمد هذا المسار)
        if (toBaseDomainFromHost(baseDomain) && tabBase !== baseDomain) {
          tabByBaseDomain.delete(baseDomain);
          throw new Error("STALE_TAB_FOR_DOMAIN");
        }

        return tab;
      })
      .catch(() => {
        tabByBaseDomain.delete(baseDomain);
        // fallback to query
        return browser.tabs.query({ url: buildUrlQueryPatterns(baseDomain) }).then((tabs) => {
          if (!tabs || tabs.length === 0) throw new Error("NO_PLATFORM_TAB");
          tabs.sort((a, b) => (b.lastAccessed || 0) - (a.lastAccessed || 0));
          tabByBaseDomain.set(baseDomain, tabs[0].id);
          logLocal(`🎯 resolved ${baseDomain} -> tab=${tabs[0].id} url=${tabs[0].url || ""}`);
          return tabs[0];
        });
      });
  }

  // 2) Query by URL patterns (أفضل بكثير من query({}) + includes)
  return browser.tabs.query({ url: buildUrlQueryPatterns(baseDomain) }).then((tabs) => {
    if (!tabs || tabs.length === 0) throw new Error("NO_PLATFORM_TAB");
    tabs.sort((a, b) => (b.lastAccessed || 0) - (a.lastAccessed || 0));
    tabByBaseDomain.set(baseDomain, tabs[0].id);
    logLocal(`🎯 resolved ${baseDomain} -> tab=${tabs[0].id} url=${tabs[0].url || ""}`);
    return tabs[0];
  });
}

// ============================================================
// Send message with retry (race with load/reload)
// ============================================================

async function sendMessageWithRetry(tabId, payload, retries = 8, delayMs = 250) {
  let lastErr = null;

  for (let i = 0; i < retries; i++) {
    try {
      return await browser.tabs.sendMessage(tabId, payload);
    } catch (e) {
      lastErr = e;
      const msg = String(e?.message || e);

      // retry only for "Receiving end does not exist"
      if (!msg.includes("Receiving end does not exist")) throw e;

      await new Promise((r) => setTimeout(r, delayMs));
    }
  }

  throw lastErr || new Error("NO_RECEIVER");
}

// ============================================================
// Kotlin → Background → Content
// ============================================================

function sendContextToPlatform(message) {
  return resolvePlatformTab(message).then(async (tab) => {
    logLocal("📤 Background → Content: tab=" + tab.id + " url=" + (tab.url || ""));

    // حتى لو لم يصل CONTENT_READY بعد، الـ retry يعالج السباق غالباً
    const response = await sendMessageWithRetry(tab.id, {
      type: "CONTEXT_TO_PAGE",
      text: message.text || "",
      contextId: message.contextId || 0,
      domain: message.domain || "",
      inputSelector: message.inputSelector || "",
      sendSelector: message.sendSelector || "",
      sendWithEnter: message.sendWithEnter !== false,
      timestamp: message.timestamp || Date.now()
    });

    logLocal("✅ Content replied: " + JSON.stringify(response || {}));
    return response;
  });
}

function sendManualCaptureToPlatform(message) {
  return resolvePlatformTab(message || {}).then(async (tab) => {
    logLocal("📤 MANUAL_CAPTURE → Content: tab=" + tab.id + " url=" + (tab.url || ""));

    const response = await sendMessageWithRetry(tab.id, {
      type: "MANUAL_CAPTURE",
      timestamp: Date.now()
    });

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
            detail
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

    // retry connect if connectNative throws
    setTimeout(() => connectToNative(), 1000);
  }
}

// ============================================================
// Content → Background
// ============================================================

browser.runtime.onMessage.addListener((message, sender) => {
  if (!message || !message.type) return Promise.resolve({ ok: false });

  // احفظ tabId فقط للمنصات المعروفة (مهم جداً)
  rememberSenderTab(sender, message);

  if (message.type === "CONTENT_READY") {
    const tabId = sender?.tab?.id;
    if (tabId !== undefined && tabId !== null) readyTabs.add(tabId);
    return Promise.resolve({ ok: true });
  }

  // Pass-through: أي رسالة أخرى من content إلى Kotlin
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

// ============================================================
// Tab lifecycle (cleanup)
// ============================================================

browser.tabs.onUpdated.addListener((tabId, changeInfo) => {
  if (changeInfo.status === "loading") {
    // أثناء reload قد يفشل sendMessage مؤقتاً
    // نخلي retry يتعامل، لكن ننظف ready state
    readyTabs.delete(tabId);
  }
});

browser.tabs.onRemoved.addListener((tabId) => {
  purgeTabFromMaps(tabId);
});

// Startup
logLocal("✅ Background script ready v=" + BG_VERSION);
connectToNative();
