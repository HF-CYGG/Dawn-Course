/**
 * 强智教务系统 (QiangZhi) 解析脚本
 * 基于新版正方教务系统解析逻辑提取，并针对强智系统特性进行优化。
 * 包含针对新版页面结构变更（内容在 span 标签内）的兼容性支持。
 * 
 * 依赖: common_parser_utils.js (提供 dedupeCourses, extractTextByTitle 等通用函数)
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
            
            if (i === 0) {
                 // 检查第一个块是否也包含课程信息 (非 timetable_con 包裹的情况)
                 // 如果 split 长度为 1，说明没有 timetable_con，可能是直接内容
                 if (courseBlocks.length === 1 && courseBlocks[0].trim().length > 0) {
                     // 这种情况下，构造一个虚拟块处理
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
            if (!teacher) teacher = extractTextByTitle(blockHtml, "任课教师");
            if (teacher) {
                teacher = teacher.replace(/教师\s*[:：]?\s*/g, "").replace(/任课教师\s*[:：]?\s*/g, "").trim();
            }
            location = extractTextByTitle(blockHtml, "上课地点");
            if (!location) location = extractTextByTitle(blockHtml, "教室");
            if (location) {
                location = location.replace(/上课地点\s*[:：]?\s*/g, "").replace(/教室\s*[:：]?\s*/g, "").replace('泰山科技学院', '').trim();
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
                    if (!teacher) {
                        var teacherTextMatch2 = /(教师|任课教师)\s*[:：]?\s*([\s\S]*?)(?=周数|节次|上课地点|教室|校区|$)/.exec(text);
                        if (teacherTextMatch2) {
                            teacher = teacherTextMatch2[2].trim();
                        }
                    }
                }
                if (!location) {
                    var locTextMatch = /上课地点\s*[:：]?\s*([^教师周数节次校区]+)/.exec(text);
                    if (locTextMatch) {
                        location = locTextMatch[1].trim().replace('泰山科技学院', '').trim();
                    }
                    if (!location) {
                        var locTextMatch2 = /(上课地点|教室)\s*[:：]?\s*([\s\S]*?)(?=教师|周数|节次|校区|$)/.exec(text);
                        if (locTextMatch2) {
                            location = locTextMatch2[2].trim().replace('泰山科技学院', '').trim();
                        }
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
        if (!listTeacher) listTeacher = extractTextByTitle(listBlockHtml, "任课教师");
        if (listTeacher) {
            listTeacher = listTeacher.replace(/教师\s*[:：]?\s*/g, "").replace(/任课教师\s*[:：]?\s*/g, "").trim();
        }
        var listLocation = extractTextByTitle(listBlockHtml, "上课地点");
        if (!listLocation) listLocation = extractTextByTitle(listBlockHtml, "教室");
        if (listLocation) {
            listLocation = listLocation.replace(/上课地点\s*[:：]?\s*/g, "").replace(/教室\s*[:：]?\s*/g, "").replace('泰山科技学院', '').trim();
        }
        if (!listTeacher) {
            var listTeacherTextMatch = /(教师|任课教师)\s*[:：]?\s*([\s\S]*?)(?=周数|节次|上课地点|教室|校区|$)/.exec(listText);
            if (listTeacherTextMatch) {
                listTeacher = listTeacherTextMatch[2].trim();
            }
        }
        if (!listLocation) {
            var listLocTextMatch = /(上课地点|教室)\s*[:：]?\s*([\s\S]*?)(?=教师|周数|节次|校区|$)/.exec(listText);
            if (listLocTextMatch) {
                listLocation = listLocTextMatch[2].trim().replace('泰山科技学院', '').trim();
            }
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
