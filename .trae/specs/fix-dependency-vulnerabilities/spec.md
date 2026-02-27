# Dependency Vulnerability Fix Spec

## Why
CodeQL 安全扫描报告了大量高风险（High）依赖漏洞，主要涉及构建工具链（Gradle 插件）与核心网络库的传递依赖（Gson, Protobuf, Netty, Bouncy Castle 等）。这些漏洞可能导致拒绝服务（DoS）、反序列化攻击或信息泄露。为了提升项目安全性并消除 CI 告警，需要升级相关依赖至安全版本。

## What Changes
- **构建工具升级**：
  - Android Gradle Plugin (AGP): 8.2.2 -> 8.3.1 (或更高稳定版)
  - Kotlin: 1.9.22 -> 1.9.23 (保持 1.x 兼容性)
  - KSP: 1.9.22-1.0.17 -> 1.9.23-1.0.19
- **核心库升级**：
  - Retrofit: 2.9.0 -> 2.11.0 (自带新版 OkHttp/Gson)
  - OkHttp: 4.12.0 -> 4.12.0 (或跟随 Retrofit 传递版本)
  - Hilt: 2.50 -> 2.51 (修复 Guava 相关漏洞)
  - Room: 2.6.1 (保持不变或微升)
- **强制依赖版本（Resolution Strategy）**：
  - 如果升级顶层库后仍残留旧版传递依赖（如 Gson 2.8.x, Protobuf 3.x），将在 `build.gradle.kts` 中强制指定安全版本：
    - Gson -> 2.10.1+
    - Guava -> 33.0.0-android+
    - Protobuf -> 3.25.3+ (或 4.x)
    - Commons Compress -> 1.26.0+

## Impact
- Affected specs: 构建配置、网络请求、依赖注入
- Affected code:
  - `gradle/libs.versions.toml`
  - `build.gradle.kts` (root & app)
  - 可能涉及 ProGuard 规则调整（若库升级引入新反射）

## ADDED Requirements
### Requirement: 依赖安全基线
系统 SHALL 使用无已知高危漏洞的第三方库版本。构建脚本 SHALL 包含依赖冲突解决策略，确保传递依赖被强制升级到安全版本。

#### Scenario: 构建检查 (Success)
- **WHEN** 执行 `./gradlew app:dependencies`
- **THEN** 输出的依赖树中，Gson 版本 >= 2.10.1，Guava 版本 >= 33.0.0，Protobuf 无 3.x 低版本

## MODIFIED Requirements
### Requirement: 网络库版本
Retrofit 与 OkHttp 组件版本 SHALL 同步升级，以利用最新的协议栈安全补丁。

## REMOVED Requirements
无。
