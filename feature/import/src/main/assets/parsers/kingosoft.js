/**
 * 青果 (Kingosoft) 教务系统解析脚本
 * 适配 Dawn Course 架构 (QuickJS, 无 DOM)
 */

function scheduleHtmlParser(html) {
    var courses = [];
    
    // 移除换行符，简化正则
    var cleanHtml = html.replace(/[\r\n]/g, "");

    // 1. 提取所有行 tr
    var trRegex = /<tr[^>]*>(.*?)<\/tr>/gi;
    var trMatch;
    
    // 2. 遍历行
    while ((trMatch = trRegex.exec(cleanHtml)) !== null) {
        var trContent = trMatch[1];
        
        // 3. 在行中提取所有 class="td" 的单元格 (课程单元格)
        // 注意：青果系统的标签单元格通常是 class="td1" 或 "td0"，课程单元格是 "td"
        var tdRegex = /<td[^>]*class=["']?td["']?[^>]*>(.*?)<\/td>/gi;
        var tdMatch;
        var dayIndex = 0; // 0 = Monday
        
        while ((tdMatch = tdRegex.exec(trContent)) !== null) {
            var cellContent = tdMatch[1];
            var day = dayIndex + 1; // 1 = Monday
            
            // 4. 解析单元格内的课程块
            // 格式示例: <div ...><font ...>课程名</font><br>教师 <br>周次[节次]<br></div>
            // 也有可能没有 div，直接是内容 (旧版)
            // 先尝试分割 div (如果有)
            
            var contentBlocks = [];
            if (cellContent.indexOf("<div") !== -1) {
                // 有 div，提取 div 内容
                var divRegex = /<div[^>]*>(.*?)<\/div>/gi;
                var divMatch;
                while ((divMatch = divRegex.exec(cellContent)) !== null) {
                    contentBlocks.push(divMatch[1]);
                }
            } else {
                // 无 div，整个 cell 就是一个块 (需处理 <br> 分隔的多个课，暂且假设无 div 时只有一个或用 <br><br> 分隔)
                // 观察提供的源码，都是有 div 的。
                // 如果是空课表 <div class="div_nokb" ...></div>，内容为空或只包含空格
                if (cellContent.indexOf("div_nokb") === -1) {
                     contentBlocks.push(cellContent);
                }
            }
            
            for (var i = 0; i < contentBlocks.length; i++) {
                var block = contentBlocks[i];
                // 排除空块
                if (!block || block.indexOf("div_nokb") !== -1 || block.trim().length === 0) continue;
                
                // 解析详情
                // 示例: <font style="font-weight: bolder">通信工程制图</font><br>张馨文 <br>1-6,9-20[1-2]<br>
                
                // 提取课程名: <font ...>(.*?)</font>
                var nameMatch = /<font[^>]*>(.*?)<\/font>/i.exec(block);
                var name = nameMatch ? nameMatch[1] : "";
                
                if (!name) continue;
                
                // 移除课程名部分，处理剩余部分
                var remaining = block.replace(/<font[^>]*>.*?<\/font>/i, "");
                
                // 剩余部分通常是: <br>教师 <br>周次[节次]<br>
                // 分割 <br>
                var parts = remaining.split(/<br>/i);
                var teacher = "";
                var weeksStr = "";
                var sectionsStr = "";
                var location = "";
                
                // 简单的启发式提取
                for (var j = 0; j < parts.length; j++) {
                    var p = parts[j].trim();
                    if (!p) continue;
                    
                    // 尝试匹配 周次[节次] 格式: 1-6,9-20[1-2]
                    var timeMatch = /([0-9,\-]+)\[([0-9,\-]+)\]/.exec(p);
                    if (timeMatch) {
                        weeksStr = timeMatch[1];
                        sectionsStr = timeMatch[2];
                        continue;
                    }
                    
                    // 如果不是时间，且不是空，假设是教师或地点
                    // 通常教师在时间之前
                    if (!weeksStr && !teacher) {
                        teacher = p;
                    } else if (weeksStr && !location) {
                        // 时间之后的通常是地点 (如果有)
                        location = p;
                    } else if (!teacher) {
                        // 还没找到时间，且 teacher 为空，赋值给 teacher
                         teacher = p;
                    }
                }
                
                // 构建课程对象
                if (name && weeksStr && sectionsStr) {
                     courses.push({
                        name: name,
                        teacher: teacher,
                        position: location,
                        day: day,
                        weeks: parseWeeks(weeksStr),
                        sections: parseSections(sectionsStr)
                    });
                }
            }
            
            dayIndex++;
        }
    }
    
    return courses;
}

// 辅助函数：解析周次字符串 "1-6,9-20" -> [1,2,3,4,5,6,9,10...]
function parseWeeks(str) {
    var weeks = [];
    var parts = str.split(",");
    for (var i = 0; i < parts.length; i++) {
        var part = parts[i];
        if (part.indexOf("-") !== -1) {
            var range = part.split("-");
            var start = parseInt(range[0]);
            var end = parseInt(range[1]);
            for (var j = start; j <= end; j++) {
                weeks.push(j);
            }
        } else {
            weeks.push(parseInt(part));
        }
    }
    return weeks;
}

// 辅助函数：解析节次字符串 "1-2" -> [1,2]
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
