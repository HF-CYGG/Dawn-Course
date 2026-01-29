/**
 * 泰山科技学院/强智教务系统 解析脚本 (Regex 实现版)
 * 对应 docs/泰山科技学院教务系统脚本开发文档.md 的逻辑
 * 
 * 注：由于 QuickJS 环境默认不包含 Cheerio 库，此处使用 Regex 实现文档中描述的相同解析逻辑。
 * 效果与文档提供的 cheerio 版本一致。
 */

function scheduleHtmlParser(html) {
    var courses = [];
    
    // 1. 查找所有 id="day-section" 的单元格
    // 匹配 <td id="1-1">...</td>
    // 支持 id="1-1" 或 id='1-1'，允许 id 前后有空格
    var tdRegex = /<td[^>]*\bid\s*=\s*["']?(\d+)-(\d+)["']?[^>]*>([\s\S]*?)<\/td>/gi;
    var match;

    while ((match = tdRegex.exec(html)) !== null) {
        var day = parseInt(match[1]);
        // var sectionIndex = parseInt(match[2]); 
        var cellContent = match[3];

        // 2. 分割课程块 (一个单元格可能有多个课程)
        // 通常由 <div class="timetable_con ..."> 包裹
        var courseBlocks = cellContent.split(/<div\s+class=["']?timetable_con/i);

        for (var i = 0; i < courseBlocks.length; i++) {
            if (i === 0) continue; // 跳过第一个 split 产生的空前缀或无关内容
            var blockHtml = '<div class="timetable_con' + courseBlocks[i];

            // 3. 提取字段 (对应文档逻辑)
            var name = "";
            var teacher = "";
            var location = "";
            var weeksStr = "";
            var sectionsStr = "";

            // --- A. 提取课程名 ---
            // 文档: 位于 u.title 或 span.title 中，移除 【...】
            var nameMatch = /class=["']?title[^>]*>[\s\S]*?<i>([\s\S]*?)<\/i>/i.exec(blockHtml);
            if (nameMatch) {
                name = stripTags(nameMatch[1]).trim();
                // 移除 【调】 等前缀
                name = name.replace(/^【.*?】/, '');
            }

            // --- B. 提取教师 ---
            // 文档: 位于 span[title="教师 "] (注意空格) 后的文本
            // 正则匹配 title="教师..." 后的 font/i 标签内容
            var teacherMatch = /title=["']?教师\s*["']?[^>]*>[\s\S]*?<\/span>\s*<font[^>]*>[\s\S]*?<i>([\s\S]*?)<\/i>/i.exec(blockHtml);
            if (teacherMatch) {
                var rawTeacher = stripTags(teacherMatch[1]).trim();
                // 移除 "教师" 前缀 (如果有)
                teacher = rawTeacher.replace(/教师\s*/, '').trim();
            }

            // --- C. 提取地点 ---
            // 文档: 位于 span[title="上课地点"] 后的文本
            var locMatch = /title=["']?上课地点["']?[^>]*>[\s\S]*?<\/span>\s*<font[^>]*>[\s\S]*?<i>([\s\S]*?)<\/i>/i.exec(blockHtml);
            if (locMatch) {
                var rawLoc = stripTags(locMatch[1]).trim();
                // 移除 "上课地点" 和 "泰山科技学院"
                location = rawLoc.replace(/上课地点\s*/, '').replace('泰山科技学院', '').trim();
            }

            // --- D. 提取周次和节次 ---
            // 文档: 位于 span[title="节/周"] 后的文本
            // 原始文本示例: " (1-2节)1-4周,7-8周,10-16周"
            // 查找包含 "节)" 和 "周" 的文本片段
            var timeMatch = /[\(（](\d+(?:-\d+)?节)[\)）]\s*([^<]*周[^<]*)/i.exec(blockHtml);
            if (timeMatch) {
                sectionsStr = timeMatch[1]; // 1-2节
                weeksStr = timeMatch[2];    // 1-4周,7-8周...
            }

            // 4. 解析并添加
            if (name && weeksStr && sectionsStr) {
                var weeks = parseWeeks(weeksStr);
                var sections = parseSections(sectionsStr);

                if (weeks.length > 0 && sections.length > 0) {
                    courses.push({
                        name: name,
                        teacher: teacher,
                        position: location,
                        day: day,
                        weeks: weeks,
                        sections: sections
                    });
                }
            }
        }
    }

    // 返回 JSON 字符串 (小爱标准也支持直接返回数组，但这里为了兼容性返回字符串)
    return JSON.stringify(courses);
}

function stripTags(html) {
    return html.replace(/<[^>]+>/g, "").replace(/&nbsp;/g, " ");
}

/**
 * 解析周次字符串
 * 输入: "1-4周,7-8周,10-16周" 或 "1-16周(双)"
 * 输出: [1,2,3,4,7,8,10,11...16]
 */
function parseWeeks(str) {
    var weeks = [];
    if (!str) return weeks;

    // 处理单双周
    var type = 0; // 0:全, 1:单, 2:双
    if (str.indexOf("单") > -1) type = 1;
    if (str.indexOf("双") > -1) type = 2;

    // 移除 "周", "单", "双" 等文字，只留数字和分隔符
    str = str.replace(/周|单|双|\(|\)|（|）/g, '');
    
    // 按逗号分割不同的时间段 "1-4, 7-8"
    var parts = str.split(/[,，;]/); 

    for (var i = 0; i < parts.length; i++) {
        var part = parts[i].trim();
        if (part.indexOf('-') > -1) {
            // 处理区间 "1-4"
            var range = part.split('-');
            var start = parseInt(range[0]);
            var end = parseInt(range[1]);
            if (!isNaN(start) && !isNaN(end)) {
                for (var w = start; w <= end; w++) {
                    // 根据单双周筛选
                    if (type === 0 || (type === 1 && w % 2 !== 0) || (type === 2 && w % 2 === 0)) {
                        weeks.push(w);
                    }
                }
            }
        } else if (part !== '') {
            // 处理单个周 "7"
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
 * @param {string} sectionsString eg: "1-2节"
 * @returns {number[]}
 */
function parseSections(sectionsString) {
    var sections = [];
    var str = sectionsString.replace(/节/g, "").replace(/[\(（\)）]/g, "");
    var parts = str.split("-");
    var start = parseInt(parts[0]);
    var end = parseInt(parts[1] || parts[0]);
    
    if (!isNaN(start)) {
        for (var s = start; s <= end; s++) {
            sections.push(s);
        }
    }
    return sections;
}
