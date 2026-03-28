// 通用 Provider：优先调用 scheduleHtmlProvider，失败则用规则识别课表 HTML
(function() {
    const finish = function(result){
        globalThis.__dawnResult = result;
        globalThis.__dawnReady = true;
    };
    const safeProvider = function(){
        if (typeof scheduleHtmlProvider !== 'function') return Promise.resolve(null);
        return Promise.resolve(scheduleHtmlProvider())
            .then(function(result){
                if (result === "do not continue") return null;
                return result;
            })
            .catch(function(){
                return null;
            });
    };
    const getFrameDoc = function(frame){
        try {
            return frame.contentDocument || frame.contentWindow?.document || null;
        } catch (error) {
            globalThis.__dawnFrameError = error?.message || '';
            return null;
        }
    };
    const isScheduleHtml = function(doc) {
        if (!doc?.body) return false;
        const html = doc.body.innerHTML || "";
        if (!html) return false;
        const hasWeekday = /(星期|周)\s*[一二三四五六日天1-7]/.test(html);
        const hasSections = /节次/.test(html) || /第?\s*\d+\s*节/.test(html);
        const hasWeeks = /周次|周数/.test(html) || /第?\s*\d+\s*周/.test(html);
        if (hasWeekday && (hasSections || hasWeeks)) return true;
        if (hasWeeks && hasSections) return true;
        const heavyRowspan = doc.querySelector('td[rowspan="5"], td[rowspan="6"], td[rowspan="7"], th[rowspan="5"]');
        if (heavyRowspan) return true;
        const headers = doc.querySelectorAll('tr, thead');
        for (const header of headers) {
            const text = header.innerText || header.textContent || "";
            if (text.includes('星期一') && text.includes('星期二')) return true;
        }
        return false;
    };
    const scanFrames = function(frames){
        for (const frame of frames) {
            const frameDoc = getFrameDoc(frame);
            const result = frameDoc ? findScheduleHtml(frameDoc) : null;
            if (result) return result;
        }
        return null;
    };
    const findScheduleHtml = function(doc) {
        if (!doc) return null;
        if (isScheduleHtml(doc)) {
            return doc.body ? doc.body.innerHTML : doc.documentElement.outerHTML;
        }
        const deskFrame = doc.querySelector?.('iframe#frmDesk') || null;
        const deskDoc = deskFrame ? getFrameDoc(deskFrame) : null;
        const deskResult = deskDoc ? findScheduleHtml(deskDoc) : null;
        if (deskResult) return deskResult;
        const frames = scanFrames(doc.getElementsByTagName('frame'));
        if (frames) return frames;
        return scanFrames(doc.getElementsByTagName('iframe'));
    };

    safeProvider().then(function(result){
        const html = result || findScheduleHtml(document) || document.documentElement.outerHTML;
        finish(html);
    });
})();
