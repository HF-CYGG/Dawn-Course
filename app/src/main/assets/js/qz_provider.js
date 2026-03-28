// 强智教务系统专用 Provider：通过接口直接拉取课表 HTML
(function() {
    try {
        globalThis.__dawnResult = null;
        globalThis.__dawnReady = false;
        (function() {
            try {
                const xhr = new XMLHttpRequest();
                xhr.open('GET', '/jsxsd/xskb/xskb_list.do?Ves632DSdyV=NEW_XSD_PYGL', false);
                xhr.send();
                globalThis.__dawnResult = xhr.status === 200 ? xhr.responseText : "";
            } catch (error_) {
                globalThis.__dawnError = error_?.message || '';
                try {
                    const iframes = document.getElementsByTagName('iframe');
                    if (iframes.length > 0) {
                        const firstDoc = iframes[0].contentWindow?.document;
                        globalThis.__dawnResult = firstDoc?.body?.innerHTML || "";
                    } else {
                        globalThis.__dawnResult = document.body?.innerHTML || "";
                    }
                } catch (error__) {
                    globalThis.__dawnError = error__?.message || '';
                    globalThis.__dawnResult = document.body?.innerHTML || "";
                }
            }
            globalThis.__dawnReady = true;
        })();
    } catch (error_) {
        globalThis.__dawnError = error_?.message || '';
        globalThis.__dawnReady = true;
    }
})();
