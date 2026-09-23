(function () {

    if (window !== window.top) return;
    if (window.__aiCaptureActive) return;
    window.__aiCaptureActive = true;

    // ✅ إرسال نبضة تحقق فورية — تظهر كـ Toast في التطبيق
    try {
        browser.runtime.sendMessage({
            type: 'DEBUG_INFO',
            info: 'SCRIPT_LOADED v1.1.0 on ' + location.hostname
        });
    } catch (e) {}

    

    var lastSentText     = '';
    var debounceTimer     = null;
    var DEBOUNCE_MS       = 1000;
    var STABLE_REQUIRED   = 2;      // عدد الفحوصات المتتالية المتطابقة
    var MIN_LEN           = 30;
    var MAX_LEN           = 3000;
    var contextBusy        = false;

    var stableCheckText  = '';
    var stableCheckCount = 0;

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

    // ── فلتر: رفض محتوى واجهة الموقع (Sidebar / Login / etc) ──────────

    function looksLikeUiJunk(text) {
        var junkPhrases = [
            'تسجيل الدخول', 'المكونات الإضافية', 'تعرف على الخطط والأسعار',
            'البحث التفصيلي', 'الدردشة مع', 'أنت قلت', 'قال ChatGPT',
            'قم بتسجيل الدخول', 'احصل على إجابات مصممة',
            'Log in', 'Sign up', 'Upgrade to Plus', 'New chat',
            'Settings', 'Help & FAQ'
        ];
        var matches = 0;
        for (var i = 0; i < junkPhrases.length; i++) {
            if (text.indexOf(junkPhrases[i]) !== -1) matches++;
            if (matches >= 2) return true;
        }
        return false;
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

    // ✅ الاحتياط الخاص بـ ChatGPT — يجب أن يبقى
    var articles = document.querySelectorAll(
        'article[data-testid^="conversation-turn"]'
    );
    for (var j = articles.length - 1; j >= 0; j--) {
        if (articles[j].querySelector('[data-message-author-role="user"]')) continue;
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
        var isKnownPlatform = false;

        if (/chatgpt\.com|openai\.com/.test(host)) {
            text = extractChatGPT();
            isKnownPlatform = true;
        } else if (/claude\.ai/.test(host)) {
            text = extractClaude();
            isKnownPlatform = true;
        } else if (/gemini\.google\.com/.test(host)) {
            text = extractGemini();
            isKnownPlatform = true;
        }

        // ✅ الاحتياط فقط للمنصات غير المعروفة
        if (!text && !isKnownPlatform) {
            text = extractByLongestBlock();
        }

        if (!text) return null;
        if (looksLikeUiJunk(text)) return null;   // ✅ رفض محتوى الواجهة

        return text.substring(0, MAX_LEN);
    }

    // ── إرسال تلقائي (بعد التحقق من الاستقرار) ──────────────────────

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

    // ── فحص الاستقرار: لا يُرسل النص إلا إذا بقي ثابتاً مرتين متتاليتين ──

    function checkStability() {
        var currentText = extractLatestResponse();

        if (!currentText) {
            stableCheckText  = '';
            stableCheckCount = 0;
            return;
        }

        if (currentText === stableCheckText) {
            stableCheckCount++;
            if (stableCheckCount >= STABLE_REQUIRED) {
                sendAutoToKotlin(currentText);
                stableCheckCount = 0;
            } else {
                // أعد الفحص بعد فترة أخرى للتأكد من الاستقرار الكامل
                debounceTimer = setTimeout(checkStability, DEBOUNCE_MS);
            }
        } else {
            stableCheckText  = currentText;
            stableCheckCount = 1;
            debounceTimer = setTimeout(checkStability, DEBOUNCE_MS);
        }
    }

    // ── مراقبة تلقائية ────────────────────────────────────────────────

    var observer = new MutationObserver(function () {
        clearTimeout(debounceTimer);
        debounceTimer = setTimeout(checkStability, DEBOUNCE_MS);
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
                    articles: document.querySelectorAll('article').length,
                    bodyLen:  document.body
                              ? document.body.innerText.length : 0
                }
            });

        }).catch(function () {});

    }, 1000);

    // ── Polling — GET_CONTEXT كل ثانية ───────────────────────────────

    setInterval(function () {

        if (document.visibilityState !== 'visible') return;
        if (contextBusy) return;

        browser.runtime.sendMessage({
            type:   'GET_CONTEXT',
            domain: location.hostname
        }).then(function (response) {

            if (!response || !response.hasContext) return;
            var context = response.context || '';
            if (!context) return;

            contextBusy = true;
            writeAndSend(context);

        }).catch(function () {});

    }, 1000);

    // ── إيجاد صندوق الإدخال ──────────────────────────────────────────

    function findInputBox() {
        var host = location.hostname;

        if (/chatgpt\.com|openai\.com/.test(host)) {
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

    // ── الكتابة في صندوق الإدخال ─────────────────────────────────────

    function writeToInputBox(text) {
        var input = findInputBox();
        if (!input) return { ok: false, stage: 'find=NULL' };

        if (input.tagName === 'TEXTAREA' || input.tagName === 'INPUT') {
            try {
                input.focus();
                var setter = Object.getOwnPropertyDescriptor(
                    window.HTMLTextAreaElement.prototype, 'value'
                ).set;
                setter.call(input, text);
                input.dispatchEvent(new Event('input',  { bubbles: true }));
                input.dispatchEvent(new Event('change', { bubbles: true }));
                return { ok: true, stage: 'textarea' };
            } catch (e) {
                return { ok: false, stage: 'textarea-err=' + e.message };
            }
        }

        input.focus();
        var activeOk = document.activeElement === input
                    || input.contains(document.activeElement);
        if (!activeOk) return { ok: false, stage: 'focus-failed' };

        try {
            var sel = window.getSelection();
            sel.removeAllRanges();
            var range = document.createRange();
            range.selectNodeContents(input);
            range.collapse(false);
            sel.addRange(range);
        } catch (e) {
            return { ok: false, stage: 'caret-err=' + e.message };
        }

        var done = document.execCommand('insertText', false, text);
        if (!done) return { ok: false, stage: 'execCommand=false' };

        var preview = text.slice(0, 20);
        var content = input.textContent || input.innerText || '';
        if (!content.includes(preview)) {
            return { ok: false, stage: 'reverted' };
        }

        return { ok: true, stage: 'contenteditable' };
    }

    // ── إيجاد زر الإرسال ─────────────────────────────────────────────

    function findSendButton() {
        var host = location.hostname;

        if (/chatgpt\.com/.test(host)) {
            return document.querySelector('button[aria-label="إرسال رسالة"]')
                || document.querySelector('button[aria-label="Send prompt"]')
                || document.querySelector('[data-testid="send-button"]')
                || document.querySelector('button[type="submit"]');
        }
        if (/gemini\.google\.com/.test(host)) {
            return document.querySelector('button[aria-label="إرسال الرسالة"]')
                || document.querySelector('button[aria-label="Send message"]')
                || document.querySelector('button[type="submit"]');
        }
        if (/claude\.ai/.test(host)) {
            return document.querySelector('button[aria-label="Send Message"]')
                || document.querySelector('button[type="submit"]');
        }
        return document.querySelector('button[type="submit"]')
            || document.querySelector('button[aria-label*="إرسال" i]')
            || document.querySelector('button[aria-label*="send" i]');
    }

    // ── ضغط زر الإرسال ───────────────────────────────────────────────

    function clickSendButton() {
        var attempts = 0;
        var interval = setInterval(function () {
            attempts++;
            var btn = findSendButton();
            if (btn && !btn.disabled) {
                clearInterval(interval);
                btn.click();
                browser.runtime.sendMessage({
                    type:   'CONTEXT_WRITTEN',
                    stage:  'clicked',
                    detail: '',
                    domain: location.hostname
                }).catch(function () {});
                contextBusy = false;
            } else if (attempts >= 15) {
                clearInterval(interval);
                var input = findInputBox();
                if (input) {
                    input.dispatchEvent(new KeyboardEvent('keydown', {
                        key: 'Enter', keyCode: 13,
                        bubbles: true, composed: true
                    }));
                }
                browser.runtime.sendMessage({
                    type:   'CONTEXT_WRITTEN',
                    stage:  'enter_pressed',
                    detail: '',
                    domain: location.hostname
                }).catch(function () {});
                contextBusy = false;
            }
        }, 200);
    }

    // ── تنفيذ الكتابة والإرسال ────────────────────────────────────────

    function writeAndSend(context) {
        var result = writeToInputBox(context);

        browser.runtime.sendMessage({
            type: 'DEBUG_INFO',
            info: 'write: ok=' + result.ok + ' stage=' + result.stage
        }).catch(function () {});

        if (!result.ok) {
            browser.runtime.sendMessage({
                type:   'CONTEXT_WRITTEN',
                stage:  'write_failed',
                detail: result.stage,
                domain: location.hostname
            }).catch(function () {});
            contextBusy = false;
            return;
        }

        setTimeout(clickSendButton, 600);
    }

    // ── تنظيف ─────────────────────────────────────────────────────────

    window.addEventListener('pagehide', function () {
        observer.disconnect();
    });

})();
