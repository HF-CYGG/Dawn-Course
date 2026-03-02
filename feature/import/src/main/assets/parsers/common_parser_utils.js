/**
 * 通用教务系统解析工具函数库
 * 包含：HTML清洗、实体解码、周次/节次解析、通用提取逻辑
 * 
 * 此文件将在具体 parser 执行前被加载。
 */

// ---------------- HTML 处理与清洗 ----------------

/**
 * 解码HTML实体 (支持 &nbsp;, &lt;, &#x...;, &#...; 等)
 * 来源: kingosoft.js (最全面版本)
 */
function decodeHtmlEntities(rawText) {
    var text = String(rawText);
    var result = "";
    var i = 0;
    while (i < text.length) {
        var ch = text.charAt(i);
        if (ch !== "&") {
            result += ch;
            i++;
            continue;
        }
        var semi = text.indexOf(";", i + 1);
        if (semi === -1 || semi - i > 12) {
            result += "&";
            i++;
            continue;
        }
        var entity = text.slice(i + 1, semi);
        var decoded = null;
        if (entity === "nbsp") decoded = " ";
        else if (entity === "lt") decoded = "<";
        else if (entity === "gt") decoded = ">";
        else if (entity === "quot") decoded = "\"";
        else if (entity === "#39") decoded = "'";
        else if (entity === "amp") decoded = "&";
        else if (entity === "apos") decoded = "'";
        else if (entity.length > 1 && entity.charAt(0) === "#") {
            var code = entity.charAt(1).toLowerCase() === "x"
                ? parseInt(entity.slice(2), 16)
                : parseInt(entity.slice(1), 10);
            if (!isNaN(code)) decoded = String.fromCharCode(code);
        }
        if (decoded === null) {
            result += "&";
            i++;
            continue;
        }
        result += decoded;
        i = semi + 1;
    }
    return result;
}

/**
 * 移除HTML标签（循环移除防止嵌套绕过）
 */
function removeHtmlTags(rawText) {
    var result = String(rawText);
    var previous;
    do {
        previous = result;
        result = result.replace(/<[^>]*>/g, "");
    } while (result !== previous);
    return result.replace(/[<>]/g, "");
}

/**
 * 清洗 HTML 内容：移除标签 -> 解码实体 -> 再次移除标签 -> 归一化空白
 */
function stripTags(html) {
    var text = removeHtmlTags(html);
    text = decodeHtmlEntities(text);
    return removeHtmlTags(text).replace(/\s+/g, " ").trim();
}

/**
 * 归一化文本：stripTags + 中文标点替换
 */
function normalizeText(html) {
    return stripTags(html).replace(/\s+/g, " ").replace(/：/g, ":").trim();
}

// ---------------- 周次与节次解析 ----------------

/**
 * 解析周次字符串
 * 支持格式：
 * - "1-16周"
 * - "1-8,10-16周"
 * - "1-16周(单)"
 * - "1,3,5周"
 * 来源: zhengfang.js (支持单双周)
 */
function parseWeeks(str) {
    var weeks = [];
    if (!str) return weeks;

    var type = 0; // 0:全, 1:单, 2:双
    if (str.indexOf("单") > -1) type = 1;
    if (str.indexOf("双") > -1) type = 2;

    str = str.replace(/周数[:：]/g, '');
    str = str.replace(/共\d+周|共\d+次|共\d+节/g, '');
    str = str.replace(/[至~～—－]/g, '-');
    str = str.replace(/周|单|双|\(|\)|（|）/g, '');
    
    var parts = str.split(/[,，;、]/); 

    for (var i = 0; i < parts.length; i++) {
        var part = parts[i].trim();
        if (part.indexOf('-') > -1) {
            var range = part.split('-');
            var start = parseInt(range[0]);
            var end = parseInt(range[1]);
            if (!isNaN(start) && !isNaN(end)) {
                for (var w = start; w <= end; w++) {
                    if (type === 0 || (type === 1 && w % 2 !== 0) || (type === 2 && w % 2 === 0)) {
                        weeks.push(w);
                    }
                }
            }
        } else if (part !== '') {
            var week = parseInt(part);
            if (!isNaN(week)) {
                 if (type === 0 || (type === 1 && week % 2 !== 0) || (type === 2 && week % 2 === 0)) {
                    weeks.push(week);
                }
            }
        }
    }
    return weeks;
}

/**
 * 解析节次字符串
 * 支持格式: "1-2节", "1-2", "1,2"
 * 来源: zhengfang.js
 */
function parseSections(sectionsString) {
    var sections = [];
    var str = sectionsString.replace(/第/g, "").replace(/节次[:：]/g, "").replace(/节/g, "").replace(/[\(（\)）]/g, "");
    str = str.replace(/[至~～—－]/g, "-");
    var parts = str.split("-");
    var start = parseInt(parts[0]);
    var end = parseInt(parts[1] || parts[0]);
    
    if (!isNaN(start)) {
        for (var s = start; s <= end; s++) {
            sections.push(s);
        }
    }
    // 简单的逗号分隔支持 (fallback)
    if (sections.length === 0 && sectionsString.indexOf(",") > -1) {
        var commaParts = sectionsString.split(",");
        for (var i = 0; i < commaParts.length; i++) {
            var val = parseInt(commaParts[i]);
            if (!isNaN(val)) sections.push(val);
        }
    }
    return sections;
}

// ---------------- 通用提取逻辑 ----------------

/**
 * 根据 title 属性提取文本 (兼容新旧版 span/font 结构)
 * 来源: qiangzhi.js (含 span 修复)
 */
function extractTextByTitle(blockHtml, titleText) {
    // 1. 尝试匹配 title 在 span 标签内，并提取 span 的内容 (新版结构)
    var patternInside = '<span[^>]*title=["\']?\\s*' + titleText + '\\s*["\']?[^>]*>([\\s\\S]*?)<\\/span>';
    var matchInside = new RegExp(patternInside, "i").exec(blockHtml);
    if (matchInside) {
        var content = stripTags(matchInside[1]).trim();
        if (content) return content;
    }

    // 2. 尝试旧逻辑：title 在某个标签内，后面紧跟着 font (部分旧版结构)
    var patternAfter = 'title=["\']?\\s*' + titleText + '\\s*["\']?[^>]*>[\\s\\S]*?<\\/span>\\s*<font[^>]*>([\\s\\S]*?)<\\/font>';
    var matchAfter = new RegExp(patternAfter, "i").exec(blockHtml);
    if (matchAfter) {
        return stripTags(matchAfter[1]).trim();
    }
    
    return "";
}

/**
 * 提取课程名称 (通常在 class="title" 的 div 或 u 标签中)
 */
function extractName(blockHtml) {
    var titleMatch = /<([a-zA-Z]+)[^>]*class=["']?title[^>]*>([\s\S]*?)<\/\1>/i.exec(blockHtml);
    if (titleMatch) {
        return stripTags(titleMatch[2]).trim();
    }
    var altMatch = /<u[^>]*class=["']?title[^>]*>([\s\S]*?)<\/u>/i.exec(blockHtml);
    if (altMatch) {
        return stripTags(altMatch[1]).trim();
    }
    return "";
}

function extractWeeksStr(text) {
    var weeksMatch = /周数\s*[:：]?\s*([^教师节次校区]+?周[^教师节次校区]*)/i.exec(text);
    if (weeksMatch) return weeksMatch[1].trim();
    var rangeMatch = /(\d+\s*[-至~～—－]\s*\d+\s*周[^\s]*)/i.exec(text);
    if (rangeMatch) return rangeMatch[1].trim();
    var singleMatch = /(\d+\s*周[^\s]*)/i.exec(text);
    if (singleMatch) return singleMatch[1].trim();
    return "";
}

function extractSectionsStr(text) {
    var sectionMatch = /节次\s*[:：]?\s*(\d+)\s*[-至~～—－]\s*(\d+)/i.exec(text);
    if (sectionMatch) return sectionMatch[1] + "-" + sectionMatch[2] + "节";
    var rangeMatch = /第?\s*(\d+)\s*[-至~～—－]\s*(\d+)\s*节/i.exec(text);
    if (rangeMatch) return rangeMatch[1] + "-" + rangeMatch[2] + "节";
    var singleMatch = /第?\s*(\d+)\s*节/i.exec(text);
    if (singleMatch) return singleMatch[1] + "节";
    return "";
}

/**
 * 课程去重 (基于 课程名|教师|地点|星期|周次|节次)
 */
function dedupeCourses(courses) {
    var map = {};
    var result = [];
    for (var i = 0; i < courses.length; i++) {
        var course = courses[i];
        var key = [
            course.name || "",
            course.teacher || "",
            course.position || "",
            course.day || "",
            (course.weeks || []).join("_"),
            (course.sections || []).join("_")
        ].join("|");
        if (!map[key]) {
            map[key] = true;
            result.push(course);
        }
    }
    return result;
}
