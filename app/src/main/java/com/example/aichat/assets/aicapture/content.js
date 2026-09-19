(function () {

    if (window !== window.top) return;
    if (window.__aiCaptureActive) return;
    window.__aiCaptureActive = true;

    var lastSentText = '';
    var debounceTimer = null;
    var DEBOUNCE_MS   = 1800;
    var MIN_LEN       = 30;
    var MAX_LEN       = 3000;

    // ── Helpers ───────────────────────────────────────────────────────

    function clean(t) {
        return (t || '')
            .replace(/\u200b/g, '')
            .replace(/[ \t]+\n/g, '\n')
            .trim();
    }

    function isVisible(el) {
        if (!el) return false;
        var r  = el.getBoundingClientRect();
        if (r.width === 0 && r.height === 0) return false;
        var cs = window.getComputedStyle(el);
        return cs.display !== 'none' && cs.visibility !== 'hidden';
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

    // ── Extractors ────────────────────────────────────────────────────

    function extractChatGPT() {
        var turns = document.querySelectorAll(
            '[data-message-author-role="assistant"]'
        );
        for (var i = turns.length - 1; i >= 0; i--) {
            var turn = turns[i];
            if (turn.closest('[data-is-streaming="true"]')) continue;
            var inner = turn.querySelector(
                '.markdown, .prose, .whitespace-pre-wrap'
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
        return lastMatching(
                '[data-is-streaming="false"] .font-claude-message'
            )
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
            'nav', 'aside', 'header', 'footer', 'form',
            'textarea', 'button', 'input', 'script', 'style',
            '[role="navigation"]', '[role="banner"]',
            '[contenteditable="true"]'
        ].join(', ');
        var blocks = document.querySelectorAll(
            'article, section, main div, p, li, pre, blockquote'
        );
        var best = '';
        for (var i = 0; i < blocks.length; i++) {
            var el = blocks[i];
            if (el.closest(EXCLUDE))    continue;
            if (!isVisible(el))         continue;
            if (el.querySelectorAll('p, li, pre').length > 60) continue;
            var t = clean(el.innerText);
            if (t.length > best.length) best = t;
        }
        return best.length >= MIN_LEN ? best : null;
    }

    function extractLatestResponse() {
        var host = location.hostname;
        var text = null;
        if (/chatgpt\.com|openai\.com/.test(host))  text = extractChatGPT();
        else if (/claude\.ai/.test(host))           text = extractClaude();
        else if (/gemini\.google\.com/.test(host))  text = extractGemini();
        if (!text) text = extractByLongestBlock();
        return text ? text.substring(0, MAX_LEN) : null;
    }

    // ── إرسال تلقائي ──────────────────────────────────────────────────

    function sendAutoToKotlin(text) {
        if (!text || text.length < 80) return;
        if (text === lastSentText)     return;
        lastSentText = text;
        try {
            // ✅ sendNativeMessage وليس sendMessage
            browser.runtime.sendNativeMessage('browser', {
                type:   'AI_RESPONSE',
                text:   text,
                domain: location.hostname
            });
        } catch (e) {}
    }

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
        } else {
            setTimeout(startObserver, 500);
        }
    }
    startObserver();

    // ── ✅ Long-Polling — ينتظر Kotlin يوقظه ─────────────────────────

    function waitForCapture() {

        // لا تسأل إذا الصفحة مخفية
        if (document.visibilityState !== 'visible') {
            setTimeout(waitForCapture, 1000);
            return;
        }

        // ✅ sendNativeMessage — يصل لـ Kotlin مباشرة
        browser.runtime.sendNativeMessage('browser', {
            type:   'WAIT_CAPTURE',
            domain: location.hostname
        })
        .then(function (response) {

            if (response && response.capture) {
                // Kotlin أيقظنا — استخرج النص
                var text = extractLatestResponse();
                var ok   = !!(text && text.length >= MIN_LEN);

                // أرسل النتيجة
                return browser.runtime.sendNativeMessage('browser', {
                    type:    'CAPTURE_RESULT',
                    success: ok,
                    text:    ok ? text : '',
                    domain:  location.hostname,
                    debug: {
                        assistant: document.querySelectorAll(
                            '[data-message-author-role="assistant"]'
                        ).length,
                        articles:  document.querySelectorAll(
                            'article'
                        ).length,
                        bodyLen:   document.body
                                   ? document.body.innerText.length : 0
                    }
                });
            }
        })
        .catch(function () {})
        .then(function () {
            // ✅ أعد الانتظار دائماً
            setTimeout(waitForCapture, 300);
        });
    }

    // نظّف عند إغلاق الصفحة
    window.addEventListener('pagehide', function () {
        observer.disconnect();
    });

    waitForCapture();

})();
