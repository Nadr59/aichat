(function () {

    // ── تجنب الحقن المزدوج ────────────────────────────────────────────
    if (window.__aiCaptureActive) return;
    window.__aiCaptureActive = true;

    var lastSentText  = '';
    var debounceTimer = null;
    var MIN_LENGTH    = 80;
    var DEBOUNCE_MS   = 1800;  // انتظر توقف الكتابة

    // ── Selectors لكل منصة ───────────────────────────────────────────
    var PLATFORM_SELECTORS = {
        'claude.ai':               "[data-is-streaming='false'] .prose p",
        'chatgpt.com':             "[data-message-author-role='assistant'] .markdown p",
        'chat.openai.com':         "[data-message-author-role='assistant'] .markdown p",
        'perplexity.ai':           ".prose p",
        'you.com':                 "[data-testid='youchat-response'] p",
        'huggingface.co':          "[data-role='assistant'] p",
        'venice.ai':               ".message-content p",
        'gemini.google.com':       "message-content p",
        'grok.com':                ".message-bubble p",
        'copilot.microsoft.com':   "[data-content='ai-response'] p",
        'chat.mistral.ai':         ".assistant-message p"
    };

    // ── اختيار selector المناسب ───────────────────────────────────────
    function getSelector() {
        var host = window.location.hostname;
        for (var domain in PLATFORM_SELECTORS) {
            if (host.includes(domain)) {
                return PLATFORM_SELECTORS[domain];
            }
        }
        // Fallback عام
        return [
            "[data-is-streaming='false']",
            "[data-message-author-role='assistant']",
            ".prose p",
            ".markdown p",
            ".message-content p"
        ].join(', ');
    }

    // ── استخراج النص ─────────────────────────────────────────────────
    function extractLatestResponse() {
        var selector = getSelector();

        try {
            var elements = document.querySelectorAll(selector);
            if (elements && elements.length > 0) {
                // اجمع آخر مجموعة فقرات
                var texts = [];
                var last  = elements[elements.length - 1];

                // ارجع للأب للحصول على الرد كاملاً
                var parent = last.closest(
                    "[data-is-streaming='false'], " +
                    "[data-message-author-role='assistant'], " +
                    ".message-content, .prose, .markdown"
                ) || last.parentElement;

                if (parent) {
                    var text = (parent.innerText || '').trim();
                    if (text.length >= MIN_LENGTH) return text;
                }

                // fallback: آخر عنصر فقط
                var text = (last.innerText || '').trim();
                if (text.length >= MIN_LENGTH) return text;
            }
        } catch (e) {}

        // ── Generic fallback ─────────────────────────────────────────
        return extractGeneric();
    }

    function extractGeneric() {
        var candidates = document.querySelectorAll(
            'p, [class*="message"], [class*="response"], [class*="answer"], [class*="assistant"]'
        );

        var longest = '';
        candidates.forEach(function (el) {
            var t = (el.innerText || '').trim();
            if (t.length > longest.length && t.length >= MIN_LENGTH) {
                longest = t;
            }
        });

        return longest;
    }

    // ── إرسال لـ Kotlin عبر browser.runtime ──────────────────────────
    function sendToKotlin(text) {
        if (!text || text.length < MIN_LENGTH) return;
        if (text === lastSentText) return;

        lastSentText = text;

        try {
            browser.runtime.sendMessage({
                type:    'AI_RESPONSE',
                text:    text.substring(0, 3000),
                url:     window.location.href,
                domain:  window.location.hostname
            });
        } catch (e) {
            // GeckoView قد لا يدعم browser.runtime في بعض الإعدادات
            console.warn('AI Capture: sendMessage failed', e);
        }
    }

    // ── مراقبة الصفحة ────────────────────────────────────────────────
    var observer = new MutationObserver(function () {
        clearTimeout(debounceTimer);
        debounceTimer = setTimeout(function () {
            var text = extractLatestResponse();
            sendToKotlin(text);
        }, DEBOUNCE_MS);
    });

    // بدء المراقبة عندما يكون document.body جاهزاً
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

})();
// ✅ أضف هذا في نهاية content.js
// يستقبل حدث من javascript: URI

window.addEventListener('AiChatCapture', (event) => {
    if (event?.detail?.type === 'CAPTURE_NOW') {
        const text = extractLatestResponse();

        browser.runtime.sendMessage({
            type:    'CAPTURE_RESULT',
            success: !!text && text.length >= 20,
            text:    text || '',
            domain:  location.hostname,
            source:  'manual'
        }).catch(() => {});
    }
});
