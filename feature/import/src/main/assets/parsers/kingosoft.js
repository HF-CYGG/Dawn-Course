/**
 * 青果 (Kingosoft) 教务系统解析脚本
 * 适配 Dawn Course 架构 (QuickJS 环境，无 DOM API)
 *
 * 功能：
 * 1. 解析 HTML 表格结构，提取课程数据
 * 2. 智能识别表头 (星期几)，解决不同学校课表格式差异
 * 3. 兼容旧版逻辑 (class="td") 作为兜底方案
 * 4. 清洗数据 (去除 &nbsp;, HTML 标签) 并转换为标准格式
 * 
 * 依赖: common_parser_utils.js
 */

function scheduleHtmlParser(html) {
    var courses = [];
    // 预处理：去除换行符，简化正则匹配
    var cleanHtml = html.replace(/[\r\n]/g, "");

    // 1. 提取所有表格行 (tr)
    var trRegex = /<tr[^>]*>(.*?)<\/tr>/gi;
    var trMatch;
    var rows = [];
    while ((trMatch = trRegex.exec(cleanHtml)) !== null) {
        rows.push(trMatch[1]);
    }

    // 2. 寻找表头行并建立索引映射
    var dayMap = {}; 
    var headerFound = false;

    // 辅助函数：提取行中的单元格内容 (td)
    function getCells(rowHtml) {
        var cells = [];
        var tdRegex = /<(td|th)[^>]*>(.*?)<\/\1>/gi;
        var match;
        while ((match = tdRegex.exec(rowHtml)) !== null) {
            cells.push(match[2]);
        }
        return cells;
    }

    var listCourses = parseListTable(cleanHtml);
    if (listCourses && listCourses.length > 0) {
        return listCourses;
    }

    // 3. 遍历每一行进行解析
    for (var r = 0; r < rows.length; r++) {
        var rowContent = rows[r];
        var cells = getCells(rowContent);

        if (!headerFound) {
            // 阶段一：寻找表头
            for (var c = 0; c < cells.length; c++) {
                // 使用通用 stripTags 获取纯文本
                var text = stripTags(cells[c]);
                
                if (text.indexOf("星期一") !== -1 || text.indexOf("周一") !== -1) dayMap[c] = 1;
                else if (text.indexOf("星期二") !== -1 || text.indexOf("周二") !== -1) dayMap[c] = 2;
                else if (text.indexOf("星期三") !== -1 || text.indexOf("周三") !== -1) dayMap[c] = 3;
                else if (text.indexOf("星期四") !== -1 || text.indexOf("周四") !== -1) dayMap[c] = 4;
                else if (text.indexOf("星期五") !== -1 || text.indexOf("周五") !== -1) dayMap[c] = 5;
                else if (text.indexOf("星期六") !== -1 || text.indexOf("周六") !== -1) dayMap[c] = 6;
                else if (text.indexOf("星期日") !== -1 || text.indexOf("周日") !== -1 || text.indexOf("星期天") !== -1) dayMap[c] = 7;
            }
            
            if (Object.keys(dayMap).length > 0) {
                headerFound = true;
                continue; 
            }
        } else {
            // 阶段二：解析数据行
            for (var c = 0; c < cells.length; c++) {
                var day = dayMap[c];
                if (!day) continue; 

                var cellContent = cells[c];
                var parsedList = parseCell(cellContent, day);
                if (parsedList && parsedList.length > 0) {
                    for(var k=0; k<parsedList.length; k++) {
                        courses.push(parsedList[k]);
                    }
                }
            }
        }
    }
    
    // 4. 兜底策略：如果没找到表头，尝试使用旧版逻辑
    if (!headerFound) {
        return parseWithLegacyLogic(html);
    }

    return courses;
}

/**
 * 旧版解析逻辑 (Fallback)
 */
function parseWithLegacyLogic(html) {
    var courses = [];
    var cleanHtml = html.replace(/[\r\n]/g, "");
    var trRegex = /<tr[^>]*>(.*?)<\/tr>/gi;
    var trMatch;
    
    while ((trMatch = trRegex.exec(cleanHtml)) !== null) {
        var trContent = trMatch[1];
        var tdRegex = /<td[^>]*class=["']?td["']?[^>]*>(.*?)<\/td>/gi;
        var tdMatch;
        var dayIndex = 0; 
        
        while ((tdMatch = tdRegex.exec(trContent)) !== null) {
            var cellContent = tdMatch[1];
            var day = dayIndex + 1;
            
            var parsedList = parseCell(cellContent, day);
            if (parsedList && parsedList.length > 0) {
                for(var k=0; k<parsedList.length; k++) {
                    courses.push(parsedList[k]);
                }
            }
            dayIndex++;
        }
    }
    return courses;
}

/**
 * 解析单个单元格内容
 */
function parseCell(cellContent, day) {
    // [Proactive Compatibility] 尝试使用新版正方/强智逻辑解析
    if (cellContent.indexOf('class="timetable_con"') !== -1 || 
        (cellContent.indexOf('title=') !== -1 && cellContent.indexOf('教师') !== -1)) {
        var zfCourses = parseZhengfangStyleCell(cellContent, day);
        if (zfCourses && zfCourses.length > 0) {
            return zfCourses;
        }
    }

    var results = [];
    var contentBlocks = [];
    
    // 1. 分块处理
    if (cellContent.indexOf("<div") !== -1) {
        var divRegex = /<div[^>]*>(.*?)<\/div>/gi;
        var divMatch;
        while ((divMatch = divRegex.exec(cellContent)) !== null) {
            contentBlocks.push(divMatch[1]);
        }
    } else {
        if (cellContent.indexOf("div_nokb") === -1) {
            contentBlocks.push(cellContent);
        }
    }
    
    // 2. 遍历每个内容块解析课程详情
    for (var i = 0; i < contentBlocks.length; i++) {
        var block = contentBlocks[i];
        if (!block || block.indexOf("div_nokb") !== -1 || block.trim().length === 0) continue;
        
        // 提取课程名: 通常在 <font> 标签中
        var nameMatch = /<font[^>]*>(.*?)<\/font>/i.exec(block);
        var name = nameMatch ? stripTags(nameMatch[1]) : "";
        if (!name) name = extractName(block);
        if (!name) {
            var blockText = stripTags(block);
            var textParts = blockText.split(/[\r\n]/g);
            for (var tp = 0; tp < textParts.length; tp++) {
                var t = textParts[tp].trim();
                if (t) {
                    name = t;
                    break;
                }
            }
        }
        if (!name) continue;
        
        // 移除课程名，剩余部分包含：教师、周次、节次、地点
        var remaining = block.replace(/<font[^>]*>.*?<\/font>/i, "");
        // 使用 <br> 分割剩余信息
        var parts = remaining.split(/<br\s*\/?>/gi);
        
        var teacher = "";
        var weeksStr = "";
        var sectionsStr = "";
        var location = "";
        
        for (var j = 0; j < parts.length; j++) {
            var p = stripTags(parts[j]);
            if (!p) continue;
            
            // 匹配周次和节次: 格式如 "1-16[1-2]" 或 "1,3,5[1-2]"
            var timeMatch = /([0-9,\-]+)\s*(?:周|周次)?\s*[\[\(（]\s*([0-9,\-]+)\s*(?:节|节次)?\s*[\]\)）]/.exec(p);
            if (timeMatch) {
                weeksStr = timeMatch[1];   // 捕获组 1: 周次
                sectionsStr = timeMatch[2]; // 捕获组 2: 节次
                continue;
            }
            if (!weeksStr) weeksStr = extractWeeksStr(p);
            if (!sectionsStr) sectionsStr = extractSectionsStr(p);
            
            // 增强：通过关键字识别地点
            if (!location && /楼|室|馆|区|号|座|园|部/.test(p)) {
                location = p;
                continue;
            }
            
            // 简单的位置/教师推断逻辑
            if (!weeksStr && !teacher) teacher = p; 
            else if (weeksStr && !location) location = p; 
            else if (!teacher) teacher = p; 
        }
        
        if (!weeksStr) weeksStr = extractWeeksStr(stripTags(remaining));
        if (!sectionsStr) sectionsStr = extractSectionsStr(stripTags(remaining));

        if (name && weeksStr && sectionsStr) {
            results.push({
                name: name,
                teacher: teacher,
                position: location,
                day: day,
                weeks: parseWeeks(weeksStr), // 使用通用 parseWeeks
                sections: parseSections(sectionsStr) // 使用通用 parseSections
            });
        }
    }
    return results;
}

/**
 * [Proactive Compatibility] Zhengfang-style Parsing Logic
 * Uses common helpers
 */
function parseZhengfangStyleCell(cellContent, day) {
    var courses = [];
    var blocks = [];
    
    if (cellContent.indexOf('class="timetable_con"') !== -1) {
        var parts = cellContent.split(/<div\s+class=["']?timetable_con/i);
        for (var i = 1; i < parts.length; i++) {
            blocks.push('<div class="timetable_con' + parts[i]);
        }
    } else {
        blocks.push(cellContent);
    }

    for (var i = 0; i < blocks.length; i++) {
        var blockHtml = blocks[i];
        // Use common helpers
        var name = extractName(blockHtml);
        var teacher = extractTextByTitle(blockHtml, "教师");
        if (!teacher) teacher = extractTextByTitle(blockHtml, "任课教师");
        if (teacher) teacher = teacher.replace(/教师\s*[:：]?\s*/g, "").replace(/任课教师\s*[:：]?\s*/g, "").trim();
        
        var location = extractTextByTitle(blockHtml, "上课地点");
        if (!location) location = extractTextByTitle(blockHtml, "教室");
        if (!location) location = extractTextByTitle(blockHtml, "校区/上课地点");
        if (location) location = location.replace(/上课地点\s*[:：]?\s*/g, "").replace(/教室\s*[:：]?\s*/g, "").replace('泰山科技学院', '').trim();
        
        var weeksStr = "";
        var sectionsStr = "";
        var timeText = extractTextByTitle(blockHtml, "节/周");
        if (timeText) {
            sectionsStr = extractSectionsStr(timeText);
            weeksStr = extractWeeksStr(timeText);
        }
        
        if (!name && !teacher && !weeksStr) continue;

        // Fallback extraction
        if (!teacher || !location || !weeksStr || !sectionsStr) {
            var text = normalizeText(blockHtml);
            if (!teacher) {
                var tm = /教师\s*[:：]?\s*([^\s/，,;；]+)/.exec(text);
                if (tm) teacher = tm[1].trim();
                if (!teacher) {
                    var tm2 = /(教师|任课教师)\s*[:：]?\s*([\s\S]*?)(?=周数|节次|上课地点|教室|校区|$)/.exec(text);
                    if (tm2) teacher = tm2[2].trim();
                }
            }
            if (!location) {
                var lm = /上课地点\s*[:：]?\s*([^教师周数节次校区]+)/.exec(text);
                if (lm) location = lm[1].trim();
                if (!location) {
                    var lm2 = /(上课地点|教室)\s*[:：]?\s*([\s\S]*?)(?=教师|周数|节次|校区|$)/.exec(text);
                    if (lm2) location = lm2[2].trim();
                }
            }
            if (!weeksStr) weeksStr = extractWeeksStr(text);
            if (!sectionsStr) sectionsStr = extractSectionsStr(text);
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
    return courses;
}

function parseListTable(cleanHtml) {
    var rows = [];
    var trRegex = /<tr[^>]*>(.*?)<\/tr>/gi;
    var trMatch;
    while ((trMatch = trRegex.exec(cleanHtml)) !== null) {
        rows.push(trMatch[1]);
    }

    var headerIndex = -1;
    var headerCells = null;
    for (var i = 0; i < rows.length; i++) {
        var cells = getRowCells(rows[i]);
        if (!cells || cells.length === 0) continue;
        var headerText = stripTags(cells.join(" "));
        if (headerText.indexOf("课程") !== -1 && (headerText.indexOf("周次") !== -1 || headerText.indexOf("星期") !== -1 || headerText.indexOf("节次") !== -1 || headerText.indexOf("上课") !== -1)) {
            headerIndex = i;
            headerCells = cells;
            break;
        }
    }

    if (headerIndex === -1 || !headerCells) return [];

    var indexMap = buildHeaderIndex(headerCells);
    if (indexMap.name < 0) return [];

    var courses = [];
    for (var r = headerIndex + 1; r < rows.length; r++) {
        var rowCells = getRowCells(rows[r]);
        if (!rowCells || rowCells.length === 0) continue;
        var nameText = getCellText(rowCells, indexMap.name);
        if (!nameText) continue;
        var teacher = getCellText(rowCells, indexMap.teacher);
        var location = getCellText(rowCells, indexMap.location);
        var weeksText = getCellText(rowCells, indexMap.weeks);
        var sectionsText = getCellText(rowCells, indexMap.sections);
        var dayText = getCellText(rowCells, indexMap.day);

        var rowText = stripTags(rowCells.join(" "));
        if (!weeksText) weeksText = extractWeeksStr(rowText);
        if (!sectionsText) sectionsText = extractSectionsStr(rowText);
        var day = parseDayFromText(dayText || rowText);

        if (!day || !weeksText || !sectionsText) continue;

        var weeks = parseWeeks(weeksText);
        var sections = parseSections(sectionsText);
        if (weeks.length === 0 || sections.length === 0) continue;

        courses.push({
            name: nameText,
            teacher: teacher,
            position: location,
            day: day,
            weeks: weeks,
            sections: sections
        });
    }
    return courses;
}

function getRowCells(rowHtml) {
    var cells = [];
    var tdRegex = /<(td|th)[^>]*>(.*?)<\/\1>/gi;
    var match;
    while ((match = tdRegex.exec(rowHtml)) !== null) {
        cells.push(match[2]);
    }
    return cells;
}

function getCellText(cells, index) {
    if (index === undefined || index === null || index < 0) return "";
    if (index >= cells.length) return "";
    return stripTags(cells[index]);
}

function buildHeaderIndex(headerCells) {
    var indexMap = {
        name: -1,
        teacher: -1,
        location: -1,
        weeks: -1,
        sections: -1,
        day: -1
    };
    for (var i = 0; i < headerCells.length; i++) {
        var text = stripTags(headerCells[i]);
        if (indexMap.name === -1 && /课程|科目/.test(text)) indexMap.name = i;
        if (indexMap.teacher === -1 && /教师|任课|讲师/.test(text)) indexMap.teacher = i;
        if (indexMap.location === -1 && /地点|教室|校区|上课地点/.test(text)) indexMap.location = i;
        if (indexMap.weeks === -1 && /周次|周数/.test(text)) indexMap.weeks = i;
        if (indexMap.sections === -1 && /节次|节数|节/.test(text)) indexMap.sections = i;
        if (indexMap.day === -1 && /星期|周几|星期几|上课日/.test(text)) indexMap.day = i;
    }
    return indexMap;
}

function parseDayFromText(text) {
    if (!text) return 0;
    var raw = stripTags(text).replace(/\s+/g, "");
    if (raw.indexOf("星期一") !== -1 || raw.indexOf("周一") !== -1) return 1;
    if (raw.indexOf("星期二") !== -1 || raw.indexOf("周二") !== -1) return 2;
    if (raw.indexOf("星期三") !== -1 || raw.indexOf("周三") !== -1) return 3;
    if (raw.indexOf("星期四") !== -1 || raw.indexOf("周四") !== -1) return 4;
    if (raw.indexOf("星期五") !== -1 || raw.indexOf("周五") !== -1) return 5;
    if (raw.indexOf("星期六") !== -1 || raw.indexOf("周六") !== -1) return 6;
    if (raw.indexOf("星期日") !== -1 || raw.indexOf("周日") !== -1 || raw.indexOf("星期天") !== -1) return 7;
    var cnMatch = /(星期|周)\s*([一二三四五六日天])/.exec(raw);
    if (cnMatch) {
        var dayCn = cnMatch[2];
        if (dayCn === "一") return 1;
        if (dayCn === "二") return 2;
        if (dayCn === "三") return 3;
        if (dayCn === "四") return 4;
        if (dayCn === "五") return 5;
        if (dayCn === "六") return 6;
        if (dayCn === "日" || dayCn === "天") return 7;
    }
    if (/^[一二三四五六日天]$/.test(raw)) {
        if (raw === "一") return 1;
        if (raw === "二") return 2;
        if (raw === "三") return 3;
        if (raw === "四") return 4;
        if (raw === "五") return 5;
        if (raw === "六") return 6;
        if (raw === "日" || raw === "天") return 7;
    }
    var numPrefixedMatch = /(星期|周)\s*([1-7])/.exec(raw);
    if (numPrefixedMatch) return parseInt(numPrefixedMatch[2], 10);
    if (/^[1-7]$/.test(raw)) return parseInt(raw, 10);
    return 0;
}
