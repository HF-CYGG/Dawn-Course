# Dawn Course (破晓课程表)

> **永久免费、开源、无广告、本地优先的 Android 课程表应用**

Dawn Course 是一款基于现代 Android 技术栈（Kotlin + Compose）构建的课程表应用。它旨在提供纯粹、高效、美观的课程管理体验，恪守“用户数据主权”原则，拒绝臃肿和各种非必要权限。

## 核心原则

本项目严格遵循以下原则（详见 `.trae/rules/00-project-overview.md`）：

1.  **永久免费 & 开源**：遵循 GPL-3.0 协议，代码完全透明。
2.  **零干扰**：无广告、无会员、无推送（除必要的课程提醒外）。
3.  **本地优先**：完全离线可用，不强制绑定云端账号，数据完全由用户掌控。
4.  **可迁移**：支持数据导入导出，方便设备间迁移。

## 主要功能

*   **课程管理**：
    *   直观的周视图/日视图课表。
    *   支持自定义课程颜色、上课地点、教师信息。
    *   支持多学期管理与切换。
    *   **调课功能**：支持单周或多周课程的临时调整（时间/地点），并提供冲突检测。
*   **个性化定制**：
    *   **动态壁纸**：支持设置自定义背景图，并提供实时高斯模糊和亮度调节。
    *   **Material You**：遵循 Material Design 3 设计规范，支持动态取色。
    *   **桌面小组件**：基于 Glance 构建的现代化桌面小组件，随时查看课程。
*   **智能导入**：
    *   内置 QuickJS 引擎，支持通过 JavaScript 脚本解析教务系统课表（如正方教务系统）。
    *   支持手动创建与编辑课程。
*   **贴心提醒**：
    *   上课前自动提醒。
    *   自动静音/免打扰模式（开发中）。

## 技术栈

本项目采用现代化的 Android 开发架构：

*   **语言**: [Kotlin](https://kotlinlang.org/) (1.9.22)
*   **UI 框架**: [Jetpack Compose](https://developer.android.com/jetpack/compose) (Material 3)
*   **架构模式**: MVVM + Clean Architecture (App, Domain, Data, UI 分层)
*   **依赖注入**: [Hilt](https://dagger.dev/hilt/)
*   **异步处理**: Coroutines + Flow
*   **本地数据库**: [Room](https://developer.android.com/training/data-storage/room) (支持 SQLCipher 加密)
*   **导航**: Navigation Compose
*   **小组件**: Jetpack Glance
*   **脚本引擎**: QuickJS (用于解析导入脚本)
*   **图片加载**: Coil

## 项目结构

项目采用多模块架构，确保关注点分离与可维护性：

```text
DawnCourse/
├── app/                # 壳工程，负责 Application 初始化与导航图构建
├── core/               # 核心基础层
│   ├── data/           # 数据层 (Repository, Database, DataStore)
│   ├── domain/         # 领域层 (UseCase, Model - 纯 Kotlin 模块)
│   └── ui/             # 通用 UI 组件与主题 (Theme, Components)
├── feature/            # 业务功能层
│   ├── timetable/      # 课程表主界面与课程编辑
│   ├── import/         # 课程导入功能 (包含 JS 解析逻辑)
│   ├── settings/       # 设置页面
│   └── widget/         # 桌面小组件
└── gradle/             # 构建配置
```

## 快速开始

### 环境要求
*   **JDK**: 17+
*   **Android Studio**: Hedgehog | Iguana 或更新版本
*   **Android SDK**: API 34 (Compile SDK)

### 构建步骤
1.  克隆仓库：
    ```bash
    git clone https://github.com/your-username/DawnCourse.git
    ```
2.  在 Android Studio 中打开项目根目录。
3.  等待 Gradle 同步完成。
4.  连接 Android 设备或启动模拟器。
5.  运行 `app` 模块。

## 贡献指南

欢迎提交 Issue 和 Pull Request！在贡献代码前，请注意以下规范：

1.  **代码注释**：所有类、方法、复杂逻辑必须包含**中文注释**。
2.  **架构规范**：
    *   严格遵守模块依赖方向：UI -> ViewModel -> UseCase -> Repository。
    *   Feature 模块之间禁止直接依赖。
3.  **代码风格**：保持 Kotlin 官方编码风格。

## 开源协议

本项目采用 [GNU General Public License v3.0 (GPL-3.0)](LICENSE) 开源协议。
