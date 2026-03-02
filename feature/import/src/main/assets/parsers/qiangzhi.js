/**
 * 强智教务系统 (QiangZhi) 解析脚本
 * 基于新版正方教务系统解析逻辑提取，并针对强智系统特性进行优化。
 * 包含针对新版页面结构变更（内容在 span 标签内）的兼容性支持。
 */

function scheduleHtmlParser(html) {
    // 强智系统逻辑与新版正方高度一致，均基于 id="day-section" 或 class="timetable_con" 识别
    var courses = parseQiangZhi(html);
    courses = dedupeCourses(courses);
    return JSON.stringify(courses);
}

/**
 * 强智教务系统解析逻辑
 * (原 zhengfang.js 中的 parseNewZhengfang 逻辑)
 */
function parseQiangZhi(html) {
    var courses = [];
    // 匹配单元格：id="1-2" (周一第2节) 或 id="1-2" (周一第2节开始)
    // 强智/新正方通常用 id="d-s" 格式，d=星期几(1-7), s=节次
    var tdRegex = /<td[^>]*\bid\s*=\s*["']?(\d+)-(\d+)["']?[^>]*>([\s\S]*?)<\/td>/gi;
    var match;

    while ((match = tdRegex.exec(html)) !== null) {
        var day = parseInt(match[1]);
        var cellContent = match[3];

        // 单元格内可能包含多门课程，通常用 class="timetable_con" 分隔
        // 或者直接平铺。这里先尝试按 timetable_con 分割
        var courseBlocks = cellContent.split(/<div\s+class=["']?timetable_con/i);

        for (var i = 0; i < courseBlocks.length; i++) {
            // split 后第一个元素可能是空或者非 timetable_con 内容，
            // 但如果 cellContent 本身就是从 <div class="timetable_con"> 开始，split 的第一个元素是空字符串
            // 如果 cellContent 包含不带 class 的内容，也需要处理。
            // 这里沿用 zhengfang.js 的逻辑：跳过 split 的第一个部分（通常是空或非课程容器），
            // 并手动补全标签。
            // 修正：如果 split 产生的第一个元素不为空且包含课程信息，可能会漏掉。
            // 但标准强智结构通常是 <div class="timetable_con">...</div>
            
            if (i === 0) {
                 // 检查第一个块是否也包含课程信息 (非 timetable_con 包裹的情况)
                 // 如果 split 长度为 1，说明没有 timetable_con，可能是直接内容
                 if (courseBlocks.length === 1 && courseBlocks[0].trim().length > 0) {
                     // 这种情况下，构造一个虚拟块处理
                     // 但通常强智系统都有 timetable_con。
                     // 如果没有 timetable_con，下面的逻辑（补全 div）会出错。
                     // 暂时保持原 zhengfang.js 逻辑，但需注意如果 block[0] 有内容如何处理。
                     // 原逻辑: if (i === 0) continue; 意味着 split 的第一部分被丢弃。
                     // 如果 cell 内容是: "Text <div class=timetable_con>..." -> Text 被丢弃
                     // 如果 cell 内容是: "<div class=timetable_con>..." -> split[0] 是 "" -> continue (正确)
                     // 如果 cell 内容是: "Course Info" (无 div) -> split 只有 1 个元素 -> i=0 continue -> 结果为空 (错误?)
                     // 原 zhengfang.js 逻辑在无 timetable_con 时会失败吗？
                     // 让我们看 zhengfang.js: if (i === 0) continue;
                     // 这意味着它假设必须有 <div class="timetable_con"> 分隔。
                     // 为了更稳健，我们稍微改进一下：如果 length=1 且没有分隔符，直接处理整个 cellContent
                 }
                 if (courseBlocks.length > 1) continue;
            }
            
            var blockHtml;
            if (courseBlocks.length > 1) {
                blockHtml = '<div class="timetable_con' + courseBlocks[i];
            } else {
                // 没有 timetable_con 分隔，尝试直接解析整个 content
                blockHtml = cellContent;
                // 如果内容太短或为空，跳过
                if (blockHtml.trim().length < 5) continue;
            }

            var name = "";
            var teacher = "";
            var location = "";
            var weeksStr = "";
            var sectionsStr = "";

            name = extractName(blockHtml);

            teacher = extractTextByTitle(blockHtml, "教师");
            if (teacher) {
                teacher = teacher.replace(/教师\s*[:：]?\s*/g, "").trim();
            }
            location = extractTextByTitle(blockHtml, "上课地点");
            if (location) {
                location = location.replace(/上课地点\s*[:：]?\s*/g, "").replace('泰山科技学院', '').trim();
            }

            var timeText = extractTextByTitle(blockHtml, "节/周");
            if (timeText) {
                sectionsStr = extractSectionsStr(timeText);
                weeksStr = extractWeeksStr(timeText);
            }

            // 补充匹配：有些系统直接用 (1-2节) 1-16周 格式
            var timeMatch = /[\(（](\d+(?:-\d+)?节)[\)）]\s*([^<]*周[^<]*)/i.exec(blockHtml);
            if (timeMatch) {
                sectionsStr = timeMatch[1];
                weeksStr = timeMatch[2];
            }

            // 兜底：如果没有通过 Title 提取到信息，尝试纯文本正则
            if (!teacher || !location || !weeksStr || !sectionsStr) {
                var text = normalizeText(blockHtml);
                if (!teacher) {
                    var teacherTextMatch = /教师\s*[:：]?\s*([^\s/，,;；]+)/.exec(text);
                    if (teacherTextMatch) {
                        teacher = teacherTextMatch[1].trim();
                    }
                }
                if (!location) {
                    var locTextMatch = /上课地点\s*[:：]?\s*([^教师周数节次校区]+)/.exec(text);
                    if (locTextMatch) {
                        location = locTextMatch[1].trim().replace('泰山科技学院', '').trim();
                    }
                }
                if (!weeksStr) {
                    weeksStr = extractWeeksStr(text);
                }
                if (!sectionsStr) {
                    sectionsStr = extractSectionsStr(text);
                }
            }

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

    // 处理列表模式 (tr > td > jc_1-2-3)
    var listRegex = /<tr[^>]*>[\s\S]*?<td[^>]*id=["']?jc_(\d+)-(\d+)-(\d+)["']?[^>]*>[\s\S]*?<\/td>\s*<td[^>]*>([\s\S]*?)<\/td>[\s\S]*?<\/tr>/gi;
    var listMatch;
    while ((listMatch = listRegex.exec(html)) !== null) {
        var listDay = parseInt(listMatch[1]);
        var sectionStart = parseInt(listMatch[2]);
        var sectionEnd = parseInt(listMatch[3]);
        var listBlockHtml = listMatch[4];
        var listName = extractName(listBlockHtml);
        var listText = normalizeText(listBlockHtml);
        var listTeacher = extractTextByTitle(listBlockHtml, "教师");
        if (listTeacher) {
            listTeacher = listTeacher.replace(/教师\s*[:：]?\s*/g, "").trim();
        }
        var listLocation = extractTextByTitle(listBlockHtml, "上课地点");
        if (listLocation) {
            listLocation = listLocation.replace(/上课地点\s*[:：]?\s*/g, "").replace('泰山科技学院', '').trim();
        }
        var listWeeksStr = extractWeeksStr(listText);
        var listSectionsStr = sectionStart ? (sectionStart + "-" + (sectionEnd || sectionStart) + "节") : extractSectionsStr(listText);
        var listTimeText = extractTextByTitle(listBlockHtml, "节/周");
        if (!listWeeksStr && listTimeText) {
            listWeeksStr = extractWeeksStr(listTimeText);
        }
        if (listName && listWeeksStr && listSectionsStr) {
            var listWeeks = parseWeeks(listWeeksStr);
            var listSections = parseSections(listSectionsStr);
            if (listWeeks.length > 0 && listSections.length > 0 && listDay > 0) {
                courses.push({
                    name: listName,
                    teacher: listTeacher,
                    position: listLocation,
                    day: listDay,
                    weeks: listWeeks,
                    sections: listSections
                });
            }
        }
    }

    return courses;
}

// ---------------- Helper Functions ----------------

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
 * 关键提取函数：根据 title 属性提取文本
 * 已包含“新版页面结构变更”修复：支持提取 span 标签内部的内容
 */
function extractTextByTitle(blockHtml, titleText) {
    // 1. 尝试匹配 title 在 span 标签内，并提取 span 的内容 (新版结构)
    // 结构: <span ... title="titleText"> ... content ... </span>
    var patternInside = '<span[^>]*title=["\']?' + titleText + '["\']?[^>]*>([\\s\\S]*?)<\\/span>';
    var matchInside = new RegExp(patternInside, "i").exec(blockHtml);
    if (matchInside) {
        return stripTags(matchInside[1]).trim();
    }

    // 2. 尝试旧逻辑：title 在某个标签内，后面紧跟着 font (部分旧版结构)
    // 结构: <span title="titleText">...</span> <font> ... content ... </font>
    var patternAfter = 'title=["\']?' + titleText + '\\s*["\']?[^>]*>[\\s\\S]*?<\\/span>\\s*<font[^>]*>([\\s\\S]*?)<\\/font>';
    var matchAfter = new RegExp(patternAfter, "i").exec(blockHtml);
    if (matchAfter) {
        return stripTags(matchAfter[1]).trim();
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
    return sections;
}

function normalizeText(html) {
    return stripTags(html).replace(/\s+/g, " ").replace(/：/g, ":").trim();
}

function stripTags(html) {
    var text = removeHtmlTags(html);
    text = decodeHtmlEntities(text);
    return removeHtmlTags(text).replace(/\s+/g, " ").trim();
}

function removeHtmlTags(rawText) {
    var result = String(rawText);
    var previous;
    do {
        previous = result;
        result = result.replace(/<[^>]*>/g, "");
    } while (result !== previous);
    return result.replace(/[<>]/g, "");
}

function decodeHtmlEntities(text) {
    if (!text) return "";
    var entities = {
        '&nbsp;': ' ', '&amp;': '&', '&lt;': '<', '&gt;': '>',
        '&quot;': '"', '&apos;': "'", '&#039;': "'"
    };
    return text.replace(/&[a-zA-Z0-9#]+;/g, function(match) {
        return entities[match] || match;
    });
}
