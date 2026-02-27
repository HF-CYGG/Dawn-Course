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

        // 循环去除 HTML 标签，防止嵌套标签绕过
        var maxLoop = 10;
        while (/<[^>]*>/.test(text) && maxLoop-- > 0) {
            text = text.replace(/<[^>]*>/g, "");
        }

        text = text.replace(/&nbsp;|&#160;/gi, " ");
        text = text.replace(/&amp;/gi, "&");
        text = text.replace(/&lt;/gi, "<");
        text = text.replace(/&gt;/gi, ">");
        text = text.replace(/&quot;/gi, "\"");
        text = text.replace(/&#39;/gi, "'");
        text = text.replace(/&#x([0-9a-fA-F]+);/g, function(_, code) {
            var n = parseInt(code, 16);
            return isNaN(n) ? "" : String.fromCharCode(n);
        });
        text = text.replace(/&#(\d+);/g, function(_, code) {
            var n = parseInt(code, 10);
            return isNaN(n) ? "" : String.fromCharCode(n);
        });
        return text.replace(/\s+/g, " ").trim();
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
