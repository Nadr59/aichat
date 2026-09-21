(function () {

    if (window !== window.top) return;
    if (window.__aiCaptureActive) return;
    window.__aiCaptureActive = true;

    var lastSentText  = '';
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

        if (/chatgpt\.com|openai\.com/.test(host)) text = extractChatGPT();
        else if (/claude\.ai/.test(host))          text = extractClaude();
        else if (/gemini\.google\.com/.test(host)) text = extractGemini();

        if (!text) text = extractByLongestBlock();
        return text ? text.substring(0, MAX_LEN) : null;
    }

    // ── إيجاد صندوق الإدخال ──────────────────────────────────────────

    function findInputBox() {
        var host = location.hostname;

        if (/chatgpt\.com/.test(host)) {
            return document.querySelector('#prompt-textarea')
                || document.querySelector('[contenteditable="true"]');
        }
        if (/claude\.ai/.test(host)) {
            return document.querySelector('.ProseMirror')
                || document.querySelector('[contenteditable="true"]');
        }
        if (/gemini\.google\.com/.test(host)) {
            return document.querySelector('.ql-editor')
                || document.querySelector('[contenteditable="true"]');
        }

        return document.querySelector('textarea')
            || document.querySelector('[contenteditable="true"]');
    }

    // ── إيجاد زر الإرسال ─────────────────────────────────────────────

    function findSendButton() {
        var host = location.hostname;

        if (/chatgpt\.com/.test(host)) {
            return document.querySelector('[data-testid="send-button"]')
                || document.querySelector('button[aria-label="Send prompt"]')
                || document.querySelector('button[aria-label="إرسال الرسالة"]');
        }
        if (/claude\.ai/.test(host)) {
            return document.querySelector('button[aria-label="Send Message"]')
                || document.querySelector('button[type="submit"]');
        }
        if (/gemini\.google\.com/.test(host)) {
            return document.querySelector('button.send-button')
                || document.querySelector('button[aria-label="Send message"]');
        }
        if (/perplexity\.ai/.test(host)) {
            return document.querySelector('button[aria-label="Submit"]')
                || document.querySelector('button[type="submit"]');
        }

        return document.querySelector('button[type="submit"]')
            || document.querySelector('button[aria-label*="send" i]')
            || document.querySelector('button[aria-label*="إرسال" i]');
    }

    // ── كتابة النص في صندوق الإدخال ──────────────────────────────────

    function writeToInputBox(text) {
        var input = findInputBox();
        if (!input) return false;

        try {
            input.focus();

            if (input.tagName === 'TEXTAREA' || input.tagName === 'INPUT') {
                var nativeInputValueSetter = Object.getOwnPropertyDescriptor(
                    window.HTMLTextAreaElement.prototype, 'value'
                ).set;
                nativeInputValueSetter.call(input, text);
                input.dispatchEvent(new Event('input',  { bubbles: true }));
                input.dispatchEvent(new Event('change', { bubbles: true }));
            } else {
                input.focus();
                document.execCommand('selectAll', false, null);
                document.execCommand('insertText', false, text);
            }

            return true;

        } catch (e) {
            console.error('[AiChat] ❌ خطأ في الكتابة:', e);
            return false;
        }
    }

    // ── ضغط زر الإرسال ───────────────────────────────────────────────

    function clickSendButton() {
        var btn = findSendButton();
        if (btn) {
            btn.click();
            return;
        }
        // بديل: Enter
        var input = findInputBox();
        if (input) {
            input.dispatchEvent(new KeyboardEvent('keydown', {
                key:      'Enter',
                code:     'Enter',
                keyCode:  13,
                bubbles:  true,
                composed: true
            }));
        }
    }

    // ── طلب السياق من Kotlin ──────────────────────────────────────────

    function requestContext() {
        browser.runtime.sendMessage({
            type:   'GET_CONTEXT',
            domain: location.hostname
        }).then(function (response) {

            if (!response || !response.hasContext) return;
            if (!response.context || !response.context.trim()) return;

            console.log('[AiChat] 📥 استُلم السياق من Kotlin');

            // ── رسالة كاملة = سياق + طلب تأكيد ──
            var confirmMsg =
                'اقرأ السياق أعلاه.\n' +
                'ثم أجب بجملة واحدة فقط:\n' +
                '"فهمت السياق — أنا جاهز لأسئلتك."';

            var fullMessage = response.context
                + '\n\n---\n'
                + confirmMsg;

            var attempts = 0;
            var interval = setInterval(function () {
                attempts++;

                var ok = writeToInputBox(fullMessage);

                if (ok) {
                    clearInterval(interval);
                    // انتظر ثانية ثم أرسل
                    setTimeout(clickSendButton, 1000);
                    console.log('[AiChat] ✅ تم إرسال السياق');

                } else if (attempts >= 10) {
                    clearInterval(interval);
                    console.warn('[AiChat] ⚠️ فشل الكتابة بعد 10 محاولات');
                }

            }, 500);

        }).catch(function () {});
    }

    // ── كشف أزرار الإرسال وإرسالها لـ Kotlin ────────────────────────

function detectAndReportButtons() {
    var buttons  = document.querySelectorAll('button');
    var visible  = [];

    buttons.forEach(function(btn) {
        if (!isVisible(btn)) return;

        var label  = btn.getAttribute('aria-label') || '';
        var testid = btn.getAttribute('data-testid') || '';
        var type   = btn.getAttribute('type') || '';

        // فقط الأزرار التي تبدو أزرار إرسال
        var isSend = label.toLowerCase().includes('send')
            || label.includes('إرسال')
            || label.includes('Submit')
            || testid.toLowerCase().includes('send')
            || type === 'submit';

        if (isSend || label || testid) {
            visible.push({
                label:  label,
                testid: testid,
                type:   type
            });
        }
    });

    // أرسل النتيجة لـ Kotlin ليعرضها Toast
    browser.runtime.sendMessage({
        type:    'DEBUG_BUTTONS',
        buttons: visible,
        domain:  location.hostname
    });
}

// استدعِها بعد ثانيتين من تحميل الصفحة
setTimeout(detectAndReportButtons, 2000);

    // ── إرسال تلقائي ──────────────────────────────────────────────────

    function sendAutoToKotlin(text) {
        if (!text || text.length < 80) return;
        if (text === lastSentText)     return;
        lastSentText = text;
        try {
            browser.runtime.sendMessage({
                type:   'AI_RESPONSE',
                text:   text,
                domain: location.hostname
            });
        } catch (e) {}
    }

    // ── مراقبة تلقائية ────────────────────────────────────────────────

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

    // ── Polling — CHECK_CAPTURE كل ثانية ─────────────────────────────

    setInterval(function () {
        if (document.visibilityState !== 'visible') return;

        browser.runtime.sendMessage({
            type:   'CHECK_CAPTURE',
            domain: location.hostname
        }).then(function (response) {

            if (!response || !response.capture) return;

            var text = extractLatestResponse();
            var ok   = !!(text && text.length >= MIN_LEN);

            browser.runtime.sendMessage({
                type:    'CAPTURE_RESULT',
                success: ok,
                text:    ok ? text : '',
                domain:  location.hostname,
                debug: {
                    assistant: document.querySelectorAll(
                        '[data-message-author-role="assistant"]'
                    ).length,
                    articles:  document.querySelectorAll('article').length,
                    bodyLen:   document.body
                               ? document.body.innerText.length : 0
                }
            });

        }).catch(function () {});

    }, 1000);

    // ── طلب السياق عند بدء التشغيل ───────────────────────────────────

    setTimeout(requestContext, 1500);

    // ── تنظيف ─────────────────────────────────────────────────────────

    window.addEventListener('pagehide', function () {
        observer.disconnect();
    });

})();
