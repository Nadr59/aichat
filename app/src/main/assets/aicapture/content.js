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
                || document.querySelector('rich-textarea')
                || document.querySelector('[contenteditable="true"]');
        }
        return document.querySelector('textarea')
            || document.querySelector('[contenteditable="true"]');
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

    // ── writeToInputBox ───────────────────────────────────────────────

    function writeToInputBox(text) {
        var input = findInputBox();
        if (!input) return { ok: false, stage: 'find=NULL' };

        if (input.tagName === 'TEXTAREA' || input.tagName === 'INPUT') {
            input.focus();
            try {
                var setter = Object.getOwnPropertyDescriptor(
                    window.HTMLTextAreaElement.prototype, 'value'
                ).set;
                setter.call(input, text);
                input.dispatchEvent(new Event('input',  { bubbles: true }));
                input.dispatchEvent(new Event('change', { bubbles: true }));
                return { ok: true, stage: 'textarea-ok' };
            } catch (e) {
                return { ok: false, stage: 'textarea-err=' + e.message };
            }
        }

        // contenteditable
        input.focus();

        var activeOk = document.activeElement === input
                    || input.contains(document.activeElement);
        if (!activeOk) {
            return { ok: false, stage: 'focus-failed active='
                + (document.activeElement
                    ? document.activeElement.tagName : 'null') };
        }

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
        if (!done) {
            return { ok: false, stage: 'execCommand=false' };
        }

        var preview = text.slice(0, 20);
        var content = input.textContent || input.innerText || '';
        if (!content.includes(preview)) {
            return { ok: false, stage: 'reverted' };
        }

        return { ok: true, stage: 'contenteditable-ok' };
    }

    // ── pressEnter ───────────────────────────────────────────────────

    function pressEnter() {
        var input = findInputBox();
        if (!input) return;
        input.dispatchEvent(new KeyboardEvent('keydown', {
            key: 'Enter', keyCode: 13,
            bubbles: true, composed: true
        }));
    }

    // ── clickSendButton ───────────────────────────────────────────────

    function clickSendButton() {
        var host = location.hostname;
        if (/gemini\.google\.com/.test(host)) {
            var attempts = 0;
            var iv = setInterval(function () {
                attempts++;
                var btn = findSendButton();
                if (btn && !btn.disabled) {
                    btn.click(); clearInterval(iv);
                } else if (attempts >= 15) {
                    clearInterval(iv); pressEnter();
                }
            }, 300);
            return;
        }
        var btn = findSendButton();
        if (btn && !btn.disabled) { btn.click(); return; }
        pressEnter();
    }

    // ── handleIncomingContext ─────────────────────────────────────────

    function handleIncomingContext(context) {
        var confirmMsg =
            'اقرأ السياق أعلاه.\n' +
            'ثم أجب بجملة واحدة فقط:\n' +
            '"فهمت السياق — أنا جاهز لأسئلتك."';

        var fullMessage = context + '\n\n---\n' + confirmMsg;

        // أبلغ background.js أن الدالة بدأت
        browser.runtime.sendMessage({
            type:   'DEBUG_INFO',
            info:   'handleIncomingContext len=' + context.length,
            domain: location.hostname
        });

        var attempts = 0;
        var iv = setInterval(function () {
            attempts++;

            var result = writeToInputBox(fullMessage);

            // أبلغ background.js بنتيجة كل محاولة
            browser.runtime.sendMessage({
                type:   'DEBUG_INFO',
                info:   'attempt=' + attempts
                        + ' ok=' + result.ok
                        + ' stage=' + result.stage,
                domain: location.hostname
            });

            if (result.ok) {
                clearInterval(iv);
                setTimeout(clickSendButton, 1000);
                browser.runtime.sendMessage({
                    type:   'CONTEXT_WRITTEN',
                    len:    fullMessage.length,
                    domain: location.hostname
                });

            } else if (attempts >= 10) {
                clearInterval(iv);
                browser.runtime.sendMessage({
                    type:   'DEBUG_INFO',
                    info:   'GIVE UP after 10 attempts',
                    domain: location.hostname
                });
            }
        }, 500);
    }

    // ── sendAutoToKotlin ──────────────────────────────────────────────

    function sendAutoToKotlin(text) {
        if (!text || text.length < 80) return;
        if (text === lastSentText)     return;
        lastSentText = text;
        browser.runtime.sendMessage({
            type:   'AI_RESPONSE',
            text:   text,
            domain: location.hostname
        });
    }

    // ── MutationObserver ──────────────────────────────────────────────

    var observer = new MutationObserver(function () {
        clearTimeout(debounceTimer);
        debounceTimer = setTimeout(function () {
            sendAutoToKotlin(extractLatestResponse());
        }, DEBOUNCE_MS);
    });

    function startObserver() {
        if (document.body) {
            observer.observe(document.body, {
                childList: true, subtree: true
            });
        } else {
            setTimeout(startObserver, 500);
        }
    }
    startObserver();

    // ── Polling كل ثانية ─────────────────────────────────────────────

    setInterval(function () {
        if (document.visibilityState !== 'visible') return;

        // CHECK_CAPTURE
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
                        '[data-message-author-role="assistant"]').length,
                    articles:  document.querySelectorAll('article').length,
                    bodyLen:   document.body
                               ? document.body.innerText.length : 0
                }
            });
        }).catch(function () {});

        // GET_CONTEXT — عبر background.js
        // GET_CONTEXT في الـ polling
browser.runtime.sendMessage({
    type:   'GET_CONTEXT',
    domain: location.hostname
}).then(function (response) {

    // ✅ سجّل كل رد بغض النظر عن القيمة
    browser.runtime.sendMessage({
        type:   'DEBUG_INFO',
        info:   'GET_CONTEXT_RESPONSE'
                + ' hasContext=' + (response ? response.hasContext : 'NULL')
                + ' contextLen=' + (response && response.context
                                    ? response.context.length : 0)
                + ' url=' + location.href.slice(0, 50),
        domain: location.hostname
    }).catch(function(){});

    if (!response) return;
    if (!response.hasContext) return;
    if (!response.context || !response.context.trim()) return;

    handleIncomingContext(response.context);

}).catch(function (e) {

    // ✅ سجّل الخطأ
    browser.runtime.sendMessage({
        type:   'DEBUG_INFO',
        info:   'GET_CONTEXT_ERROR: ' + String(e),
        domain: location.hostname
    }).catch(function(){});

});

    // ── detectAndReportButtons ────────────────────────────────────────

    function detectAndReportButtons() {
        var buttons = document.querySelectorAll('button');
        var visible = [];
        buttons.forEach(function (btn) {
            if (!isVisible(btn)) return;
            var label  = btn.getAttribute('aria-label') || '';
            var testid = btn.getAttribute('data-testid') || '';
            var type   = btn.getAttribute('type') || '';
            if (label || testid || type === 'submit') {
                visible.push({ label: label, testid: testid, type: type });
            }
        });
        browser.runtime.sendMessage({
            type:    'DEBUG_BUTTONS',
            buttons: visible,
            domain:  location.hostname
        });
    }

    setTimeout(detectAndReportButtons, 2000);

    window.addEventListener('pagehide', function () {
        observer.disconnect();
    });

})();
