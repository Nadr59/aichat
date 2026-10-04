(function () {
"use strict";

var BUILD = "v1.0.29";

var scanTimer = null;
var scanEndTimer = null;
var baseline = {};
var contextId = 0;

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

function isVisible(el) {
    if (!el) return false;

    try {
        var style = getComputedStyle(el);
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

function describe(el) {
    if (!el) return "";

    var tag = el.tagName || "";
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
                    .slice(0, 5)
                    .join(".");
        }
    } catch (e) {}

    var attrs = [];

    [
        "role",
        "data-testid",
        "data-message-author-role",
        "data-state",
        "data-index",
        "data-is-streaming",
        "aria-label"
    ].forEach(function (name) {
        try {
            var value =
                el.getAttribute(name);

            if (value) {
                attrs.push(
                    name +
                    "=" +
                    value.substring(0, 80)
                );
            }
        } catch (e) {}
    });

    return (
        tag +
        id +
        cls +
        (
            attrs.length
                ? " [" +
                  attrs.join(" ") +
                  "]"
                : ""
        )
    );
}

function elementKey(el) {
    return describe(el);
}

function collectElements() {
    var result = [];

    try {
        var elements =
            document.querySelectorAll("*");

        for (
            var i = 0;
            i < elements.length;
            i++
        ) {
            var el = elements[i];

            if (!isVisible(el)) {
                continue;
            }

            var text =
                cleanText(el.innerText || "");

            if (
                text.length < 20 ||
                text.length > 3000
            ) {
                continue;
            }

            if (
                el.tagName === "SCRIPT" ||
                el.tagName === "STYLE" ||
                el.tagName === "NOSCRIPT" ||
                el.tagName === "SVG"
            ) {
                continue;
            }

            result.push({
                element: el,
                text: text,
                key: elementKey(el)
            });
        }
    } catch (e) {
        log(
            "DOM collection error: " +
            e.message
        );
    }

    return result;
}

function createSnapshot() {
    var items =
        collectElements();

    var snapshot = {};

    for (
        var i = 0;
        i < items.length;
        i++
    ) {
        var item = items[i];

        if (!item.key) {
            continue;
        }

        snapshot[item.key] = {
            text: item.text,
            length: item.text.length
        };
    }

    return snapshot;
}

function scanChanges() {
    var current =
        createSnapshot();

    var changes = [];

    for (
        var key in current
    ) {
        if (
            !Object.prototype.hasOwnProperty.call(
                current,
                key
            )
        ) {
            continue;
        }

        var item =
            current[key];

        var old =
            baseline[key];

        if (!old) {
            changes.push({
                type: "NEW",
                key: key,
                text: item.text,
                length: item.length
            });

            continue;
        }

        if (
            old.text !== item.text
        ) {
            changes.push({
                type: "CHANGED",
                key: key,
                text: item.text,
                length: item.length
            });
        }
    }

    if (!changes.length) {
        return;
    }

    changes.sort(function (a, b) {
        return b.length - a.length;
    });

    log(
        "========== DOM CHANGES =========="
    );

    var limit =
        Math.min(changes.length, 15);

    for (
        var i = 0;
        i < limit;
        i++
    ) {
        var change =
            changes[i];

        var preview =
            change.text
                .substring(0, 500)
                .replace(/\n/g, " ");

        log(
            change.type +
            " len=" +
            change.length +
            " " +
            change.key +
            ' text="' +
            preview +
            '"'
        );
    }

    if (changes.length > limit) {
        log(
            "... " +
            (changes.length - limit) +
            " more changes"
        );
    }

    log(
        "========== DOM CHANGES END =========="
    );

    baseline = current;
}

function startDomDiagnostic(id) {
    stopDomDiagnostic();

    contextId = id || 0;

    baseline =
        createSnapshot();

    log(
        "DOM diagnostic started, baseline elements=" +
        Object.keys(baseline).length
    );

    scanTimer =
        setInterval(
            scanChanges,
            1000
        );

    scanEndTimer =
        setTimeout(
            function () {
                log(
                    "DOM diagnostic finished"
                );

                stopDomDiagnostic();
            },
            20000
        );
}

function stopDomDiagnostic() {
    if (scanTimer) {
        clearInterval(scanTimer);
        scanTimer = null;
    }

    if (scanEndTimer) {
        clearTimeout(scanEndTimer);
        scanEndTimer = null;
    }
}

function findInput() {
    var selectors = [
        "#prompt-textarea",
        "textarea[data-testid='textbox']",
        "textarea[data-testid*='textbox']",
        "textarea[placeholder*='Message']",
        "textarea[placeholder*='message']",
        "textarea[aria-label*='Message']",
        "textarea[aria-label*='message']",
        "textarea[aria-label*='الدردشة']",
        "[contenteditable='true'][role='textbox']",
        "[role='textbox']",
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
                    isVisible(elements[j]) &&
                    !elements[j].disabled &&
                    !elements[j].readOnly
                ) {
                    log(
                        "ChatGPT input found: " +
                        describe(elements[j])
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
    callback
) {
    var input = findInput();

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
            callback
        );
    }, 500);
}

function inject(input, text) {
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
                new Event("input", {
                    bubbles: true,
                    composed: true
                })
            );

            return true;
        }

        return false;
    } catch (e) {
        log(
            "Injection error: " +
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
            "Enter error: " +
            e.message
        );
        return false;
    }
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

            var id =
                message.contextId || 0;

            log(
                "CONTEXT_TO_PAGE received: " +
                text.length +
                " chars"
            );

            startDomDiagnostic(id);

            findInputWithRetry(
                10,
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
                                id
                        });

                        stopDomDiagnostic();
                        return;
                    }

                    if (
                        !inject(
                            input,
                            text
                        )
                    ) {
                        log(
                            "Text injection failed"
                        );

                        browser.runtime.sendMessage({
                            type:
                                "CONTEXT_WRITTEN",
                            success:
                                false,
                            stage:
                                "text_injection_failed",
                            contextId:
                                id
                        });

                        stopDomDiagnostic();
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
                            id
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
                                id
                        });
                    } else {
                        log(
                            "Enter failed"
                        );

                        browser.runtime.sendMessage({
                            type:
                                "CONTEXT_WRITTEN",
                            success:
                                false,
                            stage:
                                "enter_failed",
                            contextId:
                                id
                        });

                        stopDomDiagnostic();
                    }
                }
            );

            return;
        }

        if (
            message.type ===
            "SCAN_ASSISTANT_DOM"
        ) {
            log(
                "Manual DOM scan requested"
            );

            var snapshot =
                createSnapshot();

            var count =
                Object.keys(snapshot).length;

            log(
                "Visible text elements: " +
                count
            );

            var items = [];

            for (
                var key in snapshot
            ) {
                if (
                    Object.prototype.hasOwnProperty.call(
                        snapshot,
                        key
                    )
                ) {
                    items.push({
                        key: key,
                        text:
                            snapshot[key].text,
                        length:
                            snapshot[key].length
                    });
                }
            }

            items.sort(function (a, b) {
                return b.length - a.length;
            });

            var limit =
                Math.min(items.length, 20);

            for (
                var i = 0;
                i < limit;
                i++
            ) {
                log(
                    "DOM[" +
                    i +
                    "] len=" +
                    items[i].length +
                    " " +
                    items[i].key +
                    ' text="' +
                    items[i].text
                        .substring(0, 300)
                        .replace(/\n/g, " ") +
                    '"'
                );
            }

            return;
        }
    }
);

log(
    "AiChat content.js " +
    BUILD +
    " loaded"
);

log(
    "Domain: " +
    location.hostname
);

})();
