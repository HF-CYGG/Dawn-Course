# Tasks
- [x] Task 1: 升级基础构建工具 (AGP, Kotlin)
  - [x] libs.versions.toml: 升级 agp 到 8.3.1 (或 8.4.0)
  - [x] libs.versions.toml: 升级 kotlin 到 1.9.23
  - [x] libs.versions.toml: 升级 ksp 到 1.9.23-1.0.19 (需与 Kotlin 匹配)
  - [x] libs.versions.toml: 升级 room 到 2.6.1 (保持不变或微升)
  - [x] 同步项目并验证 `:app:assembleDebug` 通过

- [x] Task 2: 升级核心库 (Retrofit, Hilt, Coil)
  - [x] libs.versions.toml: 升级 retrofit 到 2.11.0
  - [x] libs.versions.toml: 升级 okhttp 到 4.12.0 (确认版本一致)
  - [x] libs.versions.toml: 升级 hilt 到 2.51 (或 2.51.1)
  - [x] libs.versions.toml: 升级 coil 到 2.6.0 (或 2.7.0)
  - [x] 同步项目并验证 `:app:assembleDebug` 通过

- [x] Task 3: 强制解决传递依赖 (Resolution Strategy)
  - [x] 检查 `./gradlew app:dependencies` 输出中的 Gson, Protobuf, Guava 版本
  - [x] 在 `app/build.gradle.kts` 或 root `build.gradle.kts` 中添加 `configurations.all { resolutionStrategy.force(...) }`
  - [x] 强制版本: `com.google.code.gson:gson:2.10.1`, `com.google.guava:guava:33.0.0-android`, `com.google.protobuf:protobuf-java:3.25.3`, `org.apache.commons:commons-compress:1.26.0` (如适用)
  - [x] 验证依赖树是否已升级

- [x] Task 4: 回归验证与安全扫描
  - [x] 本地构建 `:app:assembleDebug` 通过
  - [x] App 启动并测试关键网络功能（登录/导入/更新检查）
  - [x] (可选) 重新触发 CodeQL 扫描 (Push)

# Task Dependencies
- Task 3 依赖 Task 1 & 2 的基础升级完成
- Task 4 依赖 Task 3 的依赖树确认
