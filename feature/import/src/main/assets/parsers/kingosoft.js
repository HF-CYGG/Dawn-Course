/**
 * 青果 (Kingosoft) 教务系统解析脚本
 * 适配 Dawn Course 架构 (QuickJS 环境，无 DOM API)
 *
 * 功能：
 * 1. 解析 HTML 表格结构，提取课程数据
 * 2. 智能识别表头 (星期几)，解决不同学校课表格式差异
 * 3. 兼容旧版逻辑 (class="td") 作为兜底方案
 * 4. 清洗数据 (去除 &nbsp;, HTML 标签) 并转换为标准格式
 */

function scheduleHtmlParser(html) {
    var courses = [];
    // 预处理：去除换行符，简化正则匹配
    var cleanHtml = html.replace(/[\r\n]/g, "");

    // 1. 提取所有表格行 (tr)
    // 使用非贪婪匹配获取 <tr>...</tr> 内容
    var trRegex = /<tr[^>]*>(.*?)<\/tr>/gi;
    var trMatch;
    var rows = [];
    while ((trMatch = trRegex.exec(cleanHtml)) !== null) {
        rows.push(trMatch[1]);
    }

    // 2. 寻找表头行并建立索引映射
    // dayMap: 存储 列索引 -> 星期几 (1=周一, ..., 7=周日) 的映射关系
    var dayMap = {}; 
    var headerFound = false;

    // 辅助函数：提取行中的单元格内容 (td)
    // 兼容 <td ...> 和 <td0>, <td1> 等变体
    function getCells(rowHtml) {
        var cells = [];
        // 匹配 <td ...> ... </td>，使用非贪婪匹配 (.*?) 防止跨多个 td
        var tdRegex = /<td[^>]*>(.*?)<\/td>/gi;
        var match;
        while ((match = tdRegex.exec(rowHtml)) !== null) {
            cells.push(match[1]);
        }
        return cells;
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

    // 3. 遍历每一行进行解析
    for (var r = 0; r < rows.length; r++) {
        var rowContent = rows[r];
        var cells = getCells(rowContent);

        if (!headerFound) {
            // 阶段一：寻找表头
            // 遍历当前行的所有单元格，检查是否包含 "星期X" 或 "周X"
            for (var c = 0; c < cells.length; c++) {
                // 去除所有 HTML 标签，获取纯文本进行判断
                var text = sanitizePlainText(cells[c]);
                
                if (text.indexOf("星期一") !== -1 || text.indexOf("周一") !== -1) dayMap[c] = 1;
                else if (text.indexOf("星期二") !== -1 || text.indexOf("周二") !== -1) dayMap[c] = 2;
                else if (text.indexOf("星期三") !== -1 || text.indexOf("周三") !== -1) dayMap[c] = 3;
                else if (text.indexOf("星期四") !== -1 || text.indexOf("周四") !== -1) dayMap[c] = 4;
                else if (text.indexOf("星期五") !== -1 || text.indexOf("周五") !== -1) dayMap[c] = 5;
                else if (text.indexOf("星期六") !== -1 || text.indexOf("周六") !== -1) dayMap[c] = 6;
                else if (text.indexOf("星期日") !== -1 || text.indexOf("周日") !== -1 || text.indexOf("星期天") !== -1) dayMap[c] = 7;
            }
            
            // 如果本行包含至少一个星期关键词，标记为表头行，跳过后续解析
            if (Object.keys(dayMap).length > 0) {
                headerFound = true;
                continue; 
            }
        } else {
            // 阶段二：解析数据行
            // 根据 dayMap 映射，只解析对应的列
            for (var c = 0; c < cells.length; c++) {
                var day = dayMap[c];
                
                // 如果当前列不在映射中 (例如第一列通常是节次信息)，则跳过
                if (!day) continue; 

                var cellContent = cells[c];
                
                // 解析单元格内容提取课程列表
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
    // 旧逻辑不依赖表头，而是寻找特定的 class="td" 属性
    if (!headerFound) {
        return parseWithLegacyLogic(html);
    }

    return courses;
}

/**
 * 旧版解析逻辑 (Fallback)
 * 适用于没有明确表头，但单元格带有 class="td" 标记的旧版青果系统
 */
function parseWithLegacyLogic(html) {
    var courses = [];
    var cleanHtml = html.replace(/[\r\n]/g, "");
    var trRegex = /<tr[^>]*>(.*?)<\/tr>/gi;
    var trMatch;
    
    while ((trMatch = trRegex.exec(cleanHtml)) !== null) {
        var trContent = trMatch[1];
        // 严格匹配 class="td" 或 class='td'
        var tdRegex = /<td[^>]*class=["']?td["']?[^>]*>(.*?)<\/td>/gi;
        var tdMatch;
        var dayIndex = 0; // 隐式假设：匹配到的第一个 td 是周一，第二个是周二...
        
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
 * @param {string} cellContent 单元格 HTML
 * @param {int} day 星期几 (1-7)
 * @returns {Array} 解析出的课程对象列表
 */
function parseCell(cellContent, day) {
    // [Proactive Compatibility] 尝试使用新版正方/强智逻辑解析 (防止结构变更)
    // Try parsing with Zhengfang-style logic first if indicators are present
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
    // 青果系统通常在一个单元格内包含多门课程，可能用 <div> 包裹，也可能直接用 <br> 分隔
    if (cellContent.indexOf("<div") !== -1) {
        // 如果有 div，提取每个 div 的内容
        var divRegex = /<div[^>]*>(.*?)<\/div>/gi;
        var divMatch;
        while ((divMatch = divRegex.exec(cellContent)) !== null) {
            contentBlocks.push(divMatch[1]);
        }
    } else {
        // 无 div 包装，直接作为整块内容处理
        // 排除空课表标记 "div_nokb"
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
        var name = nameMatch ? sanitizePlainText(nameMatch[1]) : "";
        if (!name) continue; // 没有课程名则忽略
        
        // 移除课程名，剩余部分包含：教师、周次、节次、地点
        var remaining = block.replace(/<font[^>]*>.*?<\/font>/i, "");
        // 使用 <br> 分割剩余信息 (兼容 <br>, <br/>, <br />)
        var parts = remaining.split(/<br\s*\/?>/gi);
        
        var teacher = "";
        var weeksStr = "";
        var sectionsStr = "";
        var location = "";
        
        // 遍历分割后的片段，通过正则特征识别信息类型
        for (var j = 0; j < parts.length; j++) {
            var p = sanitizePlainText(parts[j]);
            if (!p) continue;
            
            // 匹配周次和节次: 格式如 "1-16[1-2]" 或 "1,3,5[1-2]"
            var timeMatch = /([0-9,\-]+)\[([0-9,\-]+)\]/.exec(p);
            if (timeMatch) {
                weeksStr = timeMatch[1];   // 捕获组 1: 周次
                sectionsStr = timeMatch[2]; // 捕获组 2: 节次
                continue;
            }
            
            // 增强：通过关键字识别地点 (优先于顺序推断)
            // 包含常见地点关键词，且之前未找到地点时，判定为地点
            if (!location && /楼|室|馆|区|号|座|园|部/.test(p)) {
                location = p;
                continue;
            }
            
            // 简单的位置/教师推断逻辑 (基于顺序的兜底策略)
            if (!weeksStr && !teacher) teacher = p; // 周次还没出现，先认为是教师
            else if (weeksStr && !location) location = p; // 周次出现后，认为是地点
            else if (!teacher) teacher = p; // 兜底
        }
        
        // 只有当核心信息完整时才添加结果
        if (name && weeksStr && sectionsStr) {
            results.push({
                name: name,
                teacher: teacher,
                position: location,
                day: day,
                weeks: parseWeeks(weeksStr),
                sections: parseSections(sectionsStr)
            });
        }
    }
    return results;
}

/**
 * 解析周次字符串
 * 输入示例: "1-16", "1-8,10-16", "1,3,5"
 * 输出: [1, 2, ..., 16]
 */
function parseWeeks(str) {
    var weeks = [];
    var parts = str.split(",");
    for (var i = 0; i < parts.length; i++) {
        var part = parts[i];
        if (part.indexOf("-") !== -1) {
            // 处理范围: "1-16"
            var range = part.split("-");
            var start = parseInt(range[0]);
            var end = parseInt(range[1]);
            for (var j = start; j <= end; j++) {
                weeks.push(j);
            }
        } else {
            // 处理单个: "1"
            weeks.push(parseInt(part));
        }
    }
    return weeks;
}

/**
 * 解析节次字符串
 * 输入示例: "1-2", "3-4"
 * 输出: [1, 2]
 */
function parseSections(str) {
    var sections = [];
    var parts = str.split(",");
    for (var i = 0; i < parts.length; i++) {
        var part = parts[i];
        if (part.indexOf("-") !== -1) {
            var range = part.split("-");
            var start = parseInt(range[0]);
            var end = parseInt(range[1]);
            for (var j = start; j <= end; j++) {
                sections.push(j);
            }
        } else {
            sections.push(parseInt(part));
        }
    }
    return sections;
}

/**
 * [Proactive Compatibility] Zhengfang-style Parsing Logic
 * Added to handle potential structure changes in Kingosoft systems
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
        var name = extractNameZhengfang(blockHtml);
        var teacher = extractTextByTitle(blockHtml, "教师");
        if (teacher) teacher = teacher.replace(/教师\s*[:：]?\s*/g, "").trim();
        
        var location = extractTextByTitle(blockHtml, "上课地点");
        if (location) location = location.replace(/上课地点\s*[:：]?\s*/g, "").replace('泰山科技学院', '').trim();
        
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
            var text = normalizeTextZhengfang(blockHtml);
            if (!teacher) {
                var tm = /教师\s*[:：]?\s*([^\s/，,;；]+)/.exec(text);
                if (tm) teacher = tm[1].trim();
            }
            if (!location) {
                var lm = /上课地点\s*[:：]?\s*([^教师周数节次校区]+)/.exec(text);
                if (lm) location = lm[1].trim();
            }
            if (!weeksStr) weeksStr = extractWeeksStr(text);
            if (!sectionsStr) sectionsStr = extractSectionsStr(text);
        }

        if (name && weeksStr && sectionsStr) {
            var weeks = parseWeeksZhengfang(weeksStr);
            var sections = parseSectionsZhengfang(sectionsStr);
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

function extractTextByTitle(blockHtml, titleText) {
    // 1. Try matching inside span (New Structure)
    var patternInside = '<span[^>]*title=["\']?' + titleText + '["\']?[^>]*>([\\s\\S]*?)<\\/span>';
    var matchInside = new RegExp(patternInside, "i").exec(blockHtml);
    if (matchInside) return stripTagsZhengfang(matchInside[1]).trim();

    // 2. Try matching after span (Old Structure)
    var patternAfter = 'title=["\']?' + titleText + '\\s*["\']?[^>]*>[\\s\\S]*?<\\/span>\\s*<font[^>]*>([\\s\\S]*?)<\\/font>';
    var matchAfter = new RegExp(patternAfter, "i").exec(blockHtml);
    if (matchAfter) return stripTagsZhengfang(matchAfter[1]).trim();
    
    return "";
}

function extractNameZhengfang(blockHtml) {
    var titleMatch = /<([a-zA-Z]+)[^>]*class=["']?title[^>]*>([\s\S]*?)<\/\1>/i.exec(blockHtml);
    if (titleMatch) return stripTagsZhengfang(titleMatch[2]).trim();
    return "";
}

function extractWeeksStr(text) {
    var weeksMatch = /周数\s*[:：]?\s*([^教师节次校区]+?周[^教师节次校区]*)/i.exec(text);
    if (weeksMatch) return weeksMatch[1].trim();
    var rangeMatch = /(\d+\s*[-至~～—－]\s*\d+\s*周[^\s]*)/i.exec(text);
    if (rangeMatch) return rangeMatch[1].trim();
    return "";
}

function extractSectionsStr(text) {
    var sectionMatch = /节次\s*[:：]?\s*(\d+)\s*[-至~～—－]\s*(\d+)/i.exec(text);
    if (sectionMatch) return sectionMatch[1] + "-" + sectionMatch[2] + "节";
    var rangeMatch = /第?\s*(\d+)\s*[-至~～—－]\s*(\d+)\s*节/i.exec(text);
    if (rangeMatch) return rangeMatch[1] + "-" + rangeMatch[2] + "节";
    return "";
}

function parseWeeksZhengfang(str) {
    var weeks = [];
    if (!str) return weeks;
    var type = 0; 
    if (str.indexOf("单") > -1) type = 1;
    if (str.indexOf("双") > -1) type = 2;
    str = str.replace(/周数[:：]/g, '').replace(/共\d+周|共\d+次|共\d+节/g, '').replace(/[至~～—－]/g, '-').replace(/周|单|双|\(|\)|（|）/g, '');
    var parts = str.split(/[,，;、]/);
    for (var i = 0; i < parts.length; i++) {
        var part = parts[i].trim();
        if (part.indexOf('-') > -1) {
            var range = part.split('-');
            var start = parseInt(range[0]);
            var end = parseInt(range[1]);
            if (!isNaN(start) && !isNaN(end)) {
                for (var w = start; w <= end; w++) {
                    if (type === 0 || (type === 1 && w % 2 !== 0) || (type === 2 && w % 2 === 0)) weeks.push(w);
                }
            }
        } else if (part !== '') {
            var week = parseInt(part);
            if (!isNaN(week)) {
                 if (type === 0 || (type === 1 && week % 2 !== 0) || (type === 2 && week % 2 === 0)) weeks.push(week);
            }
        }
    }
    return weeks;
}

function parseSectionsZhengfang(sectionsString) {
    var sections = [];
    var str = sectionsString.replace(/第/g, "").replace(/节次[:：]/g, "").replace(/节/g, "").replace(/[\(（\)）]/g, "").replace(/[至~～—－]/g, "-");
    var parts = str.split("-");
    var start = parseInt(parts[0]);
    var end = parseInt(parts[1] || parts[0]);
    if (!isNaN(start)) {
        for (var s = start; s <= end; s++) sections.push(s);
    }
    return sections;
}

function normalizeTextZhengfang(html) {
    return stripTagsZhengfang(html).replace(/\s+/g, " ").replace(/：/g, ":").trim();
}

function stripTagsZhengfang(html) {
    var text = removeHtmlTags(html);
    text = decodeHtmlEntities(text);
    return removeHtmlTags(text).replace(/\s+/g, " ").trim();
}
