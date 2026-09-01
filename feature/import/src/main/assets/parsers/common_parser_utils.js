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
 * 记录一条被丢弃的候选课程行的原因（不含页面内容，仅固定短码）
 *
 * ScriptEngine 会在脚本执行后读取 globalThis.__dc_diag，
 * 由 ImportViewModel 汇总成 "N 条记录被跳过" 的提示，便于用户反馈。
 * 允许短码：no_weeks / no_sections / no_day
 */
function reportDropped(reason) {
    try {
        if (!globalThis.__dc_diag) globalThis.__dc_diag = [];
        globalThis.__dc_diag.push(String(reason));
    } catch (e) {}
}

/**
 * 剥离周次字符串里的一切非周次信息
 *
 * 关键点：必须在按 '-' 拆区间之前做，否则像 "9周(1-2节)" 会被拼成 "91-2节"，
 * 再按 '-' 拆成 91..2 的空区间，导致单周课被静默丢弃（issue #109）。
 */
function stripWeekNoise(str) {
    return String(str)
        // 只去掉"含节次"的括号组（如 "(1-2节)"）；不动像 "(1-16周)" / "（9周）" 这种
        // 整体被括号包住的周次，避免把周次一起清掉（末尾白名单会去掉裸括号）。
        .replace(/[（(][^）)]*节[^）)]*[）)]/g, '')
        .replace(/第/g, '')                      // "第9周" -> "9周"
        .replace(/周\s*[数次]\s*[:：]?/g, '')     // 去掉 "周数:" / "周次:" 标签
        .replace(/共\s*\d+\s*[周次节]/g, '')      // 去掉 "共16周" / "共32次"
        .replace(/[至~～—–－]/g, '-')            // 各种破折号统一为 '-'
        .replace(/[\s　]+/g, '')             // 去空白（含全角空格）
        .replace(/[^0-9,，、;\-]/g, '');          // 只保留数字/分隔符/短横线（顺带去掉 周/单/双/裸括号 等）
}

/**
 * 把一个周次按单双周规则和合理范围加入结果数组
 */
function pushWeek(arr, w, type) {
    if (isNaN(w) || w < 1 || w > 53) return; // 合理性钳制：一学期不会超过 53 周
    if (type === 0 || (type === 1 && w % 2 === 1) || (type === 2 && w % 2 === 0)) {
        arr.push(w);
    }
}

/**
 * 去重并升序排序
 */
function dedupeSortWeeks(arr) {
    var seen = {};
    var out = [];
    for (var i = 0; i < arr.length; i++) {
        if (!seen[arr[i]]) {
            seen[arr[i]] = 1;
            out.push(arr[i]);
        }
    }
    out.sort(function (a, b) { return a - b; });
    return out;
}

/**
 * 解析周次字符串
 * 支持格式：
 * - "1-16周" / "1-16"
 * - "1-8,10-16周"
 * - "1-16周(单)"
 * - "1,3,5周"
 * - "9周" / "9" / "9-9周" / "第9周"（单周，issue #109）
 * - "9周(1-2节)"（节次粘连，issue #109）
 * - "16-9周"（写反的区间，容错）
 * 来源: zhengfang.js (支持单双周) + issue #109 加固
 */
function parseWeeks(str) {
    var weeks = [];
    if (!str) return weeks;

    var type = 0; // 0:全, 1:单, 2:双
    if (str.indexOf('单') > -1) type = 1;
    if (str.indexOf('双') > -1) type = 2;

    var cleaned = stripWeekNoise(str);
    var parts = cleaned.split(/[,，、;]/);

    for (var i = 0; i < parts.length; i++) {
        var part = parts[i].trim();
        if (!part) continue;
        var m = /^(\d+)-(\d+)$/.exec(part);
        if (m) {
            var start = parseInt(m[1], 10);
            var end = parseInt(m[2], 10);
            if (isNaN(start) || isNaN(end)) continue;
            if (end < start) { var t = start; start = end; end = t; } // 容错：区间写反了
            for (var w = start; w <= end; w++) {
                pushWeek(weeks, w, type);
            }
        } else {
            // 单周，或 "9-" / "-9" 这类残缺输入 —— 取其中的整数当单周
            pushWeek(weeks, parseInt(part, 10), type);
        }
    }
    return dedupeSortWeeks(weeks);
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

/**
 * 从一段文本里提取"周次子串"，交给 parseWeeks 进一步解析。
 *
 * 加固点（issue #109）：
 * - 周数上限约束为 [0-9]{1,2}，去掉原来贪婪的 [^\s]*，节次文本不会再被捕获进来。
 * - 带标签分支（"周数"/"周次"）冒号可选，且完整保留逗号列表（如 "周数 1-8,10-16周"）。
 * - 新增独立的单周分支（"9周"），不再要求必须是区间。
 */
function extractWeeksStr(text) {
    if (!text) return "";
    var t = String(text);
    var dash = "[-至~～—–－]";
    var num = "[0-9]{1,2}";
    // 单/双周标记：兼容各种包裹符 —— (单) （双） [单] |单周 /双周 等
    var parity = "(?:\\s*[（(\\[|/／｜]?\\s*[单双]\\s*[）)\\]]?周?)?";
    // 单个列表段：一个周次或 a-b 区间，允许每段自带 "周" 后缀
    // （如 "1-8周,10-16周" —— 每段都有 "周"，parseWeeks 会忽略这些 "周"）
    var seg = num + "(?:\\s*" + dash + "\\s*" + num + ")?\\s*周?";
    var list = seg + "(?:\\s*[,，、;]\\s*" + seg + ")*";
    // "周" 后缀（排除 "周次"/"周数" 这类标签，避免把节次旁边的标签 "周" 误当作周次单位）
    var zhou = "周(?![次数])";

    // 从整段匹配文本里提取单/双周标记，附加到返回值上（parity 可能出现在周次列表前或后）
    function paritySuffix(matchText) {
        return /单/.test(matchText) ? "(单)" : (/双/.test(matchText) ? "(双)" : "");
    }

    // 1) 带 "周数"/"周次" 标签，后跟数字列表/区间。
    //    冒号可选（兼容 "周数 1,3,5周"、"周数1-8,10-16周"、"周数 1-8周,10-16周" 等）；
    //    单/双标记允许出现在列表前（"周数：(单)1-16周"）或列表后（"1-16周(单)"）。
    var labeled = new RegExp("周\\s*[数次]\\s*[:：]?\\s*" + parity + "\\s*(" + list + ")" + parity, "i").exec(t);
    if (labeled) {
        return labeled[1].replace(/\s+/g, "") + paritySuffix(labeled[0]);
    }
    // 2) 无标签的连续周次串：以数字开头，由 数字/区间/逗号/"周" 组成，
    //    且必须以真正的 "周" 收尾。这样 "1-8,10-16周" 和 "1-8周,10-16周" 都能完整保留，
    //    而 "节次1-2" 因为不以 "周" 收尾不会被误匹配。前置的单/双标记也一并纳入匹配。
    var listMatch = new RegExp(
        parity + "\\s*(" + num + "(?:\\s*[-,，、;至~～—–－]\\s*" + num + "|\\s*" + zhou + ")*\\s*" + zhou + ")" + parity,
        "i"
    ).exec(t);
    if (listMatch) return listMatch[1].replace(/\s+/g, "") + paritySuffix(listMatch[0]);
    // 3) 单周 "N周"（可带单双，标记可在前可在后）—— issue #109
    var single = new RegExp(parity + "\\s*(" + num + "\\s*周)" + parity, "i").exec(t);
    if (single) return single[1].replace(/\s+/g, "") + paritySuffix(single[0]);
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

// ---------------- 测试导出（仅 Node 环境生效，App 内 QuickJS 下 module 为 undefined，此段为死代码） ----------------
if (typeof module !== 'undefined' && module.exports) {
    module.exports = {
        parseWeeks: parseWeeks,
        extractWeeksStr: extractWeeksStr,
        parseSections: parseSections,
        extractSectionsStr: extractSectionsStr,
        stripTags: stripTags,
        stripWeekNoise: stripWeekNoise,
        dedupeCourses: dedupeCourses,
        // reportDropped 由各具体解析脚本（zhengfang.js/qiangzhi.js/kingosoft.js）在运行时调用，
        // 它们与本文件是拼接加载的；此处导出同时让静态分析识别其被使用。
        reportDropped: reportDropped
    };
}
