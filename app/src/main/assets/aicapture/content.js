(function () {
"use strict";

var VERSION = "1.0.46";
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
  matches: function () { return HOST === "arena.ai"; },  
  responseSelectors: [  
    "main .prose",  
    ".prose.prose-base",  
    "div.prose"  
  ],  
  userSelectors: [  
    "[data-message-author-role='user']",  
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
var composerUserPref = null;
var toggleBtn = null;
var applyTimer = 0;

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
  return input.closest("form") ||  
    input.closest("footer") ||  
    input.closest("[role='form']") ||  
    input.closest("section") ||  
    input.parentElement ||  
    null;  
} catch (e) {  
  return null;  
}

}

function hideComposer(el) {
if (!el) return false;

if (el.getAttribute("data-aichat-hidden") === "1") {  
  composerHidden = true;  
  return true;  
}  

if (!el.getAttribute("data-aichat-old-style")) {  
  el.setAttribute("data-aichat-old-style", el.style.cssText || "");  
}  

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

if (el.getAttribute("data-aichat-hidden") !== "1") {  
  composerHidden = false;  
  return true;  
}  

var old = el.getAttribute("data-aichat-old-style");  
el.style.cssText = old || "";  
el.removeAttribute("data-aichat-hidden");  
el.removeAttribute("data-aichat-old-style");  
composerHidden = false;  
return true;

}

function updateToggleBtn() {
if (!toggleBtn) return;
toggleBtn.textContent = composerHidden
? "إظهار مربع الموقع"
: "إخفاء مربع الموقع";
toggleBtn.style.background = composerHidden
? "rgba(20,120,60,0.95)"
: "rgba(0,0,0,0.75)";
}

function applyComposerPrefSoon() {
if (CURRENT_PLATFORM.id === "generic") return;
if (applyTimer) return;

applyTimer = setTimeout(function () {  
  applyTimer = 0;  
  var el = getComposerContainer();  
  if (!el) return;  

  var wantHide = composerUserPref === null  
    ? defaultShouldHideComposer()  
    : composerUserPref;  

  if (wantHide) hideComposer(el);  
  else showComposer(el);  

  updateToggleBtn();  
}, 80);

}

function installToggleButton() {
if (CURRENT_PLATFORM.id === "generic") return;

try {  
  var existing = document.getElementById("aichat-toggle-composer");  
  if (existing) {  
    toggleBtn = existing;  
    updateToggleBtn();  
    return;  
  }  

  toggleBtn = document.createElement("button");  
  toggleBtn.id = "aichat-toggle-composer";  
  toggleBtn.type = "button";  
  toggleBtn.style.cssText =  
    "position:fixed;right:12px;bottom:12px;z-index:2147483647;" +  
    "border:0;border-radius:10px;padding:10px 12px;font-size:13px;" +  
    "color:#fff;cursor:pointer;box-shadow:0 6px 20px rgba(0,0,0,.25);" +  
    "user-select:none;";  

  toggleBtn.addEventListener("click", function () {  
    composerUserPref = composerHidden ? false : true;  
    applyComposerPrefSoon();  
  });  

  document.documentElement.appendChild(toggleBtn);  
  updateToggleBtn();  
} catch (e) {  
  log("❌ installToggleButton failed: " + e);  
}

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
  var obs = new MutationObserver(function () {  
    applyComposerPrefSoon();  
  });  
  obs.observe(document.documentElement, {  
    childList: true,  
    subtree: true  
  });  
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

function findSendButton(preferSelector) {
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

// ChatGPT: أعطِ الأولوية لعناصر المساعد الفعلية،
// ولا تستخدم حاوية المحادثة الواسعة إلا كحل احتياطي.
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

  // استخدم .agent-turn فقط إذا لم نجد أي عنصر مساعد صريح.  
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

  // آخر حل احتياطي: حاويات المحادثة القديمة.  
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

function isInvalidChatGPTResponse(text) {
var normalized = String(text || "")
.replace(/\u00a0/g, " ")
.replace(/\s+/g, " ")
.trim()
.toLowerCase();

if (!normalized) return true;

// نصوص ثابتة معروفة لا ينبغي حفظها كإجابة.  
var invalidPhrases = [  
  "chatgpt هو نظام ذكاء اصطناعي وقد يخطئ",  
  "chatgpt is an ai system and can make mistakes",  
  "chatgpt can make mistakes. check important info"  
];  

for (var i = 0; i < invalidPhrases.length; i++) {  
  if (normalized === invalidPhrases[i]) return true;  
}  

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

  if (CURRENT_PLATFORM.id === "arena" && isArenaRejectedCandidate(t)) {  
    continue;  
  }  

  if (t) return t;  
}  

return "";

}

function getCurrentAssistantText() {
if (CURRENT_PLATFORM.id !== "chatgpt") {
return extractLatestPlatformResponse();
}

var elements = getPlatformResponseElements();  

// افحص أحدث عنصر مساعد أولًا، ثم ارجع إلى الأقدم  
// فقط إذا كان العنصر الأحدث فارغًا أو يحتوي نصًا غير صالح.  
for (var i = elements.length - 1; i >= 0; i--) {  
  var el = elements[i];  
  if (!el) continue;  

  var target = el;  

  try {  
    // فضّل محتوى الإجابة على الحاوية التي قد تضم أزرارًا  
    // أو عناصر واجهة إضافية.  
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

  // لا نقبل ردًا يبدو مجرد تعليمات أو سياقًا محقونًا.  
  if (looksLikeInjectedPrompt(text)) continue;  

  return text;  
}  

// حل احتياطي للتوافق مع بعض إصدارات واجهة ChatGPT القديمة.  
// لا نعيد نص التذييل الثابت حتى لو طابق إحدى العلامات.  
var containers = getChatGPTConversationContainers();  

for (var j = containers.length - 1; j >= 0; j--) {  
  var conversationText = getElementText(containers[j]);  
  var legacyText = cleanAssistantResponse(  
    extractChatGPTAssistantText(conversationText)  
  );  

  if (!legacyText) continue;  
  if (isInvalidChatGPTResponse(legacyText)) continue;  
  if (looksLikeInjectedPrompt(legacyText)) continue;  

  return legacyText;  
}  

return "";

}

// ============================================================
// Injected-prompt / Arena rejection guard
// ============================================================

var lastInjectedSignature = "";
var lastInjectedAt = 0;

function normalizeForMatch(s) {
return String(s || "")
.replace(/\u00a0/g, " ")
.replace(/\s+/g, " ")
.replace(/[:：#*_-\u2013\u2014\(\)\(\)<>]/g, " ")
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

var t = text.trim();  

return t.startsWith("السؤال") &&  
  (Date.now() - lastInjectedAt) < 60000;

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

// منع إعادة إرسال الرد نفسه بسبب تغيّرات DOM المتكررة.
// يُعاد ضبطه عند اكتشاف رسالة مستخدم جديدة.
var lastSentResponseSignature = "";
var lastSentResponseAt = 0;

function startResponseCapture(reason) {
captureActive = true;
captureFinished = false;
lastCandidate = "";
stableSince = 0;
captureStartedAt = Date.now();
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

  // لا تعِد تهيئة دورة التقاط بدأت للتو.
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

function finishCapture(answer) {
if (captureFinished) return;

if (  
  CURRENT_PLATFORM.id === "arena" &&  
  isArenaRejectedCandidate(answer)  
) {  
  log("⚠️ FinishCapture blocked (Arena rejected candidate)");  
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

// لا نرسل النص ذاته مرتين ضمن دورة الالتقاط نفسها.  
// تسمح رسالة مستخدم جديدة بإرسال جواب مماثل مرة أخرى.  
if (  
  signature &&  
  signature === lastSentResponseSignature &&  
  now - lastSentResponseAt < 30000  
) {  
  captureFinished = true;  
  captureActive = false;  
  log("ℹ️ Duplicate automatic response suppressed");  
  return;  
}  

captureFinished = true;  
captureActive = false;  
lastSentResponseSignature = signature;  
lastSentResponseAt = now;  

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
  CURRENT_PLATFORM.id === "arena" &&  
  current &&  
  isArenaRejectedCandidate(current)  
) {  
  setTimeout(watchForResponse, 900);  
  return;  
}  

if (!current) {  
  setTimeout(watchForResponse, 1000);  
  return;  
}  

// لا نلتقط الرد السابق إذا لم يتغير محتواه.  
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

// فرز حسب ترتيب ظهور العناصر في DOM، لا حسب ترتيب المحددات.  
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

// تحديث الحالة حتى لا نعيد معالجة العنصر نفسه.  
lastObservedUserElement = latest;  
lastObservedUserSignature = signature;  
lastObservedUserCount = count;  

if (!elementChanged && !textChanged && !countIncreased) return;  

// لا نتعامل مع سياق التطبيق المحقون على أنه سؤال مستقل.  
if (looksLikeInjectedPrompt(text)) {  
  log("ℹ️ Automatic capture skipped: injected prompt detected");  
  return;  
}  

// الرسالة الجديدة تبدأ دورة جديدة؛ يسمح هذا بحفظ ردين  
// متطابقين إذا كان كل منهما جوابًا عن سؤال مستقل.  
lastSentResponseSignature = "";  
lastSentResponseAt = 0;  

log(  
  "👤 New website user message detected" +  
  " platform=" + CURRENT_PLATFORM.id +  
  " len=" + text.length  
);  

// عند إرسال الرسائل من التطبيق، يبدأ الالتقاط قبل النقر على  
// زر الإرسال. لا نعيد ضبط تلك الدورة عندما تظهر الرسالة في DOM.  
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

// تسجيل حالة الصفحة الحالية قبل بدء المراقبة، لتجنب اعتبار  
// الرسائل والردود القديمة رسائل جديدة عند فتح الموقع.  
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

  // الإرسال عن طريق Enter من مربع الكتابة.
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

  // الإرسال بالنقر على زر الإرسال.
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

  // دعم النماذج التي ترسل السؤال عبر submit.
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
      (String(injectedText || "").indexOf(HIDDEN_MARKER) !== -1  
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
      if (!injectText(input, text)) {  
        return sendContextWritten(  
          false,  
          contextId,  
          "inject_failed",  
          "injectText returned false"  
        );  
      }  

      sendContextWritten(true, contextId, "text_injected", "ok");  

      // Arena: كتابة النص فقط دون إرساله.  
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

      // يبدأ الالتقاط قبل الإرسال كما في الإصدار السابق.  
      startResponseCapture("app_injected_prompt");  

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
