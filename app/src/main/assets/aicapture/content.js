(function () {
  "use strict";

  var VERSION = "1.0.41";
  var HOST = String((location && location.hostname) || "");
  var HREF = String((location && location.href) || "");

  // إذا true: سيرسل DEBUG/READY حتى على صفحات generic
  var ALLOW_GENERIC_DIAGNOSTICS = false;

  // -------------------- Platforms --------------------
  var PLATFORMS = {
    chatgpt: {
      id: "chatgpt",
      name: "ChatGPT",
      matches: function () { return HOST === "chatgpt.com" || HOST === "chat.openai.com"; },
      responseSelectors: [
        '[data-message-author-role="assistant"]',
        '[data-role="assistant"]',
        '[data-message-author="assistant"]',
        ".agent-turn"
      ],
      extractResponse: function (el) {
        if (!el) return "";
        var md = null; try { md = el.querySelector(".markdown"); } catch (e) {}
        return getElementText(md || el);
      },
      legacyContainerMode: true
    },
    venice: {
      id: "venice",
      name: "Venice",
      matches: function () { return HOST === "venice.ai"; },
      responseSelectors: [".prose", "[data-message-author-role='assistant']", ".assistant-message", ".ai-message"],
      extractResponse: function (el) { return getElementText(el); },
      legacyContainerMode: false
    },
    arena: {
      id: "arena",
      name: "Arena",
      matches: function () { return HOST === "arena.ai"; },
      responseSelectors: ["main .prose", ".prose.prose-base", "div.prose"],
      extractResponse: function (el) { return getElementText(el); },
      legacyContainerMode: false
    },
    generic: {
      id: "generic",
      name: "Generic Web AI",
      matches: function () { return true; },
      responseSelectors: [
        '[data-message-author-role="assistant"]', '[data-role="assistant"]', '[data-message-author="assistant"]',
        ".assistant-message", ".ai-message", ".model-response", ".response-container", ".prose"
      ],
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

  // -------------------- DOM helpers --------------------
  function getElementText(el) {
    try {
      return (el && (el.innerText || el.textContent) ? (el.innerText || el.textContent) : "")
        .replace(/\u00a0/g, " ").replace(/\r/g, "").trim();
    } catch (e) { return ""; }
  }

  // -------------------- Input finders --------------------
  var DEFAULT_INPUT_SELECTORS = [
    "#prompt-textarea", "textarea#prompt-textarea", "textarea[name='prompt']",
    "textarea[data-testid='textbox']", "textarea[data-testid*='textbox']",
    "textarea[placeholder*='Message']", "textarea[placeholder*='message']",
    "textarea[aria-label*='Message']", "textarea[aria-label*='message']",
    "div[contenteditable='true'][role='textbox']", "[contenteditable='true'][role='textbox']",
    ".ProseMirror", "[data-lexical-editor='true']", "[role='textbox']",
    "[contenteditable='true']", "textarea"
  ];

  function findInput(preferSelector) {
    if (preferSelector && preferSelector.trim()) {
      try { var el = document.querySelector(preferSelector); if (el) return el; } catch (e) {}
    }
    for (var i = 0; i < DEFAULT_INPUT_SELECTORS.length; i++) {
      try { var node = document.querySelector(DEFAULT_INPUT_SELECTORS[i]); if (node) return node; } catch (e2) {}
    }
    return null;
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

  // -------------------- Composer Hide/Show + Toggle --------------------
  var composerHidden = false;
  var composerUserPref = null; // null=default, true=hide, false=show
  var toggleBtn = null;
  var applyTimer = 0;

  function defaultShouldHideComposer() {
    if (CURRENT_PLATFORM.id === "generic") return false;
    if (CURRENT_PLATFORM.id === "chatgpt") return true;  // hide ChatGPT فقط
    if (CURRENT_PLATFORM.id === "arena") return false;   // show Arena
    return false; // show others
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
        composerUserPref = composerHidden ? false : true; // toggle
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

    // حاول عدة مرات لأن DOM قد يتأخر
    var tries = 0;
    (function loop() {
      tries++;
      applyComposerPrefSoon();
      if (tries < 30) setTimeout(loop, 350);
    })();

    // راقب تغييرات DOM (منصات تعيد بناء composer)
    try {
      var obs = new MutationObserver(function () { applyComposerPrefSoon(); });
      obs.observe(document.documentElement, { childList: true, subtree: true });
    } catch (e) {}
  }

  startComposerController();

  // -------------------- Injection --------------------
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
          input.dispatchEvent(new InputEvent("input", { bubbles: true, composed: true, inputType: "insertText", data: text }));
        }
      }
      return true;
    } catch (e3) { log("❌ Text injection failed: " + (e3 && e3.message ? e3.message : String(e3))); return false; }
  }

  // -------------------- Send controls --------------------
  var DEFAULT_SEND_SELECTORS = [
    "button[data-testid='send-button']",
    "button[aria-label*='Send' i]",
    "button[type='submit']",
    "button[aria-label*='send' i]",
    "button[data-testid*='send' i]"
  ];

  function findSendButton(preferSelector) {
    if (!preferSelector || !preferSelector.trim()) return null;
    try { var b = document.querySelector(preferSelector); if (b) return b; } catch (e) {}
    for (var i = 0; i < DEFAULT_SEND_SELECTORS.length; i++) {
      try { var btn = document.querySelector(DEFAULT_SEND_SELECTORS[i]); if (btn) return btn; } catch (e2) {}
    }
    return null;
  }

  function dispatchEnter(target, withCtrl) {
    try {
      target.focus();
      var opt = { key: "Enter", code: "Enter", keyCode: 13, which: 13, ctrlKey: !!withCtrl, shiftKey: false, altKey: false, metaKey: false, bubbles: true, cancelable: true, composed: true };
      target.dispatchEvent(new KeyboardEvent("keydown", opt));
      target.dispatchEvent(new KeyboardEvent("keypress", opt));
      target.dispatchEvent(new KeyboardEvent("keyup", opt));
      try { target.blur(); } catch (e2) {}
      return true;
    } catch (e3) { log("❌ Enter dispatch failed: " + (e3 && e3.message ? e3.message : String(e3))); return false; }
  }

  // -------------------- Hide injected memory from UI (Sanitizer v2: TreeWalker fallback) --------------------
  var HIDDEN_MARKER = "aichat_hidden_context";
  var sanitizeObserver = null;
  var sanitizeThrottleTimer = 0;
  var sanitizeAggressiveTimer = 0;
  var sanitizeAggressiveUntil = 0;
  var debugDumpedForThisSend = false;

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
    return out.trim();
  }

  function sanitizeOneNodeText(node) {
    if (!node) return false;
    var t = getElementText(node);
    if (!looksLikeHasHiddenContext(t)) return false;

    var cleaned = stripHiddenContextFromText(t);
    if (!cleaned || cleaned === t) return false;

    try {
      node.textContent = cleaned; // أفضل من innerText ضد إعادة render
      try { node.setAttribute("data-aichat-sanitized", "1"); } catch (_) {}
      return true;
    } catch (e2) {
      log("❌ sanitizeOneNodeText failed: " + e2);
      return false;
    }
  }

  // فحص خفيف باستخدام selectors شائعة
  function sanitizeBySelectorsScan() {
    var nodes = [];
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
      sels = [
        "main .prose",
        ".prose",
        "[data-message-content]",
        ".message-content",
        ".content"
      ];
    }

    for (var i = 0; i < sels.length; i++) {
      try {
        var els = document.querySelectorAll(sels[i]);
        for (var j = 0; j < els.length; j++) if (nodes.indexOf(els[j]) === -1) nodes.push(els[j]);
      } catch (e) {}
    }

    if (!nodes.length) return 0;

    // نفحص آخر 60 عنصر فقط
    var N = 60;
    var start = nodes.length > N ? (nodes.length - N) : 0;

    var changed = 0;
    for (var k = start; k < nodes.length; k++) {
      if (sanitizeOneNodeText(nodes[k])) changed++;
    }
    return changed;
  }

  // ✅ الفحص الأقوى: TreeWalker يبحث عن marker داخل نصوص DOM مهما تغيرت بنية ChatGPT
  function sanitizeFromTextNode(textNode, root) {
    try {
      if (!textNode || !textNode.parentElement) return false;

      var el = textNode.parentElement;

      // حاول الوصول لأقرب حاوية user
      var userContainer = null;
      try {
        userContainer = el.closest('[data-message-author-role="user"], [data-role="user"], [data-message-author="user"], .user-message, .human-message, .message.user');
      } catch (e0) {}

      // fallback: داخل turn
      if (!userContainer) {
        var turn = null;
        try { turn = el.closest('article[data-testid="conversation-turn"], article, [role="article"]'); } catch (e1) {}
        if (turn) {
          try {
            userContainer = turn.querySelector('[data-message-author-role="user"], [data-role="user"], [data-message-author="user"]');
          } catch (e2) {}
        }
      }

      var target = userContainer || el;

      // اختر عنصر نص "قريب" يحتوي marker (حتى لا نمسح عناصر كبيرة)
      var best = null;
      var cur = target;
      for (var i = 0; i < 7 && cur && cur !== root; i++) {
        var t = getElementText(cur);
        if (looksLikeHasHiddenContext(t) && t.length < 20000) best = cur;
        cur = cur.parentElement;
      }
      best = best || target;

      return sanitizeOneNodeText(best);
    } catch (e3) {
      return false;
    }
  }

  function sanitizeByTreeWalker(maxNodes, maxChanges) {
    var root = null;
    try { root = document.querySelector("main") || document.body || document.documentElement; } catch (_) {}
    if (!root) return 0;

    var walker = null;
    try {
      walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    } catch (e) {
      return 0;
    }

    var visited = 0;
    var changed = 0;
    var node;

    while ((node = walker.nextNode())) {
      visited++;
      if (visited > (maxNodes || 2500)) break;

      var v = node.nodeValue || "";
      if (!v) continue;

      if (
        v.indexOf(HIDDEN_MARKER) === -1 &&
        v.indexOf("<shared_memories>") === -1 &&
        v.indexOf("SHARED_MEMORIES_XML:") === -1
      ) continue;

      if (sanitizeFromTextNode(node, root)) {
        changed++;
        if (changed >= (maxChanges || 3)) break;
      }
    }

    if (changed > 0) log("🧼 TreeWalker sanitized=" + changed + " visited=" + visited + " platform=" + CURRENT_PLATFORM.id);
    return changed;
  }

  function sanitizeAllNow(reason) {
    var changed = 0;

    try { changed += sanitizeBySelectorsScan(); } catch (_) {}

    // إذا لم ينجح، جرّب TreeWalker (خاصة ChatGPT)
    if (changed === 0) {
      try { changed += sanitizeByTreeWalker(3500, 5); } catch (_) {}
    }

    if (changed > 0) {
      log("🧼 Sanitized " + changed + " node(s) reason=" + (reason || "") + " platform=" + CURRENT_PLATFORM.id);
    }
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

  function debugDumpCountsOnce() {
    if (debugDumpedForThisSend) return;
    debugDumpedForThisSend = true;

    try {
      var u = 0, w = 0, mm = 0;
      try { u = document.querySelectorAll('[data-message-author-role="user"]').length; } catch (_) {}
      try { w = document.querySelectorAll("main .whitespace-pre-wrap").length; } catch (_) {}
      try { mm = (document.querySelector("main") ? 1 : 0); } catch (_) {}
      log("DBG DOM counts: main=" + mm + " userRole=" + u + " wsPre=" + w + " host=" + HOST);
    } catch (e) {}
  }

  startSanitizer();

  function scheduleSanitizeAfterSend(injectedText) {
    sanitizeAggressiveUntil = Date.now() + 14000; // 14s aggressive
    debugDumpedForThisSend = false;

    var changedNow = sanitizeAllNow("after_send_now");
    if (changedNow === 0) {
      // إذا فشل فوراً (غالباً DOM لم يبنِ bubble بعد أو selectors غير مطابقة)
      debugDumpCountsOnce();
    }

    if (sanitizeAggressiveTimer) { try { clearInterval(sanitizeAggressiveTimer); } catch (_) {} }
    var tries = 0;
    sanitizeAggressiveTimer = setInterval(function () {
      tries++;
      var ch = sanitizeAllNow("after_send_tick_" + tries);
      if (ch > 0) {
        // بمجرد أن ينجح مرة، لا نحتاج spam كثير
        // نتركه يكمل ثواني بسيطة ثم يتوقف تلقائياً
      }
      if (tries >= 56) { // 56 * 250ms = 14s
        clearInterval(sanitizeAggressiveTimer);
        sanitizeAggressiveTimer = 0;
      }
    }, 250);

    // للتشخيص
    try {
      log("🧼 scheduleSanitizeAfterSend marker=" + (String(injectedText || "").indexOf(HIDDEN_MARKER) !== -1 ? "yes" : "no") +
          " platform=" + CURRENT_PLATFORM.id);
    } catch (e) {}
  }

  // -------------------- Context feedback --------------------
  function sendContextWritten(success, contextId, stage, detail) {
    sendToBackground({ type: "CONTEXT_WRITTEN", success: !!success, contextId: contextId || 0, stage: stage || "", detail: detail || "" });
  }
  function sendContextConsumed(contextId) {
    sendToBackground({ type: "CONTEXT_CONSUMED", contextId: contextId || 0 });
  }

  // -------------------- Extraction (ChatGPT legacy) --------------------
  function getChatGPTConversationContainers() {
    var out = [];
    try {
      var sections = document.querySelectorAll('section[aria-label^="محادثة"]');
      for (var i = 0; i < sections.length; i++) out.push(sections[i]);
    } catch (e) {}
    if (!out.length) {
      try {
        var regions = document.querySelectorAll('[role="region"][aria-label="محادثة"]');
        for (var j = 0; j < regions.length; j++) out.push(regions[j]);
      } catch (e2) {}
    }
    return out;
  }

  function getPlatformResponseElements() {
    if (CURRENT_PLATFORM.id === "chatgpt" && CURRENT_PLATFORM.legacyContainerMode) return getChatGPTConversationContainers();
    var res = [], sels = CURRENT_PLATFORM.responseSelectors || [];
    for (var i = 0; i < sels.length; i++) {
      try {
        var els = document.querySelectorAll(sels[i]);
        for (var j = 0; j < els.length; j++) if (res.indexOf(els[j]) === -1) res.push(els[j]);
      } catch (e) {}
    }
    return res;
  }

  function cleanAssistantResponse(text) {
    if (!text) return "";
    var s = String(text).replace(/\r\n/g, "\n").replace(/\r/g, "\n");
    s = s.replace(/\n[ \t]*\n[ \t]*\n+/g, "\n\n").replace(/[ \t]+\n/g, "\n");
    return s.trim();
  }

  function extractChatGPTAssistantText(conversationText) {
    if (!conversationText) return "";
    var markers = ["قال ChatGPT:", "قال ChatGPT :", "ChatGPT قال:", "ChatGPT:"];
    var pos = -1, len = 0;
    for (var i = 0; i < markers.length; i++) {
      var p = conversationText.lastIndexOf(markers[i]);
      if (p > pos) { pos = p; len = markers[i].length; }
    }
    if (pos === -1) return "";
    return cleanAssistantResponse(conversationText.substring(pos + len).trim());
  }

  // -------------------- Arena rejection guard --------------------
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
      if (norm.length <= 260 && lastInjectedSignature.length <= 260) {
        var a = norm.substring(0, 120), b = lastInjectedSignature.substring(0, 120);
        if (a && b && a === b) return true;
      }
    }
    return false;
  }

  function extractLatestPlatformResponse() {
    var els = getPlatformResponseElements();
    if (!els.length) return "";
    for (var i = els.length - 1; i >= 0; i--) {
      var t = "";
      try { t = CURRENT_PLATFORM.extractResponse(els[i]); } catch (e) { t = getElementText(els[i]); }
      t = cleanAssistantResponse(t);
      if (CURRENT_PLATFORM.id === "arena" && isArenaRejectedCandidate(t)) continue;
      if (t) return t;
    }
    return "";
  }

  function getCurrentAssistantText() {
    if (CURRENT_PLATFORM.id === "chatgpt") {
      var containers = getChatGPTConversationContainers();
      if (!containers.length) return "";
      return extractChatGPTAssistantText(getElementText(containers[containers.length - 1]));
    }
    return extractLatestPlatformResponse();
  }

  // -------------------- Capture watcher --------------------
  var baselineAssistant = "";
  var captureActive = false;
  var captureFinished = false;
  var lastCandidate = "";
  var stableSince = 0;

  function startResponseCapture() {
    captureActive = true;
    captureFinished = false;
    lastCandidate = "";
    stableSince = 0;
    baselineAssistant = getCurrentAssistantText();
    log("🧪 Capture started platform=" + CURRENT_PLATFORM.id + " baselineLen=" + (baselineAssistant ? baselineAssistant.length : 0));
    watchForResponse();
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

  // -------------------- Manual capture --------------------
  function sendManualCaptureResult(text, reason) {
    var cleaned = cleanAssistantResponse(text || "");
    sendToBackground({
      type: "CAPTURE_RESULT",
      success: !!cleaned,
      text: cleaned,
      debug: { assistant: cleaned ? 1 : 0, articles: 0, bodyLen: cleaned.length, platform: CURRENT_PLATFORM.id, reason: reason || "" },
      source: CURRENT_PLATFORM.id
    });
  }

  function captureCurrentResponse() {
    var cur = getCurrentAssistantText();
    if (!cur) return sendManualCaptureResult("", "assistant_response_not_found");
    if (CURRENT_PLATFORM.id === "arena" && isArenaRejectedCandidate(cur)) return sendManualCaptureResult("", "rejected_injected_prompt");
    sendManualCaptureResult(cur, "current_response");
  }

  // -------------------- CONTEXT_TO_PAGE --------------------
  function injectAndSend(opts) {
    var text = opts.text || "";
    var contextId = opts.contextId || 0;

    lastInjectedSignature = makeSignature(text);
    lastInjectedAt = Date.now();

    startResponseCapture();

    findInputWithRetry(
      opts.inputSelector || "",
      12, 450,
      function (input) {
        try {
          if (!injectText(input, text)) return sendContextWritten(false, contextId, "inject_failed", "injectText returned false");
          sendContextWritten(true, contextId, "text_injected", "ok");

          // Arena: Enter only (+ Ctrl+Enter fallback)
          if (CURRENT_PLATFORM.id === "arena") {
            if (!opts.sendWithEnter) return sendContextWritten(false, contextId, "send_failed", "arena_sendWithEnter_disabled");
            log("🎯 Arena: send via Enter");
            if (dispatchEnter(input, false)) {
              sendContextWritten(true, contextId, "enter_sent", "arena_enter");
              sendContextConsumed(contextId);
              scheduleSanitizeAfterSend(text);
              return;
            }
            log("🎯 Arena: Enter failed, try Ctrl+Enter");
            if (dispatchEnter(input, true)) {
              sendContextWritten(true, contextId, "ctrl_enter_sent", "arena_ctrl_enter");
              sendContextConsumed(contextId);
              scheduleSanitizeAfterSend(text);
              return;
            }
            return sendContextWritten(false, contextId, "send_failed", "arena_enter_and_ctrl_enter_failed");
          }

          // Others: click if sendSelector provided, else Enter
          var sel = String(opts.sendSelector || "").trim();
          var btn = findSendButton(sel);
          if (btn) {
            try {
              btn.click();
              sendContextWritten(true, contextId, "button_clicked", "ok");
              sendContextConsumed(contextId);
              scheduleSanitizeAfterSend(text);
              return;
            } catch (e1) { log("❌ clickSend failed: " + e1); }
          }

          if (opts.sendWithEnter && dispatchEnter(input, false)) {
            sendContextWritten(true, contextId, "enter_sent", "ok");
            sendContextConsumed(contextId);
            scheduleSanitizeAfterSend(text);
            return;
          }

          sendContextWritten(false, contextId, "send_failed", "no send method worked");
        } catch (e) {
          sendContextWritten(false, contextId, "exception", e && e.message ? e.message : String(e));
        }
      },
      function () { sendContextWritten(false, contextId, "input_not_found", "input not found after retries"); }
    );
  }

  // -------------------- Messages from background --------------------
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

        log("📥 CONTEXT_TO_PAGE len=" + text.length + " platform=" + CURRENT_PLATFORM.id +
            " inputSel=" + (message.inputSelector || "") + " sendSel=" + (message.sendSelector || ""));

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

  // -------------------- READY --------------------
  if (shouldSendDiagnostics()) {
    sendToBackground({ type: "CONTENT_READY" });
    log("📡 CONTENT_READY sent");
  } else {
    try { console.log("[AiChat] generic page - skip CONTENT_READY/DEBUG (host=" + HOST + ")"); } catch (_) {}
  }
})();
