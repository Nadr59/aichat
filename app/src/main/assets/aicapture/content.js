(function () {
    "use strict";

    // ============================================================
    // AiChat — Content Script
    // Diagnostic response-element discovery
    // Version: 1.0.25-test-response-dom
    //
    // IMPORTANT:
    // - لا نغير مسار CONTEXT_TO_PAGE الناجح.
    // - لا نلتقط الرد ولا نرسله إلى Kotlin.
    // - هذا الاختبار يبحث فقط عن عناصر رسائل المساعد.
    // ============================================================

    var TAG = "AiChat";

    function log(message) {
        try {
            browser.runtime.sendMessage({
                type: "JS_LOG",
                message: message
            });
        } catch (e) {
            console.log(TAG + ": " + message);
        }
    }

    log("🔬 Response DOM diagnostic content.js ready");
    log("🌐 Domain: " + location.hostname);

    // ============================================================
    // Input helpers — المسار الناجح السابق محفوظ
    // ============================================================

    function isUsableInput(el) {
        if (!el) return false;

        var rect = el.getBoundingClientRect();

        if (rect.width <= 0 || rect.height <= 0) {
            return false;
        }

        if (el.disabled || el.readOnly) {
            return false;
        }

        return true;
    }

    function findChatGPTInput() {
        var selectors = [
            "#prompt-textarea",
            "textarea[data-testid='textbox']",
            "textarea[data-testid*='textbox']",
            "textarea[placeholder*='Message']",
            "textarea[placeholder*='message']",
            "textarea[aria-label*='Message']",
            "textarea[aria-label*='message']",

            "div[contenteditable='true'][role='textbox']",
            "[contenteditable='true'][role='textbox']",

            "div[contenteditable='true'][data-placeholder]",
            "[contenteditable='true'][data-placeholder]",

            ".ProseMirror",
            "[data-lexical-editor='true']",

            "[role='textbox']",
            "[contenteditable='true']",

            "textarea"
        ];

        for (var i = 0; i < selectors.length; i++) {
            try {
                var elements = document.querySelectorAll(selectors[i]);

                for (var j = 0; j < elements.length; j++) {
                    if (isUsableInput(elements[j])) {
                        log(
                            "✅ ChatGPT input found: " +
                            elements[j].tagName +
                            (elements[j].id ? "#" + elements[j].id : "")
                        );

                        return elements[j];
                    }
                }
            } catch (e) {
                log("⚠️ Input selector error: " + selectors[i]);
            }
        }

        return null;
    }

    function findInputWithRetry(attempt, delay, callback) {
        var input = findChatGPTInput();

        if (input) {
            callback(input);
            return;
        }

        if (attempt <= 0) {
            callback(null);
            return;
        }

        setTimeout(function () {
            findInputWithRetry(attempt - 1, delay, callback);
        }, delay);
    }

    function injectIntoInput(input, text) {
        try {
            input.focus();

            if (
                input.tagName === "TEXTAREA" ||
                input.tagName === "INPUT"
            ) {
                var prototype =
                    input.tagName === "TEXTAREA"
                        ? HTMLTextAreaElement.prototype
                        : HTMLInputElement.prototype;

                var descriptor = Object.getOwnPropertyDescriptor(
                    prototype,
                    "value"
                );

                if (descriptor && descriptor.set) {
                    descriptor.set.call(input, text);
                } else {
                    input.value = text;
                }

                input.dispatchEvent(
                    new Event("input", {
                        bubbles: true,
                        composed: true
                    })
                );

                input.dispatchEvent(
                    new Event("change", {
                        bubbles: true,
                        composed: true
                    })
                );

                return true;
            }

            if (input.isContentEditable) {
                try {
                    document.execCommand(
                        "selectAll",
                        false,
                        null
                    );

                    document.execCommand(
                        "insertText",
                        false,
                        text
                    );
                } catch (e) {
                    input.textContent = text;
                }

                input.dispatchEvent(
                    new InputEvent("input", {
                        bubbles: true,
                        composed: true,
                        inputType: "insertText",
                        data: text
                    })
                );

                return true;
            }

            return false;

        } catch (e) {
            log("❌ Text injection exception: " + e.message);
            return false;
        }
    }

    function sendEnter(input) {
        try {
            input.focus();

            var options = {
                key: "Enter",
                code: "Enter",
                keyCode: 13,
                which: 13,
                bubbles: true,
                cancelable: true,
                composed: true
            };

            input.dispatchEvent(new KeyboardEvent("keydown", options));
            input.dispatchEvent(new KeyboardEvent("keypress", options));
            input.dispatchEvent(new KeyboardEvent("keyup", options));

            return true;
        } catch (e) {
            log("❌ Enter exception: " + e.message);
            return false;
        }
    }

    // ============================================================
    // Response DOM Diagnostic
    // ============================================================

    function cleanText(text) {
        if (!text) return "";

        return text
            .replace(/\s+/g, " ")
            .trim();
    }

    function describeElement(el) {
        if (!el) return "";

        var tag = el.tagName || "UNKNOWN";
        var id = el.id ? "#" + el.id : "";

        var cls = "";
        if (typeof el.className === "string" && el.className.trim()) {
            cls = "." + el.className
                .trim()
                .split(/\s+/)
                .slice(0, 3)
                .join(".");
        }

        var role = el.getAttribute("role");
        var testid = el.getAttribute("data-testid");
        var authorRole = el.getAttribute("data-message-author-role");

        var extra = "";

        if (role) {
            extra += ' role="' + role + '"';
        }

        if (testid) {
            extra += ' data-testid="' + testid + '"';
        }

        if (authorRole) {
            extra += ' data-message-author-role="' + authorRole + '"';
        }

        return tag + id + cls + extra;
    }

    function reportCandidate(selector, elements, maxItems) {
        if (!elements || elements.length === 0) {
            return 0;
        }

        var count = 0;

        log(
            "🔎 SELECTOR: " +
            selector +
            " → count=" +
            elements.length
        );

        for (
            var i = Math.max(0, elements.length - maxItems);
            i < elements.length;
            i++
        ) {
            var el = elements[i];

            if (!el) continue;

            var text = cleanText(el.innerText || el.textContent || "");

            if (!text) {
                continue;
            }

            var snippet = text.substring(0, 180);

            log(
                "   [" +
                (i + 1) +
                "] " +
                describeElement(el) +
                " | len=" +
                text.length +
                " | text=\"" +
                snippet.replace(/"/g, "'") +
                "\""
            );

            count++;
        }

        return count;
    }

    function scanAssistantDOM(reason) {
        log("================================================");
        log("🧪 ASSISTANT DOM SCAN: " + reason);
        log("URL: " + location.href);

        var selectorGroups = [
            "[data-message-author-role='assistant']",
            "[data-message-author-role='assistant'] .markdown",
            "[data-testid='conversation-turn-assistant']",
            "[data-testid*='conversation-turn']",
            "article",
            "main article",
            "[role='article']",
            "main [role='article']"
        ];

        var totalReported = 0;

        for (var i = 0; i < selectorGroups.length; i++) {
            try {
                var elements = document.querySelectorAll(
                    selectorGroups[i]
                );

                totalReported += reportCandidate(
                    selectorGroups[i],
                    elements,
                    5
                );

            } catch (e) {
                log(
                    "⚠️ Scan selector error: " +
                    selectorGroups[i]
                );
            }
        }

        // --------------------------------------------------------
        // بحث إضافي عن العناصر التي تحتوي على مؤشرات assistant
        // --------------------------------------------------------

        try {
            var all = document.querySelectorAll(
                "[data-message-author-role], [data-testid], article"
            );

            var interesting = [];

            for (var j = 0; j < all.length; j++) {
                var el = all[j];

                var role =
                    el.getAttribute("data-message-author-role") || "";

                var testid =
                    el.getAttribute("data-testid") || "";

                var text =
                    cleanText(el.innerText || el.textContent || "");

                if (!text) continue;

                var looksInteresting =
                    role === "assistant" ||
                    testid.toLowerCase().indexOf("assistant") >= 0 ||
                    testid.toLowerCase().indexOf("conversation") >= 0;

                if (looksInteresting) {
                    interesting.push(el);
                }
            }

            log(
                "🔬 Interesting assistant/conversation candidates: " +
                interesting.length
            );

            for (
                var k = Math.max(0, interesting.length - 5);
                k < interesting.length;
                k++
            ) {
                var candidate = interesting[k];

                var candidateText =
                    cleanText(
                        candidate.innerText ||
                        candidate.textContent ||
                        ""
                    );

                log(
                    "   ⭐ " +
                    describeElement(candidate) +
                    " | len=" +
                    candidateText.length +
                    " | text=\"" +
                    candidateText
                        .substring(0, 180)
                        .replace(/"/g, "'") +
                    "\""
                );
            }

        } catch (e) {
            log(
                "⚠️ Interesting-candidate scan failed: " +
                e.message
            );
        }

        log(
            "🧪 ASSISTANT DOM SCAN END — reported=" +
            totalReported
        );

        log("================================================");
    }

    // ============================================================
    // Diagnostic sequence
    // ============================================================

    function runResponseDiagnostic() {
        log("⏳ Waiting for ChatGPT response DOM...");

        // المسح الأول
        setTimeout(function () {
            scanAssistantDOM("after 2 seconds");
        }, 2000);

        // المسح الثاني
        setTimeout(function () {
            scanAssistantDOM("after 5 seconds");
        }, 5000);

        // المسح الثالث
        setTimeout(function () {
            scanAssistantDOM("after 8 seconds");
        }, 8000);
    }

    // ============================================================
    // Direct Context → Page
    // ============================================================

    browser.runtime.onMessage.addListener(function (message) {

        if (!message) {
            return;
        }

        // --------------------------------------------------------
        // المسار الناجح الذي لا نريد تغييره
        // --------------------------------------------------------

        if (message.type === "CONTEXT_TO_PAGE") {

            var text = message.text || "";
            var contextId = message.contextId || 0;

            log(
                "📥 CONTEXT_TO_PAGE received: " +
                text.length +
                " chars, id=" +
                contextId
            );

            findInputWithRetry(10, 500, function (input) {

                if (!input) {

                    log("❌ ChatGPT input not found");

                    browser.runtime.sendMessage({
                        type: "CONTEXT_WRITTEN",
                        success: false,
                        stage: "input_not_found",
                        contextId: contextId
                    });

                    return;
                }

                var injected = injectIntoInput(
                    input,
                    text
                );

                if (!injected) {

                    log("❌ Text injection failed");

                    browser.runtime.sendMessage({
                        type: "CONTEXT_WRITTEN",
                        success: false,
                        stage: "text_injection_failed",
                        contextId: contextId
                    });

                    return;
                }

                log("✏️ Text injected successfully");

                browser.runtime.sendMessage({
                    type: "CONTEXT_WRITTEN",
                    success: true,
                    stage: "text_injected",
                    contextId: contextId
                });

                var enterSent = sendEnter(input);

                if (enterSent) {

                    log("📤 Enter sent to ChatGPT");

                    browser.runtime.sendMessage({
                        type: "CONTEXT_WRITTEN",
                        success: true,
                        stage: "enter_sent",
                        contextId: contextId
                    });

                    // ------------------------------------------------
                    // فقط بعد الإرسال نبحث عن عنصر الرد.
                    // لا نقرأ الرد ولا نرسله إلى Kotlin.
                    // ------------------------------------------------

                    runResponseDiagnostic();

                } else {

                    log("❌ Enter failed");

                    browser.runtime.sendMessage({
                        type: "CONTEXT_WRITTEN",
                        success: false,
                        stage: "enter_failed",
                        contextId: contextId
                    });
                }
            });

            return;
        }

        // --------------------------------------------------------
        // اختبار يدوي من background إذا احتجناه لاحقاً
        // --------------------------------------------------------

        if (message.type === "SCAN_ASSISTANT_DOM") {
            scanAssistantDOM("manual request");
        }

        // --------------------------------------------------------
        // الاختبار القديم يبقى بدون تغيير
        // --------------------------------------------------------

        if (message.type === "REVERSE_TEST") {
            log("🔁 REVERSE_TEST received");
        }
    });

    // ============================================================
    // جاهزية
    // ============================================================

    try {
        browser.runtime.sendMessage({
            type: "JS_LOG",
            message:
                "✅ Direct injection + response DOM diagnostic ready on " +
                location.href
        });
    } catch (e) {
        console.log(
            TAG +
            ": ready message failed"
        );
    }

})();
