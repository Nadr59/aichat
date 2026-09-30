(function () {

    // ══════════════════════════════════════════════════════════════════
    // حماية من iframe والتكرار
    // ══════════════════════════════════════════════════════════════════

    if (window !== window.top) return;
    if (window.__aiCaptureActive) return;
    window.__aiCaptureActive = true;

    // ══════════════════════════════════════════════════════════════════
    // المتغيرات العامة
    // ══════════════════════════════════════════════════════════════════

    var lastSentText      = '';
    var debounceTimer     = null;
    var DEBOUNCE_MS       = 1800;
    var MIN_LEN           = 30;
    var MAX_LEN           = 3000;
    var contextBusy       = false;           // منع تكرار writeAndSend
    var pendingContextId  = null;            // تتبع معرف الـ context الحالي
    var contextWriteTimer = null;            // timeout لتأكيد الكتابة

    // 🆕 Request Deduplication — تتبع معرف الطلب والرد
    var lastRequestId = 0;
    var pendingRequests = {};  // {requestId: timestamp}

    // ══════════════════════════════════════════════════════════════════
    // Debug Helper — تسجيل شامل
    // ══════════════════════════════════════════════════════════════════

    function logDebug(message) {
        var timestamp = new Date().toLocaleTimeString('en-US', {
            hour12: false,
            hour: '2-digit',
            minute: '2-digit',
            second: '2-digit'
        });
        
        var fullMsg = timestamp + '  ' + message;
        console.log('[AiCapture] ' + fullMsg);
        
        // أرسل لـ Kotlin للتسجيل المركزي
        browser.runtime.sendMessage({
            type: 'DEBUG_INFO',
            info: fullMsg
        }).catch(function () {
            // خطأ في الإرسال — لا نفعل شيء
        });
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

    // ══════════════════════════════════════════════════════════════════
    // Extractors
    // ══════════════════════════════════════════════════════════════════

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

    // ══════════════════════════════════════════════════════════════════
    // إيجاد صندوق الإدخال
    // ══════════════════════════════════════════════════════════════════

    function findInputBox() {
        var host = location.hostname;

        if (/chatgpt\.com|openai\.com/.test(host)) {
            var selectors = [
                '#prompt-textarea',
                'textarea[placeholder*="Message"]',
                'textarea[data-id="root"]',
                'div[contenteditable="true"]',
                'textarea'
            ];
            
            for (var i = 0; i < selectors.length; i++) {
                var el = document.querySelector(selectors[i]);
                if (el) {
                    return el;
                }
            }
            return null;
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

    // ══════════════════════════════════════════════════════════════════
    // الكتابة في صندوق الإدخال
    // ══════════════════════════════════════════════════════════════════

    function writeToInputBox(text) {
        logDebug('📝 writeToInputBox called, text length: ' + text.length);
        
        var input = findInputBox();
        if (!input) {
            logDebug('❌ writeToInputBox: No input box found');
            return { ok: false, stage: 'find=NULL' };
        }

        logDebug('✅ writeToInputBox: Input found (' + input.tagName + ')');

        // TEXTAREA أو INPUT
        if (input.tagName === 'TEXTAREA' || input.tagName === 'INPUT') {
            try {
                input.focus();
                var setter = Object.getOwnPropertyDescriptor(
                    window.HTMLTextAreaElement.prototype, 'value'
                ).set;
                setter.call(input, text);
                input.dispatchEvent(new Event('input',  { bubbles: true }));
                input.dispatchEvent(new Event('change', { bubbles: true }));
                logDebug('✅ writeToInputBox: Text written to textarea');
                return { ok: true, stage: 'textarea' };
            } catch (e) {
                logDebug('❌ writeToInputBox: Textarea error: ' + e.message);
                return { ok: false, stage: 'textarea-err=' + e.message };
            }
        }

        // contenteditable
        logDebug('📝 writeToInputBox: Trying contenteditable...');
        input.focus();
        
        var activeOk = document.activeElement === input
                    || input.contains(document.activeElement);
        if (!activeOk) {
            logDebug('❌ writeToInputBox: Focus failed');
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
            logDebug('❌ writeToInputBox: Caret error: ' + e.message);
            return { ok: false, stage: 'caret-err=' + e.message };
        }

        var done = document.execCommand('insertText', false, text);
        if (!done) {
            logDebug('❌ writeToInputBox: execCommand failed');
            return { ok: false, stage: 'execCommand=false' };
        }

        var preview = text.slice(0, 20);
        var content = input.textContent || input.innerText || '';
        if (!content.includes(preview)) {
            logDebug('❌ writeToInputBox: Text verification failed');
            return { ok: false, stage: 'reverted' };
        }

        logDebug('✅ writeToInputBox: Text written to contenteditable');
        return { ok: true, stage: 'contenteditable' };
    }

    // ══════════════════════════════════════════════════════════════════
    // إيجاد زر الإرسال
    // ══════════════════════════════════════════════════════════════════

    function findSendButton() {
        var host = location.hostname;

        if (/chatgpt\.com/.test(host)) {
            return document.querySelector('button[data-testid="send-button"]')
                || document.querySelector('button[aria-label="Send prompt"]')
                || document.querySelector('button[aria-label="إرسال رسالة"]')
                || document.querySelector('button[type="submit"]');
        }
        if (/gemini\.google\.com/.test(host)) {
            return document.querySelector('button[aria-label="Send message"]')
                || document.querySelector('button[aria-label="إرسال الرسالة"]')
                || document.querySelector('button[type="submit"]');
        }
        if (/claude\.ai/.test(host)) {
            return document.querySelector('button[aria-label="Send Message"]')
                || document.querySelector('button[type="submit"]');
        }
        return document.querySelector('button[type="submit"]')
            || document.querySelector('button[aria-label*="send" i]')
            || document.querySelector('button[aria-label*="إرسال" i]');
    }

    // ══════════════════════════════════════════════════════════════════
    // ضغط زر الإرسال (async)
    // ══════════════════════════════════════════════════════════════════

    function clickSendButton() {
        logDebug('🔘 Trying to click send button...');
        
        return new Promise(function (resolve) {
            var attempts = 0;
            var maxAttempts = 15;
            var interval = setInterval(function () {
                attempts++;
                var btn = findSendButton();
                
                if (btn && !btn.disabled) {
                    clearInterval(interval);
                    logDebug('✅ Button found and clicked');
                    btn.click();
                    resolve(true);
                } else if (attempts >= maxAttempts) {
                    clearInterval(interval);
                    logDebug('⚠️ Button not found after ' + maxAttempts + ' attempts, trying Enter');
                    
                    var input = findInputBox();
                    if (input) {
                        input.dispatchEvent(new KeyboardEvent('keydown', {
                            key: 'Enter',
                            keyCode: 13,
                            bubbles: true,
                            composed: true
                        }));
                    }
                    resolve(true);
                }
            }, 200);

            // timeout جمالي بعد 5 ثواني
            setTimeout(function () {
                if (interval) {
                    clearInterval(interval);
                    logDebug('⏱️ clickSendButton timeout');
                    resolve(false);
                }
            }, 5000);
        });
    }

    // ══════════════════════════════════════════════════════════════════
    // تنفيذ الكتابة والإرسال (async)
    // 
    // 🆕 محسّن مع CONTEXT_CONSUMED
    // ══════════════════════════════════════════════════════════════════

    function writeAndSend(context, contextId) {
        logDebug('🚀 writeAndSend START: length=' + context.length + ', id=' + contextId);
        logDebug('🚀 First 100 chars: ' + context.substring(0, 100));
        
        // ألغِ أي timeout سابق
        if (contextWriteTimer) {
            clearTimeout(contextWriteTimer);
            contextWriteTimer = null;
        }

        var result = writeToInputBox(context);
        
        logDebug('🚀 writeToInputBox result: ok=' + result.ok + ', stage=' + result.stage);

        if (!result.ok) {
            logDebug('❌ writeAndSend: Write failed, sending CONTEXT_WRITTEN');
            browser.runtime.sendMessage({
                type:      'CONTEXT_WRITTEN',
                success:   false,
                contextId: contextId,
                stage:     'write_failed',
                detail:    result.stage,
                domain:    location.hostname
            }).catch(function (e) {
                logDebug('❌ Failed to send CONTEXT_WRITTEN: ' + e.message);
            });
            
            // 🆕 أخبر الـ Kotlin أن السياق استهلك (فشل)
            browser.runtime.sendMessage({
                type: 'CONTEXT_CONSUMED',
                contextId: contextId
            }).catch(function (e) {
                logDebug('❌ Failed to send CONTEXT_CONSUMED: ' + e.message);
            });
            
            contextBusy = false;
            return;
        }

        logDebug('✅ writeAndSend: Text written, waiting before click (600ms)');

        // انتظر قليلاً قبل الضغط على الزر
        setTimeout(function () {
            logDebug('⏳ writeAndSend: Starting clickSendButton...');
            clickSendButton().then(function (clicked) {
                logDebug('✅ writeAndSend: Button handling finished, clicked=' + clicked);
                
                // انتظر قليلاً لتأكيد أن الرسالة ذهبت
                contextWriteTimer = setTimeout(function () {
                    logDebug('✅ writeAndSend: Confirming success after 1s delay');
                    browser.runtime.sendMessage({
                        type:      'CONTEXT_WRITTEN',
                        success:   true,
                        contextId: contextId,
                        stage:     'button_clicked',
                        domain:    location.hostname
                    }).catch(function (e) {
                        logDebug('❌ Failed to send CONTEXT_WRITTEN: ' + e.message);
                    });
                    
                    // 🆕 أخبر الـ Kotlin أن السياق تم استهلاكه (نجح)
                    browser.runtime.sendMessage({
                        type: 'CONTEXT_CONSUMED',
                        contextId: contextId
                    }).catch(function (e) {
                        logDebug('❌ Failed to send CONTEXT_CONSUMED: ' + e.message);
                    });
                    
                    contextBusy = false;
                    pendingContextId = null;
                }, 1000);
            }).catch(function (e) {
                logDebug('❌ writeAndSend: clickSendButton error: ' + e.message);
                browser.runtime.sendMessage({
                    type:      'CONTEXT_WRITTEN',
                    success:   false,
                    contextId: contextId,
                    stage:     'click_error',
                    detail:    e.message,
                    domain:    location.hostname
                }).catch(function () {});
                
                // 🆕 أخبر الـ Kotlin أن السياق استهلك (خطأ)
                browser.runtime.sendMessage({
                    type: 'CONTEXT_CONSUMED',
                    contextId: contextId
                }).catch(function (e) {
                    logDebug('❌ Failed to send CONTEXT_CONSUMED: ' + e.message);
                });
                
                contextBusy = false;
            });
        }, 600);
    }

    // ══════════════════════════════════════════════════════════════════
    // إرسال تلقائي (استجابة آلية)
    // ══════════════════════════════════════════════════════════════════

    function sendAutoToKotlin(text) {
        if (!text || text.length < 80) return;
        if (text === lastSentText)     return;
        
        var textSnapshot = text;
        setTimeout(function() {
            var recheck = extractLatestResponse();
            if (recheck === textSnapshot && textSnapshot !== lastSentText) {
                lastSentText = textSnapshot;
                logDebug('📤 Auto-sending response: ' + textSnapshot.length + ' chars');
                try {
                    browser.runtime.sendMessage({
                        type:   'AI_RESPONSE',
                        text:   textSnapshot,
                        domain: location.hostname
                    });
                } catch (e) {
                    logDebug('❌ Error sending AI_RESPONSE: ' + e.message);
                }
            }
        }, 1000);
    }

    // ══════════════════════════════════════════════════════════════════
    // مراقبة تلقائية (DOM mutations)
    // ══════════════════════════════════════════════════════════════════

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
            logDebug('✅ MutationObserver started');
        } else {
            setTimeout(startObserver, 500);
        }
    }
    startObserver();

    // ══════════════════════════════════════════════════════════════════
    // Polling — CHECK_CAPTURE (زر 🧠 في Kotlin)
    // ══════════════════════════════════════════════════════════════════

    setInterval(function () {
        if (document.visibilityState !== 'visible') return;

        browser.runtime.sendMessage({
            type:   'CHECK_CAPTURE',
            domain: location.hostname
        }).then(function (response) {
            if (!response || !response.capture) return;

            logDebug('📩 [aicapture] CHECK_CAPTURE received');
            
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
        }).catch(function (e) {
            logDebug('❌ CHECK_CAPTURE error: ' + e.message);
        });
    }, 1000);

    // ══════════════════════════════════════════════════════════════════
    // Polling — GET_CONTEXT (محسّن مع Debugging شامل + CONTEXT_CONSUMED)
    // 
    // 🆕 تحسينات:
    // - أضفنا requestId لكل طلب لتجنب ردود قديمة
    // - نتتبع الطلبات المعلقة والردود المقابلة
    // - نتجاهل أي رد لم يطابق requestId الحالي
    // - debugging شامل لكشف المشاكل
    // ══════════════════════════════════════════════════════════════════

    var getContextPollCount = 0;

    setInterval(function () {
        if (document.visibilityState !== 'visible') return;
        if (contextBusy) {
            return;
        }

        // 🆕 توليد معرف فريد لهذا الطلب
        lastRequestId++;
        var currentRequestId = lastRequestId;
        pendingRequests[currentRequestId] = Date.now();
        
        getContextPollCount++;

        // تنظيف الطلبات القديمة (أكثر من 10 ثواني)
        var now = Date.now();
        for (var reqId in pendingRequests) {
            if (now - pendingRequests[reqId] > 10000) {
                delete pendingRequests[reqId];
            }
        }

        browser.runtime.sendMessage({
            type:      'GET_CONTEXT',
            domain:    location.hostname,
            requestId: currentRequestId  // 🆕 أرسل معرف الطلب
        }).then(function (response) {
            // 🆕 تشخيص شامل
            logDebug('📬 GET_CONTEXT #' + currentRequestId + ': raw response=' + JSON.stringify(response));

            if (!response) {
                logDebug('⚠️ GET_CONTEXT #' + currentRequestId + ': EMPTY response (background.js might be missing return true)');
                delete pendingRequests[currentRequestId];
                return;
            }

            // تحقق من مطابقة requestId
            if (response.requestId && response.requestId !== currentRequestId) {
                logDebug('⏭️ GET_CONTEXT #' + currentRequestId + ': STALE response (expected=' + currentRequestId + ', got=' + response.requestId + '), ignoring');
                return;
            }

            delete pendingRequests[currentRequestId];

            if (!response.hasContext) {
                return;
            }
            
            var context = response.context || '';
            var contextId = response.id || 0;

            if (!context || context.length === 0) {
                logDebug('⚠️ GET_CONTEXT #' + currentRequestId + ': context is empty');
                return;
            }

            logDebug('✅ GET_CONTEXT #' + currentRequestId + ': VALID context! length=' + context.length + ', id=' + contextId);
            logDebug('✅ contextBusy=' + contextBusy + ', about to call writeAndSend');
            
            // set busy flag قبل البدء
            contextBusy = true;
            pendingContextId = contextId;
            
            logDebug('🚀 Calling writeAndSend now...');
            // ابدأ الكتابة والإرسال
            writeAndSend(context, contextId);

        }).catch(function (e) {
            logDebug('❌ GET_CONTEXT #' + currentRequestId + ' error: ' + e.message + ' | lastError=' + browser.runtime.lastError);
            delete pendingRequests[currentRequestId];
        });
    }, 1000);

    // ══════════════════════════════════════════════════════════════════
    // تنظيف عند مغادرة الصفحة
    // ══════════════════════════════════════════════════════════════════

    window.addEventListener('pagehide', function () {
        logDebug('👋 Page is hiding, disconnecting observer');
        observer.disconnect();
        if (contextWriteTimer) {
            clearTimeout(contextWriteTimer);
        }
    });

    // ══════════════════════════════════════════════════════════════════
    // رسالة البداية
    // ══════════════════════════════════════════════════════════════════

    logDebug('✅ Extension loaded successfully');

})();
