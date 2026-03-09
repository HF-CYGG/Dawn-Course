# Dawn Course (破晓课程表)

> **永久免费、开源、无广告、本地优先的 Android 课程表应用**

Dawn Course 是一款基于现代 Android 技术栈（Kotlin + Compose）构建的课程表应用。它旨在提供纯粹、高效、美观的课程管理体验，恪守“用户数据主权”原则，拒绝臃肿和各种非必要权限。

## 核心原则

本项目严格遵循以下原则：

1.  **永久免费 & 开源**：遵循 GPL-3.0 协议，代码完全透明。
2.  **零干扰**：无广告、无会员、无推送（除必要的课程提醒外）。
3.  **本地优先**：完全离线可用，不强制绑定云端账号，数据完全由用户掌控。
4.  **可迁移**：支持多种导入方式，方便数据迁移。（导出开发中）

## 主要功能

*   **课程管理**：
    *   直观的周视图/日视图课表，支持滑动切换。
    *   支持自定义课程颜色、上课地点、教师信息。
    *   支持多学期管理与切换，历史学期数据（开发中）回溯。
    *   **调课功能**：支持单周或多周课程的临时调整（时间/地点），并提供冲突检测。
*   **智能导入**：
    *   **教务系统适配**：深度适配 **新正方教务系统**（支持网页模拟登录与半自动更新），**强智**（推荐使用ICS导入）、**青果** 等主流教务系统（通过内置 JS 脚本解析）。
    *   **WakeUp 口令导入**：支持解析 WakeUp 课程表生成的分享口令，一键迁移数据。（维护中，暂时停用）
    *   **ICS 文件导入**：支持导入标准日历格式（.ics）文件。
    *   **覆盖导入机制**：导入时自动清理旧数据，确保课表纯净，杜绝数据混淆。
*   **个性化定制**：
    *   **动态壁纸**：支持设置自定义背景图，并提供实时高斯模糊和亮度调节。
    *   **Material You**：遵循 Material Design 3 设计规范，支持动态取色（Monet）。
    *   **桌面小组件**：基于 Jetpack Glance 构建的现代化桌面小组件，支持日视图/周视图，自动适配系统主题。
*   **贴心提醒**：
    *   上课前自动提醒。
    *   自动静音/免打扰模式。
*   **应用维护**：
    *   **应用内更新**：支持检查新版本，提供标准/功能/修复等多级更新提示。

## 技术栈

本项目采用最前沿的 Android 开发架构：

*   **语言**: [Kotlin](https://kotlinlang.org/) (1.9.23)
*   **UI 框架**: [Jetpack Compose](https://developer.android.com/jetpack/compose) (Material 3, BOM 2024.02.00)
*   **架构模式**: MVVM + Clean Architecture (App, Domain, Data, UI 分层)
*   **依赖注入**: [Hilt](https://dagger.dev/hilt/) (2.51)
*   **异步处理**: Coroutines + Flow
*   **本地数据库**: [Room](https://developer.android.com/training/data-storage/room) (2.6.1, 支持 SQLCipher 加密)
*   **网络请求**: [Retrofit](https://square.github.io/retrofit/) + [OkHttp](https://square.github.io/okhttp/)
*   **图片加载**: [Coil](https://coil-kt.github.io/coil/)
*   **导航**: Navigation Compose
*   **小组件**: Jetpack Glance (1.1.1)
*   **脚本引擎**: QuickJS (用于解析教务系统导入脚本)
*   **后台任务**: WorkManager

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
│   ├── import/         # 课程导入功能 (包含 JS/API/ICS 解析逻辑)
│   ├── settings/       # 设置页面
│   ├── widget/         # 桌面小组件
│   └── update/         # 应用更新检测与提示
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
    git clone https://github.com/HF-CYGG/DawnCourse.git
    ```
2.  在 Android Studio 中打开项目根目录。
3.  等待 Gradle 同步完成。
4.  连接 Android 设备或启动模拟器。
5.  (推荐) 安装 Git 钩子以确保提交前自动检查代码质量：
    ```bash
    ./gradlew installGitHooks
    ```
6.  运行 `app` 模块。

## 贡献指南

欢迎提交 Issue 和 Pull Request！
（代码不要太烂就行，需要包含必要的中文注释和测试）

1.  **自动化检查**：
    *   **CI (GitHub Actions)**：每次提交和 PR 都会在云端自动执行构建、测试和 Lint 检查。只有通过检查的代码才能被合并。
2.  **代码规范**：
    *   请确保代码通过所有单元测试。
    *   保持代码风格整洁，无 Lint 错误。
    *   关键逻辑请添加 **中文注释**。
    *   提交信息请清晰描述变更内容。

3.  **教务系统适配**：
    *   如果你希望为你的学校适配课程表导入功能，请参考 [教务系统解析脚本开发指南](parser_contribution_guide.md)。

## 开源协议

本项目采用 [GNU General Public License v3.0 (GPL-3.0)](LICENSE) 开源协议。
