# Release v1.0.3.13

## Version Info
- **Version Code**: 105
- **Version Name**: v1.0.3.13
- **Release Date**: 2026-03-02
- **Type**: Update

## Core Updates
1.  **强智教务系统支持**：新增对强智教务系统的全面支持，包括 API 直连导入与 HTML 解析脚本，并为 Kingosoft 解析器添加了兼容逻辑。
2.  **ICS 导入增强**：改进了 ICS 课程导入的解析逻辑，修正了重复事件生成以符合 RFC 5545 规范，大幅提升了导入的容错性。
3.  **UI 视觉优化**：优化了非本周课程卡片的视觉效果，现在使用主题色背景进行区分；更新了应用图标。
4.  **开发者文档**：新增了解析脚本开发指南，并更新了忽略文件配置，方便社区开发者贡献代码。

## Technical Changes
- **Refactor (Import)**：重构了作息时间生成逻辑，提取了通用解析工具函数到共享库 (`common_parser_utils.js`)，提高了代码复用性。
- **Refactor (Import)**：移除了未使用的 `maxCount` 变量。
- **Chore**: 移除了 WakeUp 导入功能及相关代码。
- **Build**: 添加了 `Material Icons Extended` 依赖以支持更多图标。
- **CI/CD**: 优化了 CI 流程，仅在 Pull Request 或非文档变更时触发构建。

## Risk Assessment
- **导入功能兼容性**：由于重构了导入逻辑和 ICS 解析，建议重点测试各类课程导入场景，确保没有引入回归问题。
- **WakeUp 功能移除**：移除 WakeUp 导入功能是破坏性变更，确认该功能已不再需要或已失效。
