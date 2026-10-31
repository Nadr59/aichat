
(function () {
"use strict";

var VERSION = "1.0.54";
var HOST = String((location && location.hostname) || "");
var HREF = String((location && location.href) || "");

var ALLOW_GENERIC_DIAGNOSTICS = false;

// ============================================================
// Platforms
// ============================================================

var PLATFORMS = {
  chatgpt: {
    id: "chatgpt",
    name: "ChatGPT",
    matches: function () {
      return HOST === "chatgpt.com" || HOST === "chat.openai.com";
    },
    responseSelectors: [
      '[data-message-author-role="assistant"]',
      '[data-role="assistant"]',
      '[data-message-author="assistant"]',
      ".agent-turn"
    ],
    userSelectors: [
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
    legacyContainerMode: true
  },

  venice: {
    id: "venice",
    name: "Venice",
    matches: function () { return HOST === "venice.ai"; },
    responseSelectors: [
      ".prose",
      "[data-message-author-role='assistant']",
      ".assistant-message",
      ".ai-message"
    ],
    userSelectors: [
      "[data-message-author-role='user']",
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
      return HOST === "arena.ai";
    },
    responseSelectors: [
      "main .prose"
    ],
    userSelectors: [
      "main [class*='bg-surface-raised'][class*='w-fit']",
      "[data-message-author-role='user']",
      ".user-message",
      ".human-message",
      ".message.user"
    ],
    userTextSelector: ".prose, .message-content, [data-message-content]",
    extractResponse: function (el) {
      return getElementText(el);
    },
    legacyContainerMode: false
  },

  generic: {
    id: "generic",
    name: "Generic Web AI",
    matches: function () { return true; },
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
    userSelectors: [
      '[data-message-author-role="user"]',
      '[data-role="user"]',
      '[data-message-author="user"]',
      ".user-message",
      ".human-message",
      ".message.user"
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
  if (!shouldSendDiagnostics()) {
    try { console.log("[AiChat] " + msg); } catch (_) {}
    return;
  }
  sendToBackground({ type: "DEBUG_INFO", info: String(msg || "") });
}

log("🧪 content.js loaded v=" + VERSION);
log("🌐 Platform detected: " + CURRENT_PLATFORM.name +
  " (" + CURRENT_PLATFORM.id + ") host=" + HOST);

// ============================================================
// DOM helpers
// ============================================================

function getElementText(el) {
  try {
    return (el && (el.innerText || el.textContent)
      ? (el.innerText || el.textContent)
      : "")
      .replace(/\u00a0/g, " ")
      .replace(/\r/g, "")
      .trim();
  } catch (e) {
    return "";
  }
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
  if (el && arr.indexOf(el) === -1) arr.push(el);
}

function describeElementForDiag(el) {
  if (!el) return "null";
  try {
    var tag = String(el.tagName || "").toLowerCase();
    var id = el.id ? "#" + el.id : "";
    var cls = el.className && typeof el.className === "string"
      ? "." + el.className.trim().replace(/\s+/g, ".")
      : "";
    var aria = el.getAttribute ? (el.getAttribute("aria-label") || "") : "";
    var type = el.getAttribute ? (el.getAttribute("type") || "") : "";
    var ph = el.getAttribute ? (el.getAttribute("placeholder") || "") : "";
    var rect = el.getBoundingClientRect ? el.getBoundingClientRect() : null;
    var rectStr = rect
      ? ("w=" + Math.round(rect.width) + " h=" + Math.round(rect.height) +
         " top=" + Math.round(rect.top))
      : "no-rect";
    var outer = "";
    try {
      outer = String(el.outerHTML || "").substring(0, 200);
    } catch (_) {}

    return tag + id + cls +
      " type=" + type + " aria=" + aria + " placeholder=" + ph +
      " rect(" + rectStr + ") outerHTML~=" + outer;
  } catch (e) {
    return "describe_failed: " + e;
  }
}

// ============================================================
// Input finders
// ============================================================

var DEFAULT_INPUT_SELECTORS = [
  "#prompt-textarea",
  "textarea#prompt-textarea",
  "textarea[name='prompt']",
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

function scoreInput(el) {
  if (!el) return -9999;
  var score = 0;

  try {
    var tag = String(el.tagName || "").toUpperCase();
    var isTextarea = tag === "TEXTAREA" || tag === "INPUT";
    var isEditable = !!el.isContentEditable ||
      el.getAttribute("contenteditable") === "true";

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

    if (
      ph.includes("message") ||
      ph.includes("ask") ||
      ph.includes("اكتب") ||
      ph.includes("رسالة")
    ) score += 8;

    if (
      aria.includes("message") ||
      aria.includes("prompt") ||
      aria.includes("اكتب") ||
      aria.includes("رسالة")
    ) score += 6;

    if (role === "textbox") score += 5;

    var rect = el.getBoundingClientRect();
    var vh = Math.max(1, window.innerHeight || 1);
    if (rect.bottom / vh > 0.7) score += 8;

    try {
      var form = el.closest && el.closest("form");
      if (
        form &&
        form.querySelector(
          "button[type='submit'], button[aria-label*='send' i], button[data-testid*='send' i]"
        )
      ) score += 10;
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

  if (preferSelector && preferSelector.trim()) {
    collectInputsBySelector(preferSelector, candidates);
  }

  for (var i = 0; i < DEFAULT_INPUT_SELECTORS.length; i++) {
    collectInputsBySelector(DEFAULT_INPUT_SELECTORS[i], candidates);
  }

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

  if (CURRENT_PLATFORM.id === "venice") {
    try {
      log(
        "🔬 DIAG findInput: candidates=" + candidates.length +
        " bestScore=" + bestScore
      );
      for (var k = 0; k < candidates.length; k++) {
        log(
          "🔬 DIAG input candidate[" + k + "] score=" + scoreInput(candidates[k]) +
          " => " + describeElementForDiag(candidates[k])
        );
      }
    } catch (eDiagInput) {
      log("🔬 DIAG findInput failed: " + eDiagInput);
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
// Injection
// ============================================================

function injectText(input, text) {
  try {
    if (!input) return false;

    if (input.tagName === "TEXTAREA" || input.tagName === "INPUT") {
      var proto = Object.getPrototypeOf(input);
      var desc = Object.getOwnPropertyDescriptor(proto, "value");

      if (desc && desc.set) desc.set.call(input, text);
      else input.value = text;

      input.dispatchEvent(new Event("input", {
        bubbles: true,
        composed: true
      }));
      input.dispatchEvent(new Event("change", {
        bubbles: true,
        composed: true
      }));
    } else {
      input.focus();

      try { document.execCommand("selectAll", false, null); } catch (e1) {}

      try {
        document.execCommand("insertText", false, text);
      } catch (e2) {
        input.textContent = text;

        try {
          input.dispatchEvent(new InputEvent("input", {
            bubbles: true,
            composed: true,
            inputType: "insertText",
            data: text
          }));
        } catch (_) {
          input.dispatchEvent(new Event("input", {
            bubbles: true,
            composed: true
          }));
        }
      }
    }

    return true;
  } catch (e3) {
    log("❌ Text injection failed: " +
      (e3 && e3.message ? e3.message : String(e3)));
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

// الدالة القديمة (بحث عام في كامل الصفحة) — أُبقيت كحل احتياطي أخير فقط.
function findSendButtonGlobal(preferSelector) {
  var selectors = [];

  if (preferSelector && preferSelector.trim()) {
    selectors.push(preferSelector);
  }

  selectors = selectors.concat(DEFAULT_SEND_SELECTORS);

  for (var i = 0; i < selectors.length; i++) {
    try {
      var btn = document.querySelector(selectors[i]);
      if (btn) return btn;
    } catch (e) {}
  }

  return null;
}

// --- [إصلاح جذري] بحث محصور بأقرب حاوية مشتركة مع حقل الإدخال الفعلي،
// لتفادي اختيار زر غير مرتبط فعليًا بصندوق المحادثة الذي حُقن فيه النص
// (مشكلة مؤكدة على Venice: عدة صناديق محادثة/أزرار إرسال موجودة بالصفحة
// في آن واحد، والبحث العام كان يختار أول زر في ترتيب DOM بلا علاقة بالمدخل).
function findSendButtonNear(input, preferSelector) {
  var selectors = [];

  if (preferSelector && preferSelector.trim()) {
    selectors.push(preferSelector);
  }

  selectors = selectors.concat(DEFAULT_SEND_SELECTORS);

  var scope = null;

  try {
    scope = input && input.closest ? input.closest("form") : null;
  } catch (_) {}

  if (!scope && input) {
    var node = input;
    var hops = 0;

    while (node && node.parentElement && hops < 8) {
      node = node.parentElement;
      hops++;

      var foundHere = false;

      for (var s = 0; s < selectors.length; s++) {
        try {
          if (node.querySelector(selectors[s])) {
            foundHere = true;
            break;
          }
        } catch (_) {}
      }

      if (foundHere) {
        scope = node;
        break;
      }
    }
  }

  if (scope) {
    if (CURRENT_PLATFORM.id === "venice") {
      try {
        log("🔬 DIAG findSendButtonNear: scope found => " +
          describeElementForDiag(scope));
      } catch (_) {}
    }

    // تفضيل صريح لزر type=submit داخل النطاق المحلي أولاً.
    try {
      var submitBtn = scope.querySelector("button[type='submit']");
      if (submitBtn) {
        if (CURRENT_PLATFORM.id === "venice") {
          log("🔬 DIAG findSendButtonNear: picked local submit button");
        }
        return submitBtn;
      }
    } catch (_) {}

    for (var i = 0; i < selectors.length; i++) {
      try {
        var localBtn = scope.querySelector(selectors[i]);
        if (localBtn) {
          if (CURRENT_PLATFORM.id === "venice") {
            log("🔬 DIAG findSendButtonNear: picked local match via '" +
              selectors[i] + "'");
          }
          return localBtn;
        }
      } catch (_) {}
    }
  } else if (CURRENT_PLATFORM.id === "venice") {
    log("🔬 DIAG findSendButtonNear: no local scope found, falling back to global");
  }

  return findSendButtonGlobal(preferSelector);
}

function canClick(btn) {
  if (!btn) return false;

  try {
    if (btn.disabled) return false;
    if (String(btn.getAttribute("aria-disabled") || "").toLowerCase() === "true") {
      return false;
    }
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
    log("❌ Enter dispatch failed: " +
      (e3 && e3.message ? e3.message : String(e3)));
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
  sendToBackground({
    type: "CONTEXT_CONSUMED",
    contextId: contextId || 0
  });
}

// ============================================================
// Venice reload-resume capture state
// ============================================================

var VENICE_CAPTURE_STORAGE_KEY = "aichat_venice_capture_state_v1";
var VENICE_CAPTURE_MAX_AGE_MS = 90000;

function setVeniceCaptureState(data) {
  try {
    sessionStorage.setItem(VENICE_CAPTURE_STORAGE_KEY, JSON.stringify(data));
  } catch (_) {}
}

function getVeniceCaptureState() {
  try {
    var raw = sessionStorage.getItem(VENICE_CAPTURE_STORAGE_KEY);
    if (!raw) return null;
    var parsed = JSON.parse(raw);
    if (!parsed || !parsed.startedAt) return null;
    return parsed;
  } catch (_) {
    return null;
  }
}

function clearVeniceCaptureState() {
  try {
    sessionStorage.removeItem(VENICE_CAPTURE_STORAGE_KEY);
  } catch (_) {}
}

// ============================================================
// Assistant response extraction
// ============================================================

function getChatGPTConversationContainers() {
  var out = [];

  try {
    var sections = document.querySelectorAll('section[aria-label^="محادثة"]');
    for (var i = 0; i < sections.length; i++) out.push(sections[i]);
  } catch (e) {}

  if (!out.length) {
    try {
      var regions = document.querySelectorAll(
        '[role="region"][aria-label="محادثة"]'
      );
      for (var j = 0; j < regions.length; j++) out.push(regions[j]);
    } catch (e2) {}
  }

  return out;
}

function getPlatformResponseElements() {
  var res = [];
  var sels = CURRENT_PLATFORM.responseSelectors || [];

  if (CURRENT_PLATFORM.id === "chatgpt") {
    var assistantSelectors = [
      '[data-message-author-role="assistant"]',
      '[data-role="assistant"]',
      '[data-message-author="assistant"]'
    ];

    for (var a = 0; a < assistantSelectors.length; a++) {
      try {
        var assistantNodes = document.querySelectorAll(
          assistantSelectors[a]
        );

        for (var b = 0; b < assistantNodes.length; b++) {
          if (res.indexOf(assistantNodes[b]) === -1) {
            res.push(assistantNodes[b]);
          }
        }
      } catch (_) {}
    }

    if (!res.length) {
      try {
        var agentTurns = document.querySelectorAll(".agent-turn");

        for (var c = 0; c < agentTurns.length; c++) {
          if (res.indexOf(agentTurns[c]) === -1) {
            res.push(agentTurns[c]);
          }
        }
      } catch (_) {}
    }

    if (!res.length) {
      return getChatGPTConversationContainers();
    }

    return res;
  }

  for (var i = 0; i < sels.length; i++) {
    try {
      var els = document.querySelectorAll(sels[i]);

      for (var j = 0; j < els.length; j++) {
        if (res.indexOf(els[j]) === -1) {
          res.push(els[j]);
        }
      }
    } catch (_) {}
  }

  return res;
}

function getArenaAssistantElements() {
  if (CURRENT_PLATFORM.id !== "arena") return [];

  var nodes = [];
  var selectors = CURRENT_PLATFORM.responseSelectors || [];

  for (var i = 0; i < selectors.length; i++) {
    try {
      var found = document.querySelectorAll(selectors[i]);

      for (var j = 0; j < found.length; j++) {
        var el = found[j];

        if (!el || !isProbablyVisible(el)) continue;

        try {
          if (
            el.closest(
              "[class*='bg-surface-raised'][class*='w-fit']"
            )
          ) {
            continue;
          }
        } catch (_) {}

        if (getElementText(el)) uniquePush(nodes, el);
      }
    } catch (_) {}
  }

  nodes.sort(compareDocumentOrder);
  return nodes;
}

function cleanAssistantResponse(text) {
  if (!text) return "";

  var s = String(text)
    .replace(/\r\n/g, "\n")
    .replace(/\r/g, "\n");

  s = s
    .replace(/\n[ \t]*\n[ \t]*\n+/g, "\n\n")
    .replace(/[ \t]+\n/g, "\n");

  return s.trim();
}

// ============================================================
// [v1.0.54] عبارات disclaimer / تسميات أدوار فارغة شائعة في واجهة
// ChatGPT، وآلية تجريد عامة منها. تُستخدم لتعميم isInvalidChatGPTResponse
// بدل الاعتماد على مطابقة تامة فقط لعبارات محدودة سابقًا، وهو ما كان
// يسمح بقبول "رد منقوص" (تسمية دور فارغة + disclaimer فقط) كرد صالح.
// ============================================================

var CHATGPT_DISCLAIMER_SNIPPETS = [
  "chatgpt هو نظام ذكاء اصطناعي وقد يخطئ",
  "chatgpt is an ai system and can make mistakes",
  "chatgpt can make mistakes. check important info",
  "chatgpt can make mistakes",
  "الذكاء الاصطناعي يمكن أن يخطئ",
  "قد يخطئ الذكاء الاصطناعي",
  "check important info"
];

function escapeRegExp(s) {
  return String(s || "").replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function stripChatGPTBoilerplate(text) {
  var out = String(text || "");

  for (var i = 0; i < CHATGPT_DISCLAIMER_SNIPPETS.length; i++) {
    try {
      var re = new RegExp(escapeRegExp(CHATGPT_DISCLAIMER_SNIPPETS[i]), "ig");
      out = out.replace(re, " ");
    } catch (_) {}
  }

  out = out
    .split("\n")
    .filter(function (line) {
      var l = line.trim();
      if (!l) return false;
      if (/^(chatgpt|you|أنت)\s*[:：]?\s*$/i.test(l)) return false;
      if (/^(قال\s+chatgpt|chatgpt\s+قال)\s*[:：]?\s*$/i.test(l)) return false;
      return true;
    })
    .join("\n");

  return out.replace(/\s+/g, " ").trim();
}

function isInvalidChatGPTResponse(text) {
  var normalized = String(text || "")
    .replace(/\u00a0/g, " ")
    .replace(/\s+/g, " ")
    .trim()
    .toLowerCase();

  if (!normalized) return true;

  var invalidPhrases = [
    "chatgpt هو نظام ذكاء اصطناعي وقد يخطئ",
    "chatgpt is an ai system and can make mistakes",
    "chatgpt can make mistakes. check important info",
    "الذكاء الاصطناعي يمكن أن يخطئ"
  ];

  for (var i = 0; i < invalidPhrases.length; i++) {
    if (normalized === invalidPhrases[i]) return true;
  }

  // [v1.0.54] فحص عام: بعد حذف الـdisclaimers وتسميات الأدوار الفارغة
  // الشائعة، إن لم يتبقَّ محتوى حقيقي => النص غير صالح (رد منقوص).
  var afterStrip = stripChatGPTBoilerplate(text).toLowerCase();

  if (!afterStrip || afterStrip.length < 2) return true;

  return false;
}

function extractChatGPTAssistantText(conversationText) {
  if (!conversationText) return "";

  var markers = [
    "قال ChatGPT:",
    "قال ChatGPT :",
    "ChatGPT قال:",
    "ChatGPT:"
  ];

  var pos = -1;
  var len = 0;

  for (var i = 0; i < markers.length; i++) {
    var p = conversationText.lastIndexOf(markers[i]);
    if (p > pos) {
      pos = p;
      len = markers[i].length;
    }
  }

  if (pos === -1) return "";

  return cleanAssistantResponse(
    conversationText.substring(pos + len).trim()
  );
}

function extractLatestPlatformResponse() {
  var els = getPlatformResponseElements();
  if (!els.length) return "";

  for (var i = els.length - 1; i >= 0; i--) {
    var t = "";

    try {
      t = CURRENT_PLATFORM.extractResponse(els[i]);
    } catch (e) {
      t = getElementText(els[i]);
    }

    t = cleanAssistantResponse(t);

    if (CURRENT_PLATFORM.id !== "chatgpt" && isArenaRejectedCandidate(t)) {
      if (CURRENT_PLATFORM.id === "venice") {
        log(
          "🔬 DIAG extractLatestPlatformResponse: candidate rejected as echo len=" +
          (t ? t.length : 0)
        );
      }
      continue;
    }

    if (t) return t;
  }

  return "";
}

function getCurrentAssistantText() {
  if (CURRENT_PLATFORM.id === "arena") {
    var arenaNodes = getArenaAssistantElements();

    for (var a = arenaNodes.length - 1; a >= 0; a--) {
      var arenaText = cleanAssistantResponse(
        CURRENT_PLATFORM.extractResponse(arenaNodes[a])
      );

      if (!arenaText) continue;
      if (isArenaRejectedCandidate(arenaText)) continue;

      return arenaText;
    }

    return "";
  }

  if (CURRENT_PLATFORM.id !== "chatgpt") {
    return extractLatestPlatformResponse();
  }

  var elements = getPlatformResponseElements();

  for (var i = elements.length - 1; i >= 0; i--) {
    var el = elements[i];
    if (!el) continue;

    var target = el;

    try {
      var content = el.querySelector(
        ".markdown, [data-message-content], .whitespace-pre-wrap"
      );

      if (content) target = content;
    } catch (_) {}

    var text = "";

    try {
      text = cleanAssistantResponse(
        CURRENT_PLATFORM.extractResponse(target)
      );
    } catch (_) {
      text = cleanAssistantResponse(getElementText(target));
    }

    if (!text) continue;
    if (isInvalidChatGPTResponse(text)) continue;
    if (looksLikeInjectedPrompt(text)) continue;

    // [v1.0.54] إرجاع النص بعد تجريد أي disclaimer/تسميات أدوار فارغة
    // متبقية عالقة ضمن نص صالح (حماية إضافية، لا تؤثر على رد سليم).
    return stripChatGPTBoilerplate(text) || text;
  }

  var containers = getChatGPTConversationContainers();

  for (var j = containers.length - 1; j >= 0; j--) {
    var legacyText = cleanAssistantResponse(
      extractChatGPTAssistantText(getElementText(containers[j]))
    );

    if (!legacyText) continue;
    if (isInvalidChatGPTResponse(legacyText)) continue;
    if (looksLikeInjectedPrompt(legacyText)) continue;

    return stripChatGPTBoilerplate(legacyText) || legacyText;
  }

  return "";
}

// ============================================================
// Injected-prompt / echo rejection guard
// ============================================================

var lastInjectedSignature = "";
var lastInjectedAt = 0;

// [v1.0.54] العلامات الفعلية الحالية التي ينتجها WebSendCoordinator.kt
// (كانت content.js تبحث فقط عن علامات قديمة لم تعد مستخدمة إطلاقًا:
// <shared_memories> بلا غلاف SHARED_MEMORIES_XML:، ## السؤال، تعليمات
// مهمة، ملاحظات إضافية — لا شيء منها يظهر في الصيغة الحالية، لذلك كانت
// looksLikeInjectedPrompt تُرجع false دائمًا للسياق الجديد، معطِّلة كل
// حماية ضد التقاط النص المحقون كرد أو كرسالة مستخدم عادية).
var HIDDEN_CONTEXT_BEGIN_MARKER = "<<<AICHAT_HIDDEN_CONTEXT_BEGIN>>>";
var HIDDEN_CONTEXT_END_MARKER = "<<<AICHAT_HIDDEN_CONTEXT_END>>>";
var ARENA_CONTEXT_BEGIN_MARKER = "### Context (visible)";
var ARENA_CONTEXT_END_MARKER = "### End Context";

// --- [إصلاح جذري v1.0.53] ---
// الكود القديم كان: /[:：#*_-\u2013\u2014\(\)\(\)<>]/g
// المقطع "_-\u2013" داخل صنف الأحرف [...] كان يُفسَّر كـ "نطاق" (range)
// من U+005F إلى U+2013 (وليس ثلاثة رموز منفصلة)! هذا النطاق الهائل يغطي
// ضمنيًا كامل الكتلة العربية Unicode (U+0600–U+06FF)، فكانت كل الحروف
// العربية تُستبدل بمسافات وتُحذف فعليًا من أي نص يمر عبر normalizeForMatch.
// النتيجة المؤكدة بدليل سجل فعلي: lastInjectedSignature = "" دائمًا لأي
// نص عربي (signatureLen=0 في كل محاولة إرسال، وليس فقط بعد إعادة التحميل)،
// مما عطّل حارس رفض الصدى (isArenaRejectedCandidate) بالكامل لكل نص عربي
// منذ البداية - وهو السبب الجذري الحقيقي وراء كل حالات التقاط الصدى على
// Venice التي ظهرت عبر الاختبارات السابقة.
// الإصلاح: نقل الشرطة "-" إلى نهاية صنف الأحرف حيث تُفسَّر حرفيًا لا كنطاق.
function normalizeForMatch(s) {
  return String(s || "")
    .replace(/\u00a0/g, " ")
    .replace(/\s+/g, " ")
    .replace(/[:：#*_\u2013\u2014()()<>-]/g, " ")
    .replace(/\s+/g, " ")
    .trim()
    .toLowerCase();
}

function makeSignature(s) {
  return normalizeForMatch(s).substring(0, 220);
}

function looksLikeInjectedPrompt(text) {
  if (!text) return false;

  // [v1.0.54] العلامات الفعلية الحالية أولاً.
  if (text.indexOf(HIDDEN_CONTEXT_BEGIN_MARKER) !== -1) return true;
  if (text.indexOf(ARENA_CONTEXT_BEGIN_MARKER) !== -1) return true;
  if (text.indexOf("WORKSPACE_SUMMARY:") !== -1) return true;
  if (text.indexOf("AGENT_PERSONA:") !== -1) return true;
  if (text.indexOf("WEB_HANDOFF_SUMMARY:") !== -1) return true;
  if (text.indexOf("SHARED_MEMORIES_XML:") !== -1) return true;
  if (text.indexOf("ACCURACY_POLICY:") !== -1) return true;
  if (text.indexOf("EXTRA_NOTES:") !== -1) return true;

  // علامات قديمة (أُبقيت للتوافق مع أي صيغة سابقة محتملة).
  if (text.indexOf("<shared_memories>") !== -1) return true;
  if (text.indexOf("## السؤال") !== -1) return true;
  if (text.indexOf("تعليمات مهمة") !== -1) return true;
  if (text.indexOf("ملاحظات إضافية") !== -1) return true;

  var t = text.trim();

  return t.startsWith("السؤال") &&
    (Date.now() - lastInjectedAt) < 60000;
}

function isArenaRejectedCandidate(text) {
  if (CURRENT_PLATFORM.id === "chatgpt") return false;
  if (!text) return false;
  if (looksLikeInjectedPrompt(text)) return true;

  if (lastInjectedSignature) {
    var norm = normalizeForMatch(text);

    if (norm === lastInjectedSignature) return true;

    if (
      norm.indexOf(lastInjectedSignature) !== -1 &&
      norm.length <= lastInjectedSignature.length + 15
    ) {
      return true;
    }
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
var diagEmptyPollCount = 0;

var lastSentResponseSignature = "";
var lastSentResponseAt = 0;

function startResponseCapture(reason) {
  captureActive = true;
  captureFinished = false;
  lastCandidate = "";
  stableSince = 0;
  captureStartedAt = Date.now();
  diagEmptyPollCount = 0;
  baselineAssistant = getCurrentAssistantText();

  log(
    "🧪 Capture started platform=" + CURRENT_PLATFORM.id +
    " reason=" + (reason || "explicit") +
    " baselineLen=" + (baselineAssistant ? baselineAssistant.length : 0)
  );

  watchForResponse();
}

function startArenaCaptureFromWebsite(reason) {
  if (CURRENT_PLATFORM.id !== "arena") return;

  if (
    captureActive &&
    !captureFinished &&
    Date.now() - captureStartedAt < 4000
  ) {
    log("ℹ️ Arena capture already active reason=" + reason);
    return;
  }

  log("🎯 Arena website send detected reason=" + reason);
  startResponseCapture("arena_" + reason);
}

function tryResumeVeniceCapture() {
  var state = getVeniceCaptureState();

  if (!state) {
    log("🔬 DIAG Venice resume: no stored capture state found on load");
    return;
  }

  var age = Date.now() - (state.startedAt || 0);

  if (age > VENICE_CAPTURE_MAX_AGE_MS) {
    log(
      "🔬 DIAG Venice resume: stored capture state expired (age=" +
      age + "ms), clearing"
    );
    clearVeniceCaptureState();
    return;
  }

  if (state.injectedSignature) {
    lastInjectedSignature = state.injectedSignature;
    lastInjectedAt = state.injectedAt || Date.now();
    log(
      "🔬 DIAG Venice resume: restored injected signature len=" +
      lastInjectedSignature.length
    );
  } else {
    log("🔬 DIAG Venice resume: stored state had no injectedSignature");
  }

  log(
    "🔬 DIAG Venice resume: resuming capture from sessionStorage after reload (age=" +
    age + "ms)"
  );

  startResponseCapture("resumed_after_reload");
}

function finishCapture(answer) {
  if (captureFinished) return;

  if (
    CURRENT_PLATFORM.id !== "chatgpt" &&
    isArenaRejectedCandidate(answer)
  ) {
    log(
      "⚠️ FinishCapture blocked (echo of injected text detected) platform=" +
      CURRENT_PLATFORM.id
    );
    lastCandidate = "";
    stableSince = 0;
    setTimeout(watchForResponse, 900);
    return;
  }

  answer = cleanAssistantResponse(answer || "");

  if (!answer) {
    lastCandidate = "";
    stableSince = 0;
    setTimeout(watchForResponse, 800);
    return;
  }

  var signature = makeSignature(answer);
  var now = Date.now();

  if (
    signature &&
    signature === lastSentResponseSignature &&
    now - lastSentResponseAt < 30000
  ) {
    captureFinished = true;
    captureActive = false;

    if (CURRENT_PLATFORM.id === "venice") {
      clearVeniceCaptureState();
    }

    log("ℹ️ Duplicate automatic response suppressed");
    return;
  }

  captureFinished = true;
  captureActive = false;
  lastSentResponseSignature = signature;
  lastSentResponseAt = now;

  if (CURRENT_PLATFORM.id === "venice") {
    clearVeniceCaptureState();
  }

  sendToBackground({
    type: "ASSISTANT_RESPONSE",
    text: answer,
    source: CURRENT_PLATFORM.id
  });

  log(
    "📤 ASSISTANT_RESPONSE sent source=" +
    CURRENT_PLATFORM.id + " len=" + answer.length
  );
}

function watchForResponse() {
  if (!captureActive || captureFinished) return;

  var current = getCurrentAssistantText();

  if (
    CURRENT_PLATFORM.id !== "chatgpt" &&
    current &&
    isArenaRejectedCandidate(current)
  ) {
    if (CURRENT_PLATFORM.id === "venice") {
      log(
        "🔬 DIAG watchForResponse: current candidate rejected as echo len=" +
        current.length
      );
    }
    setTimeout(watchForResponse, 900);
    return;
  }

  if (!current) {
    if (CURRENT_PLATFORM.id === "venice") {
      diagEmptyPollCount++;
      if (diagEmptyPollCount === 1 || diagEmptyPollCount % 5 === 0) {
        try {
          var proseCount = document.querySelectorAll(".prose").length;
          var bodyChildren = document.body ? document.body.children.length : -1;
          var elapsed = Date.now() - captureStartedAt;

          log(
            "🔬 DIAG watchForResponse: elapsed=" + elapsed + "ms" +
            " poll#" + diagEmptyPollCount +
            " proseCount=" + proseCount +
            " bodyChildren=" + bodyChildren +
            " visibilityState=" + document.visibilityState +
            " hidden=" + document.hidden
          );
        } catch (eDiag) {
          log("🔬 DIAG watchForResponse failed: " + eDiag);
        }
      }
    }

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

  if (
    lastCandidate &&
    stableSince > 0 &&
    Date.now() - stableSince >= 1800
  ) {
    finishCapture(lastCandidate);
    return;
  }

  setTimeout(watchForResponse, 800);
}

// ============================================================
// Automatic capture for normal website prompts
// ============================================================

var autoResponseObserver = null;
var autoResponseCheckTimer = 0;
var lastObservedUserElement = null;
var lastObservedUserSignature = "";
var lastObservedUserCount = 0;

function compareDocumentOrder(a, b) {
  if (a === b) return 0;
  if (!a || !b || !a.compareDocumentPosition) return 0;

  var relation = a.compareDocumentPosition(b);

  if (relation & Node.DOCUMENT_POSITION_FOLLOWING) return -1;
  if (relation & Node.DOCUMENT_POSITION_PRECEDING) return 1;

  return 0;
}

function getLatestUserMessageElement() {
  var els = getUserMessageElements();

  if (!els.length) return null;

  els.sort(compareDocumentOrder);

  return els[els.length - 1] || null;
}

function getUserMessageCount() {
  return getUserMessageElements().length;
}

function getUserMessageText(el) {
  if (!el) return "";

  var target = el;

  try {
    var selector = String(
      CURRENT_PLATFORM.userTextSelector || ""
    ).trim();

    if (selector) {
      var inner = el.querySelector(selector);
      if (inner) target = inner;
    } else if (CURRENT_PLATFORM.id === "chatgpt") {
      var textNode = el.querySelector(".whitespace-pre-wrap");
      if (textNode) target = textNode;
    }
  } catch (_) {}

  return cleanAssistantResponse(getElementText(target));
}

function checkForNormalWebsiteMessage() {
  var latest = getLatestUserMessageElement();
  if (!latest) return;

  var text = getUserMessageText(latest);
  if (!text) return;

  var signature = makeSignature(text);
  var count = getUserMessageCount();

  var elementChanged = latest !== lastObservedUserElement;
  var textChanged = signature !== lastObservedUserSignature;
  var countIncreased = count > lastObservedUserCount;

  lastObservedUserElement = latest;
  lastObservedUserSignature = signature;
  lastObservedUserCount = count;

  if (!elementChanged && !textChanged && !countIncreased) return;

  if (looksLikeInjectedPrompt(text)) {
    log("ℹ️ Automatic capture skipped: injected prompt detected");
    return;
  }

  lastSentResponseSignature = "";
  lastSentResponseAt = 0;

  log(
    "👤 New website user message detected" +
    " platform=" + CURRENT_PLATFORM.id +
    " len=" + text.length
  );

  var captureWasStartedRecently =
    captureActive &&
    !captureFinished &&
    Date.now() - captureStartedAt < 5000;

  if (captureWasStartedRecently) {
    log("ℹ️ Existing response capture retained for outgoing message");
    return;
  }

  startResponseCapture("website_user_message");
}

function scheduleAutoResponseCheck() {
  if (autoResponseCheckTimer) return;

  autoResponseCheckTimer = setTimeout(function () {
    autoResponseCheckTimer = 0;

    try {
      checkForNormalWebsiteMessage();
    } catch (e) {
      log("❌ Automatic response check failed: " + String(e));
    }
  }, 250);
}

function startAutoResponseObserver() {
  if (autoResponseObserver) return;
  if (!document.documentElement) return;

  var initialLatest = getLatestUserMessageElement();

  lastObservedUserElement = initialLatest;
  lastObservedUserSignature = initialLatest
    ? makeSignature(getUserMessageText(initialLatest))
    : "";
  lastObservedUserCount = getUserMessageCount();

  try {
    autoResponseObserver = new MutationObserver(function () {
      scheduleAutoResponseCheck();
    });

    autoResponseObserver.observe(document.documentElement, {
      childList: true,
      subtree: true,
      characterData: true
    });

    log(
      "👁️ Automatic website response observer installed" +
      " platform=" + CURRENT_PLATFORM.id +
      " existingUserMessages=" + lastObservedUserCount
    );
  } catch (e) {
    autoResponseObserver = null;
    log("❌ Automatic response observer failed: " + String(e));
  }
}

function startArenaSendObserver() {
  if (CURRENT_PLATFORM.id !== "arena") return;

  document.addEventListener("keydown", function (event) {
    if (!event || event.key !== "Enter") return;
    if (event.shiftKey || event.isComposing) return;

    var target = event.target;
    if (!target || !target.closest) return;

    var input = target.closest(
      "textarea, input, [contenteditable='true'], [role='textbox']"
    );

    if (!input) return;

    startArenaCaptureFromWebsite("enter");
  }, true);

  document.addEventListener("click", function (event) {
    var target = event.target;
    if (!target || !target.closest) return;

    var button = target.closest("button");
    if (!button) return;

    var label = [
      button.getAttribute("aria-label") || "",
      button.getAttribute("data-testid") || "",
      button.getAttribute("title") || "",
      button.innerText || ""
    ].join(" ").toLowerCase();

    if (!/(send|submit|إرسال|ارسال)/i.test(label)) return;

    startArenaCaptureFromWebsite("send_button");
  }, true);

  document.addEventListener("submit", function (event) {
    var form = event.target;
    if (!form) return;

    var input = form.querySelector(
      "textarea, input, [contenteditable='true'], [role='textbox']"
    );

    if (!input) return;

    startArenaCaptureFromWebsite("form_submit");
  }, true);

  log("👁️ Arena website-send observer installed");
}

// ============================================================
// Manual capture
// ============================================================

function sendManualCaptureResult(text, reason) {
  var cleaned = cleanAssistantResponse(text || "");

  sendToBackground({
    type: "CAPTURE_RESULT",
    success: !!cleaned,
    text: cleaned,
    debug: {
      assistant: cleaned ? 1 : 0,
      articles: 0,
      bodyLen: cleaned.length,
      platform: CURRENT_PLATFORM.id,
      reason: reason || ""
    },
    source: CURRENT_PLATFORM.id
  });
}

function captureCurrentResponse() {
  var cur = getCurrentAssistantText();

  if (!cur) {
    return sendManualCaptureResult("", "assistant_response_not_found");
  }

  if (
    CURRENT_PLATFORM.id === "arena" &&
    isArenaRejectedCandidate(cur)
  ) {
    return sendManualCaptureResult("", "rejected_injected_prompt");
  }

  sendManualCaptureResult(cur, "current_response");
}

// ============================================================
// Sanitizer — disabled for Arena
// ============================================================

var HIDDEN_MARKER = "aichat_hidden_context";
var sanitizeObserver = null;
var sanitizeThrottleTimer = 0;
var sanitizeAggressiveTimer = 0;
var sanitizeAggressiveUntil = 0;

// [v1.0.54] أُضيف فحص العلامة الفعلية الحالية (أحرف كبيرة) بالإضافة
// للعلامة القديمة (أحرف صغيرة) ولعلامات WebSendCoordinator الأخرى.
// indexOf حسّاس لحالة الأحرف، لذا HIDDEN_MARKER وحدها لم تكن تطابق أبدًا
// النص الفعلي المحقون <<<AICHAT_HIDDEN_CONTEXT_BEGIN>>>، ما كان يعطّل
// الـsanitizer بالكامل لأي رسالة تحتوي السياق الجديد.
function looksLikeHasHiddenContext(t) {
  if (!t) return false;

  return (
    t.indexOf(HIDDEN_CONTEXT_BEGIN_MARKER) !== -1 ||
    t.indexOf(HIDDEN_MARKER) !== -1 ||
    t.indexOf("<shared_memories>") !== -1 ||
    t.indexOf("SHARED_MEMORIES_XML:") !== -1 ||
    t.indexOf("WORKSPACE_SUMMARY:") !== -1 ||
    t.indexOf("AGENT_PERSONA:") !== -1 ||
    t.indexOf("WEB_HANDOFF_SUMMARY:") !== -1
  );
}

function stripHiddenContextFromText(s) {
  var out = String(s || "");

  // [v1.0.54] إزالة كتلة السياق الفعلية بين العلامتين الحاليتين.
  out = out.replace(
    /<<<AICHAT_HIDDEN_CONTEXT_BEGIN>>>[\s\S]*?<<<AICHAT_HIDDEN_CONTEXT_END>>>\s*/g,
    ""
  );

  out = out.replace(
    /<!--\s*aichat_hidden_context[\s\S]*?-->\s*/ig,
    ""
  );

  out = out.replace(
    /SHARED_MEMORIES_XML:\s*<shared_memories>[\s\S]*?<\/shared_memories>\s*/ig,
    ""
  );

  out = out.replace(
    /ACCURACY_POLICY:\s*[\s\S]*?(?=\n{2,}|$)/ig,
    ""
  );

  out = out.replace(
    /EXTRA_NOTES:\s*[\s\S]*?(?=\n{2,}|$)/ig,
    ""
  );

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

      for (var j = 0; j < els.length; j++) {
        if (out.indexOf(els[j]) === -1) out.push(els[j]);
      }
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

    try {
      node.setAttribute("data-aichat-sanitized", "1");
    } catch (_) {}

    return true;
  } catch (e2) {
    log("❌ sanitizeOneNodeText failed: " + e2);
    return false;
  }
}

function isSafeTextElement(el) {
  if (!el || !el.querySelector) return true;

  try {
    if (el.querySelector("button, [role='button'], form, textarea, input, select")) {
      return false;
    }
  } catch (_) {}

  return true;
}

function pickBestMarkerElement(container) {
  if (!container) return null;

  var preferred = [
    ".whitespace-pre-wrap",
    "[data-message-content]",
    ".markdown",
    "p",
    "div",
    "span"
  ];

  for (var i = 0; i < preferred.length; i++) {
    try {
      var el = container.querySelector(preferred[i]);
      if (!el || !isSafeTextElement(el)) continue;

      var t = getElementText(el);
      if (looksLikeHasHiddenContext(t)) return el;
    } catch (_) {}
  }

  var best = null;
  var bestLen = 1e9;

  try {
    var walker = document.createTreeWalker(
      container,
      NodeFilter.SHOW_ELEMENT
    );

    var visited = 0;

    while (walker.nextNode()) {
      visited++;
      if (visited > 900) break;

      var el2 = walker.currentNode;
      if (!el2 || !isSafeTextElement(el2)) continue;

      var txt = getElementText(el2);
      if (!looksLikeHasHiddenContext(txt)) continue;

      var len = txt.length || 0;

      if (len > 0 && len < bestLen) {
        best = el2;
        bestLen = len;
        if (bestLen < 800) break;
      }
    }
  } catch (_) {}

  return best;
}

function sanitizeChatGPTOnly() {
  if (CURRENT_PLATFORM.id !== "chatgpt") return 0;

  var turns = [];

  try {
    var list = document.querySelectorAll(
      'article[data-testid="conversation-turn"], div[data-testid="conversation-turn"]'
    );

    for (var i = 0; i < list.length; i++) turns.push(list[i]);
  } catch (_) {}

  if (!turns.length) {
    try {
      var main = document.querySelector("main");
      if (main) turns.push(main);
    } catch (_) {}
  }

  if (!turns.length) return 0;

  var start = turns.length > 10 ? turns.length - 10 : 0;

  for (var ti = turns.length - 1; ti >= start; ti--) {
    var turn = turns[ti];
    if (!turn) continue;

    var userBlocks = [];

    try {
      var ub = turn.querySelectorAll(
        '[data-message-author-role="user"], [data-role="user"], [data-message-author="user"]'
      );

      for (var j = 0; j < ub.length; j++) userBlocks.push(ub[j]);
    } catch (_) {}

    if (!userBlocks.length) userBlocks.push(turn);

    for (var ui = 0; ui < userBlocks.length; ui++) {
      var block = userBlocks[ui];
      if (!block) continue;

      var blockText = "";

      try {
        blockText = getElementText(block);
      } catch (_) {}

      if (!looksLikeHasHiddenContext(blockText)) continue;

      var best = pickBestMarkerElement(block);

      if (best) {
        if (sanitizeOneNodeText(best)) return 1;

        if (
          isSafeTextElement(block) &&
          sanitizeOneNodeText(block)
        ) return 1;
      }
    }
  }

  return 0;
}

function sanitizeByUserElements() {
  var els = getUserMessageElements();
  if (!els.length) return 0;

  var N = 16;
  var start = els.length > N ? els.length - N : 0;
  var changed = 0;

  for (var i = start; i < els.length; i++) {
    var target = pickEditableTextNode(els[i]);

    if (sanitizeOneNodeText(target)) {
      try {
        els[i].setAttribute("data-aichat-sanitized", "1");
      } catch (_) {}

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

      for (var j = 0; j < els.length; j++) {
        if (out.indexOf(els[j]) === -1) out.push(els[j]);
      }
    } catch (e) {}
  }

  return out;
}

function sanitizeByCandidatesScan() {
  var nodes = getSanitizeCandidateTextNodes();
  if (!nodes.length) return 0;

  var N = 60;
  var start = nodes.length > N ? nodes.length - N : 0;
  var changed = 0;

  for (var i = start; i < nodes.length; i++) {
    if (sanitizeOneNodeText(nodes[i])) changed++;
  }

  return changed;
}

function sanitizeAllNow(reason) {
  if (CURRENT_PLATFORM.id === "arena") return 0;

  var changedGpt = 0;

  if (CURRENT_PLATFORM.id === "chatgpt") {
    try {
      changedGpt = sanitizeChatGPTOnly();
    } catch (_) {
      changedGpt = 0;
    }
  }

  var changed1 = 0;
  var changed2 = 0;

  try { changed1 = sanitizeByUserElements(); } catch (_) {}
  try { changed2 = sanitizeByCandidatesScan(); } catch (_) {}

  var changed = changedGpt + changed1 + changed2;

  if (changed > 0) {
    log(
      "🧼 Sanitized " + changed +
      " node(s) reason=" + (reason || "") +
      " platform=" + CURRENT_PLATFORM.id
    );
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
      if (
        sanitizeAggressiveUntil &&
        Date.now() < sanitizeAggressiveUntil
      ) {
        sanitizeAllNow("mutation_aggressive");
      } else {
        requestSanitizeSoon();
      }
    });

    sanitizeObserver.observe(document.documentElement, {
      childList: true,
      subtree: true
    });

    log("🧼 Sanitizer observer installed");
  } catch (e) {
    log("❌ Sanitizer observer failed: " + e);
  }
}

startSanitizer();

function scheduleSanitizeAfterSend(injectedText) {
  if (CURRENT_PLATFORM.id === "arena") return;

  sanitizeAggressiveUntil = Date.now() + 12000;

  var changedNow = sanitizeAllNow("after_send_now");

  if (sanitizeAggressiveTimer) {
    try { clearInterval(sanitizeAggressiveTimer); } catch (_) {}
  }

  var tries = 0;

  sanitizeAggressiveTimer = setInterval(function () {
    tries++;

    sanitizeAllNow("after_send_tick_" + tries);

    if (tries >= 48) {
      clearInterval(sanitizeAggressiveTimer);
      sanitizeAggressiveTimer = 0;
    }
  }, 250);

  if (changedNow === 0) {
    try {
      log(
        "🧼 scheduleSanitizeAfterSend: changedNow=0 marker=" +
        (String(injectedText || "").indexOf(HIDDEN_CONTEXT_BEGIN_MARKER) !== -1
          ? "yes"
          : "no")
      );
    } catch (_) {}
  }
}

// ============================================================
// Send after inject — retry
// ============================================================

function sendWithRetry(input, opts, contextId, injectedText) {
  var maxTries =
    CURRENT_PLATFORM.id === "venice" ? 18 :
    CURRENT_PLATFORM.id === "chatgpt" ? 14 :
    12;

  var intervalMs = CURRENT_PLATFORM.id === "venice" ? 220 : 180;
  var tries = 0;

  function attempt() {
    tries++;

    var btn = findSendButtonNear(input, String(opts.sendSelector || "").trim());

    if (btn && canClick(btn)) {
      if (CURRENT_PLATFORM.id === "venice") {
        try {
          log("🔬 DIAG about to click button => " + describeElementForDiag(btn));
        } catch (eDiagBtn) {
          log("🔬 DIAG describe button failed: " + eDiagBtn);
        }
      }

      try {
        btn.click();
        sendContextWritten(true, contextId, "button_clicked", "ok");
        sendContextConsumed(contextId);
        scheduleSanitizeAfterSend(injectedText);

        if (CURRENT_PLATFORM.id === "venice") {
          setTimeout(function () {
            try {
              var currentVal =
                (input.tagName === "TEXTAREA" || input.tagName === "INPUT")
                  ? input.value
                  : getElementText(input);

              log(
                "🔬 DIAG post-click input check: len=" +
                (currentVal ? currentVal.length : 0) +
                " cleared=" + (!currentVal || currentVal.length === 0) +
                " visibilityState=" + document.visibilityState +
                " hidden=" + document.hidden
              );
            } catch (eDiag2) {
              log("🔬 DIAG post-click input check failed: " + eDiag2);
            }
          }, 3000);
        }

        return;
      } catch (e1) {}
    }

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

    if (tries < maxTries) {
      setTimeout(attempt, intervalMs);
      return;
    }

    sendContextWritten(
      false,
      contextId,
      "send_failed",
      "no send method worked"
    );
  }

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

  var attempts =
    CURRENT_PLATFORM.id === "venice" ? 28 :
    CURRENT_PLATFORM.id === "chatgpt" ? 18 :
    14;

  var delayMs =
    CURRENT_PLATFORM.id === "venice" ? 420 : 380;

  findInputWithRetry(
    opts.inputSelector || "",
    attempts,
    delayMs,
    function (input) {
      try {
        if (CURRENT_PLATFORM.id === "venice") {
          try {
            log("🔬 DIAG chosen input => " + describeElementForDiag(input));
          } catch (eDiagChosen) {
            log("🔬 DIAG describe chosen input failed: " + eDiagChosen);
          }
        }

        if (!injectText(input, text)) {
          return sendContextWritten(
            false,
            contextId,
            "inject_failed",
            "injectText returned false"
          );
        }

        sendContextWritten(true, contextId, "text_injected", "ok");

        if (CURRENT_PLATFORM.id === "arena") {
          sendContextWritten(
            true,
            contextId,
            "arena_text_only",
            "injected_no_send"
          );
          sendContextConsumed(contextId);
          return;
        }

        startResponseCapture("app_injected_prompt");

        if (CURRENT_PLATFORM.id === "venice") {
          setVeniceCaptureState({
            startedAt: Date.now(),
            contextId: contextId,
            injectedSignature: lastInjectedSignature,
            injectedAt: lastInjectedAt
          });
          log(
            "🔬 DIAG Venice resume: capture state stored to sessionStorage (signatureLen=" +
            lastInjectedSignature.length + ")"
          );
        }

        sendWithRetry(input, opts, contextId, text);
      } catch (e) {
        sendContextWritten(
          false,
          contextId,
          "exception",
          e && e.message ? e.message : String(e)
        );
      }
    },
    function () {
      sendContextWritten(
        false,
        contextId,
        "input_not_found",
        "input not found after retries"
      );
    }
  );
}

// ============================================================
// Messages from background
// ============================================================

browser.runtime.onMessage.addListener(function (message) {
  try {
    if (!message || !message.type) {
      return Promise.resolve({ ok: false, reason: "no_type" });
    }

    if (message.type === "MANUAL_CAPTURE") {
      log("🎯 MANUAL_CAPTURE received");
      captureCurrentResponse();

      return Promise.resolve({
        ok: true,
        type: "MANUAL_CAPTURE"
      });
    }

    if (message.type === "CONTEXT_TO_PAGE") {
      var text = String(message.text || "");
      var contextId = message.contextId || 0;

      log(
        "📥 CONTEXT_TO_PAGE len=" + text.length +
        " platform=" + CURRENT_PLATFORM.id +
        " inputSel=" + (message.inputSelector || "") +
        " sendSel=" + (message.sendSelector || "")
      );

      if (!text.trim()) {
        sendContextWritten(
          false,
          contextId,
          "empty_text",
          "text is empty"
        );

        return Promise.resolve({
          ok: false,
          reason: "empty_text"
        });
      }

      injectAndSend({
        text: text,
        contextId: contextId,
        inputSelector: message.inputSelector || "",
        sendSelector: message.sendSelector || "",
        sendWithEnter: message.sendWithEnter !== false
      });

      return Promise.resolve({
        ok: true,
        type: "CONTEXT_TO_PAGE"
      });
    }

    return Promise.resolve({
      ok: false,
      reason: "unknown_type",
      type: message.type
    });
  } catch (e) {
    try {
      console.log("[AiChat] onMessage exception", e);
    } catch (_) {}

    return Promise.resolve({
      ok: false,
      reason: "exception"
    });
  }
});

// ============================================================
// Start automatic website response observer
// ============================================================

startAutoResponseObserver();
startArenaSendObserver();

if (CURRENT_PLATFORM.id === "venice") {
  tryResumeVeniceCapture();
}

// ============================================================
// READY
// ============================================================

if (shouldSendDiagnostics()) {
  sendToBackground({ type: "CONTENT_READY" });
  log("📡 CONTENT_READY sent");
} else {
  try {
    console.log(
      "[AiChat] generic page - skip CONTENT_READY/DEBUG (host=" +
      HOST + ")"
    );
  } catch (_) {}
}
})();
