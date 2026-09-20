// ✅ احذف hashchange listener
// ✅ استخدم polling كل ثانية

setInterval(function() {
    if (document.visibilityState !== 'visible') return;

    browser.runtime.sendNativeMessage('browser', {
        type: 'CHECK_CAPTURE',
        domain: location.hostname
    }).then(function(response) {
        if (!response || !response.capture) return;

        var text = extractLatestResponse();
        var ok   = !!(text && text.length >= MIN_LEN);

        browser.runtime.sendNativeMessage('browser', {
            type:    'CAPTURE_RESULT',
            success: ok,
            text:    ok ? text : '',
            domain:  location.hostname,
            debug: {
                assistant: document.querySelectorAll(
                    '[data-message-author-role="assistant"]'
                ).length,
                articles: document.querySelectorAll('article').length,
                bodyLen:  document.body ? document.body.innerText.length : 0
            }
        });
    }).catch(function() {});

}, 1000);
