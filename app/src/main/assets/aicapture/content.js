(function () {
    "use strict";

    // ============================================================
    // AiChat — PLATFORM REGISTRY + ASSISTANT RESPONSE CAPTURE
    // Version 1.0.32
    //
    // Architecture:
    //   Content Script
    //       ├── Platform Registry
    //       ├── Input / Injection
    //       └── Response Capture
    //
    // Current adapters:
    //   - ChatGPT
    //   - Gemini
    //   - Generic fallback
    //
    // IMPORTANT:
    //   Existing GeckoView / Manifest V2 / browser.* architecture
    //   is preserved.
    // ============================================================

    var VERSION = "1.0.32";

    // ============================================================
    // DEBUG LOG
    // ============================================================

    function log(message) {
        try {
            browser.runtime.sendMessage({
                type: "DEBUG_LOG",
                text: message
            });
        } catch (e) {
            console.log("[AiChat] " + message);
        }
    }

    log("🧪 content.js loaded v=" + VERSION);

    // ============================================================
    // PLATFORM REGISTRY
    // ============================================================

    var PLATFORMS = {

        chatgpt: {
            id: "chatgpt",
            name: "ChatGPT",

            matches: function () {
                return location.hostname === "chatgpt.com" ||
                       location.hostname === "chat.openai.com";
            },

            responseSelectors: [
                '[data-message-author-role="assistant"]',
                '[data-role="assistant"]',
                '[data-message-author="assistant"]',
                ".agent-turn"
            ],

            extractResponse: function (element) {
                if (!element) {
                    return "";
                }

                // Prefer rendered markdown.
                var markdown = null;

                try {
                    markdown =
                        element.querySelector(".markdown");
                } catch (e) {}

                if (markdown) {
                    return getElementText(markdown);
                }

                return getElementText(element);
            },

            legacyContainerMode: true
        },

        gemini: {
            id: "gemini",
            name: "Gemini",

            matches: function () {
                return location.hostname === "gemini.google.com";
            },

            responseSelectors: [
                "model-response",
                ".model-response",
                ".model-response-container",
                ".model-response-text",
                '[data-test-id="model-response"]',
                '[data-test-id*="model-response"]',
                "message-content",
                ".response-container",
                ".presented-response-container",
                '[aria-label="Gemini response"]',
                '[data-message-author-role="assistant"]',
                '[data-message-author-role="model"]'
            ],

            extractResponse: function (element) {
                if (!element) {
                    return "";
                }

                // Prefer the actual response content when available.
                var contentSelectors = [
                    ".model-response-text",
                    ".markdown",
                    "message-content",
                    ".response-content",
                    ".response-container"
                ];

                for (var i = 0; i < contentSelectors.length; i++) {
                    try {
                        var child =
                            element.querySelector(
                                contentSelectors[i]
                            );

                        if (child) {
                            var childText =
                                getElementText(child);

                            if (childText) {
                                return childText;
                            }
                        }
                    } catch (e) {}
                }

                return getElementText(element);
            },

            legacyContainerMode: false
        },
        arena: {
    id: "arena",
    name: "Arena",
    matches: function () {
        return location.hostname === "arena.ai";
    },

    responseSelectors: [
        "main .prose",
        ".prose.prose-base",
        "div.prose"
    ],

    legacyContainerMode: false
},

        generic: {
            id: "generic",
            name: "Generic Web AI",

            matches: function () {
                return true;
            },

            responseSelectors: [
                '[data-message-author-role="assistant"]',
                '[data-role="assistant"]',
                '[data-message-author="assistant"]',
                ".assistant-message",
                ".ai-message",
                ".model-response",
                ".response-container"
            ],

            extractResponse: function (element) {
                return getElementText(element);
            },

            legacyContainerMode: false
        }
    };

    // ============================================================
    // PLATFORM DETECTION
    // ============================================================

    function detectPlatform() {

        if (PLATFORMS.chatgpt.matches()) {
            return PLATFORMS.chatgpt;
        }

        if (PLATFORMS.gemini.matches()) {
            return PLATFORMS.gemini;
        }

        return PLATFORMS.generic;
    }

    var CURRENT_PLATFORM = detectPlatform();

    log(
        "🌐 Platform detected: " +
        CURRENT_PLATFORM.name +
        " (" +
        CURRENT_PLATFORM.id +
        ")"
    );

    // ============================================================
    // INPUT
    // ============================================================

    var INPUT_SELECTORS = [
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

    function findInput() {

        for (var i = 0; i < INPUT_SELECTORS.length; i++) {

            try {

                var element =
                    document.querySelector(
                        INPUT_SELECTORS[i]
                    );

                if (element) {
                    return element;
                }

            } catch (e) {}
        }

        return null;
    }

    function findInputWithRetry(
        attempts,
        delay,
        callback
    ) {

        var count = 0;

        function attempt() {

            var input = findInput();

            if (input) {
                callback(input);
                return;
            }

            count++;

            if (count >= attempts) {

                log(
                    "❌ " +
                    CURRENT_PLATFORM.name +
                    " input not found"
                );

                return;
            }

            setTimeout(
                attempt,
                delay
            );
        }

        attempt();
    }

    // ============================================================
    // INJECTION
    // ============================================================

    function injectText(input, text) {

        try {

            if (
                input.tagName === "TEXTAREA" ||
                input.tagName === "INPUT"
            ) {

                var prototype =
                    Object.getPrototypeOf(input);

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

            } else {

                input.focus();

                try {

                    document.execCommand(
                        "selectAll",
                        false,
                        null
                    );

                } catch (e) {}

                try {

                    document.execCommand(
                        "insertText",
                        false,
                        text
                    );

                } catch (e) {

                    input.textContent = text;

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
                }
            }

            log(
                "✅ Text injected successfully"
            );

            return true;

        } catch (e) {

            log(
                "❌ Text injection failed: " +
                e
            );

            return false;
        }
    }

    // ============================================================
    // ENTER
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

            log(
                "✅ Enter sent to " +
                CURRENT_PLATFORM.name
            );

            return true;

        } catch (e) {

            log(
                "❌ Enter failed: " +
                e
            );

            return false;
        }
    }

    // ============================================================
    // GENERIC DOM TEXT
    // ============================================================

    function getElementText(element) {

        try {

            return (
                element.innerText ||
                element.textContent ||
                ""
            )
                .replace(/\u00a0/g, " ")
                .replace(/\r/g, "")
                .trim();

        } catch (e) {

            return "";
        }
    }

    // ============================================================
    // CHATGPT LEGACY CONTAINERS
    //
    // Kept intentionally because the existing ChatGPT capture
    // path is already proven to work.
    // ============================================================

    function getChatGPTConversationContainers() {

        var result = [];

        try {

            var sections =
                document.querySelectorAll(
                    'section[aria-label^="محادثة"]'
                );

            for (
                var i = 0;
                i < sections.length;
                i++
            ) {

                result.push(
                    sections[i]
                );
            }

        } catch (e) {}

        if (result.length === 0) {

            try {

                var regions =
                    document.querySelectorAll(
                        '[role="region"][aria-label="محادثة"]'
                    );

                for (
                    var j = 0;
                    j < regions.length;
                    j++
                ) {

                    result.push(
                        regions[j]
                    );
                }

            } catch (e) {}
        }

        return result;
    }

    // ============================================================
    // PLATFORM RESPONSE ELEMENTS
    // ============================================================

    function getPlatformResponseElements() {

        var result = [];

        if (
            CURRENT_PLATFORM.id ===
            "chatgpt" &&
            CURRENT_PLATFORM.legacyContainerMode
        ) {

            // Preserve the old proven ChatGPT route.
            return getChatGPTConversationContainers();
        }

        var selectors =
            CURRENT_PLATFORM.responseSelectors;

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
                        result.indexOf(
                            elements[j]
                        ) === -1
                    ) {

                        result.push(
                            elements[j]
                        );
                    }
                }

            } catch (e) {}
        }

        return result;
    }

    // ============================================================
    // RESPONSE CLEANING
    // ============================================================

    function cleanAssistantResponse(text) {

        if (!text) {
            return "";
        }

        var cleaned = text;

        cleaned = cleaned
            .replace(/\r\n/g, "\n")
            .replace(/\r/g, "\n");

        // --------------------------------------------------------
        // ChatGPT footer
        // --------------------------------------------------------

        var footer =
            "ChatGPT هو نظام ذكاء اصطناعي وقد يخطئ.";

        var footerIndex =
            cleaned.indexOf(footer);

        if (footerIndex !== -1) {

            cleaned =
                cleaned.substring(
                    0,
                    footerIndex
                );
        }

        // --------------------------------------------------------
        // Known sources markers
        // --------------------------------------------------------

        var sourceMarkers = [
            "\nF المصادر",
            "\nالمصادر",
            "F المصادر"
        ];

        var sourceIndex = -1;

        for (
            var i = 0;
            i < sourceMarkers.length;
            i++
        ) {

            var index =
                cleaned.indexOf(
                    sourceMarkers[i]
                );

            if (index !== -1) {

                if (
                    sourceIndex === -1 ||
                    index < sourceIndex
                ) {

                    sourceIndex = index;
                }
            }
        }

        if (sourceIndex !== -1) {

            cleaned =
                cleaned.substring(
                    0,
                    sourceIndex
                );
        }

        // --------------------------------------------------------
        // Gemini / generic obvious UI markers
        // --------------------------------------------------------

        var genericFooterMarkers = [
            "\nWas this response helpful?",
            "\nهل كانت هذه الإجابة مفيدة؟"
        ];

        for (
            var g = 0;
            g < genericFooterMarkers.length;
            g++
        ) {

            var genericIndex =
                cleaned.indexOf(
                    genericFooterMarkers[g]
                );

            if (genericIndex !== -1) {

                cleaned =
                    cleaned.substring(
                        0,
                        genericIndex
                    );
            }
        }

        // --------------------------------------------------------
        // Normalize whitespace
        // --------------------------------------------------------

        cleaned = cleaned
            .replace(/^\s+/, "")
            .replace(/\s+$/, "");

        cleaned = cleaned.replace(
            /\n[ \t]*\n[ \t]*\n+/g,
            "\n\n"
        );

        cleaned = cleaned.replace(
            /[ \t]+\n/g,
            "\n"
        );

        return cleaned.trim();
    }

    // ============================================================
    // CHATGPT EXTRACTION
    //
    // EXACT existing logic preserved.
    // ============================================================

    function extractChatGPTAssistantText(
        conversationText
    ) {

        if (!conversationText) {
            return "";
        }

        var markers = [
            "قال ChatGPT:",
            "قال ChatGPT :",
            "ChatGPT قال:",
            "ChatGPT:"
        ];

        var position = -1;
        var markerLength = 0;

        for (
            var i = 0;
            i < markers.length;
            i++
        ) {

            var currentPosition =
                conversationText.lastIndexOf(
                    markers[i]
                );

            if (
                currentPosition >
                position
            ) {

                position =
                    currentPosition;

                markerLength =
                    markers[i].length;
            }
        }

        if (position === -1) {
            return "";
        }

        var answer =
            conversationText
                .substring(
                    position +
                    markerLength
                )
                .trim();

        return cleanAssistantResponse(
            answer
        );
    }

    // ============================================================
    // GENERIC / GEMINI EXTRACTION
    // ============================================================

    function extractLatestPlatformResponse() {

        var elements =
            getPlatformResponseElements();

        if (elements.length === 0) {
            return "";
        }

        // Always inspect the newest matching element first.
        for (
            var i = elements.length - 1;
            i >= 0;
            i--
        ) {

            var element =
                elements[i];

            var text = "";

            try {

                text =
                    CURRENT_PLATFORM.extractResponse(
                        element
                    );

            } catch (e) {

                text =
                    getElementText(
                        element
                    );
            }

            text =
                cleanAssistantResponse(
                    text
                );

            if (text) {

                return text;
            }
        }

        return "";
    }

    // ============================================================
    // CURRENT RESPONSE
    // ============================================================

    function getCurrentAssistantText() {

        if (
            CURRENT_PLATFORM.id ===
            "chatgpt"
        ) {

            var containers =
                getChatGPTConversationContainers();

            if (
                containers.length === 0
            ) {

                return "";
            }

            var lastContainer =
                containers[
                    containers.length - 1
                ];

            var conversationText =
                getElementText(
                    lastContainer
                );

            return extractChatGPTAssistantText(
                conversationText
            );
        }

        return extractLatestPlatformResponse();
    }

    // ============================================================
    // CAPTURE STATE
    // ============================================================

    var baselineAssistant = "";

    var captureActive = false;

    var captureFinished = false;

    var lastCandidate = "";

    var stableSince = 0;

    // ============================================================
    // DEBUG RESPONSE ELEMENTS
    // ============================================================

    function logResponseElementDiagnostics() {
    var elements = getPlatformResponseElements();

    log("🔬 " + CURRENT_PLATFORM.name +
        " response candidates=" + elements.length);

    elements.slice(-10).forEach(function (el, index) {
        var text = (el.innerText || el.textContent || "").trim();

        log(
            "🔎 candidate[" + index + "] " +
            el.tagName +
            " class=" + (el.className || "") +
            " len=" + text.length
        );
    });
}
    function logArenaMutationDiagnostics() {
    if (location.hostname !== "arena.ai") return;

    log("🔬 ===== ARENA MUTATION DIAGNOSTICS START =====");

    var logged = [];
    var observer = new MutationObserver(function (mutations) {
        mutations.forEach(function (mutation) {

            var target = mutation.target;

            if (!target || target.nodeType !== 1) return;

            var tag = target.tagName;

            // نستبعد العناصر العامة التي لا تفيدنا
            if (
                tag !== "DIV" &&
                tag !== "P" &&
                tag !== "ARTICLE" &&
                tag !== "LI" &&
                tag !== "SPAN"
            ) {
                return;
            }

            if (
                target.matches("textarea, input, button, nav, header, footer")
            ) {
                return;
            }

            var text = (target.innerText || target.textContent || "").trim();

            if (text.length < 30) return;

            // منع تكرار نفس العنصر
            if (logged.indexOf(target) !== -1) return;
            logged.push(target);

            var className = "";

            try {
                className = typeof target.className === "string"
                    ? target.className
                    : "";
            } catch (e) {}

            log(
                "🧬 Arena changed: " +
                tag +
                " class=" + className +
                " len=" + text.length
            );

            // نكتفي بعدد محدود حتى لا يمتلئ سجل التشخيص
            if (logged.length >= 15) {
                observer.disconnect();
                log("🔬 Arena mutation limit reached");
                log("🔬 ===== ARENA MUTATION DIAGNOSTICS END =====");
            }
        });
    });

    observer.observe(document.body, {
        subtree: true,
        childList: true,
        characterData: true
    });

    // إيقاف المراقبة تلقائيًا بعد 10 ثوانٍ
    setTimeout(function () {
        observer.disconnect();

        log(
            "🔬 Arena mutation diagnostics stopped, candidates=" +
            logged.length
        );

        log("🔬 ===== ARENA MUTATION DIAGNOSTICS END =====");
    }, 10000);
 }

function logArenaDomDiagnostics() {
    log("🔬 ===== ARENA DOM DIAGNOSTICS =====");

    var selectors = [
        "main",
        "article",
        '[role="main"]',
        '[role="article"]',
        '[class*="message"]',
        '[class*="response"]',
        '[class*="assistant"]',
        '[class*="markdown"]',
        '[class*="prose"]',
        '[class*="content"]'
    ];

    var seen = [];
    var count = 0;

    selectors.forEach(function (selector) {
        var elements = document.querySelectorAll(selector);

        for (var i = elements.length - 1; i >= 0 && count < 20; i--) {
            var el = elements[i];
            var text = (el.innerText || el.textContent || "").trim();

            if (text.length < 20) continue;
            if (seen.indexOf(el) !== -1) continue;

            seen.push(el);
            count++;

            log(
                "🧩 Arena[" + count + "] " +
                el.tagName +
                " class=" + (el.className || "") +
                " len=" + text.length
            );
        }
    });

    log("🔬 Arena diagnostic candidates=" + count);
    log("🔬 ===== END ARENA DOM DIAGNOSTICS =====");
            }


    // ============================================================
    // START CAPTURE
    // ============================================================

    function startResponseCapture() {

        captureActive = true;

        captureFinished = false;

        lastCandidate = "";

        stableSince = 0;

        baselineAssistant =
            getCurrentAssistantText();

        log(
            "🧪 Response capture started " +
            "platform=" +
            CURRENT_PLATFORM.id +
            " baselineAssistant=" +
            baselineAssistant.length
        );

        // For Gemini this is especially useful during the first test.
        if (CURRENT_PLATFORM.id === "gemini") {
    logResponseElementDiagnostics();
}

if (location.hostname === "arena.ai") {
    setTimeout(function () {
        logArenaDomDiagnostics();
    }, 1500);

    logArenaMutationDiagnostics();
}

        watchForResponse();
    }

    // ============================================================
    // FINISH CAPTURE
    // ============================================================

    function finishCapture(answer) {

        if (captureFinished) {
            return;
        }

        captureFinished = true;

        captureActive = false;

        answer =
            cleanAssistantResponse(
                answer || ""
            );

        if (!answer) {

            log(
                "⚠️ Clean assistant response empty"
            );

            return;
        }

        log(
            "🧹 Response cleaned len=" +
            answer.length
        );

        log(
            "📝 CLEAN RESPONSE: " +
            answer.substring(
                0,
                1200
            )
        );

        try {

            browser.runtime.sendMessage({
                type: "ASSISTANT_RESPONSE",
                text: answer,
                source: CURRENT_PLATFORM.id,
                timestamp: Date.now()
            });

            log(
                "📤 CLEAN ASSISTANT_RESPONSE sent source=" +
                CURRENT_PLATFORM.id
            );

        } catch (e) {

            log(
                "❌ ASSISTANT_RESPONSE send failed: " +
                e
            );
        }
    }

    // ============================================================
    // RESPONSE WATCHER
    // ============================================================

    function watchForResponse() {

        if (
            !captureActive ||
            captureFinished
        ) {

            return;
        }

        var current =
            getCurrentAssistantText();

        if (!current) {

            setTimeout(
                watchForResponse,
                1000
            );

            return;
        }

        // --------------------------------------------------------
        // Ignore the old response.
        // --------------------------------------------------------

        if (
            baselineAssistant &&
            current ===
            baselineAssistant
        ) {

            setTimeout(
                watchForResponse,
                1000
            );

            return;
        }

        // --------------------------------------------------------
        // Response changed.
        // --------------------------------------------------------

        if (
            current !==
            lastCandidate
        ) {

            lastCandidate =
                current;

            stableSince =
                Date.now();

            log(
                "🔄 " +
                CURRENT_PLATFORM.name +
                " assistant response changed len=" +
                current.length
            );

            setTimeout(
                watchForResponse,
                1200
            );

            return;
        }

        // --------------------------------------------------------
        // Response stable.
        // --------------------------------------------------------

        if (
            lastCandidate &&
            stableSince > 0 &&
            Date.now() -
                stableSince >=
                1800
        ) {

            finishCapture(
                lastCandidate
            );

            return;
        }

        setTimeout(
            watchForResponse,
            800
        );
    }

    // ============================================================
    // MESSAGE FROM BACKGROUND
    // ============================================================

    browser.runtime.onMessage.addListener(
        function (message) {

            if (!message) {
                return;
            }

            if (
                message.type ===
                "CONTEXT_TO_PAGE"
            ) {

                var text =
                    message.text || "";

                log(
                    "📥 CONTEXT_TO_PAGE received len=" +
                    text.length +
                    " platform=" +
                    CURRENT_PLATFORM.id
                );

                if (!text) {

                    log(
                        "⚠️ CONTEXT_TO_PAGE text empty"
                    );

                    return;
                }

                // ------------------------------------------------
                // IMPORTANT:
                // Capture starts BEFORE sending the prompt.
                // ------------------------------------------------

                startResponseCapture();

                findInputWithRetry(
                    10,
                    500,
                    function (input) {

                        var selectorInfo =
                            input.tagName +
                            (
                                input.id
                                    ? "#" +
                                      input.id
                                    : ""
                            ) +
                            (
                                input.className
                                    ? "." +
                                      String(
                                          input.className
                                      )
                                        .split(/\s+/)
                                        .slice(
                                            0,
                                            4
                                        )
                                        .join(".")
                                    : ""
                            );

                        log(
                            "🔎 " +
                            CURRENT_PLATFORM.name +
                            " input found: " +
                            selectorInfo
                        );

                        var injected =
                            injectText(
                                input,
                                text
                            );

                        if (!injected) {
                            return;
                        }

                        setTimeout(
                            function () {

                                sendEnter(
                                    input
                                );

                            },
                            300
                        );
                    }
                );
            }
        }
    );

    // ============================================================
    // READY
    // ============================================================

    try {

        browser.runtime.sendMessage({
            type: "CONTENT_READY",
            version: VERSION,
            url: location.href
        });

        log(
            "📡 CONTENT_READY sent"
        );

    } catch (e) {

        log(
            "❌ CONTENT_READY failed: " +
            e
        );
    }

})();
