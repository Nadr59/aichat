(function () {
    "use strict";

    // ============================================================
    // AiChat — Content Script
    // Version: 1.0.27
    //
    // الوظائف:
    // 1. استقبال CONTEXT_TO_PAGE
    // 2. حقن النص في ChatGPT
    // 3. إرسال Enter
    // 4. مراقبة رد المساعد
    // 5. استخراج رد واحد فقط
    // 6. إرسال ASSISTANT_RESPONSE إلى background.js
    //
    // لا يوجد:
    // - RAG
    // - MemoryCurator
    // - قراءة document.body بالكامل بغرض الحفظ
    // - تغيير مسار الحقن الناجح
    // ============================================================

    var TAG = "AiChat";
    var BUILD = "v1.0.27";

    // ============================================================
    // Response capture state
    // ============================================================

    var responseJob = null;
    var responseTimer = null;
    var responseObserver = null;

    var lastResponseFingerprint = "";
    var responseSequence = 0;

    var RESPONSE_MIN_LEN = 2;
    var RESPONSE_STABLE_MS = 1800;
    var RESPONSE_TIMEOUT_MS = 30000;
    var RESPONSE_SCAN_MS = 800;

    // ============================================================
    // Logging
    // ============================================================

    function log(message) {
        try {
            browser.runtime.sendMessage({
                type: "JS_LOG",
                message: message
            });
        } catch (e) {
            try {
                console.log(TAG + ": " + message);
            } catch (ignore) {}
        }
    }

    function debug(message) {
        try {
            var time = new Date().toLocaleTimeString("en-US", {
                hour12: false,
                hour: "2-digit",
                minute: "2-digit",
                second: "2-digit"
            });

            log(time + " " + message);
        } catch (e) {
            log(message);
        }
    }

    function cleanText(text) {
        if (!text) return "";

        return String(text)
            .replace(/\u200B/g, "")
            .replace(/\uFEFF/g, "")
            .replace(/\r/g, "")
            .replace(/[ \t]+/g, " ")
            .replace(/\n{3,}/g, "\n\n")
            .trim();
    }

    function normalizeFingerprint(text) {
        return cleanText(text)
            .replace(/\s+/g, " ")
            .trim();
    }

    function describeElement(el) {
        if (!el) return "null";

        var tag = el.tagName || "UNKNOWN";
        var id = el.id ? "#" + el.id : "";

        var cls = "";

        try {
            if (
                typeof el.className === "string" &&
                el.className.trim()
            ) {
                cls =
                    "." +
                    el.className
                        .trim()
                        .split(/\s+/)
                        .slice(0, 4)
                        .join(".");
            }
        } catch (e) {}

        var attrs = [];

        [
            "role",
            "data-testid",
            "data-message-author-role",
            "aria-label",
            "data-state",
            "data-index",
            "data-is-streaming"
        ].forEach(function (name) {
            try {
                var value = el.getAttribute(name);

                if (value) {
                    attrs.push(
                        name +
                            '="' +
                            String(value).substring(0, 100) +
                            '"'
                    );
                }
            } catch (e) {}
        });

        return (
            tag +
            id +
            cls +
            (attrs.length
                ? " [" + attrs.join(" ") + "]"
                : "")
        );
    }

    // ============================================================
    // Visibility
    // ============================================================

    function isVisible(el) {
        try {
            if (!el) return false;

            var style = window.getComputedStyle(el);

            if (
                style.display === "none" ||
                style.visibility === "hidden" ||
                style.opacity === "0"
            ) {
                return false;
            }

            var rect = el.getBoundingClientRect();

            return (
                rect.width > 0 &&
                rect.height > 0
            );
        } catch (e) {
            return false;
        }
    }

    // ============================================================
    // ChatGPT input
    // ============================================================

    function isUsableInput(el) {
        if (!el) return false;

        try {
            var rect = el.getBoundingClientRect();

            if (
                rect.width <= 0 ||
                rect.height <= 0
            ) {
                return false;
            }

            if (
                el.disabled ||
                el.readOnly
            ) {
                return false;
            }

            return true;
        } catch (e) {
            return false;
        }
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

            "textarea[aria-label*='الدردشة']",

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

        for (
            var i = 0;
            i < selectors.length;
            i++
        ) {

            try {

                var elements =
                    document.querySelectorAll(
                        selectors[i]
                    );

                for (
                    var j = 0;
                    j < elements.length;
                    j++
                ) {

                    if (
                        isUsableInput(
                            elements[j]
                        )
                    ) {

                        log(
                            "✅ ChatGPT input found: " +
                            describeElement(
                                elements[j]
                            )
                        );

                        return elements[j];
                    }
                }

            } catch (e) {}
        }

        return null;
    }

    function findInputWithRetry(
        attempt,
        delay,
        callback
    ) {

        var input =
            findChatGPTInput();

        if (input) {
            callback(input);
            return;
        }

        if (attempt <= 0) {
            callback(null);
            return;
        }

        setTimeout(function () {

            findInputWithRetry(
                attempt - 1,
                delay,
                callback
            );

        }, delay);
    }

    // ============================================================
    // Injection
    // ============================================================

    function injectIntoInput(
        input,
        text
    ) {

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

                var descriptor =
                    Object.getOwnPropertyDescriptor(
                        prototype,
                        "value"
                    );

                if (
                    descriptor &&
                    descriptor.set
                ) {

                    descriptor.set.call(
                        input,
                        text
                    );

                } else {

                    input.value = text;
                }

                input.dispatchEvent(
                    new Event(
                        "input",
                        {
                            bubbles: true,
                            composed: true
                        }
                    )
                );

                input.dispatchEvent(
                    new Event(
                        "change",
                        {
                            bubbles: true,
                            composed: true
                        }
                    )
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

                try {

                    input.dispatchEvent(
                        new InputEvent(
                            "input",
                            {
                                bubbles: true,
                                composed: true,
                                inputType: "insertText",
                                data: text
                            }
                        )
                    );

                } catch (e) {

                    input.dispatchEvent(
                        new Event(
                            "input",
                            {
                                bubbles: true,
                                composed: true
                            }
                        )
                    );
                }

                return true;
            }

            return false;

        } catch (e) {

            log(
                "❌ Text injection exception: " +
                e.message
            );

            return false;
        }
    }

    // ============================================================
    // Enter
    // ============================================================

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

            input.dispatchEvent(
                new KeyboardEvent(
                    "keydown",
                    options
                )
            );

            input.dispatchEvent(
                new KeyboardEvent(
                    "keypress",
                    options
                )
            );

            input.dispatchEvent(
                new KeyboardEvent(
                    "keyup",
                    options
                )
            );

            return true;

        } catch (e) {

            log(
                "❌ Enter exception: " +
                e.message
            );

            return false;
        }
    }

    // ============================================================
    // Response extraction helpers
    // ============================================================

    function isStreamingElement(el) {

        if (!el) return false;

        try {

            if (
                el.getAttribute(
                    "data-is-streaming"
                ) === "true"
            ) {
                return true;
            }

            if (
                el.querySelector(
                    "[data-is-streaming='true']"
                )
            ) {
                return true;
            }

        } catch (e) {}

        return false;
    }

    function extractAssistantFromRoot(root) {

        if (!root) return [];

        var results = [];

        var selectors = [

            '[data-message-author-role="assistant"]',

            '[data-testid="conversation-turn-assistant"]',

            '[data-testid*="conversation-turn"]',

            'article[data-testid*="conversation"]',

            '[role="article"]'
        ];

        for (
            var s = 0;
            s < selectors.length;
            s++
        ) {

            try {

                var elements =
                    root.querySelectorAll(
                        selectors[s]
                    );

                for (
                    var i = 0;
                    i < elements.length;
                    i++
                ) {

                    var el =
                        elements[i];

                    if (!isVisible(el)) {
                        continue;
                    }

                    if (isStreamingElement(el)) {
                        continue;
                    }

                    var text = "";

                    try {

                        var inner =
                            el.querySelector(
                                ".markdown, .prose, .whitespace-pre-wrap"
                            );

                        text = cleanText(
                            inner
                                ? inner.innerText
                                : el.innerText
                        );

                    } catch (e) {

                        text = cleanText(
                            el.innerText
                        );
                    }

                    if (
                        text.length >=
                        RESPONSE_MIN_LEN
                    ) {

                        results.push({
                            text: text,
                            element: el,
                            source: "assistant-selector"
                        });
                    }
                }

            } catch (e) {}
        }

        return results;
    }

    // ============================================================
    // Generic visible text extraction
    // ============================================================

    function extractVisibleTextBlocks(root) {

        if (!root) return [];

        var results = [];

        try {

            var blocks =
                root.querySelectorAll(
                    "article, section, main, p, pre, blockquote"
                );

            for (
                var i = 0;
                i < blocks.length;
                i++
            ) {

                var el = blocks[i];

                if (!isVisible(el)) {
                    continue;
                }

                if (
                    el.closest(
                        "nav, aside, header, footer, form"
                    )
                ) {
                    continue;
                }

                if (
                    el.matches(
                        "textarea, input, button"
                    )
                ) {
                    continue;
                }

                if (
                    el.closest(
                        "[contenteditable='true']"
                    )
                ) {
                    continue;
                }

                if (isStreamingElement(el)) {
                    continue;
                }

                var text =
                    cleanText(
                        el.innerText
                    );

                if (
                    text.length >=
                    RESPONSE_MIN_LEN
                ) {

                    results.push({
                        text: text,
                        element: el,
                        source: "visible-block"
                    });
                }
            }

        } catch (e) {}

        return results;
    }

    // ============================================================
    // Shadow DOM
    // ============================================================

    function collectShadowRoots(
        root,
        results
    ) {

        if (!root) return;

        try {

            var elements =
                root.querySelectorAll("*");

            for (
                var i = 0;
                i < elements.length;
                i++
            ) {

                var el =
                    elements[i];

                if (el.shadowRoot) {

                    results.push(
                        el.shadowRoot
                    );

                    collectShadowRoots(
                        el.shadowRoot,
                        results
                    );
                }
            }

        } catch (e) {}
    }

    // ============================================================
    // Same-origin iframe scan
    // ============================================================

    function collectFrameRoots() {

        var roots = [];

        roots.push({
            root: document,
            source: "DOCUMENT"
        });

        try {

            var iframes =
                document.querySelectorAll(
                    "iframe"
                );

            for (
                var i = 0;
                i < iframes.length;
                i++
            ) {

                try {

                    var frameDocument =
                        iframes[i].contentDocument;

                    if (frameDocument) {

                        roots.push({
                            root: frameDocument,
                            source:
                                "IFRAME#" +
                                (i + 1)
                        });
                    }

                } catch (e) {

                    debug(
                        "🔒 iframe[" +
                        i +
                        "] inaccessible"
                    );
                }
            }

        } catch (e) {}

        return roots;
    }

    // ============================================================
    // Find latest possible assistant response
    // ============================================================

    function findLatestResponseCandidate() {

        var roots =
            collectFrameRoots();

        var all = [];

        for (
            var r = 0;
            r < roots.length;
            r++
        ) {

            var rootInfo =
                roots[r];

            var assistant =
                extractAssistantFromRoot(
                    rootInfo.root
                );

            for (
                var a = 0;
                a < assistant.length;
                a++
            ) {

                assistant[a].source =
                    rootInfo.source +
                    "/" +
                    assistant[a].source;

                all.push(
                    assistant[a]
                );
            }
        }

        // --------------------------------------------------------
        // الأولوية لعنصر assistant الصريح
        // --------------------------------------------------------

        if (all.length > 0) {

            return all[
                all.length - 1
            ];
        }

        // --------------------------------------------------------
        // Fallback محدود جدًا:
        // لا نأخذ document.body.innerText.
        // نبحث عن أكبر كتلة مرئية منفردة.
        // --------------------------------------------------------

        var generic = [];

        for (
            var g = 0;
            g < roots.length;
            g++
        ) {

            var blocks =
                extractVisibleTextBlocks(
                    roots[g].root
                );

            for (
                var b = 0;
                b < blocks.length;
                b++
            ) {

                blocks[b].source =
                    roots[g].source +
                    "/" +
                    blocks[b].source;

                generic.push(
                    blocks[b]
                );
            }
        }

        if (generic.length === 0) {
            return null;
        }

        generic.sort(function (x, y) {
            return (
                y.text.length -
                x.text.length
            );
        });

        return generic[0];
    }

    // ============================================================
    // Response baseline
    // ============================================================

    function getResponseSnapshot() {

        var roots =
            collectFrameRoots();

        var snapshot = {
            assistantCount: 0,
            longestText: "",
            fingerprints: []
        };

        for (
            var r = 0;
            r < roots.length;
            r++
        ) {

            var items =
                extractAssistantFromRoot(
                    roots[r].root
                );

            snapshot.assistantCount +=
                items.length;

            for (
                var i = 0;
                i < items.length;
                i++
            ) {

                var text =
                    normalizeFingerprint(
                        items[i].text
                    );

                if (
                    text.length >
                    snapshot.longestText.length
                ) {
                    snapshot.longestText =
                        text;
                }

                snapshot.fingerprints.push(
                    text
                );
            }
        }

        return snapshot;
    }

    // ============================================================
    // Stop old capture job
    // ============================================================

    function stopResponseCapture() {

        if (responseTimer) {

            clearInterval(
                responseTimer
            );

            responseTimer = null;
        }

        if (responseObserver) {

            try {
                responseObserver.disconnect();
            } catch (e) {}

            responseObserver = null;
        }

        responseJob = null;
    }

    // ============================================================
    // Send ASSISTANT_RESPONSE
    // ============================================================

    function sendAssistantResponse(
        text,
        turnId
    ) {

        text = cleanText(text);

        if (
            text.length <
            RESPONSE_MIN_LEN
        ) {
            return false;
        }

        var fingerprint =
            normalizeFingerprint(text);

        if (
            fingerprint ===
            lastResponseFingerprint
        ) {

            debug(
                "♻️ Duplicate assistant response ignored"
            );

            return false;
        }

        lastResponseFingerprint =
            fingerprint;

        var message = {
            type: "ASSISTANT_RESPONSE",
            text: text,
            platform: "chatgpt",
            turnId:
                turnId ||
                "chatgpt-" +
                    Date.now() +
                    "-" +
                    responseSequence
        };

        responseSequence++;

        debug(
            "📤 ASSISTANT_RESPONSE sending, len=" +
            text.length +
            " turnId=" +
            message.turnId
        );

        try {

            browser.runtime.sendMessage(
                message
            );

            return true;

        } catch (e) {

            debug(
                "❌ ASSISTANT_RESPONSE failed: " +
                e.message
            );

            return false;
        }
    }

    // ============================================================
    // Start automatic response capture
    // ============================================================

    function startResponseCapture(
        contextId
    ) {

        stopResponseCapture();

        var baseline =
            getResponseSnapshot();

        responseJob = {
            contextId:
                contextId || 0,

            startedAt:
                Date.now(),

            baselineCount:
                baseline.assistantCount,

            baselineLongest:
                baseline.longestText,

            candidate: "",

            candidateSince: 0,

            sent: false
        };

        debug(
            "🎯 Response capture started" +
            " baselineAssistantCount=" +
            baseline.assistantCount +
            " baselineLen=" +
            baseline.longestText.length
        );

        // --------------------------------------------------------
        // MutationObserver
        // --------------------------------------------------------

        try {

            responseObserver =
                new MutationObserver(
                    function () {

                        checkForAssistantResponse(
                            "mutation"
                        );
                    }
                );

            responseObserver.observe(
                document.documentElement,
                {
                    childList: true,
                    subtree: true,
                    characterData: true
                }
            );

        } catch (e) {

            debug(
                "⚠️ MutationObserver unavailable: " +
                e.message
            );
        }

        // --------------------------------------------------------
        // Polling is only for detection.
        // It does NOT send anything to Kotlin.
        // --------------------------------------------------------

        responseTimer =
            setInterval(
                function () {

                    checkForAssistantResponse(
                        "timer"
                    );

                },
                RESPONSE_SCAN_MS
            );

        // --------------------------------------------------------
        // Timeout
        // --------------------------------------------------------

        setTimeout(
            function () {

                if (
                    !responseJob ||
                    responseJob.sent
                ) {
                    return;
                }

                debug(
                    "⏱️ Response capture timeout"
                );

                stopResponseCapture();

            },
            RESPONSE_TIMEOUT_MS
        );
    }

    // ============================================================
    // Check response
    // ============================================================

    function checkForAssistantResponse(
        source
    ) {

        if (!responseJob) {
            return;
        }

        if (responseJob.sent) {
            return;
        }

        var elapsed =
            Date.now() -
            responseJob.startedAt;

        if (
            elapsed >
            RESPONSE_TIMEOUT_MS
        ) {
            stopResponseCapture();
            return;
        }

        var candidate =
            findLatestResponseCandidate();

        if (!candidate) {
            return;
        }

        var text =
            cleanText(
                candidate.text
            );

        if (
            text.length <
            RESPONSE_MIN_LEN
        ) {
            return;
        }

        var normalized =
            normalizeFingerprint(
                text
            );

        // --------------------------------------------------------
        // لا نأخذ نصًا قديمًا كان موجودًا قبل الإرسال
        // --------------------------------------------------------

        if (
            normalized ===
            responseJob.baselineLongest
        ) {
            return;
        }

        // --------------------------------------------------------
        // النص تغير أثناء streaming
        // --------------------------------------------------------

        if (
            normalized !==
            responseJob.candidate
        ) {

            responseJob.candidate =
                normalized;

            responseJob.candidateSince =
                Date.now();

            debug(
                "📝 Response candidate changed" +
                " len=" +
                text.length +
                " source=" +
                candidate.source
            );

            return;
        }

        // --------------------------------------------------------
        // ننتظر استقرار النص
        // --------------------------------------------------------

        var stableFor =
            Date.now() -
            responseJob.candidateSince;

        if (
            stableFor <
            RESPONSE_STABLE_MS
        ) {
            return;
        }

        // --------------------------------------------------------
        // تحقق أخير
        // --------------------------------------------------------

        debug(
            "✅ Assistant response stable" +
            " len=" +
            text.length +
            " source=" +
            candidate.source
        );

        responseJob.sent = true;

        var turnId =
            "ctx-" +
            responseJob.contextId +
            "-" +
            Date.now();

        var sent =
            sendAssistantResponse(
                text,
                turnId
            );

        if (sent) {

            debug(
                "✅ ASSISTANT_RESPONSE sent successfully"
            );

        }

        stopResponseCapture();
    }

    // ============================================================
    // Diagnostic frame scan
    // ============================================================

    function scanFrames(reason) {

        log("================================================");
        log(
            "🖼️ FRAME SCAN: " +
            reason
        );

        try {

            log(
                "📄 Current document: " +
                location.href
            );

            log(
                "📄 Title: " +
                (document.title || "(empty)")
            );

            log(
                "🪟 Window frames count: " +
                window.frames.length
            );

            var iframes =
                document.querySelectorAll(
                    "iframe"
                );

            log(
                "🖼️ iframe elements: " +
                iframes.length
            );

            for (
                var i = 0;
                i < iframes.length;
                i++
            ) {

                var frame =
                    iframes[i];

                var src =
                    frame.getAttribute(
                        "src"
                    ) || "";

                var title =
                    frame.getAttribute(
                        "title"
                    ) || "";

                var name =
                    frame.getAttribute(
                        "name"
                    ) || "";

                log(
                    "🔹 iframe[" +
                    i +
                    "] src=\"" +
                    src.substring(0, 250) +
                    "\" title=\"" +
                    title.substring(0, 100) +
                    "\" name=\"" +
                    name.substring(0, 100) +
                    "\""
                );

                try {

                    var frameDocument =
                        frame.contentDocument;

                    if (frameDocument) {

                        log(
                            "   ✅ iframe[" +
                            i +
                            "] same-origin accessible"
                        );

                        log(
                            "   URL=" +
                            frameDocument.location.href
                        );

                        log(
                            "   title=" +
                            frameDocument.title
                        );

                        var bodyText =
                            cleanText(
                                frameDocument.body
                                    ? frameDocument.body.innerText
                                    : ""
                            );

                        log(
                            "   textLen=" +
                            bodyText.length +
                            " text=\"" +
                            bodyText
                                .substring(0, 300)
                                .replace(/"/g, "'") +
                            "\""
                        );

                    } else {

                        log(
                            "   ⚠️ iframe[" +
                            i +
                            "] contentDocument unavailable"
                        );
                    }

                } catch (e) {

                    log(
                        "   🔒 iframe[" +
                        i +
                        "] inaccessible: " +
                        e.message
                    );
                }
            }

        } catch (e) {

            log(
                "❌ Frame scan failed: " +
                e.message
            );
        }

        log(
            "🖼️ FRAME SCAN END"
        );

        log("================================================");
    }

    // ============================================================
    // Response diagnostic
    // ============================================================

    function runResponseDiagnostic() {

        log(
            "⏳ Waiting for ChatGPT response DOM..."
        );

        setTimeout(
            function () {

                scanFrames(
                    "after 2 seconds"
                );

            },
            2000
        );

        setTimeout(
            function () {

                scanFrames(
                    "after 5 seconds"
                );

            },
            5000
        );

        setTimeout(
            function () {

                scanFrames(
                    "after 8 seconds"
                );

            },
            8000
        );
    }

    // ============================================================
    // Messages
    // ============================================================

    browser.runtime.onMessage.addListener(
        function (message) {

            if (!message) {
                return;
            }

            // ====================================================
            // Kotlin → background → content
            // ====================================================

            if (
                message.type ===
                "CONTEXT_TO_PAGE"
            ) {

                var text =
                    message.text || "";

                var contextId =
                    message.contextId || 0;

                log(
                    "📥 CONTEXT_TO_PAGE received: " +
                    text.length +
                    " chars, id=" +
                    contextId
                );

                // -----------------------------------------------
                // مهم:
                // نبدأ capture قبل الحقن حتى نسجل baseline.
                // -----------------------------------------------

                startResponseCapture(
                    contextId
                );

                findInputWithRetry(
                    10,
                    500,
                    function (input) {

                        if (!input) {

                            log(
                                "❌ ChatGPT input not found"
                            );

                            browser.runtime.sendMessage({
                                type:
                                    "CONTEXT_WRITTEN",

                                success:
                                    false,

                                stage:
                                    "input_not_found",

                                contextId:
                                    contextId
                            });

                            stopResponseCapture();

                            return;
                        }

                        var injected =
                            injectIntoInput(
                                input,
                                text
                            );

                        if (!injected) {

                            log(
                                "❌ Text injection failed"
                            );

                            browser.runtime.sendMessage({
                                type:
                                    "CONTEXT_WRITTEN",

                                success:
                                    false,

                                stage:
                                    "text_injection_failed",

                                contextId:
                                    contextId
                            });

                            stopResponseCapture();

                            return;
                        }

                        log(
                            "✏️ Text injected successfully"
                        );

                        browser.runtime.sendMessage({
                            type:
                                "CONTEXT_WRITTEN",

                            success:
                                true,

                            stage:
                                "text_injected",

                            contextId:
                                contextId
                        });

                        // ---------------------------------------
                        // الحفاظ على مسار Enter الناجح
                        // ---------------------------------------

                        var enterSent =
                            sendEnter(input);

                        if (enterSent) {

                            log(
                                "📤 Enter sent to ChatGPT"
                            );

                            browser.runtime.sendMessage({
                                type:
                                    "CONTEXT_WRITTEN",

                                success:
                                    true,

                                stage:
                                    "enter_sent",

                                contextId:
                                    contextId
                            });

                            // -----------------------------------
                            // التشخيص فقط
                            // -----------------------------------

                            runResponseDiagnostic();

                        } else {

                            log(
                                "❌ Enter failed"
                            );

                            browser.runtime.sendMessage({
                                type:
                                    "CONTEXT_WRITTEN",

                                success:
                                    false,

                                stage:
                                    "enter_failed",

                                contextId:
                                    contextId
                            });

                            stopResponseCapture();
                        }
                    }
                );

                return;
            }

            // ====================================================
            // Manual response scan
            // ====================================================

            if (
                message.type ===
                "SCAN_ASSISTANT_DOM"
            ) {

                debug(
                    "🔎 Manual assistant scan requested"
                );

                var candidate =
                    findLatestResponseCandidate();

                if (candidate) {

                    debug(
                        "🔎 Candidate found len=" +
                        candidate.text.length +
                        " source=" +
                        candidate.source
                    );

                } else {

                    debug(
                        "🔎 No assistant candidate found"
                    );
                }

                return;
            }

            // ====================================================
            // Manual capture request
            // ====================================================

            if (
                message.type ===
                "CAPTURE_ASSISTANT_RESPONSE"
            ) {

                debug(
                    "🎯 Manual assistant capture requested"
                );

                var manualCandidate =
                    findLatestResponseCandidate();

                if (manualCandidate) {

                    sendAssistantResponse(
                        manualCandidate.text,
                        message.turnId ||
                            "manual-" +
                                Date.now()
                    );

                } else {

                    debug(
                        "❌ Manual capture: no candidate"
                    );
                }

                return;
            }

            // ====================================================
            // Reverse test compatibility
            // ====================================================

            if (
                message.type ===
                "REVERSE_TEST"
            ) {

                log(
                    "🔁 REVERSE_TEST received"
                );

                return;
            }
        }
    );

    // ============================================================
    // Ready
    // ============================================================

    log(
        "🚀 AiChat content.js " +
        BUILD +
        " loaded"
    );

    log(
        "🌐 Domain: " +
        location.hostname
    );

    log(
        "🪟 Top frame: " +
        (window === window.top)
    );

    try {

        browser.runtime.sendMessage({
            type: "JS_LOG",

            message:
                "✅ Direct injection + automatic assistant response capture ready on " +
                location.href
        });

    } catch (e) {}

})();
