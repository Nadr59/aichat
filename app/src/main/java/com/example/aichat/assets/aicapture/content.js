(function () {

    if (window.__aiCaptureActive) return;
    window.__aiCaptureActive = true;

    var lastSentText = '';
    var debounceTimer = null;
    var MIN_AUTO = 80;   // للتلقائي
    var MIN_MANUAL = 20; // لليدوي — أقل تشدداً
    var DEBOUNCE_MS = 1800;

    // ── Selectors 2024 ────────────────────────────────────────────────
    var PLATFORM_SELECTORS = {
        'chatgpt.com': [
            'article[data-testid^="conversation-turn-"] .agent-turn .whitespace-pre-wrap',
            'article[data-testid^="conversation-turn-"] .agent-turn',
            '[data-message-author-role="assistant"] .markdown',
            '[data-message-author-role="assistant"]',
            '.agent-turn'
        ],
        'chat.openai.com': [
            'article[data-testid^="conversation-turn-"] .agent-turn',
            '[data-message-author-role="assistant"] .markdown',
            '[data-message-author-role="assistant"]'
        ],
        'claude.ai': [
            '[data-is-streaming="false"] .font-claude-message',
            '.font-claude-message',
            '[data-is-streaming="false"] .prose',
            '.prose'
        ],
        'gemini.google.com': [
            'model-response .markdown',
            'model-response',
            '.response-content'
        ],
        'perplexity.ai': [
            '.prose',
            '[class*="answer"]',
            '[class*="response"]'
        ],
        'copilot.microsoft.com': [
            '[data-content="ai-response"]',
            '.ac-textBlock',
            '[class*="response"]'
        ],
        'grok.com': [
            '.message-bubble',
            '[class*="response"]',
            '[class*="assistant"]'
        ],
        'chat.mistral.ai': [
            '.assistant-message',
            '[class*="assistant"]'
        ],
        'you.com': [
            '[data-testid="youchat-response"]',
            '[class*="answer"]'
        ]
    };

    // ── الاستخراج الرئيسي ─────────────────────────────────────────────
    function extractLatestResponse(minLength) {
        minLength = minLength || MIN_AUTO;
        var host  = window.location.hostname.replace('www.', '');

        // 1. جرب selectors المنصة
        for (var domain in PLATFORM_SELECTORS) {
            if (host.includes(domain)) {
                var result = trySelectors(PLATFORM_SELECTORS[domain], minLength);
                if (result) return result;
                break;
            }
        }

        // 2. Fallback ذكي
        return smartFallback(minLength);
    }

    function trySelectors(selectors, minLength) {
        for (var i = 0; i < selectors.length; i++) {
            var result = trySelector(selectors[i], minLength);
            if (result) return result;
        }
        return null;
    }

    function trySelector(selector, minLength) {
        try {
            var elements = document.querySelectorAll(selector);
            if (!elements || elements.length === 0) return null;
            // من الآخر للأول
            for (var i = elements.length - 1; i >= 0; i--) {
                var text = (elements[i].innerText || '').trim();
                if (text.length >= minLength) return text;
            }
        } catch(e) {}
        return null;
    }

    // ── Fallback ذكي — يأخذ أطول نص ─────────────────────────────────
    function smartFallback(minLength) {
        var allElements = document.querySelectorAll('*');
        var candidates  = [];

        for (var i = 0; i < allElements.length; i++) {
            var el = allElements[i];

            // تجاهل العناصر الأب الكبيرة
            if (el.children.length > 15) continue;

            // تجاهل عناصر الإدخال
            var tag = el.tagName.toLowerCase();
            if (['script','style','input','textarea','nav',
                 'header','footer'].includes(tag)) continue;

            var text = (el.innerText || '').trim();
            if (text.length >= minLength) {
                candidates.push({
                    el:   el,
                    text: text,
                    len:  text.length
                });
            }
        }

        if (candidates.length === 0) return null;

        // رتب — الأطول أولاً، لكن تجنب النص الضخم جداً (صفحة كاملة)
        candidates.sort(function(a, b) { return b.len - a.len; });

        // خذ أول نص بين 100 و 5000 حرف
        for (var j = 0; j < candidates.length; j++) {
            var len = candidates[j].len;
            if (len >= minLength && len <= 5000) {
                return candidates[j].text;
            }
        }

        // إذا لم نجد، خذ الأطول
        return candidates[0].text.substring(0, 3000);
    }

    // ── إرسال تلقائي ──────────────────────────────────────────────────
    function sendAutoToKotlin(text) {
        if (!text || text.length < MIN_AUTO) return;
        if (text === lastSentText) return;
        lastSentText = text;
        try {
            browser.runtime.sendMessage({
                type:   'AI_RESPONSE',
                text:   text.substring(0, 3000),
                url:    window.location.href,
                domain: window.location.hostname
            });
        } catch (e) {}
    }

    // ── مراقبة تلقائية ────────────────────────────────────────────────
    var observer = new MutationObserver(function() {
        clearTimeout(debounceTimer);
        debounceTimer = setTimeout(function() {
            sendAutoToKotlin(extractLatestResponse(MIN_AUTO));
        }, DEBOUNCE_MS);
    });

    function startObserver() {
        if (document.body) {
            observer.observe(document.body, {
                childList:     true,
                subtree:       true,
                characterData: true
            });
        } else {
            setTimeout(startObserver, 500);
        }
    }
    startObserver();

    // ── Port — للحفظ اليدوي ───────────────────────────────────────────
    var activePort = null;

    function connectPort() {
        try {
            activePort = browser.runtime.connectNative('browser');

            activePort.onMessage.addListener(function(message) {
                if (!message || message.type !== 'CAPTURE_NOW') return;

                // ✅ للحفظ اليدوي نستخدم حد أدنى أقل
                var text = extractLatestResponse(MIN_MANUAL);
                var ok   = !!(text && text.length >= MIN_MANUAL);

                activePort.postMessage({
                    type:    'CAPTURE_RESULT',
                    success: ok,
                    text:    ok ? text.substring(0, 3000) : '',
                    domain:  window.location.hostname,
                    source:  'manual'
                });
            });

            activePort.onDisconnect.addListener(function() {
                activePort = null;
                setTimeout(connectPort, 3000);
            });

        } catch (e) {
            setTimeout(connectPort, 5000);
        }
    }

    connectPort();

})();
