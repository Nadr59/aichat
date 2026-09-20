"use strict";

(function () {

// ⛔ منع الحقن المزدوج
    if (window !== window.top)    return;
    if (window.__aiCaptureActive) return;
    window.__aiCaptureActive = true;

var NATIVE_APP     = "browser";
    var lastSentText   = "";
    var debounceTimer  = null;
    var lastCaptureKey = "";
    var DEBOUNCE_MS    = 1800;
    var MIN_LEN        = 30;
    var MAX_LEN        = 3000;
    var checkCount     = 0;

// ═══ Logging — يظهر في logcat عبر ConsoleDelegate ═══

function log() {
        try {
            var args = Array.prototype.slice.call(arguments);
            args.unshift("[AiChat]");
            console.log.apply(console, args);
        } catch (e) {}
    }

function logError(tag, e) {
        try {
            console.error("[AiChat] " + tag + ":", e);
        } catch (err) {}
    }

// ═══ القناة المباشرة لـ Kotlin — بدون background.js إطلاقاً ═══
    // بفضل صلاحية nativeMessagingFromContent في manifest
    // هذه الرسائل تصل مباشرة إلى ext.setMessageDelegate(delegate, "browser")

function sendToNative(payload) {
        return browser.runtime.sendNativeMessage(NATIVE_APP, payload);
    }

// ═══ Helpers ═══

function clean(t) {
        return (t || "")
            .replace(/\u200b/g, "")
            .replace(/[ \t]+\n/g, "\n")
            .trim();
    }

function isVisible(el) {
        if (!el) return false;
        var r  = el.getBoundingClientRect();
        if (r.width === 0 && r.height === 0) return false;
        var cs = window.getComputedStyle(el);
        return cs.display !== "none" && cs.visibility !== "hidden";
    }

function lastMatching(selector) {
        var els;
        try { els = document.querySelectorAll(selector); }
        catch (e) { return null; }
        for (var i = els.length - 1; i >= 0; i--) {
            var t = clean(els[i].innerText);
            if (t.length >= MIN_LEN) return t;
        }
        return null;
    }

// ═══ Extractors ═══

function extractChatGPT() {
        var turns = document.querySelectorAll(
            '[data-message-author-role="assistant"]'
        );
        for (var i = turns.length - 1; i >= 0; i--) {
            var turn = turns[i];
            if (turn.closest('[data-is-streaming="true"]')) continue;
            var inner = turn.querySelector(
                ".markdown, .prose, .whitespace-pre-wrap"
            ) || turn;
            var t = clean(inner.innerText);
            if (t.length >= MIN_LEN) return t;
        }
        var articles = document.querySelectorAll(
            'article[data-testid^="conversation-turn"]'
        );
        for (var j = articles.length - 1; j >= 0; j--) {
            if (articles[j].querySelector(
                '[data-message-author-role="user"]'
            )) continue;
            var t2 = clean(articles[j].innerText);
            if (t2.length >= MIN_LEN) return t2;
        }
        return null;
    }

function extractClaude() {
        return lastMatching('[data-is-streaming="false"] .font-claude-message')
            || lastMatching('.font-claude-message')
            || lastMatching('[data-is-streaming="false"]');
    }

function extractGemini() {
        return lastMatching('model-response .markdown')
            || lastMatching('message-content')
            || lastMatching('model-response');
    }

function extractByLongestBlock() {
        var EXCLUDE = [
            "nav", "aside", "header", "footer", "form",
            "textarea", "button", "input", "script", "style",
            '[role="navigation"]', '[role="banner"]',
            '[contenteditable="true"]'
        ].join(", ");

var blocks = document.querySelectorAll(
            "article, section, main div, p, li, pre, blockquote"
        );
        var best = "";
        for (var i = 0; i < blocks.length; i++) {
            var el = blocks[i];
            if (el.closest(EXCLUDE)) continue;
            if (!isVisible(el))      continue;
            if (el.querySelectorAll("p, li, pre").length > 60) continue;
            var t = clean(el.innerText);
            if (t.length > best.length) best = t;
        }
        return best.length >= MIN_LEN ? best : null;
    }

function extractLatestResponse() {
        var host = location.hostname;
        var text = null;

if (/chatgpt\.com|openai\.com/.test(host)) text = extractChatGPT();
        else if (/claude\.ai/.test(host))          text = extractClaude();
        else if (/gemini\.google\.com/.test(host))  text = extractGemini();

if (!text) text = extractByLongestBlock();
        return text ? text.substring(0, MAX_LEN) : null;
    }

function buildDebug() {
        try {
            return {
                host:      location.hostname,
                assistant: document.querySelectorAll(
                    '[data-message-author-role="assistant"]'
                ).length,
                articles:  document.querySelectorAll("article").length,
                bodyLen:   document.body
                    ? document.body.innerText.length : 0
            };
        } catch (e) {
            return { host: location.hostname, error: String(e) };
        }
    }

// ═══ 1) الإرسال التلقائي — مباشر لـ Kotlin ═══

function sendAutoToKotlin(text) {
        if (!text || text.length < 80) return;
        if (text === lastSentText)    return;
        lastSentText = text;

sendToNative({
            type:   "AI_RESPONSE",
            text:   text,
            domain: location.hostname
        }).then(function () {
            log("✅ auto response sent:", text.substring(0, 40) + "…");
        }).catch(function (e) {
            logError("AI_RESPONSE failed", e);
        });
    }

// ═══ 2) مراقبة تلقائية (MutationObserver) ═══

var observer = new MutationObserver(function () {
        clearTimeout(debounceTimer);
        debounceTimer = setTimeout(function () {
            sendAutoToKotlin(extractLatestResponse());
        }, DEBOUNCE_MS);
    });

function startObserver() {
        if (document.body) {
            observer.observe(document.body, {
                childList: true,
                subtree:   true
            });
            log("observer started on", location.hostname);
        } else {
            setTimeout(startObserver, 500);
        }
    }
    startObserver();

// ═══ 3) الحفظ اليدوي — المسار المباشر الجديد ═══
    //
    // القديم:  content → runtime.sendMessage → background → sendNativeMessage → Kotlin
    // الجديد:  content → sendNativeMessage → Kotlin ✅
    //
    // الرد يصل مباشرة في .then() — لا relay عبر background.

setInterval(function () {

if (document.visibilityState !== "visible") return;

checkCount++;
        if (checkCount % 15 === 1) {
            log("polling alive — check #" + checkCount);
        }

sendToNative({
            type:   "CHECK_CAPTURE",
            domain: location.hostname
        }).then(function (response) {

// ⚠️ الرد قد يصل نصاً إذا عاد Kotlin بـ String
            if (typeof response === "string" && response.length > 0) {
                try { response = JSON.parse(response); }
                catch (e) { response = { capture: false }; }
            }

if (!response || typeof response !== "object") {
                log("CHECK_CAPTURE: empty/invalid response:", response);
                return;
            }

if (!response.capture) {
                // الحالة الطبيعية — بدون تسجيل مزعج
                return;
            }

log("✅ CHECK_CAPTURE → capture=true — extracting…");

var text = extractLatestResponse();
            var ok   = !!(text && text.length >= MIN_LEN);

log(ok ? "✅ extracted " + text.length + " chars"
                   : "❌ extraction failed");

sendToNative({
                type:    "CAPTURE_RESULT",
                success: ok,
                text:    ok ? text : "",
                domain:  location.hostname,
                debug:   buildDebug()
            }).then(function () {
                log("✅ CAPTURE_RESULT sent");
            }).catch(function (e) {
                logError("CAPTURE_RESULT failed", e);
            });

}).catch(function (e) {
            // ⛔ لا تكتم الخطأ — هذا ما دفن المشكلة سابقاً
            logError("CHECK_CAPTURE failed", e);
        });

}, 1000);

// ═══ تنظيف ═══

window.addEventListener("pagehide", function () {
        observer.disconnect();
    });

log("content.js v1.1.0 loaded on", location.hostname);

})();
