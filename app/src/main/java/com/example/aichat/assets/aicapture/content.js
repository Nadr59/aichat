(function () {

    if (window.__aiCaptureActive) return;
    window.__aiCaptureActive = true;

    var lastSentText  = '';
    var debounceTimer = null;
    var MIN_LENGTH    = 20;   // ✅ خفضنا من 80 إلى 20 للتشخيص
    var DEBOUNCE_MS   = 1800;

    // ── Selectors محدثة 2024 ──────────────────────────────────────────
    var PLATFORM_SELECTORS = {

        // ChatGPT — محدث 2024
        'chatgpt.com': [
            '[data-message-author-role="assistant"] .markdown',
            '[data-message-author-role="assistant"]',
            '.agent-turn .whitespace-pre-wrap',
            'div[class*="markdown"]',
            '.text-message'
        ],

        'chat.openai.com': [
            '[data-message-author-role="assistant"] .markdown',
            '[data-message-author-role="assistant"]',
            '.agent-turn .whitespace-pre-wrap'
        ],

        // Claude — محدث 2024
        'claude.ai': [
            '[data-is-streaming="false"] .prose',
            '.font-claude-message',
            '[data-testid="assistant-message"]',
            '.prose'
        ],

        'gemini.google.com': [
            'model-response .markdown',
            'model-response',
            '.response-content'
        ],

        'perplexity.ai': [
            '.prose',
            '[class*="answer"]'
        ],

        'copilot.microsoft.com': [
            '[data-content="ai-response"]',
            '.ac-textBlock'
        ],

        'grok.com': [
            '.message-bubble',
            '[class*="response"]'
        ],

        'chat.mistral.ai': [
            '.assistant-message',
            '[class*="assistant"]'
        ]
    };

    // ── استخراج النص ─────────────────────────────────────────────────
    function extractLatestResponse() {
        var host      = window.location.hostname.replace('www.', '');
        var selectors = null;

        // ابحث عن selectors المنصة
        for (var domain in PLATFORM_SELECTORS) {
            if (host.includes(domain)) {
                selectors = PLATFORM_SELECTORS[domain];
                break;
            }
        }

        // جرب كل selector بالترتيب
        if (selectors) {
            for (var i = 0; i < selectors.length; i++) {
                var result = trySelector(selectors[i]);
                if (result) return result;
            }
        }

        // Fallback عام
        return extractGeneric();
    }

    function trySelector(selector) {
        try {
            var elements = document.querySelectorAll(selector);
            if (!elements || elements.length === 0) return null;

            // جرب من آخر عنصر للأول
            for (var i = elements.length - 1; i >= 0; i--) {
                var text = (elements[i].innerText || '').trim();
                if (text.length >= MIN_LENGTH) return text;
            }
        } catch(e) {}
        return null;
    }

    function extractGeneric() {
        // ✅ استراتيجية جديدة — ابحث عن أطول نص في الصفحة
        // من العناصر التي تبدو ردوداً
        var candidates = document.querySelectorAll(
            '[class*="assistant"], [class*="bot"], [class*="ai-"],' +
            '[class*="response"], [class*="answer"], [class*="message"],' +
            '[role="article"], article, .prose, .markdown'
        );

        var best = '';
        candidates.forEach(function(el) {
            // تجنب العناصر الأب التي تحتوي على كل شيء
            if (el.children.length > 10) return;
            var t = (el.innerText || '').trim();
            if (t.length > best.length && t.length >= MIN_LENGTH) {
                best = t;
            }
        });

        return best.length >= MIN_LENGTH ? best : null;
    }

    // ── إرسال تلقائي ──────────────────────────────────────────────────
    function sendAutoToKotlin(text) {
        if (!text || text.length < MIN_LENGTH) return;
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
            sendAutoToKotlin(extractLatestResponse());
        }, DEBOUNCE_MS);
    });

    function startObserver() {
        if (document.body) {
            observer.observe(document.body, {
                childList: true, subtree: true, characterData: true
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

                var text = extractLatestResponse();
                var ok   = !!(text && text.length >= MIN_LENGTH);

                // ✅ سجّل في console للتشخيص
                console.log('[AiChat] CAPTURE_NOW received');
                console.log('[AiChat] extracted text:', text ? text.substring(0, 100) : 'NULL');
                console.log('[AiChat] success:', ok);

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

            console.log('[AiChat] Port connected ✅');

        } catch (e) {
            console.warn('[AiChat] connectNative failed:', e);
            setTimeout(connectPort, 5000);
        }
    }

    connectPort();

})();
