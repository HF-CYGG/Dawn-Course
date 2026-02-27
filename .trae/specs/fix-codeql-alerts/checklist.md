* [x] output\_console.js 不再使用不安全的 HTML 注入方式渲染不可信文本

* [x] zhengfang.js / kingosoft.js 不再依赖不完整的多字符清洗来“防注入”

* [x] 关键安全处理函数均有详细中文注释（解释输入不可信与策略边界）

* [x] `:app:assembleDebug` 或等价构建任务在本地/CI 通过

* [ ] CodeQL 对应 High 告警已消除（或证明为误报并给出可接受理由与最小化风险措施）

* [ ] 导入功能与输出面板的核心功能回归通过（手动验证即可）
