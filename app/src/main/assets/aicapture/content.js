(function () {
    "use strict";

    // ══════════════════════════════════════════════════════════════════
    // حماية من iframe والتكرار
    // ══════════════════════════════════════════════════════════════════

    if (window !== window.top) return;
    if (window.__aiCaptureActive) return;
    window.__aiCaptureActive = true;

    // ══════════════════════════════════════════════════════════════════
    // الثوابت والمتغيرات
    // ══════════════════════════════════════════════════════════════════

    var BUILD           = 'v1.0.16-poll';   // غيّرها مع كل تعديل للتأكد من النسخة
    var POLL_MS         = 1000;
    var DEBOUNCE_MS     = 1800;
    var MIN_LEN         = 30;
    var MAX_LEN         = 3000;
    var JOB_TIMEOUT_MS  = 20000;

    var lastSentText    = '';
    var debounceTimer   = null;
    var pollCount       = 0;
    var pollInFlight    = false;
    var pollStartedAt   = 0;
    var lastPollErr     = '';
    var activeJob       = null;     // مهمة الكتابة/الإرسال الجارية

    // ══════════════════════════════════════════════════════════════════
    // Messaging helpers
    // ══════════════════════════════════════════════════════════════════

    function send(msg) {
        try {
            return browser.runtime.sendMessage(msg).catch(function () {});
        } catch (e) {
            return Promise.resolve();
        }
    }

    function logDebug(message) {
        var ts = new Date().toLocaleTimeString('en-US', {
            hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit'
        });
        var full = ts + '  ' + message;
        console.log('[AiCapture] ' + full);
        send({ type: 'DEBUG_INFO', info: full });
    }

    // ══════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════

    function clean(t) {
        return (t || '')
            .replace(/\u200b/g, '')
            .replace(/[ \t]+\n/g, '\n')
            .trim();
    }

    function norm(s) {
        return (s || '').replace(/\s+/g, ' ').trim();
    }

    function q(sel, root) {
        try { return (root || document).querySelector(sel); }
        catch (e) { return null; }
    }

    function isVisible(el) {
        if (!el) return false;
        var r = el.getBoundingClientRect();
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

    // ══════════════════════════════════════════════════════════════════
    // Extractors
    // ══════════════════════════════════════════════════════════════════

    function extractChatGPT() {
        var turns = document.querySelectorAll('[data-message-author-role="assistant"]');
        for (var i = turns.length - 1; i >= 0; i--) {
            var turn = turns[i];
            if (turn.closest('[data-is-streaming="true"]')) continue;
            var inner = turn.querySelector('.markdown, .prose, .whitespace-pre-wrap') || turn;
            var t = clean(inner.innerText);
            if (t.length >= MIN_LEN) return t;
        }
        var articles = document.querySelectorAll('article[data-testid^="conversation-turn"]');
        for (var j = articles.length - 1; j >= 0; j--) {
            if (articles[j].querySelector('[data-message-author-role="user"]')) continue;
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
            'nav', 'aside', 'header', 'footer', 'form',
            'textarea', 'button', 'input', 'script', 'style',
            '[role="navigation"]', '[role="banner"]',
            '[contenteditable="true"]'
        ].join(', ');

        var blocks = document.querySelectorAll('article, section, main div, p, li, pre, blockquote');
        var best = '';
        for (var i = 0; i < blocks.length; i++) {
            var el = blocks[i];
            if (el.closest(EXCLUDE)) continue;
            if (!isVisible(el)) continue;
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

    // ══════════════════════════════════════════════════════════════════
    // Find Input Box
    // ══════════════════════════════════════════════════════════════════

    function findInputBox() {
        var host = location.hostname;

        if (/chatgpt\.com|openai\.com/.test(host)) {
            return q('#prompt-textarea')
                || q('textarea[placeholder*="Message"]')
                || q('textarea[data-id="root"]')
                || q('div[contenteditable="true"]')
                || q('textarea');
        }
        if (/claude\.ai/.test(host)) {
            return q('.ProseMirror[contenteditable="true"]')
                || q('.ProseMirror')
                || q('[contenteditable="true"]');
        }
        if (/gemini\.google\.com/.test(host)) {
            return q('.ql-editor')
                || q('[contenteditable="true"]');
        }
        return q('textarea') || q('[contenteditable="true"]');
    }

    function isInputEmpty() {
        var input = findInputBox();
        if (!input) return true;
        if (input.tagName === 'TEXTAREA' || input.tagName === 'INPUT') {
            return (input.value || '').trim().length === 0;
        }
        return (input.textContent || '').trim().length === 0;
    }

    // ══════════════════════════════════════════════════════════════════
    // Write to Input Box
    // ══════════════════════════════════════════════════════════════════

    function contentHasText(input, text) {
        var preview = norm(text).slice(0, 20);
        return norm(input.textContent || input.innerText || '').indexOf(preview) !== -1;
    }

    function selectAllContents(input) {
        var sel = window.getSelection();
        sel.removeAllRanges();
        var range = document.createRange();
        range.selectNodeContents(input);
        sel.addRange(range);
    }

    function tryPaste(input, text) {
        try {
            var dt = new DataTransfer();
            dt.setData('text/plain', text);
            var ev = new ClipboardEvent('paste', {
                clipboardData: dt, bubbles: true, cancelable: true
            });
            input.dispatchEvent(ev);
            return true;
        } catch (e) {
            return false;
        }
    }

    function writeToInputBox(text) {
        logDebug('📝 writeToInputBox: length=' + text.length);

        var input = findInputBox();
        if (!input) {
            logDebug('❌ writeToInputBox: No input box found');
            return { ok: false, stage: 'find=NULL' };
        }
        logDebug('✅ Input found: <' + input.tagName + '>');

        // ── textarea / input ──
        if (input.tagName === 'TEXTAREA' || input.tagName === 'INPUT') {
            try {
                input.focus();
                var proto  = input.tagName === 'TEXTAREA'
                    ? window.HTMLTextAreaElement.prototype
                    : window.HTMLInputElement.prototype;
                var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
                setter.call(input, text);
                input.dispatchEvent(new Event('input',  { bubbles: true }));
                input.dispatchEvent(new Event('change', { bubbles: true }));
                logDebug('✅ Text written to textarea');
                return { ok: true, stage: 'textarea' };
            } catch (e) {
                logDebug('❌ Textarea error: ' + e.message);
                return { ok: false, stage: 'textarea-err=' + e.message };
            }
        }

        // ── contenteditable ──
        input.focus();
        var activeOk = document.activeElement === input || input.contains(document.activeElement);
        if (!activeOk) {
            logDebug('❌ Focus failed');
            return { ok: false, stage: 'focus-failed' };
        }

        try { selectAllContents(input); }
        catch (e) {
            logDebug('❌ Selection error: ' + e.message);
            return { ok: false, stage: 'caret-err=' + e.message };
        }

        var done = false;
        try { done = document.execCommand('insertText', false, text); }
        catch (e) { done = false; }

        if (done && contentHasText(input, text)) {
            logDebug('✅ Text written (execCommand)');
            return { ok: true, stage: 'contenteditable' };
        }

        // بديل: محاكاة اللصق
        logDebug('⚠️ execCommand failed/unverified, trying paste fallback');
        try { input.focus(); selectAllContents(input); } catch (e) {}
        if (tryPaste(input, text) && contentHasText(input, text)) {
            logDebug('✅ Text written (paste fallback)');
            return { ok: true, stage: 'contenteditable-paste' };
        }

        logDebug('❌ Text verification failed');
        return { ok: false, stage: done ? 'reverted' : 'execCommand=false' };
    }

    // ══════════════════════════════════════════════════════════════════
    // Send Button
    // ══════════════════════════════════════════════════════════════════

    function findSendButton() {
        var host = location.hostname;
        var btn  = null;

        if (/chatgpt\.com|openai\.com/.test(host)) {
            btn = q('button[data-testid="send-button"]')
               || q('#composer-submit-button')
               || q('button[aria-label="Send prompt" i]')
               || q('button[aria-label="Send message" i]')
               || q('button[aria-label="إرسال رسالة"]');
        } else if (/gemini\.google\.com/.test(host)) {
            btn = q('button.send-button')
               || q('button[aria-label="Send message" i]')
               || q('button[aria-label="إرسال الرسالة"]');
        } else if (/claude\.ai/.test(host)) {
            btn = q('button[aria-label="Send message" i]');
        }
        if (btn) return btn;

        // احتياطي عام: ابحث ضمن نفس النموذج الذي يحوي حقل الإدخال أولًا
        var input = findInputBox();
        var scope = (input && input.closest && input.closest('form')) || document;
        return q('button[type="submit"]', scope)
            || q('button[aria-label*="send" i]', scope)
            || q('button[aria-label*="إرسال"]', scope);
    }

    function isDisabled(btn) {
        return btn.disabled || btn.getAttribute('aria-disabled') === 'true';
    }

    function pressEnter() {
        var input = findInputBox();
        if (!input) return false;
        input.focus();
        ['keydown', 'keypress', 'keyup'].forEach(function (type) {
            input.dispatchEvent(new KeyboardEvent(type, {
                key: 'Enter', code: 'Enter', keyCode: 13, which: 13,
                bubbles: true, cancelable: true, composed: true
            }));
        });
        return true;
    }

    // يرجع { clicked: bool, method: 'button' | 'enter' | 'none' }
    function clickSendButton() {
        logDebug('🔘 Trying to click send button...');

        return new Promise(function (resolve) {
            var attempts = 0;
            var MAX      = 15;
            var timer = setInterval(function () {
                attempts++;
                var btn = findSendButton();

                if (btn && !isDisabled(btn)) {
                    clearInterval(timer);
                    btn.click();
                    logDebug('✅ Send button clicked (attempt ' + attempts + ')');
                    resolve({ clicked: true, method: 'button' });
                    return;
                }

                if (attempts >= MAX) {
                    clearInterval(timer);
                    logDebug('⚠️ Button not usable after ' + MAX + ' attempts, trying Enter');
                    var ok = pressEnter();
                    resolve({ clicked: ok, method: ok ? 'enter' : 'none' });
                }
            }, 200);
        });
    }

    // ══════════════════════════════════════════════════════════════════
    // Job: Write and Send
    // ══════════════════════════════════════════════════════════════════

    function finishJob(job, success, stage, detail) {
        if (job.done) return;
        job.done = true;

        job.timers.forEach(function (t) { clearTimeout(t); });
        job.timers = [];

        logDebug((success ? '✅' : '❌') + ' Job finished: id=' + job.id +
                 ', stage=' + stage + (detail ? ', ' + detail : ''));

        send({
            type:      'CONTEXT_WRITTEN',
            success:   !!success,
            contextId: job.id,
            stage:     stage,
            detail:    detail || '',
            domain:    location.hostname
        });
        send({ type: 'CONTEXT_CONSUMED', contextId: job.id });

        if (activeJob === job) activeJob = null;
    }

    function writeAndSend(context, contextId) {
        var job = { id: contextId, done: false, startedAt: Date.now(), timers: [] };
        activeJob = job;

        logDebug('🚀 writeAndSend: length=' + context.length + ', id=' + contextId +
                 ', vis=' + document.visibilityState);

        try {
            var result = writeToInputBox(context);
            if (!result.ok) {
                finishJob(job, false, 'write_failed', result.stage);
                return;
            }

            job.timers.push(setTimeout(function () {
                clickSendButton().then(function (res) {
                    job.timers.push(setTimeout(function () {
                        var cleared = isInputEmpty();
                        var stage = res.method === 'button' ? 'button_clicked'
                                  : res.method === 'enter'  ? 'enter_sent'
                                  : 'send_failed';
                        finishJob(job, res.clicked, stage, 'input_cleared=' + cleared);
                    }, 1000));
                }).catch(function (e) {
                    finishJob(job, false, 'click_error', e && e.message);
                });
            }, 600));

        } catch (e) {
            finishJob(job, false, 'exception', e && e.message);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Auto Send (ردود الذكاء الاصطناعي)
    // ══════════════════════════════════════════════════════════════════

    function sendAutoToKotlin(text) {
        if (activeJob)                 return;
        if (!text || text.length < 80) return;
        if (text === lastSentText)     return;

        var snapshot = text;
        setTimeout(function () {
            var recheck = extractLatestResponse();
            if (recheck === snapshot && snapshot !== lastSentText) {
                lastSentText = snapshot;
                logDebug('📤 Auto-sending response: ' + snapshot.length + ' chars');
                send({
                    type:   'AI_RESPONSE',
                    text:   snapshot,
                    domain: location.hostname
                });
            }
        }, 1000);
    }

    // ══════════════════════════════════════════════════════════════════
    // Observer
    // ══════════════════════════════════════════════════════════════════

    var observer = new MutationObserver(function () {
        clearTimeout(debounceTimer);
        debounceTimer = setTimeout(function () {
            sendAutoToKotlin(extractLatestResponse());
        }, DEBOUNCE_MS);
    });

    function startObserver() {
        if (document.body) {
            observer.observe(document.body, { childList: true, subtree: true });
            logDebug('✅ MutationObserver started');
        } else {
            setTimeout(startObserver, 500);
        }
    }
    startObserver();

    // ══════════════════════════════════════════════════════════════════
    // Manual capture
    // ══════════════════════════════════════════════════════════════════

    function handleCapture() {
        logDebug('📩 Capture requested');

        var text = extractLatestResponse();
        var ok   = !!(text && text.length >= MIN_LEN);

        send({
            type:    'CAPTURE_RESULT',
            success: ok,
            text:    ok ? text : '',
            domain:  location.hostname,
            debug: {
                assistant: document.querySelectorAll('[data-message-author-role="assistant"]').length,
                articles:  document.querySelectorAll('article').length,
                bodyLen:   document.body ? document.body.innerText.length : 0
            }
        });
    }

    // ══════════════════════════════════════════════════════════════════
    // Polling — POLL (capture + context في طلب واحد)
    // ══════════════════════════════════════════════════════════════════

    function poll() {
        pollCount++;

        // نبض حياة (قبل أي شرط) لتشخيص التوقف
        if (pollCount % 10 === 1) {
            logDebug('💓 alive: vis=' + document.visibilityState +
                     ' busy=' + (!!activeJob) + ' host=' + location.hostname +
                     ' build=' + BUILD);
        }

        // watchdog: مهمة عالقة
        if (activeJob) {
            if (Date.now() - activeJob.startedAt > JOB_TIMEOUT_MS) {
                logDebug('⚠️ Job watchdog timeout, resetting');
                finishJob(activeJob, false, 'watchdog_timeout', '');
            } else {
                return;
            }
        }

        // منع تداخل الطلبات
        if (pollInFlight) {
            if (Date.now() - pollStartedAt > 8000) pollInFlight = false;
            else return;
        }
        pollInFlight  = true;
        pollStartedAt = Date.now();

        browser.runtime.sendMessage({
            type:    'POLL',
            domain:  location.hostname,
            visible: document.visibilityState === 'visible'
        }).then(function (resp) {
            pollInFlight = false;
            lastPollErr  = '';
            if (!resp) return;

            if (resp.capture) handleCapture();

            if (resp.hasContext && resp.context && !activeJob) {
                logDebug('✅ Got context: length=' + resp.context.length + ', id=' + resp.id);
                writeAndSend(resp.context, resp.id || 0);
            }
        }).catch(function (e) {
            pollInFlight = false;
            var m = (e && e.message) ? e.message : String(e);
            if (m !== lastPollErr) {          // لا تكرر نفس الخطأ كل ثانية
                lastPollErr = m;
                logDebug('❌ POLL error: ' + m);
            }
        });
    }

    setInterval(poll, POLL_MS);

    // ══════════════════════════════════════════════════════════════════
    // Page lifecycle
    // ══════════════════════════════════════════════════════════════════

    window.addEventListener('pagehide', function () {
        logDebug('👋 pagehide');
        observer.disconnect();
    });

    window.addEventListener('pageshow', function (ev) {
        if (ev.persisted) {
            logDebug('🔁 pageshow (bfcache), restarting observer');
            startObserver();
        }
    });

    // ══════════════════════════════════════════════════════════════════
    // Startup
    // ══════════════════════════════════════════════════════════════════

    logDebug('✅ Extension loaded successfully BUILD=' + BUILD);

})();
