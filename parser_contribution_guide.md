# 教务系统解析脚本开发指南

Dawn Course 使用 QuickJS 引擎在本地运行 JavaScript 脚本来解析教务系统网页。如果你希望为你的学校适配课程表导入功能，可以按照本指南编写解析脚本。

## 1. 运行环境说明

- **引擎**: QuickJS (支持 ES2020 大部分特性)
- **环境**: 纯 JS 环境，**没有 DOM API** (`document`, `window`, `alert` 等不可用)。
- **输入**: `html` (字符串) - 对应教务系统课表页面的 HTML 源码。
- **输出**: JSON 字符串 - 包含课程列表的数组。
- **预加载库**: 系统会自动预加载 `common_parser_utils.js`，你可以直接使用其中的工具函数。

## 2. 脚本模板

请将以下代码复制为新的 `.js` 文件（例如 `my_university.js`），并根据你的教务系统 HTML 结构修改 `parseMyUniversity` 函数。

```javascript
/**
 * [学校名称/系统名称] 教务系统解析脚本
 * 
 * 依赖: common_parser_utils.js (自动加载)
 * 提供函数: stripTags, decodeHtmlEntities, parseWeeks, parseSections, dedupeCourses 等
 */

function scheduleHtmlParser(html) {
    // 1. 清洗 HTML (可选，视情况而定)
    // var cleanHtml = html.replace(/[\r\n]/g, "");

    // 2. 提取课程数据
    var courses = parseMyUniversity(html);

    // 3. 去重 (使用通用工具函数)
    courses = dedupeCourses(courses);

    // 4. 返回 JSON 字符串
    return JSON.stringify(courses);
}

/**
 * 解析主逻辑
 */
function parseMyUniversity(html) {
    var courses = [];
    
    // 示例：使用正则匹配提取数据 (因为没有 DOM API)
    // 建议先在电脑浏览器控制台测试正则，或者使用 strings 处理
    
    // 假设 HTML 结构是表格...
    // var rows = html.match(/<tr[^>]*>([\s\S]*?)<\/tr>/gi);
    
    // ... 具体解析逻辑 ...
    // 模拟添加一个课程：
    /*
    courses.push({
        name: "高等数学",
        teacher: "张三",
        position: "教学楼A101",
        day: 1,              // 星期几 (1-7)
        weeks: [1, 2, 3, 4], // 周次数组
        sections: [1, 2]     // 节次数组
    });
    */

    return courses;
}
```

## 3. 返回数据结构

解析脚本最终返回的 JSON 应当是一个对象数组，每个对象代表一门课程（的一个时间段）。

| 字段 | 类型 | 说明 | 示例 |
| :--- | :--- | :--- | :--- |
| `name` | String | 课程名称 | `"高等数学"` |
| `teacher` | String | 教师姓名 | `"张三"` |
| `position` | String | 上课地点 | `"教学楼A101"` |
| `day` | Number | 星期几 (1=周一, 7=周日) | `1` |
| `weeks` | Array&lt;Number&gt; | 上课周次列表 | `[1, 2, 3, 5, 7]` |
| `sections` | Array&lt;Number&gt; | 上课节次列表 | `[1, 2]` |

> **只上一周的课程**（如"形势与政策"）也必须正常输出，`weeks` 就是单元素数组，例如 `[9]`。
> Kotlin 侧会把它拆成 `startWeek == endWeek` 的区间，无需特殊处理。
>
> **`weeks` 或 `sections` 为空数组的课程会被静默丢弃。** 如果你在某个分支里因为
> 解析不出周次/节次而不 push 这门课，请调用 `reportDropped("no_weeks")`（见下），
> 让导入页能提示用户"有 N 条记录被跳过"，而不是让课程凭空消失、无从排查。

## 4. 可用工具函数 (common_parser_utils.js)

无需引入，直接调用即可：

*   `stripTags(html)`: 移除 HTML 标签并解码实体，返回纯文本。
*   `decodeHtmlEntities(text)`: 解码 HTML 实体 (支持 `&nbsp;`, `&#x...;` 等)。
*   `parseWeeks(str)`: 解析周次字符串。已加固，可处理：区间 `"1-16周"`、单双周 `"1-16周(单)"`、
    逗号列表 `"1,3,5周"`、**单周 `"9周"` / `"9"` / `"第9周"`**、**节次粘连 `"9周(1-2节)"`**、写反的区间 `"16-9周"`。
*   `extractWeeksStr(str)`: 从一段文本里提取"周次子串"再交给 `parseWeeks`。已加固，支持 `"周次:5"`（无"周"字）、独立单周 `"9周"` 等。
*   `parseSections(str)`: 解析节次字符串 (如 `"1-2节"` -> `[1, 2]`)。
*   `dedupeCourses(courses)`: 对课程数组进行去重。
*   `extractTextByTitle(html, title)`: 根据 `title` 属性提取文本。
*   `reportDropped(reason)`: 记录一条被跳过的候选课程行，`reason` 只能是固定短码
    `"no_weeks"` / `"no_sections"` / `"no_day"`（**不要传入课程名等页面内容**）。

### JS 解析器测试

`feature/import/src/test/js/parse_weeks.test.cjs` 是纯 Node、零依赖的周次解析回归测试。
本地运行 `node feature/import/src/test/js/parse_weeks.test.cjs`；CI 通过 Gradle 任务
`:feature:import:jsParserTest`（挂在 `testDebugUnitTest` / `check` 上）自动执行。新增或修改
`parseWeeks` / `extractWeeksStr` 时请补充对应用例。

## 5. 开发建议

1.  **获取 HTML**: 在 App 内进入导入页面，登录教务系统，到达课表页。如果解析失败，可以尝试打印 `html` 内容（需在 Android Logcat 查看，或自行搭建测试环境）。
2.  **正则调试**: 推荐使用 regex101.com 测试正则表达式。
3.  **本地测试**: 将 HTML 保存为文本文件，使用 Node.js + 模拟环境运行脚本进行调试。
