# CodeQL 安全告警修复 Spec

## Why
当前仓库在 GitHub Code Scanning（CodeQL）中出现多条高风险告警，主要集中在前端/脚本侧的 DOM 注入与不完整的输入清洗逻辑。这些问题在极端输入或被恶意构造输入时可能造成 XSS（脚本注入）或数据污染，影响用户安全与项目可信度。

## What Changes
- 修复 `app/src/main/assets/js/output_console.js` 中 “DOM text reinterpreted as HTML” 类告警：禁止把不可信文本以 HTML 方式插入 DOM。
- 修复 `feature/import/src/main/assets/parsers/*.js` 中 “Incomplete multi-character sanitization” 类告警：用可靠的文本插入/转义策略替代不完整的字符替换。
- 补齐脚本侧的统一“安全输出”工具方法，减少未来重复引入同类问题。
- **不引入** 新的第三方在线服务、不增加账号绑定、不引入广告/会员；保持离线可用与本地优先。

## Impact
- Affected specs: 安全（XSS/输入校验）、导入脚本稳定性、日志/调试输出安全
- Affected code:
  - `app/src/main/assets/js/output_console.js`
  - `feature/import/src/main/assets/parsers/zhengfang.js`
  - `feature/import/src/main/assets/parsers/kingosoft.js`
  - （如存在）其他通过 `innerHTML/outerHTML/insertAdjacentHTML` 写入不可信字符串的位置

## ADDED Requirements

### Requirement: 安全 DOM 输出
系统 SHALL 在所有脚本输出到 DOM 的路径上，默认使用“按文本输出”的方式（如 `textContent` 或创建 TextNode），避免将不可信内容作为 HTML 解释。

#### Scenario: 控制台输出（Success）
- **WHEN** 任何不可信字符串（包含 `<script>`, `</div>`, `&`, `"`, `'` 等）被写入输出面板
- **THEN** 页面只显示其文本本身，不会生成新的 DOM 节点，不会执行脚本

#### Scenario: 兼容原本展示（Success）
- **WHEN** 输出包含换行、制表、普通 Unicode 字符
- **THEN** 显示效果与修复前一致（可读性不下降），且不会破坏布局

### Requirement: 导入脚本字符串处理
系统 SHALL 在导入脚本解析过程中，对来自网页/接口的字符串（课程名、教师、地点、周次描述等）进行一致的安全处理：
- 用“文本插入”替代“拼接 HTML”
- 如必须生成 HTML（极少数场景），必须使用严格白名单，并确保不会引入事件处理器/脚本 URL

#### Scenario: 解析结果展示（Success）
- **WHEN** 教务系统返回字段包含 `<`, `>`, `&` 或类似 HTML 片段
- **THEN** 解析结果按纯文本进入业务侧，不引入 HTML 结构，不影响后续导入流程

## MODIFIED Requirements

### Requirement: 现有脚本输出能力
现有 `output_console.js` 的输出能力保持可用，但内部实现 SHALL 改为安全输出实现，且在可控范围内保留颜色/样式能力（如当前确实需要）。

## REMOVED Requirements
无。
