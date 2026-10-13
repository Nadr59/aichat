(function () {
  "use strict";

  // ============================================================
  // AiChat — PLATFORM REGISTRY + AUTO SEND + CAPTURE
  // Version 1.0.36
  // ============================================================

  var VERSION = "1.0.36";

  // ============================================================
  // DEBUG LOG → Kotlin
  // ============================================================

  function log(message) {
    try {
      browser.runtime.sendMessage({
        type: "DEBUG_INFO",
        info: message,
        timestamp: Date.now()
      });
    } catch (e) {
      console.log("[AiChat] " + message);
    }
  }

  log("🧪 content.js loaded v=" + VERSION);

  // ============================================================
  // PLATFORM REGISTRY
  // ============================================================

  var PLATFORMS = {
    chatgpt: {
      id: "chatgpt",
      name: "ChatGPT",
      matches: function () {
        return location.hostname === "chatgpt.com" || location.hostname === "chat.openai.com";
      },
      responseSelectors: [
        '[data-message-author-role="assistant"]',
        '[data-role="assistant"]',
        '[data-message-author="assistant"]',
        ".agent-turn"
      ],
      extractResponse: function (element) {
        if (!element) return "";
        var markdown = null;
        try { markdown = element.querySelector(".markdown"); } catch (e) {}
        if (markdown) return getElementText(markdown);
        return getElementText(element);
      },
      legacyContainerMode: true
    },

    venice: {
      id: "venice",
      name: "Venice",
      matches: function () {
        return location.hostname === "venice.ai";
      },
      responseSelectors: [
        ".prose",
        "[data-message-author-role='assistant']",
        ".assistant-message",
        ".ai-message"
      ],
      extractResponse: function (element) {
        return getElementText(element);
      },
      legacyContainerMode: false
    },

    arena: {
      id: "arena",
      name: "Arena",
      matches: function () {
        return location.hostname === "arena.ai";
      },
      responseSelectors: [
        "main .prose",
        ".prose.prose-base",
        "div.prose"
      ],
      extractResponse: function (element) {
        return getElementText(element);
      },
      legacyContainerMode: false
    },

    generic: {
      id: "generic",
      name: "Generic Web AI",
      matches: function () {
        return true;
      },
      responseSelectors: [
        '[data-message-author-role="assistant"]',
        '[data-role="assistant"]',
        '[data-message-author="assistant"]',
        ".assistant-message",
        ".ai-message",
        ".model-response",
        ".response-container",
        ".prose"
      ],
      extractResponse: function (element) {
        return getElementText(element);
      },
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
  log("🌐 Platform detected: " + CURRENT_PLATFORM.name + " (" + CURRENT_PLATFORM.id + ") host=" + location.hostname);

  // ============================================================
  // DOM utilities
  // ============================================================

  function getElementText(element) {
    try {
      return (element.innerText || element.textContent || "")
        .replace(/\u00a0/g, " ")
        .replace(/\r/g, "")
        .trim();
    } catch (e) {
      return "";
    }
  }

  function normalizeSpaces(s) {
    return (s || "").replace(/\u00a0/g, " ").replace(/\s+/g, " ").trim();
  }

  // ============================================================
  // Input finders (fallback list)
  // ============================================================

  var DEFAULT_INPUT_SELECTORS = [
    // ChatGPT
    "#prompt-textarea",
    "textarea#prompt-textarea",
    "textarea[name='prompt']",

    // common
    "textarea[data-testid='textbox']",
    "textarea[data-testid*='textbox']",
    "textarea[placeholder*='Message']",
    "textarea[placeholder*='message']",
    "textarea[aria-label*='Message']",
    "textarea[aria-label*='message']",
    "div[contenteditable='true'][role='textbox']",
    "[contenteditable='true'][role='textbox']",
    ".ProseMirror",
    "[data-lexical-editor='true']",
    "[role='textbox']",
    "[contenteditable='true']",
    "textarea"
  ];

  function findInput(preferSelector) {
    // 1) Prefer exact selector from Kotlin (platform DB)
    if (preferSelector && preferSelector.trim()) {
      try {
        var el = document.querySelector(preferSelector);
        if (el) return el;
      } catch (e) {}
    }

    // 2) Fallback list
    for (var i = 0; i < DEFAULT_INPUT_SELECTORS.length; i++) {
      try {
        var element = document.querySelector(DEFAULT_INPUT_SELECTORS[i]);
        if (element) return element;
      } catch (e) {}
    }
    return null;
  }

  function findInputWithRetry(preferSelector, attempts, delay, callback, onFail) {
    var count = 0;
    function attempt() {
      var input = findInput(preferSelector);
      if (input) {
        callback(input);
        return;
      }
      count++;
      if (count >= attempts) {
        if (onFail) onFail();
        return;
      }
      setTimeout(attempt, delay);
    }
    attempt();
  }

  // ============================================================
  // Injection
  // ============================================================

  function injectText(input, text) {
    try {
      if (input.tagName === "TEXTAREA" || input.tagName === "INPUT") {
        var prototype = Object.getPrototypeOf(input);
        var descriptor = Object.getOwnPropertyDescriptor(prototype, "value");

        if (descriptor && descriptor.set) descriptor.set.call(input, text);
        else input.value = text;

        input.dispatchEvent(new Event("input", { bubbles: true, composed: true }));
        input.dispatchEvent(new Event("change", { bubbles: true, composed: true }));
      } else {
        input.focus();

        try { document.execCommand("selectAll", false, null); } catch (e) {}

        try {
          document.execCommand("insertText", false, text);
        } catch (e) {
          input.textContent = text;
          input.dispatchEvent(
            new InputEvent("input", {
              bubbles: true,
              composed: true,
              inputType: "insertText",
              data: text
            })
          );
        }
      }

      return true;
    } catch (e) {
      log("❌ Text injection failed: " + (e && e.message ? e.message : String(e)));
      return false;
    }
  }

  // ============================================================
  // Send click / enter
  // ============================================================

  var DEFAULT_SEND_SELECTORS = [
    // ChatGPT
    "button[data-testid='send-button']",
    "button[aria-label*='Send' i]",

    // Generic
    "button[type='submit']",
    "button[aria-label*='send' i]",
    "button[data-testid*='send' i]"
  ];

  function findSendButton(preferSelector) {
    if (preferSelector && preferSelector.trim()) {
      try {
        var b = document.querySelector(preferSelector);
        if (b) return b;
      } catch (e) {}
    }

    for (var i = 0; i < DEFAULT_SEND_SELECTORS.length; i++) {
      try {
        var btn = document.querySelector(DEFAULT_SEND_SELECTORS[i]);
        if (btn) return btn;
      } catch (e) {}
    }

    return null;
  }

  function clickSend(btn) {
    try {
      btn.click();
      return true;
    } catch (e) {
      log("❌ clickSend failed: " + (e && e.message ? e.message : String(e)));
      return false;
    }
  }

  function sendEnter(input) {
    try {
      input.focus();

      var options = {
        key: "Enter",
        code: "Enter",
        keyCode: 13,
        which: 13,
        bubbles: true,
        cancelable: true,
        composed: true
      };

      input.dispatchEvent(new KeyboardEvent("keydown", options));
      input.dispatchEvent(new KeyboardEvent("keypress", options));
      input.dispatchEvent(new KeyboardEvent("keyup", options));

      return true;
    } catch (e) {
      log("❌ Enter failed: " + (e && e.message ? e.message : String(e)));
      return false;
    }
  }

  // ============================================================
  // Context feedback → Kotlin
  // ============================================================

  function sendContextWritten(success, contextId, stage, detail) {
    try {
      browser.runtime.sendMessage({
        type: "CONTEXT_WRITTEN",
        success: !!success,
        contextId: contextId || 0,
        stage: stage || "",
        detail: detail || "",
        platform: CURRENT_PLATFORM.id,
        timestamp: Date.now()
      });
    } catch (e) {
      // ignore
    }
  }

  function sendContextConsumed(contextId) {
    try {
      browser.runtime.sendMessage({
        type: "CONTEXT_CONSUMED",
        contextId: contextId || 0,
        platform: CURRENT_PLATFORM.id,
        timestamp: Date.now()
      });
    } catch (e) {
      // ignore
    }
  }

  // ============================================================
  // ChatGPT legacy extraction containers
  // ============================================================

  function getChatGPTConversationContainers() {
    var result = [];

    try {
      var sections = document.querySelectorAll('section[aria-label^="محادثة"]');
      for (var i = 0; i < sections.length; i++) result.push(sections[i]);
    } catch (e) {}

    if (result.length === 0) {
      try {
        var regions = document.querySelectorAll('[role="region"][aria-label="محادثة"]');
        for (var j = 0; j < regions.length; j++) result.push(regions[j]);
      } catch (e) {}
    }

    return result;
  }

  function getPlatformResponseElements() {
    var result = [];

    if (CURRENT_PLATFORM.id === "chatgpt" && CURRENT_PLATFORM.legacyContainerMode) {
      return getChatGPTConversationContainers();
    }

    var selectors = CURRENT_PLATFORM.responseSelectors || [];
    for (var i = 0; i < selectors.length; i++) {
      try {
        var elements = document.querySelectorAll(selectors[i]);
        for (var j = 0; j < elements.length; j++) {
          if (result.indexOf(elements[j]) === -1) result.push(elements[j]);
        }
      } catch (e) {}
    }

    return result;
  }

  // ============================================================
  // Response cleaning (كما هو تقريباً)
  // ============================================================

  function cleanAssistantResponse(text) {
    if (!text) return "";

    var cleaned = text
      .replace(/\r\n/g, "\n")
      .replace(/\r/g, "\n");

    // تقليل فراغات
    cleaned = cleaned.replace(/\n[ \t]*\n[ \t]*\n+/g, "\n\n");
    cleaned = cleaned.replace(/[ \t]+\n/g, "\n");

    return cleaned.trim();
  }

  function extractChatGPTAssistantText(conversationText) {
    if (!conversationText) return "";

    var markers = ["قال ChatGPT:", "قال ChatGPT :", "ChatGPT قال:", "ChatGPT:"];
    var position = -1;
    var markerLength = 0;

    for (var i = 0; i < markers.length; i++) {
      var currentPosition = conversationText.lastIndexOf(markers[i]);
      if (currentPosition > position) {
        position = currentPosition;
        markerLength = markers[i].length;
      }
    }

    if (position === -1) return "";

    var answer = conversationText.substring(position + markerLength).trim();
    return cleanAssistantResponse(answer);
  }

  function extractLatestPlatformResponse() {
    var elements = getPlatformResponseElements();
    if (elements.length === 0) return "";

    for (var i = elements.length - 1; i >= 0; i--) {
      var element = elements[i];
      var text = "";

      try {
        text = CURRENT_PLATFORM.extractResponse(element);
      } catch (e) {
        text = getElementText(element);
      }

      text = cleanAssistantResponse(text);

      if (CURRENT_PLATFORM.id === "arena" && isArenaRejectedCandidate(text)) {
        continue;
      }

      if (text) return text;
    }

    return "";
  }

  function getCurrentAssistantText() {
    if (CURRENT_PLATFORM.id === "chatgpt") {
      var containers = getChatGPTConversationContainers();
      if (containers.length === 0) return "";
      var lastContainer = containers[containers.length - 1];
      var conversationText = getElementText(lastContainer);
      return extractChatGPTAssistantText(conversationText);
    }
    return extractLatestPlatformResponse();
  }

  // ============================================================
  // Arena guard (كما كان + تحسين بسيط)
  // ============================================================

  var lastInjectedText = "";
  var lastInjectedSignature = "";
  var lastInjectedAt = 0;

  function normalizeForMatch(s) {
    return (s || "")
      .replace(/\u00a0/g, " ")
      .replace(/\s+/g, " ")
      .replace(/[:：#*_\-\u2013\u2014\[\]\(\)<>]/g, " ")
      .replace(/\s+/g, " ")
      .trim()
      .toLowerCase();
  }

  function makeSignature(s) {
    return normalizeForMatch(s).substring(0, 220);
  }

  function looksLikeInjectedPrompt(text) {
    if (!text) return false;

    if (text.indexOf("<shared_memories>") !== -1) return true;
    if (text.indexOf("## السؤال") !== -1) return true;
    if (text.indexOf("تعليمات مهمة") !== -1) return true;
    if (text.indexOf("ملاحظات إضافية") !== -1) return true;

    var trimmed = text.trim();
    if (trimmed.startsWith("السؤال") && (Date.now() - lastInjectedAt) < 60000) return true;

    return false;
  }

  function isArenaRejectedCandidate(text) {
    if (CURRENT_PLATFORM.id !== "arena") return false;
    if (!text) return false;

    if (looksLikeInjectedPrompt(text)) return true;

    if (lastInjectedSignature) {
      var sigText = normalizeForMatch(text);
      if (sigText.indexOf(lastInjectedSignature) !== -1) return true;

      if (sigText.length <= 260 && lastInjectedSignature.length <= 260) {
        var a = sigText.substring(0, 120);
        var b = lastInjectedSignature.substring(0, 120);
        if (a && b && a === b) return true;
      }
    }

    return false;
  }

  // ============================================================
  // Capture watcher (كما هو)
  // ============================================================

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
      setTimeout(watchForResponse, 900);
      return;
    }

    captureFinished = true;
    captureActive = false;

    answer = cleanAssistantResponse(answer || "");
    if (!answer) return;

    try {
      browser.runtime.sendMessage({
        type: "ASSISTANT_RESPONSE",
        text: answer,
        source: CURRENT_PLATFORM.id,
        timestamp: Date.now()
      });
      log("📤 ASSISTANT_RESPONSE sent source=" + CURRENT_PLATFORM.id + " len=" + answer.length);
    } catch (e) {
      log("❌ ASSISTANT_RESPONSE send failed: " + e);
    }
  }

  function watchForResponse() {
    if (!captureActive || captureFinished) return;

    var current = getCurrentAssistantText();

    if (CURRENT_PLATFORM.id === "arena" && current && isArenaRejectedCandidate(current)) {
      setTimeout(watchForResponse, 900);
      return;
    }

    if (!current) {
      setTimeout(watchForResponse, 1000);
      return;
    }

    if (baselineAssistant && current === baselineAssistant) {
      setTimeout(watchForResponse, 1000);
      return;
    }

    if (current !== lastCandidate) {
      lastCandidate = current;
      stableSince = Date.now();
      setTimeout(watchForResponse, 1200);
      return;
    }

    if (lastCandidate && stableSince > 0 && (Date.now() - stableSince) >= 1800) {
      finishCapture(lastCandidate);
      return;
    }

    setTimeout(watchForResponse, 800);
  }

  // ============================================================
  // Manual capture
  // ============================================================

  function sendManualCaptureResult(success, text, reason) {
    var cleaned = cleanAssistantResponse(text || "");

    var debug = {
      assistant: cleaned ? 1 : 0,
      articles: 0,
      bodyLen: cleaned.length,
      platform: CURRENT_PLATFORM.id,
      reason: reason || ""
    };

    try {
      browser.runtime.sendMessage({
        type: "CAPTURE_RESULT",
        success: !!cleaned,
        text: cleaned,
        debug: debug,
        source: CURRENT_PLATFORM.id,
        timestamp: Date.now()
      });
    } catch (e) {
      log("❌ CAPTURE_RESULT send failed: " + e);
    }
  }

  function captureCurrentResponse() {
    var current = getCurrentAssistantText();

    if (!current) {
      sendManualCaptureResult(false, "", "assistant_response_not_found");
      return;
    }

    if (CURRENT_PLATFORM.id === "arena" && isArenaRejectedCandidate(current)) {
      sendManualCaptureResult(false, "", "rejected_injected_prompt");
      return;
    }

    sendManualCaptureResult(true, current, "current_response");
  }

  // ============================================================
  // CONTEXT_TO_PAGE main flow
  // ============================================================

  function injectAndSend(opts) {
    var text = opts.text || "";
    var contextId = opts.contextId || 0;

    // Arena guard signature
    lastInjectedText = text;
    lastInjectedSignature = makeSignature(text);
    lastInjectedAt = Date.now();

    // نبدأ الالتقاط قبل الإرسال
    startResponseCapture();

    // 1) find input
    findInputWithRetry(
      opts.inputSelector || "",
      12,
      450,
      function (input) {
        try {
          var injected = injectText(input, text);
          if (!injected) {
            sendContextWritten(false, contextId, "inject_failed", "injectText returned false");
            return;
          }

          sendContextWritten(true, contextId, "text_injected", "ok");

          // 2) try click send button
          var btn = findSendButton(opts.sendSelector || "");
          if (btn) {
            var clicked = clickSend(btn);
            if (clicked) {
              sendContextWritten(true, contextId, "button_clicked", "ok");
              sendContextConsumed(contextId);
              return;
            }
            // إذا فشل click نجرّب Enter
          }

          // 3) fallback enter
          if (opts.sendWithEnter) {
            var sent = sendEnter(input);
            if (sent) {
              sendContextWritten(true, contextId, "enter_sent", "ok");
              sendContextConsumed(contextId);
              return;
            }
          }

          sendContextWritten(false, contextId, "send_failed", "no send method worked");
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
    if (!message || !message.type) return;

    if (message.type === "MANUAL_CAPTURE") {
      log("🎯 MANUAL_CAPTURE received");
      captureCurrentResponse();
      return Promise.resolve({ ok: true, type: "MANUAL_CAPTURE" });
    }

    if (message.type === "CONTEXT_TO_PAGE") {
      var text = message.text || "";
      var contextId = message.contextId || 0;

      log(
        "📥 CONTEXT_TO_PAGE len=" +
          text.length +
          " platform=" +
          CURRENT_PLATFORM.id +
          " inputSel=" +
          (message.inputSelector || "") +
          " sendSel=" +
          (message.sendSelector || "")
      );

      if (!text.trim()) {
        sendContextWritten(false, contextId, "empty_text", "text is empty");
        return Promise.resolve({ ok: false, reason: "empty_text" });
      }

      // ✅ تنفيذ الإرسال التلقائي مع selectors
      injectAndSend({
        text: text,
        contextId: contextId,
        inputSelector: message.inputSelector || "",
        sendSelector: message.sendSelector || "",
        sendWithEnter: message.sendWithEnter !== false
      });

      // نرد بسرعة للـ background
      return Promise.resolve({ ok: true, type: "CONTEXT_TO_PAGE" });
    }
  });

  // ============================================================
  // READY
  // ============================================================

  try {
    browser.runtime.sendMessage({
      type: "CONTENT_READY",
      version: VERSION,
      url: location.href
    });
    log("📡 CONTENT_READY sent");
  } catch (e) {
    log("❌ CONTENT_READY failed: " + e);
  }
})();
