(function () {
  "use strict";

  var VERSION = "1.0.46";
  var HOST = String((location && location.hostname) || "");
  var HREF = String((location && location.href) || "");

  // إذا true: سيرسل DEBUG/READY حتى على صفحات generic
  var ALLOW_GENERIC_DIAGNOSTICS = false;

  // ============================================================
  // Platforms
  // ============================================================

  function hostEndsWith(h, suffix) {
    try { return String(h || "").toLowerCase().endsWith(String(suffix || "").toLowerCase()); } catch (_) { return false; }
  }
  function hostContains(h, part) {
    try { return String(h || "").toLowerCase().indexOf(String(part || "").toLowerCase()) !== -1; } catch (_) { return false; }
  }

  var PLATFORMS = {
    chatgpt: {
      id: "chatgpt",
      name: "ChatGPT",
      matches: function () {
        return HOST === "chatgpt.com" || HOST === "chat.openai.com" || hostEndsWith(HOST, ".openai.com");
      },
      responseSelectors: [
        // ChatGPT new UI
        'article[data-message-author-role="assistant"]',
        '[data-message-author-role="assistant"]',
        '[data-role="assistant"]',
        '[data-message-author="assistant"]',
        ".agent-turn"
      ],
      userSelectors: [
        'article[data-message-author-role="user"]',
        '[data-message-author-role="user"]',
        '[data-role="user"]',
        '[data-message-author="user"]'
      ],
      userTextSelector: ".whitespace-pre-wrap, .markdown, [data-message-content]",
      extractResponse: function (el) {
        if (!el) return "";
        var md = null;
        try { md = el.querySelector(".markdown"); } catch (e) {}
        return getElementText(md || el);
      },
      // ✅ تم تعطيل legacyContainerMode لأنه كان سبب التقاط التحذير
      legacyContainerMode: false
    },

    venice: {
      id: "venice",
      name: "Venice",
      matches: function () {
        // بعض نسخ الموقع قد تكون chat.venice.ai أو www.venice.ai
        return HOST === "venice.ai" || hostEndsWith(HOST, ".venice.ai");
      },
      responseSelectors: [
        '[data-message-author-role="assistant"]',
        '[data-role="assistant"]',
        ".assistant-message",
        ".ai-message",
        ".message.assistant",
        ".prose",
        "main .prose"
      ],
      userSelectors: [
        '[data-message-author-role="user"]',
        '[data-role="user"]',
        ".user-message",
        ".human-message",
        ".message.user"
      ],
      userTextSelector: ".prose, .message-content, .content, [data-message-content]",
      extractResponse: function (el) { return getElementText(el); },
      legacyContainerMode: false
    },

    arena: {
      id: "arena",
      name: "Arena",
      matches: function () {
        // ✅ دعم الدومينات الشائعة للـ Arena
        if (HOST === "arena.ai") return true;
        if (HOST === "lmarena.ai") return true;
        if (HOST === "arena.lmsys.org") return true;
        if (HOST === "chat.lmsys.org") return true;
        if (hostEndsWith(HOST, ".lmsys.org")) return true;
        // fallback: كلمة arena مع lmsys
        if (hostContains(HOST, "lmsys") && hostContains(HOST, "arena")) return true;
        return false;
      },
      responseSelectors: [
        '[data-message-author-role="assistant"]',
        '[data-role="assistant"]',
        ".assistant-message",
        ".ai-message",
        ".message.assistant",
        "main .prose",
        ".prose.prose-base",
        "div.prose",
        "main div.prose"
      ],
      userSelectors: [
        '[data-message-author-role="user"]',
        '[data-role="user"]',
        ".user-message",
        ".human-message",
        ".message.user"
      ],
      userTextSelector: "main .prose, .prose, .message-content, [data-message-content]",
      extractResponse: function (el) { return getElementText(el); },
      legacyContainerMode: false
    },

    generic: {
      id: "generic",
      name: "Generic Web AI",
      matches: function () { return true; },
      responseSelectors: [
        '[data-message-author-role="assistant"]', '[data-role="assistant"]', '[data-message-author="assistant"]',
        ".assistant-message", ".ai-message", ".model-response", ".response-container", ".prose", "main .prose"
      ],
      userSelectors: [
        '[data-message-author-role="user"]', '[data-role="user"]', '[data-message-author="user"]',
        ".user-message", ".human-message", ".message.user"
      ],
      userTextSelector: ".prose, .message-content, [data-message-content]",
      extractResponse: function (el) { return getElementText(el); },
      legacyContainerMode: false
    }
  };

  function detectPlatform() {
    if (PLATFORMS.chatgpt.matches()) return PLATFORMS.chatgpt;
    if (PLATFORMS.venice.matches()) return PLATFORMS.venice;
    if (PLATFORMS.arena.matches()) return PLATFORMS.arena;
    return PLATFORMS.generic;
  }

  var CURRENT_PLATFORM = detectPlatform();

  function shouldSendDiagnostics() {
    return CURRENT_PLATFORM.id !== "generic" || !!ALLOW_GENERIC_DIAGNOSTICS;
  }

  // ============================================================
  // Messaging helpers
  // ============================================================

  function sendToBackground(payload) {
    try {
      if (!payload || !payload.type) return;
      payload.platform = payload.platform || CURRENT_PLATFORM.id;
      payload.host = payload.host || HOST;
      payload.url = payload.url || HREF;
      payload.version = payload.version || VERSION;
      payload.timestamp = payload.timestamp || Date.now();
      browser.runtime.sendMessage(payload);
    } catch (e) {
      try { console.log("[AiChat] sendToBackground failed:", e); } catch (_) {}
    }
  }

  function log(msg) {
    if (!shouldSendDiagnostics()) { try { console.log("[AiChat] " + msg); } catch (_) {} return; }
    sendToBackground({ type: "DEBUG_INFO", info: String(msg || "") });
  }

  log("🧪 content.js loaded v=" + VERSION);
  log("🌐 Platform detected: " + CURRENT_PLATFORM.name + " (" + CURRENT_PLATFORM.id + ") host=" + HOST);

  // ============================================================
  // DOM helpers
  // ============================================================

  function getElementText(el) {
    try {
      return (el && (el.innerText || el.textContent) ? (el.innerText || el.textContent) : "")
        .replace(/\u00a0/g, " ")
        .replace(/\r/g, "")
        .trim();
    } catch (e) { return ""; }
  }

  function isProbablyVisible(el) {
    if (!el) return false;
    try {
      var rect = el.getBoundingClientRect();
      if (!rect || rect.width < 2 || rect.height < 2) return false;
      var cs = window.getComputedStyle(el);
      if (!cs) return true;
      if (cs.display === "none" || cs.visibility === "hidden") return false;
      return true;
    } catch (_) {
      return false;
    }
  }

  function uniquePush(arr, el) {
    if (!el) return;
    if (arr.indexOf(el) === -1) arr.push(el);
  }

  // ============================================================
  // Input finders (stable for Venice first-open)
  // ============================================================

  var DEFAULT_INPUT_SELECTORS = [
    "#prompt-textarea", "textarea#prompt-textarea", "textarea[name='prompt']",
    "textarea[data-testid='textbox']", "textarea[data-testid*='textbox']",
    "textarea[placeholder*='Message']", "textarea[placeholder*='message']",
    "textarea[aria-label*='Message']", "textarea[aria-label*='message']",
    "div[contenteditable='true'][role='textbox']", "[contenteditable='true'][role='textbox']",
    ".ProseMirror", "[data-lexical-editor='true']", "[role='textbox']",
    "[contenteditable='true']", "textarea"
  ];

  function scoreInput(el) {
    if (!el) return -9999;
    var score = 0;

    try {
      var tag = String(el.tagName || "").toUpperCase();
      var isTextarea = tag === "TEXTAREA" || tag === "INPUT";
      var isEditable = !!el.isContentEditable || el.getAttribute("contenteditable") === "true";
      if (!isTextarea && !isEditable) score -= 20;

      if (el.disabled) score -= 50;
      if (el.readOnly) score -= 40;

      if (isProbablyVisible(el)) score += 25;

      var id = String(el.id || "").toLowerCase();
      var name = String(el.getAttribute("name") || "").toLowerCase();
      var ph = String(el.getAttribute("placeholder") || "").toLowerCase();
      var aria = String(el.getAttribute("aria-label") || "").toLowerCase();
      var role = String(el.getAttribute("role") || "").toLowerCase();

      if (id.includes("prompt")) score += 10;
      if (name.includes("prompt")) score += 6;
      if (ph.includes("message") || ph.includes("ask") || ph.includes("اكتب") || ph.includes("رسالة")) score += 8;
      if (aria.includes("message") || aria.includes("prompt") || aria.includes("اكتب") || aria.includes("رسالة")) score += 6;
      if (role === "textbox") score += 5;

      var rect = el.getBoundingClientRect();
      var vh = Math.max(1, window.innerHeight || 1);
      var bottomRatio = rect.bottom / vh;
      if (bottomRatio > 0.7) score += 8;

      try {
        var form = el.closest && el.closest("form");
        if (form && form.querySelector("button[type='submit'], button[aria-label*='send' i], button[data-testid*='send' i]")) score += 10;
      } catch (_) {}
    } catch (_) {}

    return score;
  }

  function collectInputsBySelector(sel, out) {
    if (!sel || !sel.trim()) return;
    try {
      var list = document.querySelectorAll(sel);
      for (var i = 0; i < list.length; i++) uniquePush(out, list[i]);
    } catch (_) {}
  }

  function findInput(preferSelector) {
    var candidates = [];

    if (preferSelector && preferSelector.trim()) collectInputsBySelector(preferSelector, candidates);
    for (var i = 0; i < DEFAULT_INPUT_SELECTORS.length; i++) collectInputsBySelector(DEFAULT_INPUT_SELECTORS[i], candidates);

    if (!candidates.length) return null;

    var best = null;
    var bestScore = -9999;
    for (var j = 0; j < candidates.length; j++) {
      var s = scoreInput(candidates[j]);
      if (s > bestScore) {
        bestScore = s;
        best = candidates[j];
      }
    }

    return best || candidates[0] || null;
  }

  function findInputWithRetry(preferSelector, attempts, delay, ok, fail) {
    var n = 0;
    (function loop() {
      var input = findInput(preferSelector);
      if (input) return ok(input);
      if (++n >= attempts) return fail && fail();
      setTimeout(loop, delay);
    })();
  }

  // ============================================================
  // Composer Hide/Show + Toggle
  // ============================================================

  var composerHidden = false;
  var composerUserPref = null; // null=default, true=hide, false=show
  var toggleBtn = null;
  var applyTimer = 0;

  // سياسة الإخفاء:
  // - ChatGPT: نخفي composer (لأننا نستخدم مربع التطبيق)
  // - Venice: لا نخفي (يمكنك تغييره لاحقًا)
  // - Arena: لا نخفي (لأننا نكتب فقط بدون إرسال)
  function defaultShouldHideComposer() {
    if (CURRENT_PLATFORM.id === "generic") return false;
    if (CURRENT_PLATFORM.id === "chatgpt") return true;
    return false;
  }

  function getComposerContainer() {
    if (CURRENT_PLATFORM.id === "generic") return null;
    var input = findInput("");
    if (!input) return null;
    try {
      return input.closest("form") || input.closest("footer") || input.closest("[role='form']") ||
        input.closest("section") || input.parentElement || null;
    } catch (e) { return null; }
  }

  function hideComposer(el) {
    if (!el) return false;
    if (el.getAttribute("data-aichat-hidden") === "1") { composerHidden = true; return true; }
    if (!el.getAttribute("data-aichat-old-style")) el.setAttribute("data-aichat-old-style", el.style.cssText || "");
    el.setAttribute("data-aichat-hidden", "1");
    el.style.opacity = "0";
    el.style.height = "0px";
    el.style.minHeight = "0px";
    el.style.maxHeight = "0px";
    el.style.overflow = "hidden";
    el.style.padding = "0";
    el.style.margin = "0";
    el.style.pointerEvents = "none";
    composerHidden = true;
    return true;
  }

  function showComposer(el) {
    if (!el) return false;
    if (el.getAttribute("data-aichat-hidden") !== "1") { composerHidden = false; return true; }
    var old = el.getAttribute("data-aichat-old-style");
    el.style.cssText = old || "";
    el.removeAttribute("data-aichat-hidden");
    el.removeAttribute("data-aichat-old-style");
    composerHidden = false;
    return true;
  }

  function updateToggleBtn() {
    if (!toggleBtn) return;
    toggleBtn.textContent = composerHidden ? "إظهار مربع الموقع" : "إخفاء مربع الموقع";
    toggleBtn.style.background = composerHidden ? "rgba(20,120,60,0.95)" : "rgba(0,0,0,0.75)";
  }

  function applyComposerPrefSoon() {
    if (CURRENT_PLATFORM.id === "generic") return;
    if (applyTimer) return;
    applyTimer = setTimeout(function () {
      applyTimer = 0;
      var el = getComposerContainer();
      if (!el) return;
      var wantHide = (composerUserPref === null) ? defaultShouldHideComposer() : composerUserPref;
      wantHide ? hideComposer(el) : showComposer(el);
      updateToggleBtn();
    }, 80);
  }

  function installToggleButton() {
    if (CURRENT_PLATFORM.id === "generic") return;
    try {
      if (document.getElementById("aichat-toggle-composer")) {
        toggleBtn = document.getElementById("aichat-toggle-composer");
        updateToggleBtn();
        return;
      }
      toggleBtn = document.createElement("button");
      toggleBtn.id = "aichat-toggle-composer";
      toggleBtn.type = "button";
      toggleBtn.style.cssText =
        "position:fixed;right:12px;bottom:12px;z-index:2147483647;border:0;border-radius:10px;" +
        "padding:10px 12px;font-size:13px;color:#fff;cursor:pointer;box-shadow:0 6px 20px rgba(0,0,0,.25);" +
        "user-select:none;";
      toggleBtn.addEventListener("click", function () {
        composerUserPref = composerHidden ? false : true;
        applyComposerPrefSoon();
      });
      document.documentElement.appendChild(toggleBtn);
      updateToggleBtn();
    } catch (e) { log("❌ installToggleButton failed: " + e); }
  }

  function startComposerController() {
    if (CURRENT_PLATFORM.id === "generic") return;
    installToggleButton();
    applyComposerPrefSoon();

    var tries = 0;
    (function loop() {
      tries++;
      applyComposerPrefSoon();
      if (tries < 30) setTimeout(loop, 350);
    })();

    try {
      var obs = new MutationObserver(function () { applyComposerPrefSoon(); });
      obs.observe(document.documentElement, { childList: true, subtree: true });
    } catch (e) {}
  }

  startComposerController();

  // ============================================================
  // Injection
  // ============================================================

  function injectText(input, text) {
    try {
      if (!input) return false;

      if (input.tagName === "TEXTAREA" || input.tagName === "INPUT") {
        var proto = Object.getPrototypeOf(input);
        var desc = Object.getOwnPropertyDescriptor(proto, "value");
        if (desc && desc.set) desc.set.call(input, text); else input.value = text;
        input.dispatchEvent(new Event("input", { bubbles: true, composed: true }));
        input.dispatchEvent(new Event("change", { bubbles: true, composed: true }));
      } else {
        input.focus();
        try { document.execCommand("selectAll", false, null); } catch (e1) {}
        try { document.execCommand("insertText", false, text); }
        catch (e2) {
          input.textContent = text;
          try {
            input.dispatchEvent(new InputEvent("input", { bubbles: true, composed: true, inputType: "insertText", data: text }));
          } catch (_) {
            input.dispatchEvent(new Event("input", { bubbles: true, composed: true }));
          }
        }
      }
      return true;
    } catch (e3) {
      log("❌ Text injection failed: " + (e3 && e3.message ? e3.message : String(e3)));
      return false;
    }
  }

  // ============================================================
  // Send controls
  // ============================================================

  var DEFAULT_SEND_SELECTORS = [
    "button[data-testid='send-button']",
    "button[aria-label*='Send' i]",
    "button[type='submit']",
    "button[aria-label*='send' i]",
    "button[data-testid*='send' i]"
  ];

  // ✅ مهم: fallback يعمل حتى لو preferSelector فاضي
  function findSendButton(preferSelector) {
    var selectors = [];
    if (preferSelector && preferSelector.trim()) selectors.push(preferSelector);
    selectors = selectors.concat(DEFAULT_SEND_SELECTORS);

    for (var i = 0; i < selectors.length; i++) {
      try {
        var btn = document.querySelector(selectors[i]);
        if (btn) return btn;
      } catch (e) {}
    }
    return null;
  }

  function canClick(btn) {
    if (!btn) return false;
    try {
      if (btn.disabled) return false;
      var aria = String(btn.getAttribute("aria-disabled") || "").toLowerCase();
      if (aria === "true") return false;
    } catch (_) {}
    return true;
  }

  function dispatchEnter(target, withCtrl) {
    try {
      target.focus();
      var opt = {
        key: "Enter",
        code: "Enter",
        keyCode: 13,
        which: 13,
        ctrlKey: !!withCtrl,
        shiftKey: false,
        altKey: false,
        metaKey: false,
        bubbles: true,
        cancelable: true,
        composed: true
      };
      target.dispatchEvent(new KeyboardEvent("keydown", opt));
      target.dispatchEvent(new KeyboardEvent("keypress", opt));
      target.dispatchEvent(new KeyboardEvent("keyup", opt));
      try { target.blur(); } catch (e2) {}
      return true;
    } catch (e3) {
      log("❌ Enter dispatch failed: " + (e3 && e3.message ? e3.message : String(e3)));
      return false;
    }
  }

  // ============================================================
  // Context feedback
  // ============================================================

  function sendContextWritten(success, contextId, stage, detail) {
    sendToBackground({
      type: "CONTEXT_WRITTEN",
      success: !!success,
      contextId: contextId || 0,
      stage: stage || "",
      detail: detail || ""
    });
  }

  function sendContextConsumed(contextId) {
    sendToBackground({ type: "CONTEXT_CONSUMED", contextId: contextId || 0 });
  }

  // ============================================================
  // Assistant response extraction
  // ============================================================

  function cleanAssistantResponse(text) {
    if (!text) return "";
    var s = String(text).replace(/\r\n/g, "\n").replace(/\r/g, "\n");
    s = s.replace(/\n[ \t]*\n[ \t]*\n+/g, "\n\n").replace(/[ \t]+\n/g, "\n");
    return s.trim();
  }

  function isChatGPTDisclaimerCandidate(text) {
    if (!text) return false;
    var t = String(text).trim();
    if (!t) return false;

    var s = t.toLowerCase();

    // English disclaimer
    if (s.indexOf("chatgpt can make mistakes") !== -1) return true;
    if (s.indexOf("consider checking important information") !== -1) return true;

    // Arabic disclaimer (أكثر من صيغة)
    if (s.indexOf("قد يخطئ") !== -1) return true;
    if (s.indexOf("تحقق من المعلومات") !== -1) return true;

    // Heuristic: short text containing chatgpt + mistake-like wording
    if (t.length < 200 && s.indexOf("chatgpt") !== -1 && (s.indexOf("mistake") !== -1 || s.indexOf("خطأ") !== -1 || s.indexOf("يخطئ") !== -1)) {
      return true;
    }

    return false;
  }

  function getPlatformResponseElements() {
    var res = [];
    var sels = CURRENT_PLATFORM.responseSelectors || [];
    for (var i = 0; i < sels.length; i++) {
      try {
        var els = document.querySelectorAll(sels[i]);
        for (var j = 0; j < els.length; j++) if (res.indexOf(els[j]) === -1) res.push(els[j]);
      } catch (e) {}
    }
    return res;
  }

  function extractLatestPlatformResponseWithMeta() {
    var els = getPlatformResponseElements();
    if (!els.length) return { text: "", count: 0, reason: "no_elements" };

    var skippedInvisible = 0;
    var skippedDisclaimer = 0;
    var skippedArenaRejected = 0;

    for (var i = els.length - 1; i >= 0; i--) {
      var el = els[i];
      if (!el) continue;

      // Try to avoid UI chrome / hidden blocks
      if (!isProbablyVisible(el)) { skippedInvisible++; continue; }

      var t = "";
      try { t = CURRENT_PLATFORM.extractResponse(el); } catch (e) { t = getElementText(el); }
      t = cleanAssistantResponse(t);

      if (!t) continue;

      // ChatGPT: ignore the “may make mistakes” footer
      if (CURRENT_PLATFORM.id === "chatgpt" && isChatGPTDisclaimerCandidate(t)) { skippedDisclaimer++; continue; }

      // Arena guard
      if (CURRENT_PLATFORM.id === "arena" && isArenaRejectedCandidate(t)) { skippedArenaRejected++; continue; }

      return {
        text: t,
        count: els.length,
        reason: "ok",
        skippedInvisible: skippedInvisible,
        skippedDisclaimer: skippedDisclaimer,
        skippedArenaRejected: skippedArenaRejected
      };
    }

    return {
      text: "",
      count: els.length,
      reason: "all_candidates_rejected",
      skippedInvisible: skippedInvisible,
      skippedDisclaimer: skippedDisclaimer,
      skippedArenaRejected: skippedArenaRejected
    };
  }

  function getCurrentAssistantText() {
    var r = extractLatestPlatformResponseWithMeta();
    return r.text || "";
  }

  // ============================================================
  // Arena rejection guard (avoid capturing injected prompt)
  // ============================================================

  var lastInjectedSignature = "";
  var lastInjectedAt = 0;

  function normalizeForMatch(s) {
    return String(s || "")
      .replace(/\u00a0/g, " ")
      .replace(/\s+/g, " ")
      .replace(/[:：#*_\-\u2013\u2014\[\]\(\)<>]/g, " ")
      .replace(/\s+/g, " ")
      .trim()
      .toLowerCase();
  }
  function makeSignature(s) { return normalizeForMatch(s).substring(0, 220); }

  function looksLikeInjectedPrompt(text) {
    if (!text) return false;
    if (text.indexOf("<shared_memories>") !== -1) return true;
    if (text.indexOf("## السؤال") !== -1) return true;
    if (text.indexOf("تعليمات مهمة") !== -1) return true;
    if (text.indexOf("ملاحظات إضافية") !== -1) return true;
    if (text.indexOf("### Context (visible)") !== -1) return true;
    if (text.indexOf("### End Context") !== -1) return true;

    var t = text.trim();
    return t.startsWith("السؤال") && (Date.now() - lastInjectedAt) < 60000;
  }

  function isArenaRejectedCandidate(text) {
    if (CURRENT_PLATFORM.id !== "arena") return false;
    if (!text) return false;
    if (looksLikeInjectedPrompt(text)) return true;

    if (lastInjectedSignature) {
      var norm = normalizeForMatch(text);
      if (norm.indexOf(lastInjectedSignature) !== -1) return true;
    }
    return false;
  }

  // ============================================================
  // Capture watcher
  // ============================================================

  var baselineAssistant = "";
  var captureActive = false;
  var captureFinished = false;
  var lastCandidate = "";
  var stableSince = 0;

  var captureStartedAt = 0;
  var CAPTURE_TIMEOUT_MS = 90000; // 90s

  function startResponseCapture() {
    captureActive = true;
    captureFinished = false;
    lastCandidate = "";
    stableSince = 0;
    captureStartedAt = Date.now();
    baselineAssistant = getCurrentAssistantText();
    log("🧪 Capture started platform=" + CURRENT_PLATFORM.id + " baselineLen=" + (baselineAssistant ? baselineAssistant.length : 0));
    watchForResponse();
  }

  function stopCapture(reason) {
    if (!captureActive) return;
    captureActive = false;
    captureFinished = true;
    log("⏹️ Capture stopped reason=" + String(reason || ""));
  }

  function finishCapture(answer) {
    if (captureFinished) return;

    if (CURRENT_PLATFORM.id === "arena" && isArenaRejectedCandidate(answer)) {
      log("⚠️ FinishCapture blocked (Arena rejected candidate) — keep watching");
      lastCandidate = "";
      stableSince = 0;
      return setTimeout(watchForResponse, 900);
    }

    captureFinished = true;
    captureActive = false;

    answer = cleanAssistantResponse(answer || "");
    if (!answer) return;

    sendToBackground({ type: "ASSISTANT_RESPONSE", text: answer, source: CURRENT_PLATFORM.id });
    log("📤 ASSISTANT_RESPONSE sent source=" + CURRENT_PLATFORM.id + " len=" + answer.length);
  }

  function watchForResponse() {
    if (!captureActive || captureFinished) return;

    if (captureStartedAt && (Date.now() - captureStartedAt) > CAPTURE_TIMEOUT_MS) {
      return stopCapture("timeout");
    }

    var current = getCurrentAssistantText();

    if (CURRENT_PLATFORM.id === "arena" && current && isArenaRejectedCandidate(current)) return setTimeout(watchForResponse, 900);
    if (!current) return setTimeout(watchForResponse, 1000);
    if (baselineAssistant && current === baselineAssistant) return setTimeout(watchForResponse, 1000);

    if (current !== lastCandidate) {
      lastCandidate = current;
      stableSince = Date.now();
      return setTimeout(watchForResponse, 1200);
    }

    if (lastCandidate && stableSince > 0 && (Date.now() - stableSince) >= 1800) return finishCapture(lastCandidate);
    setTimeout(watchForResponse, 800);
  }

  // ============================================================
  // Manual capture
  // ============================================================

  function sendManualCaptureResult(text, reason, meta) {
    var cleaned = cleanAssistantResponse(text || "");
    var m = meta || {};
    sendToBackground({
      type: "CAPTURE_RESULT",
      success: !!cleaned,
      text: cleaned,
      debug: {
        assistant: cleaned ? 1 : 0,
        articles: 0,
        bodyLen: cleaned.length,
        platform: CURRENT_PLATFORM.id,
        reason: reason || "",
        candidates: m.count || 0,
        skippedInvisible: m.skippedInvisible || 0,
        skippedDisclaimer: m.skippedDisclaimer || 0,
        skippedArenaRejected: m.skippedArenaRejected || 0
      },
      source: CURRENT_PLATFORM.id
    });
  }

  function captureCurrentResponse() {
    var r = extractLatestPlatformResponseWithMeta();
    if (!r.text) return sendManualCaptureResult("", r.reason || "assistant_response_not_found", r);
    if (CURRENT_PLATFORM.id === "arena" && isArenaRejectedCandidate(r.text)) return sendManualCaptureResult("", "rejected_injected_prompt", r);
    sendManualCaptureResult(r.text, "current_response", r);
  }

  // ============================================================
  // Sanitizer (hide hidden context from UI) — disabled for Arena
  // ============================================================

  var HIDDEN_MARKER = "aichat_hidden_context";
  var sanitizeObserver = null;
  var sanitizeThrottleTimer = 0;
  var sanitizeAggressiveTimer = 0;
  var sanitizeAggressiveUntil = 0;

  function looksLikeHasHiddenContext(t) {
    if (!t) return false;
    return (
      t.indexOf(HIDDEN_MARKER) !== -1 ||
      t.indexOf("<shared_memories>") !== -1 ||
      t.indexOf("SHARED_MEMORIES_XML:") !== -1
    );
  }

  function stripHiddenContextFromText(s) {
    var out = String(s || "");
    out = out.replace(/<!--\s*aichat_hidden_context[\s\S]*?-->\s*/ig, "");
    out = out.replace(/SHARED_MEMORIES_XML:\s*<shared_memories>[\s\S]*?<\/shared_memories>\s*/ig, "");
    out = out.replace(/ACCURACY_POLICY:\s*[\s\S]*?(?=\n{2,}|$)/ig, "");
    out = out.replace(/EXTRA_NOTES:\s*[\s\S]*?(?=\n{2,}|$)/ig, "");
    out = out.replace(/WORKSPACE_SUMMARY:\s*[\s\S]*?(?=\n{2,}|$)/ig, "");
    return out.trim();
  }

  function getUserMessageElements() {
    var out = [];
    var sels = (CURRENT_PLATFORM.userSelectors || []).concat([
      '[data-message-author-role="user"]',
      '[data-role="user"]',
      '[data-message-author="user"]',
      ".user-message",
      ".human-message",
      ".message.user"
    ]);

    for (var i = 0; i < sels.length; i++) {
      try {
        var els = document.querySelectorAll(sels[i]);
        for (var j = 0; j < els.length; j++) if (out.indexOf(els[j]) === -1) out.push(els[j]);
      } catch (e) {}
    }
    return out;
  }

  function pickEditableTextNode(msgEl) {
    if (!msgEl) return null;

    if (CURRENT_PLATFORM.id === "chatgpt") {
      try {
        var w = msgEl.querySelector(".whitespace-pre-wrap");
        if (w) return w;
      } catch (e0) {}
    }

    var sel = String(CURRENT_PLATFORM.userTextSelector || "").trim();
    if (sel) {
      try {
        var inner = msgEl.querySelector(sel);
        if (inner) return inner;
      } catch (e) {}
    }
    return msgEl;
  }

  function sanitizeOneNodeText(node) {
    if (!node) return false;
    var t = getElementText(node);
    if (!looksLikeHasHiddenContext(t)) return false;

    var cleaned = stripHiddenContextFromText(t);
    if (!cleaned || cleaned === t) return false;

    try {
      node.textContent = cleaned;
      try { node.setAttribute("data-aichat-sanitized", "1"); } catch (_) {}
      return true;
    } catch (e2) {
      log("❌ sanitizeOneNodeText failed: " + e2);
      return false;
    }
  }

  function sanitizeByUserElements() {
    var els = getUserMessageElements();
    if (!els.length) return 0;

    var N = 16;
    var start = els.length > N ? (els.length - N) : 0;

    var changed = 0;
    for (var i = start; i < els.length; i++) {
      var target = pickEditableTextNode(els[i]);
      if (sanitizeOneNodeText(target)) {
        try { els[i].setAttribute("data-aichat-sanitized", "1"); } catch (_) {}
        changed++;
      }
    }
    return changed;
  }

  function getSanitizeCandidateTextNodes() {
    var out = [];
    var sels = [];

    if (CURRENT_PLATFORM.id === "chatgpt") {
      sels = [
        'main [data-message-author-role="user"] .whitespace-pre-wrap',
        'main [data-message-author-role="user"] [data-message-content]',
        'main [data-message-author-role="user"]',
        "main .whitespace-pre-wrap",
        "main [data-message-content]"
      ];
    } else {
      sels = ["main .prose", ".prose", "[data-message-content]", ".message-content", ".content"];
    }

    for (var i = 0; i < sels.length; i++) {
      try {
        var els = document.querySelectorAll(sels[i]);
        for (var j = 0; j < els.length; j++) if (out.indexOf(els[j]) === -1) out.push(els[j]);
      } catch (e) {}
    }
    return out;
  }

  function sanitizeByCandidatesScan() {
    var nodes = getSanitizeCandidateTextNodes();
    if (!nodes.length) return 0;

    var N = 60;
    var start = nodes.length > N ? (nodes.length - N) : 0;

    var changed = 0;
    for (var i = start; i < nodes.length; i++) {
      if (sanitizeOneNodeText(nodes[i])) changed++;
    }
    return changed;
  }

  function sanitizeAllNow(reason) {
    // ✅ Arena: لا نخفي شيئًا (تريد الذاكرة ظاهرة هناك)
    if (CURRENT_PLATFORM.id === "arena") return 0;

    var changed1 = 0, changed2 = 0;
    try { changed1 = sanitizeByUserElements(); } catch (_) {}
    try { changed2 = sanitizeByCandidatesScan(); } catch (_) {}

    var changed = changed1 + changed2;
    if (changed > 0) log("🧼 Sanitized " + changed + " node(s) reason=" + (reason || "") + " platform=" + CURRENT_PLATFORM.id);
    return changed;
  }

  function requestSanitizeSoon() {
    if (sanitizeThrottleTimer) return;
    sanitizeThrottleTimer = setTimeout(function () {
      sanitizeThrottleTimer = 0;
      sanitizeAllNow("throttled");
    }, 120);
  }

  function startSanitizer() {
    sanitizeAllNow("startup");

    if (sanitizeObserver) return;
    try {
      sanitizeObserver = new MutationObserver(function () {
        if (sanitizeAggressiveUntil && Date.now() < sanitizeAggressiveUntil) {
          sanitizeAllNow("mutation_aggressive");
        } else {
          requestSanitizeSoon();
        }
      });
      sanitizeObserver.observe(document.documentElement, { childList: true, subtree: true });
      log("🧼 Sanitizer observer installed");
    } catch (e) {
      log("❌ Sanitizer observer failed: " + e);
    }
  }

  startSanitizer();

  function scheduleSanitizeAfterSend(injectedText) {
    if (CURRENT_PLATFORM.id === "arena") return; // no-op

    sanitizeAggressiveUntil = Date.now() + 12000;

    sanitizeAllNow("after_send_now");

    if (sanitizeAggressiveTimer) { try { clearInterval(sanitizeAggressiveTimer); } catch (_) {} }
    var tries = 0;
    sanitizeAggressiveTimer = setInterval(function () {
      tries++;
      sanitizeAllNow("after_send_tick_" + tries);
      if (tries >= 48) {
        clearInterval(sanitizeAggressiveTimer);
        sanitizeAggressiveTimer = 0;
      }
    }, 250);
  }

  // ============================================================
  // Send after inject (retry; fixes Venice first-open)
  // ============================================================

  function sendWithRetry(input, opts, contextId, injectedText) {
    var maxTries =
      (CURRENT_PLATFORM.id === "venice") ? 18 :
      (CURRENT_PLATFORM.id === "chatgpt") ? 14 :
      12;

    var intervalMs = (CURRENT_PLATFORM.id === "venice") ? 220 : 180;

    var tries = 0;

    function attempt() {
      tries++;

      // 1) try click send button
      var btn = findSendButton(String(opts.sendSelector || "").trim());
      if (btn && canClick(btn)) {
        try {
          btn.click();
          sendContextWritten(true, contextId, "button_clicked", "ok");
          sendContextConsumed(contextId);
          scheduleSanitizeAfterSend(injectedText);
          return;
        } catch (e1) {}
      }

      // 2) try form.requestSubmit
      try {
        var form = input && input.closest ? input.closest("form") : null;
        if (form && form.requestSubmit) {
          form.requestSubmit();
          sendContextWritten(true, contextId, "form_requestSubmit", "ok");
          sendContextConsumed(contextId);
          scheduleSanitizeAfterSend(injectedText);
          return;
        }
      } catch (_) {}

      // 3) Enter fallback after a few tries
      if (opts.sendWithEnter && tries >= 6) {
        if (dispatchEnter(input, false)) {
          sendContextWritten(true, contextId, "enter_sent", "ok");
          sendContextConsumed(contextId);
          scheduleSanitizeAfterSend(injectedText);
          return;
        }
        if (dispatchEnter(input, true)) {
          sendContextWritten(true, contextId, "ctrl_enter_sent", "ok");
          sendContextConsumed(contextId);
          scheduleSanitizeAfterSend(injectedText);
          return;
        }
      }

      if (tries < maxTries) return setTimeout(attempt, intervalMs);

      sendContextWritten(false, contextId, "send_failed", "no send method worked");
    }

    // give UI a moment to enable send button after input event
    setTimeout(attempt, 120);
  }

  // ============================================================
  // CONTEXT_TO_PAGE
  // ============================================================

  function injectAndSend(opts) {
    var text = opts.text || "";
    var contextId = opts.contextId || 0;

    lastInjectedSignature = makeSignature(text);
    lastInjectedAt = Date.now();

    // Venice first-open needs longer
    var attempts =
      (CURRENT_PLATFORM.id === "venice") ? 28 :
      (CURRENT_PLATFORM.id === "chatgpt") ? 18 :
      14;

    var delayMs =
      (CURRENT_PLATFORM.id === "venice") ? 420 :
      380;

    findInputWithRetry(
      opts.inputSelector || "",
      attempts, delayMs,
      function (input) {
        try {
          if (!injectText(input, text)) {
            return sendContextWritten(false, contextId, "inject_failed", "injectText returned false");
          }
          sendContextWritten(true, contextId, "text_injected", "ok");

          // ✅ ابدأ الالتقاط دائمًا بعد الحقن (حتى Arena text-only)
          // - في Arena نحن لا نرسل تلقائيًا لتجنب مشاكل الأمان
          // - لكن نترك الالتقاط شغالًا، بحيث إذا ضغطت "إرسال" يدويًا داخل Arena يتم التقاط الرد
          startResponseCapture();

          // ✅ Arena: text-only (no send) لتجنب الكابتشا/الأمان
          if (CURRENT_PLATFORM.id === "arena") {
            sendContextWritten(true, contextId, "arena_text_only", "injected_no_send_capture_waiting");
            sendContextConsumed(contextId);
            return;
          }

          // ChatGPT/Venice/others: reliable send with retry
          sendWithRetry(input, opts, contextId, text);
        } catch (e) {
          sendContextWritten(false, contextId, "exception", e && e.message ? e.message : String(e));
        }
      },
      function () {
        sendContextWritten(false, contextId, "input_not_found", "input not found after retries");
      }
    );
  }

  // ============================================================
  // Messages from background
  // ============================================================

  browser.runtime.onMessage.addListener(function (message) {
    try {
      if (!message || !message.type) return Promise.resolve({ ok: false, reason: "no_type" });

      if (message.type === "MANUAL_CAPTURE") {
        log("🎯 MANUAL_CAPTURE received");
        captureCurrentResponse();
        return Promise.resolve({ ok: true, type: "MANUAL_CAPTURE" });
      }

      if (message.type === "CONTEXT_TO_PAGE") {
        var text = String(message.text || "");
        var contextId = message.contextId || 0;

        log("📥 CONTEXT_TO_PAGE len=" + text.length +
          " platform=" + CURRENT_PLATFORM.id +
          " inputSel=" + (message.inputSelector || "") +
          " sendSel=" + (message.sendSelector || ""));

        if (!text.trim()) {
          sendContextWritten(false, contextId, "empty_text", "text is empty");
          return Promise.resolve({ ok: false, reason: "empty_text" });
        }

        injectAndSend({
          text: text,
          contextId: contextId,
          inputSelector: message.inputSelector || "",
          sendSelector: message.sendSelector || "",
          sendWithEnter: message.sendWithEnter !== false
        });

        return Promise.resolve({ ok: true, type: "CONTEXT_TO_PAGE" });
      }

      return Promise.resolve({ ok: false, reason: "unknown_type", type: message.type });
    } catch (e) {
      try { console.log("[AiChat] onMessage exception", e); } catch (_) {}
      return Promise.resolve({ ok: false, reason: "exception" });
    }
  });

  // ============================================================
  // READY
  // ============================================================

  if (shouldSendDiagnostics()) {
    sendToBackground({ type: "CONTENT_READY" });
    log("📡 CONTENT_READY sent");
  } else {
    try { console.log("[AiChat] generic page - skip CONTENT_READY/DEBUG (host=" + HOST + ")"); } catch (_) {}
  }
})();
