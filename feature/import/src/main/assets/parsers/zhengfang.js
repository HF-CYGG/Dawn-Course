// ZhengFang Parser Example
// 这是一个运行在 QuickJS 中的 JavaScript 脚本

/**
 * 解析入口函数
 * @param html 教务系统导出的 HTML 字符串
 * @returns JSON 字符串，包含解析后的课程列表
 */
function parse(html) {
    var courses = [];
    
    // 模拟解析逻辑：实际开发中需要使用 cheerio 或正则匹配 HTML
    // 这里为了演示，假设 HTML 中包含了特定的课程标记
    
    // 示例：解析一个硬编码的课程
    // 真实场景：遍历 HTML 表格，提取 td 内容
    
    /*
    假设 HTML 结构：
    <td id="TD1_1">高等数学<br>张三<br>1-16周<br>一教101</td>
    */
    
    // 由于 QuickJS 环境没有 DOM API，我们需要依赖正则或纯字符串处理
    // 或者引入一个轻量级的 JS HTML Parser 库
    
    // 简单的正则匹配示例
    // 匹配类似 "高等数学" 的文本
    // var nameMatch = /课程名称：(.*?)<br>/g.exec(html);
    
    // 构造测试数据
    var course1 = {
        name: "高等数学 (Parsed)",
        teacher: "张教授",
        location: "一教-101",
        dayOfWeek: 1, // 周一
        startSection: 1, // 第1节
        duration: 2, // 持续2节
        startWeek: 1,
        endWeek: 16,
        weekType: 0 // 全周
    };
    
    courses.push(course1);
    
    // 返回标准 JSON 结构
    return JSON.stringify({
        courses: courses,
        error: null
    });
}

// 将 parse 函数暴露给全局对象，以便 Kotlin 调用
globalThis.parse = parse;
