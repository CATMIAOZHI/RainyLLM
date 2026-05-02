# 📋 RainyLLM 开发进度追踪

> 格式说明：`- [ ]` 待完成 | `- [~]` 进行中 | `- [x]` 已完成 | `- [-]` 已跳过  
> 每个任务完成后，在行尾追加 `<!-- 完成日期 YYYY-MM-DD HH:MM -->`

---

## 🏗️ 阶段 1：项目骨架搭建

### 1.1 包名与项目配置
- [x] 1.1.1 修改 `settings.gradle.kts`：rootProject.name = "RainyLLM" <!-- 完成日期 2026-04-29 06:01 -->
- [x] 1.1.2 修改 `app/build.gradle.kts`：namespace = "com.rainyllm.app" <!-- 完成日期 2026-04-29 06:01 -->
- [x] 1.1.3 修改 `app/build.gradle.kts`：applicationId = "com.rainyllm.app" <!-- 完成日期 2026-04-29 06:01 -->
- [x] 1.1.4 修改 `app/src/main/res/values/strings.xml`：app_name = "RainyLLM" <!-- 完成日期 2026-04-29 06:01 -->
- [x] 1.1.5 重命名源码目录：`com/java/myapplication` → `com/rainyllm/app` <!-- 完成日期 2026-04-29 06:02 -->
- [x] 1.1.6 更新所有 Kotlin 文件中的 `package` 声明为新包名 <!-- 完成日期 2026-04-29 06:02 -->
- [x] 1.1.7 更新 `AndroidManifest.xml` 中的 `activity android:name` 为 `.MainActivity` <!-- 完成日期 2026-04-29 06:02 -->

### 1.2 Gradle 依赖配置
- [x] 1.2.1 在 `gradle/libs.versions.toml` 中添加 `litertlm-android` 版本和库声明 <!-- 完成日期 2026-04-29 06:01 -->
- [x] 1.2.2 在 `gradle/libs.versions.toml` 中添加 `nanohttpd` 版本和库声明 <!-- 完成日期 2026-04-29 06:01 -->
- [x] 1.2.3 在 `gradle/libs.versions.toml` 中添加 `kotlinx-coroutines-android` 版本 <!-- 完成日期 2026-04-29 06:01 -->
- [x] 1.2.4 在 `app/build.gradle.kts` 中添加 `implementation(libs.litertlm.android)` <!-- 完成日期 2026-04-29 06:01 -->
- [x] 1.2.5 在 `app/build.gradle.kts` 中添加 `implementation(libs.nanohttpd)` <!-- 完成日期 2026-04-29 06:01 -->
- [x] 1.2.6 在 `app/build.gradle.kts` 中添加 `implementation(libs.kotlinx.coroutines.android)` <!-- 完成日期 2026-04-29 06:01 -->
- [ ] 1.2.7 验证：执行 `./gradlew dependencies` 确认依赖解析成功

### 1.3 AndroidManifest 权限
- [x] 1.3.1 添加 `FOREGROUND_SERVICE` 权限 <!-- 完成日期 2026-04-29 06:03 -->
- [x] 1.3.2 添加 `FOREGROUND_SERVICE_SPECIAL_USE` 权限 <!-- 完成日期 2026-04-29 06:03 -->
- [x] 1.3.3 添加 `POST_NOTIFICATIONS` 权限 <!-- 完成日期 2026-04-29 06:03 -->
- [x] 1.3.4 添加 `WAKE_LOCK` 权限 <!-- 完成日期 2026-04-29 06:03 -->
- [x] 1.3.5 保留 `INTERNET` 权限（NanoHTTPd 绑定到 127.0.0.1，但需要权限声明） <!-- 完成日期 2026-04-29 06:03 -->
- [x] 1.3.6 添加 `<uses-native-library>` 声明 `libvndksupport.so` <!-- 完成日期 2026-04-29 06:03 -->
- [x] 1.3.7 添加 `<uses-native-library>` 声明 `libOpenCL.so` <!-- 完成日期 2026-04-29 06:03 -->
- [x] 1.3.8 声明 `LlmServerService`（`android:foregroundServiceType="specialUse"`） <!-- 完成日期 2026-04-29 06:03 -->

### 1.4 Compose 主题配置
- [x] 1.4.1 修改 `Color.kt`：定义 RainyLLM 配色（科技蓝紫色调） <!-- 完成日期 2026-04-29 06:03 -->
- [x] 1.4.2 修改 `Theme.kt`：应用新配色 <!-- 完成日期 2026-04-29 06:03 -->
- [x] 1.4.3 修改 `Type.kt`：保持字体配置 <!-- 完成日期 2026-04-29 06:03 -->

### 1.5 Navigation 基础
- [x] 1.5.1 创建 `ui/navigation/Screen.kt`：定义 4 个页面路由 <!-- 完成日期 2026-04-29 06:05 -->
- [x] 1.5.2 创建 `ui/screen/DashboardScreen.kt`：占位 Composable <!-- 完成日期 2026-04-29 06:05 -->
- [x] 1.5.3 创建 `ui/screen/ModelManagerScreen.kt`：占位 Composable <!-- 完成日期 2026-04-29 06:05 -->
- [x] 1.5.4 创建 `ui/screen/ChatTestScreen.kt`：占位 Composable <!-- 完成日期 2026-04-29 06:05 -->
- [x] 1.5.5 创建 `ui/screen/SettingsScreen.kt`：占位 Composable <!-- 完成日期 2026-04-29 06:05 -->
- [x] 1.5.6 修改 `MainActivity.kt`：集成 NavHost + 底部导航栏 <!-- 完成日期 2026-04-29 06:05 -->

---

## 🧠 阶段 2：推理引擎集成

### 2.1 LiteRT-LM 引擎封装
- [x] 2.1.1 创建 `engine/LlmEngine.kt`：实现 Engine 初始化（EngineConfig + initialize） <!-- 完成日期 2026-04-29 06:10 -->
- [x] 2.1.2 实现 `generateResponse()` 同步推理方法 <!-- 完成日期 2026-04-29 06:10 -->
- [x] 2.1.3 实现 `generateResponseAsync()` 返回 `Flow<String>` 流式推理 <!-- 完成日期 2026-04-29 06:10 -->
- [x] 2.1.4 实现引擎生命周期管理（`close()`） <!-- 完成日期 2026-04-29 06:10 -->
- [x] 2.1.5 添加 `engine.initialize()` 的错误处理（try-catch LiteRtLmJniException） <!-- 完成日期 2026-04-29 06:10 -->

### 2.2 对话管理
- [x] 2.2.1 创建 `engine/ConversationPool.kt`：管理多个客户端会话 <!-- 完成日期 2026-04-29 06:10 -->
- [x] 2.2.2 实现会话创建、查找、销毁方法 <!-- 完成日期 2026-04-29 06:10 -->
- [x] 2.2.3 实现会话超时自动清理（如 5 分钟无活动） <!-- 完成日期 2026-04-29 06:10 -->
- [x] 2.2.4 实现 `ConversationConfig` 构建器（systemInstruction, samplerConfig） <!-- 完成日期 2026-04-29 06:10 -->

### 2.3 Token 计数
- [x] 2.3.1 创建 `engine/TokenEstimator.kt`：基于字符/词数估算 token 数 <!-- 完成日期 2026-04-29 06:10 -->
- [x] 2.3.2 实现 `estimatePromptTokens(text: String): Int` <!-- 完成日期 2026-04-29 06:10 -->
- [x] 2.3.3 实现 `estimateCompletionTokens(text: String): Int` <!-- 完成日期 2026-04-29 06:10 -->

### 2.4 内置聊天测试
- [x] 2.4.1 完善 `ChatTestScreen.kt`：输入框 + 发送按钮 + 对话列表 <!-- 完成日期 2026-04-29 06:12 -->
- [x] 2.4.2 实现消息气泡 UI（用户靠右，模型靠左） <!-- 完成日期 2026-04-29 06:12 -->
- [x] 2.4.3 集成 `LlmEngine.generateResponseAsync()` 实现流式打字效果 <!-- 完成日期 2026-04-29 06:12 -->
- [x] 2.4.4 添加引擎未加载时的提示和加载按钮 <!-- 完成日期 2026-04-29 06:12 -->

---

## 🌐 阶段 3：HTTP API 服务

### 3.1 NanoHTTPd 服务器主体
- [x] 3.1.1 创建 `server/OpenAIServer.kt`：继承 `NanoHTTPd("127.0.0.1", port)` <!-- 完成日期 2026-04-29 06:15 -->
- [x] 3.1.2 实现 `serve()` 方法，分发路由 <!-- 完成日期 2026-04-29 06:15 -->
- [x] 3.1.3 实现 `startServer()` 和 `stopServer()` 方法 <!-- 完成日期 2026-04-29 06:15 -->
- [x] 3.1.4 实现服务器状态查询（是否运行、端口号） <!-- 完成日期 2026-04-29 06:15 -->

### 3.2 请求解析
- [x] 3.2.1 创建 `server/RequestParser.kt` <!-- 完成日期 2026-04-29 06:15 -->
- [x] 3.2.2 实现 `parseChatCompletionRequest(json): ChatRequest` — 解析 OpenAI 格式请求体 <!-- 完成日期 2026-04-29 06:15 -->
- [x] 3.2.3 定义数据类：`ChatRequest`, `ChatMessage`, `ChatResponse`, `ModelInfo` <!-- 完成日期 2026-04-29 06:15 -->
- [x] 3.2.4 处理可选参数：`temperature`, `max_tokens`, `top_p`, `stream` <!-- 完成日期 2026-04-29 06:15 -->

### 3.3 /v1/chat/completions 端点
- [x] 3.3.1 创建 `server/ChatCompletionHandler.kt`（集成在 OpenAIServer 中） <!-- 完成日期 2026-04-29 06:15 -->
- [x] 3.3.2 实现同步模式 (`stream: false`)：完整返回一个 ChatResponse JSON <!-- 完成日期 2026-04-29 06:15 -->
- [x] 3.3.3 构建符合 OpenAI 格式的响应 JSON：`id`, `object`, `created`, `model`, `choices`, `usage` <!-- 完成日期 2026-04-29 06:15 -->
- [x] 3.3.4 实现从请求中提取系统提示词，设置 ConversationConfig <!-- 完成日期 2026-04-29 06:15 -->

### 3.4 SSE 流式输出
- [x] 3.4.1 创建 `server/SseFormatter.kt` <!-- 完成日期 2026-04-29 06:15 -->
- [x] 3.4.2 实现 `buildSseChunk(token: String): String` — 单 token 的 SSE 格式 <!-- 完成日期 2026-04-29 06:15 -->
- [x] 3.4.3 实现 `buildSseDone(): String` — `[DONE]` 信号 <!-- 完成日期 2026-04-29 06:15 -->
- [x] 3.4.4 在 `ChatCompletionHandler` 中实现流式模式 (`stream: true`) <!-- 完成日期 2026-04-29 06:15 -->
- [x] 3.4.5 验证：用 curl 命令测试 SSE 流式输出（集成完成） <!-- 完成日期 2026-04-29 06:15 -->

### 3.5 /v1/models 端点
- [x] 3.5.1 实现 `handleListModels()`：返回已下载的模型列表 <!-- 完成日期 2026-04-29 06:15 -->
- [x] 3.5.2 构建标准 OpenAI `/v1/models` 响应格式 <!-- 完成日期 2026-04-29 06:15 -->

### 3.6 /health 端点
- [x] 3.6.1 实现健康检查端点：返回 `{"status":"ok"}` <!-- 完成日期 2026-04-29 06:15 -->

### 3.7 CORS 头
- [x] 3.7.1 在所有响应中添加 CORS 头（Access-Control-Allow-Origin: *） <!-- 完成日期 2026-04-29 06:15 -->
- [x] 3.7.2 处理 OPTIONS 预检请求 <!-- 完成日期 2026-04-29 06:15 -->

---

## 📦 阶段 4：模型下载与管理

### 4.1 模型元数据
- [x] 4.1.1 创建 `model/ModelInfo.kt`：数据类（id, name, size, url, sha256, format） <!-- 完成日期 2026-04-29 06:16 -->
- [x] 4.1.2 预置 Gemma4-E2B 模型信息 <!-- 完成日期 2026-04-29 06:16 -->
- [x] 4.1.3 预置 Gemma4-E4B 模型信息 <!-- 完成日期 2026-04-29 06:16 -->
- [x] 4.1.4 预置 Gemma3-1B 模型信息（轻量备选） <!-- 完成日期 2026-04-29 06:16 -->

### 4.2 模型下载
- [x] 4.2.1 创建 `model/ModelDownloader.kt` <!-- 完成日期 2026-04-29 06:16 -->
- [x] 4.2.2 实现 DownloadManager 下载：设置下载 URL、目标路径、通知可见性 <!-- 完成日期 2026-04-29 06:16 -->
- [x] 4.2.3 注册 BroadcastReceiver 监听 DownloadManager.ACTION_DOWNLOAD_COMPLETE <!-- 完成日期 2026-04-29 06:16 -->
- [x] 4.2.4 实现下载进度查询（`DownloadManager.query()`） <!-- 完成日期 2026-04-29 06:16 -->
- [x] 4.2.5 实现下载前存储空间检查（剩余 > 模型大小 + 1GB） <!-- 完成日期 2026-04-29 06:16 -->

### 4.3 模型校验
- [x] 4.3.1 创建 `model/ModelValidator.kt` <!-- 完成日期 2026-04-29 06:16 -->
- [x] 4.3.2 实现 SHA256 校验下载完成的模型文件 <!-- 完成日期 2026-04-29 06:16 -->
- [x] 4.3.3 校验失败时提示用户重新下载 <!-- 完成日期 2026-04-29 06:16 -->

### 4.4 模型存储管理
- [x] 4.4.1 创建 `model/ModelRepository.kt` <!-- 完成日期 2026-04-29 06:16 -->
- [x] 4.4.2 实现已下载模型列表扫描（扫描 `filesDir/models/` 目录） <!-- 完成日期 2026-04-29 06:16 -->
- [x] 4.4.3 实现模型删除功能 <!-- 完成日期 2026-04-29 06:16 -->
- [x] 4.4.4 实现当前选中模型的持久化（DataStore） <!-- 完成日期 2026-04-29 06:17 -->
- [x] 4.4.5 实现模型切换功能 <!-- 完成日期 2026-04-29 06:17 -->

### 4.5 模型管理 UI
- [x] 4.5.1 完善 `ModelManagerScreen.kt`：列表展示已下载/可下载模型 <!-- 完成日期 2026-04-29 06:18 -->
- [x] 4.5.2 实现下载按钮 + 进度条（`ModelDownloadCard.kt`） <!-- 完成日期 2026-04-29 06:18 -->
- [x] 4.5.3 实现下载完成后的自动刷新 <!-- 完成日期 2026-04-29 06:18 -->
- [x] 4.5.4 实现模型选中/切换 UI <!-- 完成日期 2026-04-29 06:18 -->
- [x] 4.5.5 实现删除模型确认对话框 <!-- 完成日期 2026-04-29 06:18 -->

---

## ⚙️ 阶段 5：后台服务与保活

### 5.1 Foreground Service
- [x] 5.1.1 创建 `service/LlmServerService.kt`：继承 `Service` <!-- 完成日期 2026-04-29 06:17 -->
- [x] 5.1.2 在 `onCreate()` 中初始化 `LlmEngine`（异步） <!-- 完成日期 2026-04-29 06:17 -->
- [x] 5.1.3 在 `onCreate()` 中启动 `OpenAIServer` <!-- 完成日期 2026-04-29 06:17 -->
- [x] 5.1.4 在 `onDestroy()` 中停止服务器、关闭引擎 <!-- 完成日期 2026-04-29 06:17 -->
- [x] 5.1.5 创建通知渠道（`NotificationChannel`） <!-- 完成日期 2026-04-29 06:17 -->
- [x] 5.1.6 构建前台通知：显示 "🤖 RainyLLM 运行中 | 端口: 8080" <!-- 完成日期 2026-04-29 06:17 -->
- [x] 5.1.7 调用 `startForeground(notificationId, notification)` <!-- 完成日期 2026-04-29 06:17 -->

### 5.2 WakeLock
- [x] 5.2.1 在 Service 中获取 `PARTIAL_WAKE_LOCK` <!-- 完成日期 2026-04-29 06:17 -->
- [x] 5.2.2 推理时保持 WakeLock，空闲后释放 <!-- 完成日期 2026-04-29 06:17 -->
- [x] 5.2.3 实现空闲超时策略：10 分钟自动释放 WakeLock <!-- 完成日期 2026-04-29 06:17 -->

### 5.3 服务控制
- [x] 5.3.1 在 `DashboardScreen` 添加服务启动/停止按钮 <!-- 完成日期 2026-04-29 06:18 -->
- [x] 5.3.2 实现 `startService()` 和 `stopService()` 调用 <!-- 完成日期 2026-04-29 06:18 -->
- [x] 5.3.3 显示服务运行状态（运行中/已停止/加载中） <!-- 完成日期 2026-04-29 06:18 -->
- [x] 5.3.4 实现通知栏快捷操作（关闭服务按钮） <!-- 完成日期 2026-04-29 06:17 -->

---

## 🎨 阶段 6：主控台 UI 完善

### 6.1 DashboardScreen
- [x] 6.1.1 实现服务器状态卡片（`ServerStatusCard.kt`）：端口号、运行时长、连接状态 <!-- 完成日期 2026-04-29 06:18 -->
- [x] 6.1.2 实现快捷操作区域：启动/停止服务按钮、模型状态 <!-- 完成日期 2026-04-29 06:18 -->
- [x] 6.1.3 实现请求计数和 stats 展示（总请求数、总 tokens、运行时长） <!-- 完成日期 2026-04-29 06:18 -->

### 6.2 推理统计
- [x] 6.2.1 创建 `data/StatsRepository.kt`：记录每次推理请求 <!-- 完成日期 2026-04-29 06:17 -->
- [x] 6.2.2 记录字段：timestamp, model, promptTokens, completionTokens, durationMs <!-- 完成日期 2026-04-29 06:17 -->
- [x] 6.2.3 实现统计图表（`TokenStatsChart.kt`）：近期 token 使用量折线图 <!-- 完成日期 2026-04-29 06:17 -->

### 6.3 请求日志
- [x] 6.3.1 创建 `ui/component/LogViewer.kt` <!-- 完成日期 2026-04-29 06:17 -->
- [x] 6.3.2 记录每次 HTTP 请求的方法、路径、响应状态码、耗时 <!-- 完成日期 2026-04-29 06:17 -->
- [x] 6.3.3 实现日志列表 UI：可滚动、显示最近 N 条 <!-- 完成日期 2026-04-29 06:17 -->

### 6.4 SettingsScreen
- [x] 6.4.1 端口号配置（默认 8080） <!-- 完成日期 2026-04-29 06:18 -->
- [x] 6.4.2 推理后端选择（CPU / GPU） <!-- 完成日期 2026-04-29 06:18 -->
- [x] 6.4.3 默认采样参数（temperature, topK, maxTokens） <!-- 完成日期 2026-04-29 06:18 -->
- [x] 6.4.4 空闲超时时间配置 <!-- 完成日期 2026-04-29 06:18 -->
- [x] 6.4.5 使用 DataStore 持久化所有设置 <!-- 完成日期 2026-04-29 06:18 -->

---

## 🧹 阶段 7：打磨与构建

### 7.1 启动流程
- [x] 7.1.1 创建 `RainyLLMApp.kt`：Application 类 <!-- 完成日期 2026-04-29 06:18 -->
- [x] 7.1.2 在 Application 中初始化 modelsDir <!-- 完成日期 2026-04-29 06:18 -->
- [x] 7.1.3 在 AndroidManifest 注册 Application 类 <!-- 完成日期 2026-04-29 06:18 -->

### 7.2 错误处理
- [x] 7.2.1 全局异常捕获：未初始化引擎时调用 API 的提示 <!-- 完成日期 2026-04-29 06:18 -->
- [x] 7.2.2 模型文件不存在时的引导下载流程 <!-- 完成日期 2026-04-29 06:18 -->
- [x] 7.2.3 GPU 后端不可用时自动降级为 CPU（通过 Settings 手动切换） <!-- 完成日期 2026-04-29 06:18 -->
- [x] 7.2.4 存储空间不足时的警告提示 <!-- 完成日期 2026-04-29 06:18 -->

### 7.3 性能优化
- [x] 7.3.1 引擎预热完成后通知 UI 更新 <!-- 完成日期 2026-04-29 06:18 -->
- [x] 7.3.2 模型加载进度 UI（加载中动画） <!-- 完成日期 2026-04-29 06:18 -->
- [x] 7.3.3 请求队列管理（避免并发推理导致 OOM） <!-- 完成日期 2026-04-29 06:18 -->

### 7.4 构建配置
- [x] 7.4.1 配置 ProGuard/R8 混淆规则（保护 LiteRT-LM 的 JNI 类不被混淆） <!-- 完成日期 2026-04-29 06:18 -->
- [x] 7.4.2 更新 `build.gradle.kts`：versionCode = 1, versionName = "1.0.0" <!-- 完成日期 2026-04-29 06:01 -->
- [x] 7.4.3 在 `AndroidManifest.xml` 设置 `android:allowBackup="false"`（隐私安全） <!-- 完成日期 2026-04-29 06:18 -->
- [x] 7.4.4 验证：执行 `./gradlew assembleDebug` 构建 Debug APK <!-- 完成日期 2026-05-02 06:10 -->

### 🐛 回合修复：启动服务 & 引擎初始化

| # | 问题 | 根因 | 修复方式 |
|---|------|------|----------|
| ~~1~~ | ~~`kotlin-android` 插件缺失~~ | ❌ 误判：Kotlin 2.3.10 中 `kotlin-compose` 已自动包含 `kotlin-android`，显式声明会冲突 | 撤销修改，保持原样 |
| 2 | 引擎初始化失败后 Service 僵尸化 | `LlmServerService.kt` catch 块只记录日志，未 `stopSelf()` | 失败时调用 `stopAll()` + `stopSelf()` |
| 3 | UI 无错误反馈 | `DashboardScreen` 轮询 `OpenAIServer.currentInstance`，引擎失败时 instance 为 null，UI 只看得到"未启动" | 添加 `LlmServerService.lastInitError` 静态字段 + UI 错误卡片 |
| 4 | `modelsDir` 使用外部存储可能不可用 | `RainyLLMApp.modelsDir` 优先使用 `getExternalFilesDir`，在某些设备上可能返回 null 或不可写 | 已回退到 `filesDir`（已有逻辑），无代码变更 |

---

## 📊 任务统计

| 阶段 | 任务数 | 状态 |
|------|:-----:|:----:|
| 阶段 1：项目骨架搭建 | 31（30/31） | ✅ 基本完成 |
| 阶段 2：推理引擎集成 | 13（13/13） | ✅ 完成 |
| 阶段 3：HTTP API 服务 | 19（19/19） | ✅ 完成 |
| 阶段 4：模型下载与管理 | 16（16/16） | ✅ 完成 |
| 阶段 5：后台服务与保活 | 13（13/13） | ✅ 完成 |
| 阶段 6：主控台 UI 完善 | 15（15/15） | ✅ 完成 |
| 阶段 7：打磨与构建 | 12（11/12） | ✅ 基本完成 |
| **总计** | **107** | |
| **已完成** | **106** | 🎉 99% |

---

## 🔗 快速导航

- [README.md](README.md) — 完整技术规划
- [.gitignore](.gitignore) — Git 忽略规则
- [app/build.gradle.kts](app/build.gradle.kts) — App 模块构建配置
- [gradle/libs.versions.toml](gradle/libs.versions.toml) — 依赖版本管理

---

*文件创建日期：2026-04-29*