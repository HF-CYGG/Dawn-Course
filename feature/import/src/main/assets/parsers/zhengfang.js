/**
 * 泰山科技学院/强智教务系统 解析脚本 (Regex 实现版)
 * 对应 docs/泰山科技学院教务系统脚本开发文档.md 的逻辑
 * 
 * 注：由于 QuickJS 环境默认不包含 Cheerio 库，此处使用 Regex 实现文档中描述的相同解析逻辑。
 * 效果与文档提供的 cheerio 版本一致。
 */

function scheduleHtmlParser(html) {
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

            var teacherMatch = /title=["']?教师\s*["']?[^>]*>[\s\S]*?<\/span>\s*<font[^>]*>[\s\S]*?<i>([\s\S]*?)<\/i>/i.exec(blockHtml);
            if (teacherMatch) {
                var rawTeacher = stripTags(teacherMatch[1]).trim();
                teacher = rawTeacher.replace(/教师\s*/, '').trim();
            }

            var locMatch = /title=["']?上课地点["']?[^>]*>[\s\S]*?<\/span>\s*<font[^>]*>[\s\S]*?<i>([\s\S]*?)<\/i>/i.exec(blockHtml);
            if (locMatch) {
                var rawLoc = stripTags(locMatch[1]).trim();
                location = rawLoc.replace(/上课地点\s*/, '').replace('泰山科技学院', '').trim();
            }

            var timeMatch = /[\(（](\d+(?:-\d+)?节)[\)）]\s*([^<]*周[^<]*)/i.exec(blockHtml);
            if (timeMatch) {
                sectionsStr = timeMatch[1]; // 1-2节
                weeksStr = timeMatch[2];    // 1-4周,7-8周...
            }

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

    var listRegex = /<tr[^>]*>[\s\S]*?<td[^>]*id=["']?jc_(\d+)-(\d+)-(\d+)["']?[^>]*>[\s\S]*?<\/td>\s*<td[^>]*>([\s\S]*?)<\/td>[\s\S]*?<\/tr>/gi;
    var listMatch;
    while ((listMatch = listRegex.exec(html)) !== null) {
        var listDay = parseInt(listMatch[1]);
        var sectionStart = parseInt(listMatch[2]);
        var sectionEnd = parseInt(listMatch[3]);
        var listBlockHtml = listMatch[4];
        var listName = extractName(listBlockHtml);
        var listText = normalizeText(listBlockHtml);
        var listTeacherMatch = /教师\s*[:：]?\s*([^\s/，,;；]+)/.exec(listText);
        var listTeacher = listTeacherMatch ? listTeacherMatch[1].trim() : "";
        var listLocMatch = /上课地点\s*[:：]?\s*([^教师周数节次校区]+)/.exec(listText);
        var listLocation = listLocMatch ? listLocMatch[1].trim().replace('泰山科技学院', '').trim() : "";
        var listWeeksStr = extractWeeksStr(listText);
        var listSectionsStr = sectionStart ? (sectionStart + "-" + (sectionEnd || sectionStart) + "节") : extractSectionsStr(listText);
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

    courses = dedupeCourses(courses);
    return JSON.stringify(courses);
}

function stripTags(html) {
    return html.replace(/<[^>]+>/g, "").replace(/&nbsp;/g, " ");
}

function normalizeText(html) {
    return stripTags(html).replace(/\s+/g, " ").replace(/：/g, ":").trim();
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
