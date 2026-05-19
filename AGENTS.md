# AGENTS.md

本文件供 OpenAI Codex 使用。

本项目原始规则文件位于：

- `.trae/rules/00-memory-summary.md`
- `.trae/rules/00-project-overview.md`
- `.trae/rules/01-architecture.md`
- `.trae/rules/02-android-coding.md`
- `.trae/rules/03-ui-compose.md`

## Codex 工作要求

在修改本项目代码前，必须遵守以下规则：

1. 优先阅读并理解 `.trae/rules/` 目录下的项目规则。
2. 不要只根据局部文件猜测架构，应结合项目总览、架构规则、Android 编码规则和 Compose UI 规则。
3. 修改代码前先说明影响范围。
4. 修改完成后检查是否破坏现有架构分层。
5. 对 Android / Kotlin / Compose 代码，遵守 `.trae/rules/02-android-coding.md` 和 `.trae/rules/03-ui-compose.md`。
6. 对服务端、脚本同步、自动修复相关代码，优先遵守 `.trae/rules/01-architecture.md`。
7. 不要随意重构无关模块。
8. 不要删除已有注释、配置、脚本和规则文件，除非明确要求。
9. 输出中文说明。
10. 对复杂修改，先给方案，再执行代码修改。