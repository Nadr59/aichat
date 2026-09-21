(function () {

    if (window !== window.top) return;
    if (window.__contextBridgeActive) return;
    window.__contextBridgeActive = true;

    var NATIVE_APP = 'memory_context';
    var port       = null;

    // ── اتصال Port مع Kotlin ─────────────────────────────────────────

    function connect() {
        try {
            port = browser.runtime.connectNative(NATIVE_APP);
        } catch (e) {
            console.error('[bridge] connectNative failed:', e);
            return;
        }

        port.onMessage.addListener(onKotlinMessage);

        port.onDisconnect.addListener(function () {
            console.log('[bridge] port disconnected');
            port = null;
        });

        // ✅ أخبر Kotlin أن الصفحة جاهزة
        port.postMessage({ type: 'READY', url: location.href });
        console.log('[bridge] READY sent');
    }

    // ── استقبال من Kotlin ─────────────────────────────────────────────

    function onKotlinMessage(msg) {
        if (!msg || !msg.type) return;
        console.log('[bridge] from Kotlin:', msg.type);

        if (msg.type === 'HELLO') {
            // Kotlin يؤكد الاتصال
            port.postMessage({ type: 'READY', url: location.href });
        }

        if (msg.type === 'DELIVER') {
            handleDeliver(msg);
        }
    }

    // ── تنفيذ الإرسال ─────────────────────────────────────────────────

    function handleDeliver(msg) {
        var requestId = msg.requestId || '';
        var text      = (msg.systemDocument || '')
                      + (msg.memoryContext  ? '\n\n' + msg.memoryContext : '')
                      + '\n\n---\n'
                      + 'اقرأ السياق أعلاه.\n'
                      + 'ثم أجب بجملة واحدة فقط:\n'
                      + '"فهمت السياق — أنا جاهز لأسئلتك."';
        var submit    = !!msg.submit;

        var result = writeToInputBox(text);

        if (!result.ok) {
            report(requestId, 'write_failed', result.stage);
            return;
        }

        if (!submit) {
            report(requestId, 'filled', '');
            return;
        }

        // انتظر ثانية ثم اضغط إرسال
        setTimeout(function () {
            var clicked = clickSendButton();
            report(requestId, clicked ? 'clicked' : 'send_failed', '');
        }, 1000);
    }

    // ── إبلاغ Kotlin بالنتيجة ────────────────────────────────────────

    function report(requestId, stage, detail) {
        if (!port) return;
        port.postMessage({
            type:      'RESULT',
            requestId: requestId,
            stage:     stage,
            detail:    detail || ''
        });
        console.log('[bridge] RESULT stage=' + stage);
    }

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

        // TEXTAREA
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

        // contenteditable
        input.focus();

        var activeOk = document.activeElement === input
                    || input.contains(document.activeElement);
        if (!activeOk) {
            return { ok: false, stage: 'focus-failed' };
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
        if (!done) return { ok: false, stage: 'execCommand=false' };

        var preview = text.slice(0, 20);
        var content = input.textContent || input.innerText || '';
        if (!content.includes(preview)) {
            return { ok: false, stage: 'reverted' };
        }

        return { ok: true, stage: 'contenteditable' };
    }

    // ── إيجاد وضغط زر الإرسال ────────────────────────────────────────

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

    function clickSendButton() {
        var host = location.hostname;

        // Gemini — الزر يظهر بعد الكتابة
        if (/gemini\.google\.com/.test(host)) {
            var found = false;
            for (var i = 0; i < 10; i++) {
                var btn = findSendButton();
                if (btn && !btn.disabled) {
                    btn.click();
                    found = true;
                    break;
                }
            }
            if (!found) pressEnter();
            return found;
        }

        var btn = findSendButton();
        if (btn && !btn.disabled) {
            btn.click();
            return true;
        }

        pressEnter();
        return false;
    }

    function pressEnter() {
        var input = findInputBox();
        if (!input) return;
        input.dispatchEvent(new KeyboardEvent('keydown', {
            key: 'Enter', keyCode: 13,
            bubbles: true, composed: true
        }));
    }

    // ── ابدأ ─────────────────────────────────────────────────────────

    connect();

    window.addEventListener('pagehide', function () {
        if (port) port.disconnect();
    });

})();
