(function () {
"use strict";

var BUILD = "v1.0.28";

var responseJob = null;
var responseTimer = null;
var responseObserver = null;

var lastResponseFingerprint = "";
var responseSequence = 0;

var RESPONSE_MIN_LEN = 2;
var RESPONSE_STABLE_MS = 1800;
var RESPONSE_TIMEOUT_MS = 30000;
var RESPONSE_SCAN_MS = 800;

function log(message) {
    try {
        browser.runtime.sendMessage({
            type: "JS_LOG",
            message: message
        });
    } catch (e) {
        try {
            console.log("AiChat: " + message);
        } catch (ignore) {}
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

function normalize(text) {
    return cleanText(text)
        .replace(/\s+/g, " ")
        .trim();
}

function isVisible(el) {
    if (!el) return false;

    try {
        var style = window.getComputedStyle(el);
        var rect = el.getBoundingClientRect();

        return (
            style.display !== "none" &&
            style.visibility !== "hidden" &&
            style.opacity !== "0" &&
            rect.width > 0 &&
            rect.height > 0
        );
    } catch (e) {
        return false;
    }
}

function isUsableInput(el) {
    if (!el || !isVisible(el)) return false;

    try {
        return !el.disabled && !el.readOnly;
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

    for (var i = 0; i < selectors.length; i++) {
        try {
            var elements = document.querySelectorAll(selectors[i]);

            for (var j = 0; j < elements.length; j++) {
                if (isUsableInput(elements[j])) {
                    log(
                        "ChatGPT input found: " +
                        elements[j].tagName +
                        (elements[j].id
                            ? "#" + elements[j].id
                            : "")
                    );

                    return elements[j];
                }
            }
        } catch (e) {}
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
        findInputWithRetry(
            attempt - 1,
            delay,
            callback
        );
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

            var descriptor =
                Object.getOwnPropertyDescriptor(
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

            try {
                input.dispatchEvent(
                    new InputEvent("input", {
                        bubbles: true,
                        composed: true,
                        inputType: "insertText",
                        data: text
                    })
                );
            } catch (e) {
                input.dispatchEvent(
                    new Event("input", {
                        bubbles: true,
                        composed: true
                    })
                );
            }

            return true;
        }

        return false;
    } catch (e) {
        log(
            "Text injection failed: " +
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
            new KeyboardEvent("keydown", options)
        );

        input.dispatchEvent(
            new KeyboardEvent("keypress", options)
        );

        input.dispatchEvent(
            new KeyboardEvent("keyup", options)
        );

        return true;
    } catch (e) {
        log(
            "Enter failed: " +
            e.message
        );
        return false;
    }
}

function isStreamingElement(el) {
    if (!el) return false;

    try {
        if (
            el.getAttribute("data-is-streaming") ===
            "true"
        ) {
            return true;
        }

        return !!el.querySelector(
            "[data-is-streaming='true']"
        );
    } catch (e) {
        return false;
    }
}

function extractAssistantFromRoot(root) {
    if (!root) return [];

    var results = [];

    var selectors = [
        '[data-message-author-role="assistant"]',
        '[data-testid="conversation-turn-assistant"]',
        '[data-testid*="conversation-turn"][data-message-author-role="assistant"]',
        'article[data-message-author-role="assistant"]',
        '[role="article"][data-message-author-role="assistant"]'
    ];

    for (var s = 0; s < selectors.length; s++) {
        try {
            var elements =
                root.querySelectorAll(selectors[s]);

            for (var i = 0; i < elements.length; i++) {
                var el = elements[i];

                if (!isVisible(el)) continue;
                if (isStreamingElement(el)) continue;

                var content =
                    el.querySelector(
                        ".markdown, .prose, .whitespace-pre-wrap"
                    );

                var text = cleanText(
                    content
                        ? content.innerText
                        : el.innerText
                );

                if (text.length >= RESPONSE_MIN_LEN) {
                    results.push({
                        text: text,
                        element: el
                    });
                }
            }
        } catch (e) {}
    }

    return results;
}

function getResponseSnapshot() {
    var items =
        extractAssistantFromRoot(document);

    var longest = "";

    for (var i = 0; i < items.length; i++) {
        var text = normalize(items[i].text);

        if (text.length > longest.length) {
            longest = text;
        }
    }

    return {
        count: items.length,
        longest: longest
    };
}

function findLatestResponseCandidate() {
    var items =
        extractAssistantFromRoot(document);

    if (!items.length) {
        return null;
    }

    return items[items.length - 1];
}

function stopResponseCapture() {
    if (responseTimer) {
        clearInterval(responseTimer);
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

function sendAssistantResponse(text, turnId) {
    text = cleanText(text);

    if (text.length < RESPONSE_MIN_LEN) {
        return false;
    }

    var fingerprint = normalize(text);

    if (fingerprint === lastResponseFingerprint) {
        log("Duplicate assistant response ignored");
        return false;
    }

    lastResponseFingerprint = fingerprint;

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

    log(
        "ASSISTANT_RESPONSE sending len=" +
        text.length
    );

    try {
        browser.runtime.sendMessage(message);
        return true;
    } catch (e) {
        log(
            "ASSISTANT_RESPONSE failed: " +
            e.message
        );
        return false;
    }
}

function checkForAssistantResponse() {
    if (!responseJob || responseJob.sent) {
        return;
    }

    if (
        Date.now() - responseJob.startedAt >
        RESPONSE_TIMEOUT_MS
    ) {
        log("Response capture timeout");
        stopResponseCapture();
        return;
    }

    var candidate =
        findLatestResponseCandidate();

    if (!candidate) {
        return;
    }

    var text =
        cleanText(candidate.text);

    var normalized =
        normalize(text);

    if (text.length < RESPONSE_MIN_LEN) {
        return;
    }

    if (
        normalized ===
        responseJob.baselineLongest
    ) {
        return;
    }

    if (
        normalized !==
        responseJob.candidate
    ) {
        responseJob.candidate =
            normalized;

        responseJob.candidateSince =
            Date.now();

        log(
            "Response candidate changed len=" +
            text.length
        );

        return;
    }

    if (
        Date.now() -
        responseJob.candidateSince <
        RESPONSE_STABLE_MS
    ) {
        return;
    }

    responseJob.sent = true;

    var turnId =
        "ctx-" +
        responseJob.contextId +
        "-" +
        Date.now();

    log(
        "Assistant response stable len=" +
        text.length
    );

    sendAssistantResponse(
        text,
        turnId
    );

    stopResponseCapture();
}

function startResponseCapture(contextId) {
    stopResponseCapture();

    var baseline =
        getResponseSnapshot();

    responseJob = {
        contextId: contextId || 0,
        startedAt: Date.now(),
        baselineCount: baseline.count,
        baselineLongest: baseline.longest,
        candidate: "",
        candidateSince: 0,
        sent: false
    };

    log(
        "Response capture started baseline=" +
        baseline.count +
        "/" +
        baseline.longest.length
    );

    try {
        responseObserver =
            new MutationObserver(
                checkForAssistantResponse
            );

        responseObserver.observe(
            document.documentElement,
            {
                childList: true,
                subtree: true,
                characterData: true
            }
        );
    } catch (e) {}

    responseTimer =
        setInterval(
            checkForAssistantResponse,
            RESPONSE_SCAN_MS
        );

    setTimeout(function () {
        if (
            responseJob &&
            !responseJob.sent
        ) {
            log(
                "Response capture timeout"
            );

            stopResponseCapture();
        }
    }, RESPONSE_TIMEOUT_MS);
}

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
                "CONTEXT_TO_PAGE received: " +
                text.length +
                " chars"
            );

            startResponseCapture(
                contextId
            );

            findInputWithRetry(
                10,
                500,
                function (input) {
                    if (!input) {
                        log(
                            "ChatGPT input not found"
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

                    if (
                        !injectIntoInput(
                            input,
                            text
                        )
                    ) {
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
                        "Text injected successfully"
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

                    if (
                        sendEnter(input)
                    ) {
                        log(
                            "Enter sent to ChatGPT"
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
                    } else {
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

        if (
            message.type ===
            "CAPTURE_ASSISTANT_RESPONSE"
        ) {
            var candidate =
                findLatestResponseCandidate();

            if (candidate) {
                sendAssistantResponse(
                    candidate.text,
                    message.turnId ||
                        "manual-" +
                            Date.now()
                );
            } else {
                log(
                    "Manual capture: no assistant element"
                );
            }

            return;
        }

        if (
            message.type ===
            "SCAN_ASSISTANT_DOM"
        ) {
            var items =
                extractAssistantFromRoot(
                    document
                );

            log(
                "Assistant elements found: " +
                items.length
            );

            for (
                var i = 0;
                i < items.length;
                i++
            ) {
                log(
                    "Assistant[" +
                    i +
                    "] len=" +
                    items[i].text.length
                );
            }
        }
    }
);

log(
    "AiChat content.js " +
    BUILD +
    " loaded"
);

})();
