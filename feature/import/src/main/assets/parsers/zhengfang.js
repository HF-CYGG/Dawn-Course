/**
 * 泰山科技学院/强智教务系统 解析脚本 (Regex 实现版)
 * 对应 docs/泰山科技学院教务系统脚本开发文档.md 的逻辑
 * 
 * 注：由于 QuickJS 环境默认不包含 Cheerio 库，此处使用 Regex 实现文档中描述的相同解析逻辑。
 * 效果与文档提供的 cheerio 版本一致。
 * 
 * 更新：集成“旧正方”教务系统解析逻辑，兼容更多正方系统版本。
 */

function scheduleHtmlParser(html) {
    // 尝试使用新正方/强智逻辑解析 (Based on ID/Class)
    var courses = parseNewZhengfang(html);
    
    // 如果没有找到课程，尝试使用旧正方逻辑解析 (Based on Text/Table Structure)
    if (courses.length === 0) {
        courses = parseOldZhengfang(html);
    }
    
    courses = dedupeCourses(courses);
    return JSON.stringify(courses);
}

/**
 * 新正方/强智教务系统解析逻辑
 */
function parseNewZhengfang(html) {
    var courses = [];
    var tdRegex = /<td[^>]*\bid\s*=\s*["']?(\d+)-(\d+)["']?[^>]*>([\s\S]*?)<\/td>/gi;
    var match;

    while ((match = tdRegex.exec(html)) !== null) {
        var day = parseInt(match[1]);
        var cellContent = match[3];

        var courseBlocks = cellContent.split(/<div\s+class=["']?timetable_con/i);

        for (var i = 0; i < courseBlocks.length; i++) {
            if (i === 0) continue;
            var blockHtml = '<div class="timetable_con' + courseBlocks[i];

            var name = "";
            var teacher = "";
            var location = "";
            var weeksStr = "";
            var sectionsStr = "";

            name = extractName(blockHtml);

            teacher = extractTextByTitle(blockHtml, "教师");
            if (teacher) {
                teacher = cleanTeacherName(teacher);
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

            var timeMatch = /[\(（](\d+(?:-\d+)?节)[\)）]\s*([^<]*周[^<]*)/i.exec(blockHtml);
            if (timeMatch) {
                sectionsStr = timeMatch[1];
                weeksStr = timeMatch[2];
            }

            if (!teacher || !location || !weeksStr || !sectionsStr) {
                var text = normalizeText(blockHtml);
                if (!teacher) {
                    var teacherTextMatch = /教师\s*[:：]?\s*([^\s/，,;；]+)/.exec(text);
                    if (teacherTextMatch) {
                        teacher = cleanTeacherName(teacherTextMatch[1].trim());
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
            listTeacher = cleanTeacherName(listTeacher);
        } else {
            var listTeacherMatch = /教师\s*[:：]?\s*([^\s/，,;；]+)/.exec(listText);
            if (listTeacherMatch) {
                listTeacher = cleanTeacherName(listTeacherMatch[1].trim());
            }
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

/**
 * 旧正方教务系统解析逻辑 (兼容 DOM/Provider 逻辑)
 */
function parseOldZhengfang(html) {
    var courses = [];
    var rows = [];
    var trRegex = /<tr[^>]*>([\s\S]*?)<\/tr>/gi;
    var match;
    while ((match = trRegex.exec(html)) !== null) {
        rows.push(match[1]);
    }

    for (var i = 0; i < rows.length; i++) {
        // i 对应 provider.js 中的 index
        // provider.js: index -= 1; if (index < 1) return;
        // 即跳过 index 0 和 1 (Header 和 早晨/标签行)
        // 有效数据从 i=2 开始
        // 默认节次 defaultSection = index - 1 (因为 index 0 是 header, index 1 是空/标签, index 2 是第一节)
        // 实际上正方表格通常: Row 0=Header, Row 1=MorningSpan+Section1? 或者 Row 1=MorningSpan, Row 2=Section1?
        // 这里的逻辑主要依赖内容识别，但兜底需要 defaultSection
        
        var rowHtml = rows[i];
        var cells = [];
        var tdRegex = /<td[^>]*>([\s\S]*?)<\/td>/gi;
        var tdMatch;
        while ((tdMatch = tdRegex.exec(rowHtml)) !== null) {
            cells.push(tdMatch[1]);
        }
        
        for (var j = 0; j < cells.length; j++) {
            var cellHtml = cells[j];
            // Split by <br>
            var parts = cellHtml.split(/<br\s*\/?>/gi);
            // Filter empty and clean
            var rawInfo = [];
            for(var k=0; k<parts.length; k++) {
                var p = sanitizePlainText(parts[k]);
                if (p) rawInfo.push(p);
            }
            
            if (rawInfo.length < 2) continue;
            
            var idx = 0;
            // 遍历行寻找以 "周" 开头的时间描述
            while (idx < rawInfo.length) {
                if (!/^周/.test(rawInfo[idx])) {
                    idx++;
                    continue;
                }
                
                // 找到时间行 idx
                // 结构通常为: Name, Time, Teacher, Location
                // provider.js: Name=index, Time=index+1, Teacher=index+2, Location=index+3
                // 这里的 idx 是 Time 的位置
                
                if (idx - 1 < 0) { idx++; continue; }
                
                var name = rawInfo[idx-1];
                var timeStr = rawInfo[idx];
                var teacher = (idx + 1 < rawInfo.length) ? cleanTeacherName(rawInfo[idx+1]) : "";
                var location = (idx + 2 < rawInfo.length) ? rawInfo[idx+2] : "";
                
                // 解析 Day
                var dayChar = timeStr.charAt(1);
                var day = 0;
                var char2Day = {'一':1, '二':2, '三':3, '四':4, '五':5, '六':6, '日':7, '七':7};
                if (char2Day[dayChar]) day = char2Day[dayChar];
                
                // 解析 Ranges (节次 和 周次)
                var ranges = parseOldTimeRanges(timeStr);
                var sections = [];
                var weeks = [];
                
                if (ranges.length === 2) {
                    sections = ranges[0];
                    weeks = ranges[1];
                } else if (ranges.length === 1) {
                    // 只有周次，节次由行号推断
                    // defaultSection logic: 假设 Row 2 -> Section 1
                    var defaultSection = i - 1; 
                    if (defaultSection > 0) sections.push(defaultSection);
                    weeks = ranges[0];
                }
                
                // 处理单双周
                if (/\|单周/.test(timeStr)) {
                    weeks = weeks.filter(function(w) { return w % 2 !== 0; });
                } else if (/\|双周/.test(timeStr)) {
                    weeks = weeks.filter(function(w) { return w % 2 === 0; });
                }
                
                if (day > 0 && weeks.length > 0 && sections.length > 0) {
                    courses.push({
                        name: name,
                        teacher: teacher,
                        position: location,
                        day: day,
                        weeks: weeks,
                        sections: sections
                    });
                }
                
                idx += 3; // 跳过已处理的块
            }
        }
    }
    return courses;
}

function parseOldTimeRanges(rawTime) {
    var result = [];
    var regex = /(\d+)[-,]?(\d*)/g;
    var match;
    // 限制只匹配前面的数字部分，避免匹配到无关内容
    // 但正方格式通常比较规范，如 "周二第1,2节{第1-16周}"
    while ((match = regex.exec(rawTime)) !== null) {
        var start = parseInt(match[1]);
        var end = match[2] ? parseInt(match[2]) : start;
        var range = [];
        for(var k=start; k<=end; k++) range.push(k);
        result.push(range);
    }
    return result;
}

function sanitizePlainText(rawHtml) {
    if (!rawHtml) return "";
    var text = String(rawHtml);
    text = removeHtmlTags(text);
    text = decodeHtmlEntities(text);
    // Decode后再次清洗，防止实体解码产生新标签
    text = removeHtmlTags(text);
    return text.replace(/\s+/g, " ").trim();
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
 * 解码HTML实体
 */
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

function stripTags(html) {
    var text = removeHtmlTags(html);
    text = decodeHtmlEntities(text);
    return removeHtmlTags(text).replace(/\s+/g, " ").trim();
}

function normalizeText(html) {
    return stripTags(html).replace(/\s+/g, " ").replace(/：/g, ":").trim();
}

function cleanTeacherName(raw) {
    if (!raw) return "";
    var text = stripTags(raw);
    text = text.replace(/教师\s*[:：]?\s*/g, "").trim();
    var keywordRegex = /(教学班组成|教学班|选课备注|考核方式|课程学时组成|总学时|学时|学分|班级|课程性质|课程类别)\s*[:：]?/;
    var match = keywordRegex.exec(text);
    if (match) {
        text = text.substring(0, match.index).trim();
    }
    text = text.replace(/[，,;；]\s*$/, "").trim();
    return text;
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

function extractTextByTitle(blockHtml, titleText) {
    var pattern = 'title=["\']?' + titleText + '\\s*["\']?[^>]*>[\\s\\S]*?<\\/span>\\s*<font[^>]*>([\\s\\S]*?)<\\/font>';
    var match = new RegExp(pattern, "i").exec(blockHtml);
    if (match) {
        return stripTags(match[1]).trim();
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
