# RainyLLM — Agent 规则手册

> Android 本地 LLM 推理服务器。LiteRT-LM 引擎 + NanoHTTPd + Compose UI。

## 技术栈速查

| 层 | 技术 |
|----|------|
| 推理引擎 | `com.google.ai.edge.litertlm:litertlm-android` v0.14.0 |
| HTTP | `org.nanohttpd:nanohttpd:2.3.1` |
| UI | Jetpack Compose + Material 3（元气猫系粉色主题） |
| 存储 | DataStore Preferences |
| 异步 | Kotlin Coroutines + Flow |
| 后台 | Foreground Service + WakeLock |

## 项目结构

```
app/src/main/java/com/rainyllm/app/
├── RainyLLMApp.kt              # Application，单例入口
├── MainActivity.kt             # 唯一 Activity，5 标签页导航
├── engine/
│   ├── LlmEngine.kt            # LiteRT-LM 封装（init 约10秒）
│   └── TokenEstimator.kt
├── server/
│   ├── OpenAIServer.kt         # NanoHTTPd，OpenAI 兼容 API + SSE
│   ├── RequestParser.kt
│   └── SseFormatter.kt
├── model/
│   ├── ModelInfo.kt / ModelRepository.kt / ModelDownloader.kt / ModelValidator.kt / ModelUpdateChecker.kt
├── service/
│   ├── LlmServerService.kt     # 前台服务，引擎+HTTP 托管
│   ├── KeepAliveService.kt     # 通知栏保活
│   ├── FloatingWindowManager.kt # 全局悬浮窗（单例 object）
│   └── NotificationActionReceiver.kt # 通知栏按钮广播
├── data/
│   ├── AppPreferences.kt       # DataStore 偏好
│   └── StatsRepository.kt      # 推理统计持久化
└── ui/
    ├── navigation/Screen.kt    # 5 标签页路由
    ├── screen/                 # Dashboard / Models / Chat / Performance / Settings
    ├── component/              # 可复用组件
    └── theme/                  # 粉色主题 Color/Theme/Type
```

## 硬规则 / 红线

- **引擎初始化必须异步**：`LlmEngine.initialize()` 耗时约 10 秒，必须在 `Dispatchers.IO` 中执行
- **推理串行锁**：LiteRT-LM 只支持单一活跃会话，`OpenAIServer` 用 `ReentrantLock` 保护
- **127.0.0.1 绑定**：不暴露到局域网，安全底线
- **悬浮窗生命周期**：`FloatingWindowManager` 是进程级单例 object，由 `RainyLLMApp` 初始化；显示/隐藏由 `setEnabled()` 控制
- **全局状态同步**：`LlmServerService.isInitializing` / `isStopping` 是静态标志，DashboardScreen 和 FloatingWindowManager 都读它们来同步「启动中/停止中」
- **二次确认**：悬浮窗的启动/停止按钮需要点两下才执行（防误触），3秒超时恢复
- **音频输入**：API 路径必须用 `Content.AudioBytes(bytes)`，不要用 `Content.AudioFile(path)`（沙箱权限导致无法读取）。音频编码器要求 16kHz/24kHz mono WAV 或 MP3，其他容器格式（M4A等）需转码
- **日志截断**：`LogViewer` 的请求体展示截断到 10K 字符，防止 base64 音频（可达 400K+）导致 Compose Text OOM 闪退。复制按钮仍复制完整内容
- **日志系统**：`LogEntry` 使用唯一 `id: Long` 标识（非数组下标），`logRequest` 返回 ID 供 `updateLogById` 异步更新；日志上限 100 条（非 1000），多模态请求体只存摘要（文本前 8K + 图片/音频字节数），不存完整 Base64
- **请求生命周期**：`handleChatCompletion` / `handleSyncResponse` / `handleStreamResponse` 返回 `HandlerResult` data class（含 response + requestBody + responseSummary + token counts），`serve()` 一次性写入日志，不使用全局 `pending*` 字段
- **活跃推理保护**：`OpenAIServer.activeRequests: AtomicInteger` 在推理前后 `increment/decrement`，空闲超时 watcher 检查 `activeRequests.get() == 0` 才允许休眠，防止 JNI 推理中关闭引擎
- **空闲超时开关**：设置中 Switch 控制，`idleTimeoutMin = 0` 表示关闭，watcher 检测到 `<= 0` 时直接退出
- **自定义模型名**：存储在 DataStore JSON 映射（`model_custom_names` key）中，通过 `getModelDisplayName()` 优先显示别名。支持清空恢复原名
- **通知栏 Action**：`NotificationActionReceiver` 处理「悬浮窗」和「退出」两个广播
- **上下文容量校验**：`buildHistoryMessages` 检查 system + history 总量，`handleChatCompletion` 额外检查 system + history + current prompt + max_tokens 输出预算，超出 KV Cache 上限返回 400
- **tool_calls 类型**：`RequestParser` 把 `tool_calls` 的 JSONArray 手动解析为 `List<Map<String, Any>>`（非裸 `msg.get("tool_calls")`），确保后续 cast 成功
- **tool_choice 限制**：`"none"` → 不传工具给引擎；`"auto"` → 正常传；`"required"` / 对象格式 → 直接返回 400（LiteRT-LM 0.14.0 不支持）
- **模型导入安全**：`importModelFromStream` / `importModel` 对文件名做 `File(name).name` 规范化 + `canonicalPath` 校验，防止路径穿越
- **SHA256 校验策略**：匹配 → 正常使用；不匹配 → 显示预期/实际哈希前 16 位对比，不自动选中模型，用户手动选择
- **Release 工作流**：tag push 自动提取版本号；`workflow_dispatch` 需手动输入 `version` 参数；SemVer 正则验证；预发布标签（如 `v1.2.0-beta.1`）PATCH 提取已修复

## 构建

```bash
export ANDROID_HOME=$HOME/Android
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
./gradlew assembleDebug
# APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest  # 运行 53 个单元测试
```

## 悬浮窗关键逻辑

- **显示条件**：`userEnabled && canDrawOverlays()`
- **状态源**：每秒轮询 `OpenAIServer.currentInstance`
- **最小化**：点 `−` → 切换到圆形 App 图标；点图标 → 还原
- **权限**：需要 `SYSTEM_ALERT_WINDOW`，通过 `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` 引导
- **配色**：草莓粉 `#FF85A2` / 浅樱粉 `#FFA5B5` / 暗粉底 `#E83D262B`

## 关键 API 端点

| 端点 | 说明 |
|------|------|
| `GET /health` | 健康检查 |
| `GET /v1/models` | 模型列表 |
| `POST /v1/chat/completions` | 对话（支持 stream + 多模态 + tool calling） |

## 测试

单元测试覆盖纯 JVM 可测层（TokenEstimator / RequestParser / SseFormatter / ModelInfo），共 53 个测试用例。CI 在 `assembleRelease` 前执行 `testDebugUnitTest`。

| 测试类 | 测试数 | 覆盖内容 |
|--------|--------|---------|
| `TokenEstimatorTest` | 14 | 空文本/纯英文/纯中文/混合/数字/多模态/估算 |
| `RequestParserTest` | 18 | 基本字段/max_tokens/tool_choice/多模态/tools/tool_calls 多轮回归 |
| `SseFormatterTest` | 10 | SSE chunk/done/tool_calls 格式 |
| `ModelInfoTest` | 10 | 预置模型/标准化匹配/大小格式化 |
| `ExampleUnitTest` | 1 | 模板 |

> LiteRT-LM JNI 层的 crash（如 coroutines 二进制不兼容）无法用单元测试覆盖，需手动验证。

## 深入文档

- `README.md` — 完整功能说明和 API 使用示例