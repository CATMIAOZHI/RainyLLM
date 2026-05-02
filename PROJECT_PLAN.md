# 🤖 RainyLLM — 完整技术规划

> *"在手机上跑 Gemma，开放 OpenAI 兼容 API，让任何 AI 客户端都能调用"* 🐱☁️

一个纯离线的 Android 本地 LLM 推理服务器。集成 Google LiteRT-LM 引擎运行 Gemma 4 模型，通过 NanoHTTPd 在 `127.0.0.1` 广播 OpenAI 兼容 API，供 ChatBox、Open WebUI 等第三方 AI 客户端调用。

---

## 🎯 项目定位

| 维度 | 说明 |
|------|------|
| **做什么** | Android 手机跑 Gemma 4，开本地 HTTP 服务，暴露 OpenAI 兼容 API |
| **给谁用** | 自己。手机上的其他 AI App 通过 `127.0.0.1` 调用 |
| **核心价值** | 完全离线、零费用、隐私安全、随时随地有 AI |
| **对标** | 手机版 Ollama（但基于 LiteRT-LM + Gemma） |

---

## 🏗️ 整体架构

```
┌──────────────────────────────────────────────────┐
│                  Android App                       │
│                                                    │
│  ┌──────────────────────────────────────────────┐ │
│  │          Compose UI（管理界面）               │ │
│  │  模型下载进度 · 服务开关 · 推理统计 · 日志    │ │
│  └────────────────────┬─────────────────────────┘ │
│                       │                            │
│  ┌────────────────────▼─────────────────────────┐ │
│  │          HTTP Server (NanoHTTPd 2.3.1)        │ │
│  │  POST /v1/chat/completions   ← OpenAI 兼容   │ │
│  │  GET  /v1/models              ← 模型列表      │ │
│  │  GET  /health                 ← 健康检查      │ │
│  │  (支持 SSE 流式输出)                          │ │
│  └────────────────────┬─────────────────────────┘ │
│                       │                            │
│  ┌────────────────────▼─────────────────────────┐ │
│  │      推理引擎 (LiteRT-LM Kotlin API)          │ │
│  │  Engine → Conversation → sendMessageAsync()  │ │
│  │  · GPU 加速（3808 tk/s prefill）             │ │
│  │  · Kotlin Flow 流式输出                       │ │
│  └────────────────────┬─────────────────────────┘ │
│                       │                            │
│  ┌────────────────────▼─────────────────────────┐ │
│  │          模型存储 + 下载管理                    │ │
│  │  · 模型文件 (.litertlm)                        │ │
│  │  · HuggingFace LiteRT Community 下载          │ │
│  │  · DownloadManager 断点续传                   │ │
│  └──────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────┘
```

---

## 📊 技术栈（2026年4月最新，经官方文档核实）

### 推理引擎

| 项目 | 值 |
|------|-----|
| 框架 | **LiteRT-LM**（Google AI Edge） |
| API 状态 | ✅ Kotlin Stable |
| 依赖坐标 | `com.google.ai.edge.litertlm:litertlm-android:latest.release` |
| Maven 仓库 | Google Maven（`google()`） |
| 推荐模型 | Gemma4-E2B（2.58GB）或 Gemma4-E4B（3.65GB） |
| 模型来源 | [HuggingFace LiteRT Community](https://huggingface.co/litert-community) |
| 未审查模型 | [E2B Abliterated](https://huggingface.co/nqd145/Gemma-4-E2B-it-abliterated-litertlm) · [E4B Abliterated](https://huggingface.co/typomonster/supergemma4-e4b-abliterated-litert-lm) |
| 模型格式 | `.litertlm` |
| 加速后端 | CPU / GPU / NPU |
| GPU 性能 | Prefill: 3808 tk/s, Decode: 52 tk/s (S26 Ultra) |

> ⚠️ MediaPipe LLM Inference API 已被 Google 标记为 Deprecated，不要使用。

### HTTP 服务器

| 项目 | 值 |
|------|-----|
| 框架 | **NanoHTTPd** |
| 版本 | 2.3.1（稳定版） |
| Gradle 坐标 | `org.nanohttpd:nanohttpd:2.3.1` |

### Android 基础

| 项目 | 值 |
|------|-----|
| 语言 | Kotlin 100% |
| UI | Jetpack Compose + Material Design 3 |
| 架构 | MVVM + Repository |
| 异步 | Kotlin Coroutines + Flow |
| 数据存储 | DataStore（偏好设置） |
| 后台运行 | Foreground Service + WakeLock |

---

## 📁 完整项目结构（目标）

```
RainyLLM/
├── app/
│   ├── src/main/java/com/rainyllm/app/
│   │   │
│   │   ├── RainyLLMApp.kt                    ← Application 类
│   │   │
│   │   ├── ui/
│   │   │   ├── MainActivity.kt               ← 唯一 Activity
│   │   │   ├── screen/
│   │   │   │   ├── DashboardScreen.kt         ← 主控台：服务状态+开关+统计
│   │   │   │   ├── ModelManagerScreen.kt      ← 模型下载/切换/删除
│   │   │   │   ├── ChatTestScreen.kt          ← 内置聊天测试页
│   │   │   │   └── SettingsScreen.kt          ← 端口/后端/采样参数设置
│   │   │   ├── component/
│   │   │   │   ├── ServerStatusCard.kt        ← 服务状态卡片
│   │   │   │   ├── ModelDownloadCard.kt       ← 下载进度卡片
│   │   │   │   ├── TokenStatsChart.kt         ← 推理统计图表
│   │   │   │   └── LogViewer.kt              ← 请求日志
│   │   │   └── theme/
│   │   │       ├── Color.kt / Theme.kt / Type.kt
│   │   │
│   │   ├── server/
│   │   │   ├── OpenAIServer.kt               ← NanoHTTPd 服务器主体
│   │   │   ├── ChatCompletionHandler.kt      ← /v1/chat/completions 处理
│   │   │   ├── SseFormatter.kt               ← SSE 流式格式化
│   │   │   └── RequestParser.kt              ← OpenAI 请求体解析
│   │   │
│   │   ├── engine/
│   │   │   ├── LlmEngine.kt                  ← LiteRT-LM Engine 封装
│   │   │   ├── ConversationPool.kt           ← 多会话管理
│   │   │   └── TokenEstimator.kt             ← Token 计数估算
│   │   │
│   │   ├── model/
│   │   │   ├── ModelInfo.kt                  ← 模型元数据
│   │   │   ├── ModelRepository.kt            ← 本地模型列表管理
│   │   │   ├── ModelDownloader.kt            ← DownloadManager 下载
│   │   │   └── ModelValidator.kt             ← SHA256 校验
│   │   │
│   │   ├── service/
│   │   │   └── LlmServerService.kt           ← Foreground Service 保活
│   │   │
│   │   └── data/
│   │       ├── AppPreferences.kt              ← DataStore 偏好存储
│   │       └── StatsRepository.kt            ← 推理统计记录
│   │
│   └── src/main/AndroidManifest.xml
│
├── gradle/libs.versions.toml
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

**预估规模**：约 26 个 Kotlin 源文件，~3800 行代码。

---

## 🔧 核心代码预览（基于 LiteRT-LM 官方 API）

### Gradle 依赖

```kotlin
// app/build.gradle.kts
dependencies {
    // LiteRT-LM 推理引擎
    implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release")
    
    // HTTP 服务器
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    
    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
```

### 推理引擎封装

```kotlin
// LlmEngine.kt
import com.google.ai.edge.litertlm.*

class LlmEngine(private val modelPath: String) {
    private var engine: Engine? = null
    
    suspend fun initialize(backend: Backend = Backend.CPU()) {
        withContext(Dispatchers.IO) {
            // initialize() 可能需要 10 秒，必须在后台线程！
            val config = EngineConfig(
                modelPath = modelPath,
                backend = backend,
                cacheDir = /* context.cacheDir.path */
            )
            engine = Engine(config).also { it.initialize() }
        }
    }
    
    fun createConversation(config: ConversationConfig? = null): Conversation {
        return engine!!.createConversation(config)
    }
    
    suspend fun generateResponse(prompt: String): String {
        return engine!!.createConversation().use { it.sendMessage(prompt).text }
    }
    
    fun generateResponseAsync(
        conversation: Conversation, prompt: String
    ): Flow<String> {
        return conversation.sendMessageAsync(prompt).map { it.text }
    }
    
    fun close() { engine?.close() }
}
```

### OpenAI 兼容 API

```kotlin
// OpenAIServer.kt — 核心路由
class OpenAIServer(port: Int, engine: LlmEngine) : NanoHTTPd("127.0.0.1", port) {
    override fun serve(session: IHTTPSession): Response {
        return when {
            session.uri == "/v1/chat/completions" ->
                handleChatCompletion(session)   // OpenAI 格式对话
            session.uri == "/v1/models" ->
                handleListModels()              // 模型列表
            session.uri == "/health" ->
                jsonResponse(OK, """{"status":"ok"}""")
            else ->
                notFound()
        }
    }
}
```

### 流式 SSE 输出

```
POST /v1/chat/completions  (stream: true)

data: {"choices":[{"delta":{"content":"你"}}]}
data: {"choices":[{"delta":{"content":"好"}}]}
data: {"choices":[{"delta":{"content":"！"}}]}
data: [DONE]
```

LiteRT-LM 的 `sendMessageAsync()` 返回 `Flow<Message>`，天然适合 SSE 流式输出。

---

## 📐 开发路线图

```
阶段 1：骨架搭建           阶段 2：推理接入           阶段 3：API 服务            阶段 4：打磨
┌──────────────┐       ┌──────────────┐       ┌──────────────┐       ┌──────────────┐
│ 项目初始化    │       │ LiteRT-LM 集成│       │ NanoHTTPd    │       │ Foreground   │
│ Compose 导航  │  →    │ Gemma4 加载   │  →    │ OpenAI API   │  →    │ Service     │
│ 页面骨架      │       │ 内置对话测试  │       │ SSE 流式     │       │ 性能优化     │
│ 包名配置      │       │ 模型管理模块  │       │ 多轮对话     │       │ UI 打磨      │
└──────────────┘       └──────────────┘       └──────────────┘       └──────────────┘
```

> 详细任务拆分见 [PROGRESS.md](PROGRESS.md)，共 107 个细分任务。

---

## 🎯 最终效果

```
┌─────────────────────────────────────────────┐
│             你的 Android 手机                 │
│                                              │
│  ┌──────────┐   POST /v1/chat/completions    │
│  │ ChatBox  │───────────┐                    │
│  │ 客户端   │           │                    │
│  └──────────┘           │   ┌──────────────┐ │
│                         ├──▶│  RainyLLM     │ │
│  ┌──────────┐           │   │  127.0.0.1    │ │
│  │ Open     │───────────┘   │  :8080        │ │
│  │ WebUI    │               │  Gemma4-E2B   │ │
│  └──────────┘               └──────────────┘ │
│                                              │
│  ┌──────────┐   GET /v1/models               │
│  │ 任意兼容 │◀────────────────────────────── │
│  │ 客户端   │   {"data":[{"id":"gemma4-2b"}  │
│  └──────────┘                 ...]}          │
└─────────────────────────────────────────────┘
```

---

## ⚠️ 关键注意事项

| 事项 | 说明 |
|------|------|
| 🔧 模型大小 | Gemma4-E2B 约 2.58GB，需要充足存储空间 |
| 🧠 内存需求 | 推荐 8GB+ RAM 手机，推理时约占用 2-3GB |
| ⏱️ 冷启动 | `engine.initialize()` 约需 10 秒，需异步执行 |
| 🔌 后台保活 | 必须使用 Foreground Service + 通知栏常驻 |
| 🔋 电量 | 持续推理会发热耗电，需要空闲超时机制 |
| 📡 纯本地 | 127.0.0.1 只对本机开放，不暴露到局域网 |
| 🎮 GPU | GPU 加速需在 AndroidManifest 声明 libOpenCL |
| 📥 模型下载 | 建议在 WiFi 环境下下载，支持断点续传 |

---

## 📦 AndroidManifest 关键配置

```xml
<!-- 新增权限 -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.INTERNET" />

<application>
    <!-- GPU 加速依赖 -->
    <uses-native-library android:name="libvndksupport.so" android:required="false"/>
    <uses-native-library android:name="libOpenCL.so" android:required="false"/>
    
    <service
        android:name=".service.LlmServerService"
        android:foregroundServiceType="specialUse"
        android:exported="false" />
</application>
```

---

## 📚 参考资源

| 资源 | 链接 |
|------|------|
| LiteRT-LM 概述 | https://ai.google.dev/edge/litert-lm/overview |
| LiteRT-LM Android 指南 | https://ai.google.dev/edge/litert-lm/android |
| LiteRT-LM GitHub | https://github.com/google-ai-edge/LiteRT-LM |
| HuggingFace 模型 | https://huggingface.co/litert-community |
| 🔥 未审查 E2B | https://huggingface.co/nqd145/Gemma-4-E2B-it-abliterated-litertlm |
| 🔥 未审查 E4B | https://huggingface.co/typomonster/supergemma4-e4b-abliterated-litert-lm |
| NanoHTTPd | https://github.com/NanoHttpd/nanohttpd |
| AI Edge Gallery（参考实现） | https://github.com/google-ai-edge/gallery |

---

## 📄 License

MIT License © 2026

---

*Made with ☁️ and 🐱 paws by Rainy & 水晴*