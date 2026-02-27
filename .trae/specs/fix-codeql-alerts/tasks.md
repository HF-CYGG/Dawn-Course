# Tasks
- [x] Task 1: 定位并复现 CodeQL 告警来源
  - [x] 收集告警列表与对应文件/行号（output_console.js、zhengfang.js、kingosoft.js）
  - [x] 识别不安全 DOM API 使用点：`innerHTML/outerHTML/insertAdjacentHTML/document.write` 等
  - [x] 识别不完整清洗模式：多次 `replace()`、正则漏项、仅替换部分字符等

- [x] Task 2: 修复 output_console.js 的 DOM 注入风险
  - [x] 将不可信字符串输出改为 `textContent` 或 TextNode（避免 HTML 解释）
  - [x] 若需要保留样式：拆分为“可信模板 + 文本节点”组合，禁止将用户/网页输入拼接进 HTML
  - [x] 为关键函数补齐中文注释：说明为什么不能用 innerHTML、以及安全输出策略

- [x] Task 3: 修复导入 parsers 的不完整清洗问题
  - [x] 解析结果统一做“纯文本清洗”，避免把 HTML 标签/实体透传到上层
  - [x] 输出面板统一用 `textContent` 渲染不可信字符串，解析结果即使包含尖括号也不会被当作 HTML 执行
  - [x] 为关键函数补齐中文注释：说明输入来源不可信、清洗/输出边界

- [ ] Task 4: 扩面扫描与回归验证
  - [x] 全仓搜索 HTML 注入相关 API，并评估是否需要同策略修复
  - [x] 本地构建通过：`assembleDebug`（至少 app 模块）
  - [ ] 重新触发 CodeQL 扫描（PR 或 push），确保相关 High 告警消失或降级为可接受水平

# Task Dependencies
- Task 2、Task 3 依赖 Task 1 的精确定位
- Task 4 依赖 Task 2、Task 3 的实现完成
