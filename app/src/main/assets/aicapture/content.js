(function () {
    "use strict";

    // ============================================================
    // AiChat — Content Script
    // Response DOM / Shadow DOM diagnostic
    // Version: 1.0.26
    //
    // الهدف:
    // تحديد المكان الحقيقي الذي يوجد فيه نص رد ChatGPT.
    //
    // لا يوجد التقاط للرد.
    // لا يوجد إرسال للرد إلى Kotlin.
    // مسار الحقن الناجح محفوظ.
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

    function cleanText(text) {
        if (!text) return "";

        return String(text)
            .replace(/\s+/g, " ")
            .trim();
    }

    function describeElement(el) {
        if (!el) return "null";

        var tag = el.tagName || "UNKNOWN";
        var id = el.id ? "#" + el.id : "";

        var cls = "";

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

        var attrs = [];

        [
            "role",
            "data-testid",
            "data-message-author-role",
            "aria-label",
            "data-state",
            "data-index"
        ].forEach(function (name) {
            try {
                var value = el.getAttribute(name);

                if (value) {
                    attrs.push(
                        name + '="' + value.substring(0, 100) + '"'
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
    // Shadow DOM
    // ============================================================

    function collectShadowRoots(root, results) {
        if (!root) return;

        try {
            var elements = root.querySelectorAll("*");

            for (var i = 0; i < elements.length; i++) {
                var el = elements[i];

                if (el.shadowRoot) {
                    results.push(el.shadowRoot);

                    collectShadowRoots(
                        el.shadowRoot,
                        results
                    );
                }
            }
        } catch (e) {
            log(
                "⚠️ Shadow root scan error: " +
                e.message
            );
        }
    }

    // ============================================================
    // Text node diagnostic
    // ============================================================

    function scanTextNodes(root, sourceName, candidates) {

        if (!root) return;

        try {
            var walker = document.createTreeWalker(
                root,
                NodeFilter.SHOW_TEXT,
                {
                    acceptNode: function (node) {

                        var text =
                            cleanText(node.nodeValue);

                        if (!text) {
                            return NodeFilter.FILTER_REJECT;
                        }

                        if (text.length < 25) {
                            return NodeFilter.FILTER_REJECT;
                        }

                        var parent =
                            node.parentElement;

                        if (!parent) {
                            return NodeFilter.FILTER_REJECT;
                        }

                        if (!isVisible(parent)) {
                            return NodeFilter.FILTER_REJECT;
                        }

                        return NodeFilter.FILTER_ACCEPT;
                    }
                }
            );

            var node;

            while ((node = walker.nextNode())) {

                var text =
                    cleanText(node.nodeValue);

                var parent =
                    node.parentElement;

                if (!parent) continue;

                candidates.push({
                    text: text,
                    parent: parent,
                    source: sourceName
                });
            }

        } catch (e) {
            log(
                "⚠️ Text-node scan failed (" +
                sourceName +
                "): " +
                e.message
            );
        }
    }

    // ============================================================
    // Visible text candidate scan
    // ============================================================

    function scanVisibleTextCandidates(reason) {

        log("================================================");
        log(
            "🧬 VISIBLE TEXT / SHADOW DOM SCAN: " +
            reason
        );

        var roots = [
            {
                root: document,
                name: "DOCUMENT"
            }
        ];

        var shadowRoots = [];

        collectShadowRoots(
            document,
            shadowRoots
        );

        log(
            "🌑 Shadow roots found: " +
            shadowRoots.length
        );

        for (var i = 0; i < shadowRoots.length; i++) {
            roots.push({
                root: shadowRoots[i],
                name: "SHADOW#" + (i + 1)
            });
        }

        var candidates = [];

        for (var r = 0; r < roots.length; r++) {

            scanTextNodes(
                roots[r].root,
                roots[r].name,
                candidates
            );
        }

        log(
            "📝 Visible text nodes >=25 chars: " +
            candidates.length
        );

        // --------------------------------------------------------
        // إزالة التكرارات تقريبًا حسب parent + text
        // --------------------------------------------------------

        var unique = [];
        var seen = {};

        for (var c = 0; c < candidates.length; c++) {

            var item = candidates[c];

            var key =
                item.source +
                "|" +
                describeElement(item.parent) +
                "|" +
                item.text.substring(0, 250);

            if (seen[key]) {
                continue;
            }

            seen[key] = true;
            unique.push(item);
        }

        log(
            "📝 Unique candidates: " +
            unique.length
        );

        // --------------------------------------------------------
        // نعرض آخر 20 مرشحًا
        // --------------------------------------------------------

        var start =
            Math.max(0, unique.length - 20);

        for (var u = start; u < unique.length; u++) {

            var candidate =
                unique[u];

            var text =
                candidate.text;

            log(
                "🔸 [" +
                (u + 1) +
                "] source=" +
                candidate.source +
                " | " +
                describeElement(candidate.parent) +
                " | len=" +
                text.length +
                ' | text="' +
                text
                    .substring(0, 220)
                    .replace(/"/g, "'") +
                '"'
            );
        }

        // --------------------------------------------------------
        // ابحث عن أكبر النصوص
        // --------------------------------------------------------

        var sorted =
            unique.slice().sort(function (a, b) {
                return (
                    b.text.length -
                    a.text.length
                );
            });

        log("📊 TOP LONG TEXT CANDIDATES:");

        var topCount =
            Math.min(10, sorted.length);

        for (var t = 0; t < topCount; t++) {

            var top =
                sorted[t];

            log(
                "⭐ TOP[" +
                (t + 1) +
                "] " +
                top.source +
                " | " +
                describeElement(top.parent) +
                " | len=" +
                top.text.length +
                ' | text="' +
                top.text
                    .substring(0, 300)
                    .replace(/"/g, "'") +
                '"'
            );
        }

        log(
            "🧬 VISIBLE TEXT / SHADOW DOM SCAN END"
        );

        log("================================================");
    }

    // ============================================================
    // Input — المسار الناجح السابق
    // ============================================================

    function isUsableInput(el) {
        if (!el) return false;

        var rect =
            el.getBoundingClientRect();

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

                    input.textContent =
                        text;
                }

                input.dispatchEvent(
                    new InputEvent(
                        "input",
                        {
                            bubbles: true,
                            composed: true,
                            inputType:
                                "insertText",
                            data: text
                        }
                    )
                );

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
    // بعد الإرسال: فحص DOM
    // ============================================================

    function runResponseDiagnostic() {

        log(
            "⏳ Waiting for ChatGPT response DOM..."
        );

        setTimeout(function () {

            scanVisibleTextCandidates(
                "after 2 seconds"
            );

        }, 2000);

        setTimeout(function () {

            scanVisibleTextCandidates(
                "after 5 seconds"
            );

        }, 5000);

        setTimeout(function () {

            scanVisibleTextCandidates(
                "after 8 seconds"
            );

        }, 8000);
    }

    // ============================================================
    // Messages
    // ============================================================

    browser.runtime.onMessage.addListener(
        function (message) {

            if (!message) return;

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
                        }
                    }
                );

                return;
            }

            if (
                message.type ===
                "SCAN_ASSISTANT_DOM"
            ) {

                scanVisibleTextCandidates(
                    "manual request"
                );
            }

            if (
                message.type ===
                "REVERSE_TEST"
            ) {

                log(
                    "🔁 REVERSE_TEST received"
                );
            }
        }
    );

    // ============================================================
    // Ready
    // ============================================================

    log(
        "🔬 Response text/shadow diagnostic content.js ready"
    );

    log(
        "🌐 Domain: " +
        location.hostname
    );

    try {

        browser.runtime.sendMessage({
            type: "JS_LOG",
            message:
                "✅ Direct injection + deep response DOM diagnostic ready on " +
                location.href
        });

    } catch (e) {}

})();
