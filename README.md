# Dawn Course（破晓课程表）

> 免费、开源（GPL-3.0）、无广告、本地优先的 Android 课程表应用  
> 专注离线可用、数据可迁移与长期可维护的现代 Android 工程实践（Kotlin + Compose + Clean Architecture）

## 项目定位

破晓课程表（Dawn Course）是一款面向学生日常使用的课程表应用，提供从教务系统导入、课程管理、提醒与个性化显示等核心能力。项目坚持“用户数据主权”与“本地优先”原则：在不强制云账号的前提下，尽可能做到离线可用、可备份、可迁移。

## 核心原则

本项目严格遵循以下原则：

1. **永久免费 & 开源**：遵循 GPL-3.0 协议。
2. **零干扰**：无广告、无会员强绑定、无非必要推送。
3. **本地优先**：核心功能离线可用，不强制依赖云端服务。
4. **可迁移**：提供多种导入方式，并支持备份与还原能力。
5. **可维护**：以可测试性、可替换性、长期维护为第一优先级。

## 主要功能

### 课程与展示

- 周视图 / 日视图课表展示，支持滑动切换
- 课程信息管理：名称、地点、教师、颜色等
- 多学期管理与切换
- 动态壁纸背景：支持高斯模糊与亮度调节
- Material You（Material 3）动态取色
- 桌面小组件（Jetpack Glance）：日/周视图组件，适配系统主题

### 智能导入与更新

- 教务系统导入：适配新正方、强智、青果等主流系统（WebView + JS 解析）
- ICS 文件导入：支持标准日历格式（.ics）
- 覆盖导入机制：导入时清理旧数据，避免混淆
- 脚本云同步：导入/自动更新脚本支持从云端拉取，并具备本地缓存与内置 Assets 兜底

### 同步与备份（本地优先）

- 本地备份与还原：支持导出/导入备份文件，并在还原前展示备份预览信息
- WebDAV 同步（可选）：用于跨设备备份文件上传/下载，支持自动同步策略

### 贴心提醒与维护

- 上课提醒
- 自动静音/免打扰策略（按设置联动）
- 应用内更新检查与提示

## 快速开始（开发者）

### 环境要求

- **JDK**：17+
- **Android Studio**：Hedgehog / Iguana 或更新版本
- **Android SDK**：API 34（Compile SDK）

### 构建步骤

1. 克隆仓库：

   ```bash
   git clone https://github.com/HF-CYGG/DawnCourse.git
   ```

2. 用 Android Studio 打开项目根目录，等待 Gradle Sync 完成
3. 连接真机或启动模拟器
4. 运行 `app` 模块

可选（推荐）：安装 Git 钩子以确保提交前自动执行质量检查：

```bash
./gradlew installGitHooks
```

## 技术栈与版本

以下版本信息以仓库默认配置为准；完整依赖以 `gradle/libs.versions.toml` 为最终来源。

- **语言**：Kotlin 1.9.23
- **UI**：Jetpack Compose（Material 3，BOM 2024.02.00）
- **架构**：MVVM + Clean Architecture（App / Domain / Data / UI 分层）
- **依赖注入**：Hilt 2.51
- **异步**：Coroutines + Flow
- **数据库**：Room 2.6.1（可选 SQLCipher 加密）
- **网络**：Retrofit + OkHttp
- **图片加载**：Coil
- **导航**：Navigation Compose
- **小组件**：Jetpack Glance 1.1.1
- **脚本引擎**：QuickJS
- **后台任务**：WorkManager

## 项目结构

项目采用多模块结构，强调边界清晰与依赖方向可控：

```text
DawnCourse/
├── app/                # 壳工程：Application 初始化 / DI / 导航
├── core/               # 核心层
│   ├── data/           # 数据层：Repository 实现 / DB / DataStore / Sync
│   ├── domain/         # 领域层：UseCase / Model（纯 Kotlin，可测试）
│   └── ui/             # 通用 UI：主题 / 组件
├── feature/            # 功能层：导入/课表/设置/小组件/更新等（相互解耦）
└── server/             # 静态资源服务（用于提供云端脚本下载等）
```

### 分层依赖（强制）

- UI → ViewModel → UseCase → Repository → DataSource
- feature 模块之间不直接依赖
- UI 不直接访问 DAO
- ViewModel 不持有 Android Context

## 脚本体系（导入相关）

导入与自动更新依赖一组可独立演进的 JavaScript 脚本，用于 WebView 交互与 HTML 提取/解析：

- 内置脚本（兜底）：`app/src/main/assets/js/`
- 云端脚本（可更新）：`server/html/scripts/js/`
- 获取策略：云端 → 本地缓存 → Assets 兜底（保证离线可用与可控升级）

如需贡献新的教务系统脚本，请参考：

- [教务系统解析脚本开发指南](parser_contribution_guide.md)

## LLM 兜底解析服务（可选）

当动态脚本解析失败时，可部署 LLM 兜底解析服务提升导入成功率。该服务由 nginx 反代，端口默认 10000。

### 运行方式

```bash
cd server
docker-compose up -d --build
```

### 配置方式（环境变量）

在 `server/docker-compose.yml` 中配置以下环境变量即可切换模型与策略（解析默认复用模型 1）：

- 模型 1（低成本总结 + 解析）
  - LLM_SUMMARY_PROVIDER / LLM_SUMMARY_API_KEY / LLM_SUMMARY_MODEL / LLM_SUMMARY_BASE_URL
- 模型 2（高成本脚本修复）
  - LLM_SCRIPT_PROVIDER / LLM_SCRIPT_API_KEY / LLM_SCRIPT_MODEL / LLM_SCRIPT_BASE_URL
- 队列策略
  - MIN_QUEUE_SIZE：触发脚本修复的最小提交数
  - MERGE_WINDOW_MS：合并窗口（毫秒）
  - REPROCESS_WINDOW_MS：脚本更新后 24 小时内的二次提交处理窗口
- Redis（内置）
  - REDIS_URL：默认 redis://redis:6379
- 限流与签名
  - RATE_LIMIT_PER_MIN / RATE_LIMIT_SCHOOL_PER_MIN
  - SCRIPT_SIGN_KEY：脚本签名密钥（可选，HMAC）
  - SCRIPT_SIGN_PRIVATE_KEY：脚本签名私钥（可选，RSA）
- 指标与统计
  - SCHOOL_METRICS_FILE：学校维度统计输出 TXT 文件路径
  - METRICS_FLUSH_MS：学校统计写盘间隔（毫秒）
- 持久化与升级兼容
  - SCRIPT_OUTPUT_DIR：脚本与元数据输出目录（建议挂载为持久化卷）
  - LEGACY_SCRIPT_OUTPUT_DIRS：老版本脚本目录列表（逗号分隔），用于升级时回退读取与自动迁移

### 容器持久化与升级兼容

- 建议将 `SCRIPT_OUTPUT_DIR` 挂载为持久化卷，保证容器重启或升级后脚本与 meta 不丢失
- 若旧版本脚本目录不在默认路径，请设置 `LEGACY_SCRIPT_OUTPUT_DIRS`，升级后首次访问会自动迁移到新目录
- 脚本 meta 缺失时服务端会基于脚本内容自动补写，避免客户端验签因升级丢失数据而失败

### Prometheus 指标

- 接口：`/metrics`
- 示例字段：
  - `dawncourse_parse_success_total`
  - `dawncourse_school_parse_success_total{schoolId="xxx"}`

### 监控面板

- 地址：`/admin/`
- 初始账号与密码：服务端启动时随机生成并打印到日志
- 登录后可查看学校维度统计、解析成功率、失败记录与费用信息

### 脚本签名与客户端验签

- 服务端会在脚本写入时生成 `*.meta.json`，包含 `sha256`、`signature`、`alg` 与版本号。
- 可通过 `/api/v1/script_meta?scriptName=xxx.js` 查询脚本签名元信息。
- 客户端在拉取脚本时会同时拉取 `*.meta.json`，校验 `sha256` 与签名。
- RSA 验签公钥需配置在 `core/data/build.gradle.kts`：
  - `buildConfigField("String", "SCRIPT_VERIFY_PUBLIC_KEY", "\"你的 PEM 公钥\"")`

### 配置示例

**示例 1：DeepSeek（解析 + 总结） + GPT（脚本修复）**

```yaml
LLM_SUMMARY_PROVIDER: deepseek
LLM_SUMMARY_API_KEY: "your-deepseek-key"
LLM_SUMMARY_MODEL: deepseek-chat
LLM_SCRIPT_PROVIDER: gpt
LLM_SCRIPT_API_KEY: "your-openai-key"
LLM_SCRIPT_MODEL: gpt-4o
```

**示例 2：通义千问（解析 + 总结） + GLM（脚本修复）**

```yaml
LLM_SUMMARY_PROVIDER: qwen
LLM_SUMMARY_API_KEY: "your-qwen-key"
LLM_SUMMARY_MODEL: qwen-plus
LLM_SCRIPT_PROVIDER: glm
LLM_SCRIPT_API_KEY: "your-glm-key"
LLM_SCRIPT_MODEL: glm-4
```

**示例 3：Gemini（解析 + 总结 + 脚本修复）**

```yaml
LLM_SUMMARY_PROVIDER: gemini
LLM_SUMMARY_API_KEY: "your-gemini-key"
LLM_SUMMARY_MODEL: gemini-1.5-flash
LLM_SCRIPT_PROVIDER: gemini
LLM_SCRIPT_API_KEY: "your-gemini-key"
LLM_SCRIPT_MODEL: gemini-1.5-pro
```

### 官方文档参考

- DeepSeek API 文档：https://platform.deepseek.com/api-docs
- 通义千问（DashScope）文档：https://help.aliyun.com/document_detail/2400391.html
- 智谱 GLM 文档：https://open.bigmodel.cn/dev/api
- Gemini 文档：https://ai.google.dev/gemini-api/docs
- OpenAI 文档：https://platform.openai.com/docs

## 常见问题（FAQ）

- **为什么强调本地优先？**  
  因为课程数据属于用户个人数据资产，应当离线可用、可迁移、可备份，并尽量避免强绑定云账号。
- **WebDAV 是必须的吗？**  
  不是。WebDAV 同步是可选能力，用于跨设备备份文件的上传/下载，不影响核心功能离线使用。

## 贡献指南

欢迎提交 Issue 与 Pull Request。

- 反馈 Bug：请提交 Issue，并尽量附带复现步骤、设备信息与日志
- 功能建议：请提交 Issue 说明使用场景与期望行为
- 代码规范：
  - 保持代码整洁，通过必要的构建与静态检查
  - 关键逻辑请添加中文注释
  - 遵守模块边界与依赖方向

## 开源协议

本项目采用 [GNU General Public License v3.0 (GPL-3.0)](LICENSE) 开源协议。
